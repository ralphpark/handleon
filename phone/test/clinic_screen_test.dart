import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:pdf/pdf.dart';
import 'package:phone/app.dart';
import 'package:phone/backend.dart';
import 'package:phone/driver/clinic_screen.dart';
import 'package:phone/theme.dart';
import 'package:printing/printing.dart';

import 'support/clinic_fixtures.dart';
import 'support/fake_backend.dart';
import 'support/harness.dart';

const printingChannel = MethodChannel('net.nfet.printing');

Future<void> createSheet(WidgetTester t, {String button = '한 장 만들기'}) async {
  final action = t
      .widget<FilledButton>(find.widgetWithText(FilledButton, button))
      .onPressed!;
  await t.runAsync(() async => await (action as Future<void> Function())());
  await t.pumpAndSettle();
  // PdfPreview debounces native raster work for 300 ms.
  await t.pump(const Duration(milliseconds: 300));
  await t.pumpAndSettle();
}

void main() {
  final shares = <Map<dynamic, dynamic>>[];
  Object? shareError;
  String? rasterError;
  Completer<int>? pendingShare;

  setUp(() {
    shares.clear();
    shareError = null;
    rasterError = null;
    pendingShare = null;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(printingChannel, (call) async {
          if (call.method == 'printingInfo') {
            return {'canRaster': true, 'canShare': true};
          }
          if (call.method == 'rasterPdf') {
            final job = (call.arguments as Map)['job'];
            // Native rasterization is exercised separately against actual PDFs.
            // End the platform stream without a page; no real printer/network calls.
            scheduleMicrotask(
              () => TestWidgetsFlutterBinding.ensureInitialized().channelBuffers
                  .push(
                    printingChannel.name,
                    printingChannel.codec.encodeMethodCall(
                      MethodCall('onPageRasterEnd', {
                        'job': job,
                        'error': rasterError,
                      }),
                    ),
                    (_) {},
                  ),
            );
            return null;
          }
          if (call.method == 'sharePdf') {
            shares.add(call.arguments as Map);
            if (shareError != null) throw shareError!;
            return pendingShare?.future ?? 1;
          }
          throw StateError('Unexpected printing call: ${call.method}');
        });
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(printingChannel, null);
  });

  testWidgets('clinic screen is driver only', (t) async {
    final b = FakeBackend(role: Role.driver, userId: 'driver');
    await t.pumpWidget(AppRouter(backend: b));
    await t.pumpAndSettle();
    await tapText(t, '진료·상담용 한 장');
    expect(find.byType(ClinicScreen), findsOneWidget);
    expect(find.text('한 장 만들기'), findsOneWidget);
    expect(b.clinicCalls, 0);
    b.role = Role.family;
    b.userId = 'family';
    b.events.add(null);
    await t.pumpAndSettle();
    expect(find.byType(ClinicScreen), findsNothing);
    expect(find.text('진료·상담용 한 장'), findsNothing);
    Navigator.of(t.element(find.text('가족 소식'))).pushNamed('/driver/clinic');
    await t.pumpAndSettle();
    expect(find.byType(ClinicScreen), findsNothing);
    await showScreen(t, ClinicScreen(backend: b));
    expect(find.text('한 장 만들기'), findsNothing);
    expect(b.clinicCalls, 0);
  });

  testWidgets('clinic timeout returns to an enabled retry action', (t) async {
    final pending = Completer<ClinicSummary>();
    final b = FakeBackend(role: Role.driver, userId: 'driver')
      ..pendingClinic = pending;
    await showScreen(t, ClinicScreen(backend: b));
    await t.tap(find.text('한 장 만들기'));
    await t.pump();
    expect(t.widget<FilledButton>(find.byType(FilledButton)).onPressed, isNull);
    pending.completeError(ClinicError.unavailable);
    await t.pumpAndSettle();
    expect(find.byType(CircularProgressIndicator), findsNothing);
    expect(
      t.widget<FilledButton>(find.byType(FilledButton)).onPressed,
      isNotNull,
    );
    expect(find.text('다시 시도'), findsOneWidget);
    b.pendingClinic = null;
    await createSheet(t, button: '다시 시도');
    expect(b.clinicCalls, 2);
    expect(find.byType(PdfPreview), findsOneWidget);
    expect(shares, isEmpty);
  });

  testWidgets('failure shows retry', (t) async {
    final b = FakeBackend(role: Role.driver, userId: 'driver')
      ..clinicError = StateError('private server detail');
    await showScreen(t, ClinicScreen(backend: b));
    await createSheet(t);
    expect(find.text('다시 시도'), findsOneWidget);
    expect(find.byType(PdfPreview), findsNothing);
    expect(find.textContaining('private server detail'), findsNothing);
    b.clinicError = null;
    await createSheet(t, button: '다시 시도');
    expect(find.byType(PdfPreview), findsOneWidget);
    expect(find.text('저장·공유'), findsOneWidget);
    expect(b.clinicCalls, 2);
    expect(shares, isEmpty);
  });

  testWidgets(
    'fallback preview is labelled and sharing uses the displayed PDF',
    (t) async {
      final b = FakeBackend(
        role: Role.driver,
        userId: 'driver',
        displayName: '아버지',
      )..clinicResult = clinicSummary(fallback: true);
      await showScreen(t, ClinicScreen(backend: b));
      expect(find.byType(PdfPreview), findsNothing);
      expect(find.text('저장·공유'), findsNothing);
      await createSheet(t);
      expect(find.text('기본 요약'), findsOneWidget);
      final preview = t.widget<PdfPreview>(find.byType(PdfPreview));
      expect(preview.initialPageFormat, PdfPageFormat.a4);
      expect(preview.canChangePageFormat, false);
      expect(preview.canChangeOrientation, false);
      expect(preview.allowPrinting, false);
      expect(preview.allowSharing, false);
      final bytes = await preview.build(PdfPageFormat.a4);
      expect(bytes, isA<Uint8List>());
      expect(shares, isEmpty);
      await tapText(t, '저장·공유');
      expect(shares, hasLength(1));
      expect(shares.single['doc'], bytes);
      expect(
        shares.single['name'],
        matches(r'^handleon-clinic-\d{4}-\d{2}-\d{2}\.pdf$'),
      );
      expect(shares.single['emails'], isNull);
      expect(b.clinicCalls, 1);
      expect(b.sentNote, isNull);
    },
  );

  testWidgets(
    'generation blocks duplicate requests and ignores a late signed-out result',
    (t) async {
      final pending = Completer<ClinicSummary>();
      final b = FakeBackend(role: Role.driver, userId: 'driver')
        ..pendingClinic = pending;
      await showScreen(t, ClinicScreen(backend: b));
      await t.tap(find.text('한 장 만들기'));
      await t.pump();
      expect(find.byType(CircularProgressIndicator), findsOneWidget);
      final button = t.widget<FilledButton>(find.byType(FilledButton));
      expect(button.onPressed, isNull);
      expect(b.clinicCalls, 1);
      b.role = null;
      b.userId = null;
      pending.complete(clinicSummary());
      await t.pumpAndSettle();
      expect(find.byType(PdfPreview), findsNothing);
      expect(find.text('저장·공유'), findsNothing);
      expect(t.takeException(), isNull);
    },
  );

  testWidgets('share failure can retry without regenerating the summary', (
    t,
  ) async {
    final b = FakeBackend(role: Role.driver, userId: 'driver');
    await showScreen(t, ClinicScreen(backend: b));
    await createSheet(t);
    shareError = PlatformException(
      code: 'share_failed',
      message: 'private native detail',
    );
    await tapText(t, '저장·공유');
    expect(find.text('공유 화면을 열지 못했어요. 다시 시도해 주세요'), findsOneWidget);
    expect(find.textContaining('private native detail'), findsNothing);
    expect(find.byType(PdfPreview), findsOneWidget);
    shareError = null;
    pendingShare = Completer<int>();
    await t.tap(find.text('저장·공유'));
    await t.pump();
    expect(t.widget<FilledButton>(find.byType(FilledButton)).onPressed, isNull);
    pendingShare!.complete(1);
    await t.pumpAndSettle();
    expect(b.clinicCalls, 1);
    expect(shares, hasLength(2));
    expect(shares[0]['doc'], shares[1]['doc']);
  });

  testWidgets('preview retry fits narrow large text and reuses the PDF', (
    t,
  ) async {
    final b = FakeBackend(role: Role.driver, userId: 'driver');
    await t.binding.setSurfaceSize(const Size(320, 640));
    addTearDown(() => t.binding.setSurfaceSize(null));
    await t.pumpWidget(
      MaterialApp(
        theme: darkTheme,
        builder: (context, child) => MediaQuery(
          data: MediaQuery.of(context)
              .copyWith(textScaler: const TextScaler.linear(2)),
          child: child!,
        ),
        home: ClinicScreen(backend: b),
      ),
    );
    await t.pumpAndSettle();
    rasterError = 'private raster detail';
    await createSheet(t);
    // printing reports raster errors to Flutter as well as its onError UI.
    expect(t.takeException(), 'private raster detail');
    expect(find.text('미리보기를 열지 못했어요'), findsOneWidget);
    expect(find.textContaining('private raster detail'), findsNothing);
    expect(t.takeException(), isNull);
    rasterError = null;
    await tapText(t, '미리보기 다시 열기');
    expect(find.text('미리보기를 열지 못했어요'), findsNothing);
    expect(b.clinicCalls, 1);
    expect(t.takeException(), isNull);
    await tapText(t, '저장·공유');
    expect(shares, hasLength(1));
  });
}
