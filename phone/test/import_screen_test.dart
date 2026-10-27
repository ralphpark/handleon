import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:flutter/material.dart';
import 'package:phone/driver/import_screen.dart';
import 'package:phone/driver/timeline_parser.dart';

import 'support/fake_backend.dart';
import 'support/harness.dart';

void main() {
  testWidgets('import screen explains a child can help', (t) async {
    await showScreen(t, ImportScreen(backend: FakeBackend()));
    expect(
      find.text('자녀분이 대신 해 드려도 돼요. 휴대폰 설정 → 위치 → 타임라인 → 내보내기 순서예요.'),
      findsOneWidget,
    );
    expect(find.text('가져온 기록은 월간 비교에만 써요'), findsOneWidget);
  });
  test('large imports produce only contract A keys and bounded files', () {
    final trip = TimelineParser.parse(
      File('test/fixtures/timeline_ondevice.json').readAsStringSync(),
    ).first;
    final files = encodeImportChunks(List.filled(2001, trip));
    expect(files, hasLength(3));
    expect(files.map((f) => (jsonDecode(f)['trips'] as List).length), [
      1000,
      1000,
      1,
    ]);
    for (final f in files) {
      expect(utf8.encode(f).length, lessThanOrEqualTo(5000000));
      final data = jsonDecode(f) as Map;
      expect(data.keys.toSet(), {'version', 'source', 'trips'});
      expect(data['version'], 1);
      expect(data['source'], 'google_timeline');
      expect(data['trips'][0].keys.toSet(), {
        'start',
        'end',
        'startLat',
        'startLon',
        'endLat',
        'endLon',
        'distanceKm',
      });
    }
  });
  test('oversized input is rejected before JSON parsing', () {
    expect(
      () => parseImportFile(List.filled(maxImportInputBytes + 1, 32)),
      throwsFormatException,
    );
  });
  testWidgets('broken file has readable message and no upload', (t) async {
    final b = FakeBackend();
    await showScreen(
      t,
      ImportScreen(
        backend: b,
        pickFile: () async => const ImportFile('bad.json', [123]),
      ),
    );
    await pickAndWait(t);
    expect(find.text('읽을 수 없는 파일이에요'), findsOneWidget);
    expect(b.uploads, isEmpty);
  });
  testWidgets('preview requires explicit send before upload', (t) async {
    final b = FakeBackend();
    final bytes = File('test/fixtures/timeline_ondevice.json')
        .readAsBytesSync();
    await showScreen(
      t,
      ImportScreen(
        backend: b,
        pickFile: () async => ImportFile('timeline.json', bytes),
      ),
    );
    await pickAndWait(t);
    expect(find.textContaining('차로 이동한 기록 3건'), findsOneWidget);
    expect(b.uploads, isEmpty);
    await tapText(t, '차로 보내기');
    expect(b.uploads, hasLength(1));
    expect(find.text('차가 다음에 켜질 때 가져가요').last, findsOneWidget);
  });
}

Future<void> pickAndWait(WidgetTester t) async {
  // compute() uses a real isolate; runAsync awaits its real event loop.
  final action = t
      .widget<OutlinedButton>(find.widgetWithText(OutlinedButton, '파일 고르기'))
      .onPressed!;
  await t.runAsync(() async {
    await (action as Future<void> Function())();
  });
  await t.pumpAndSettle();
}
