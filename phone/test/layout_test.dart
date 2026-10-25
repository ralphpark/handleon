import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:phone/auth/auth_screen.dart';
import 'package:phone/backend.dart';
import 'package:phone/driver/import_screen.dart';
import 'package:phone/driver/invite_screen.dart';
import 'package:phone/driver/my_summary_screen.dart';
import 'package:phone/driver/pair_screen.dart';
import 'package:phone/driver/sharing_screen.dart';
import 'package:phone/driver/family_preview_screen.dart';
import 'package:phone/family/note_screen.dart';
import 'package:phone/family/accept_screen.dart';
import 'package:phone/family/feed_screen.dart';
import 'package:phone/share_options.dart';
import 'package:phone/theme.dart';

import 'support/fake_backend.dart';
import 'support/sharing_fixtures.dart';
import 'summary_card_test.dart' as fixtures;

void main() {
  final screens = <String, Widget Function(FakeBackend)>{
    'auth': (b) => AuthScreen(backend: b),
    'driver': (b) => DriverHome(backend: b),
    'pair': (b) => PairScreen(backend: b, cameraEnabled: false),
    'import': (b) => ImportScreen(backend: b),
    'invite': (b) => InviteScreen(backend: b),
    'sharing': (b) => SharingScreen(backend: b),
    'accept': (b) => AcceptScreen(backend: b),
    'family': (b) => FamilyHome(backend: b),
    'preview': (b) =>
        FamilyPreviewScreen(backend: b, familyId: 'f', familyName: '큰딸'),
    'note': (b) => NoteScreen(backend: b, driverId: 'd', driverName: '아버지'),
  };
  for (final scale in [1.0, 2.0]) {
    for (final entry in screens.entries) {
      testWidgets('${entry.key} fits 390x844 at text scale $scale', (t) async {
        await t.binding.setSurfaceSize(const Size(390, 844));
        addTearDown(() => t.binding.setSurfaceSize(null));
        final b = FakeBackend(role: Role.driver, userId: 'd')
          ..months = [
            MonthRow(
              month: DateTime(2026, 10),
              mode: 'condition',
              stage: 'watch',
              domains: const {'mobility': 1.2, 'operation': .8},
              top: const [
                TopItem(text: '평소보다 달라진 점이 있어요', grade: 'A', feature: 'M1'),
              ],
              letter: '이번 달의 운전 이야기예요.',
            ),
          ]
          ..links = [familyLink()]
          ..feed = [fixtures.row(ShareScope.detail)]
          ..shares = [
            const Share(
              familyId: 'f',
              familyName: '가족에게 보일 긴 이름',
              scope: ShareScope.detail,
              timing: ShareTiming.d14,
              sharePattern: false,
            ),
          ];
        b.patterns = [pattern()];
        b.views['f'] = FamilyView(
          paused: false,
          feed: b.feed,
          patterns: b.patterns,
        );
        await t.pumpWidget(
          MaterialApp(
            theme: darkTheme,
            builder: (context, child) => MediaQuery(
              data: MediaQuery.of(context)
                  .copyWith(textScaler: TextScaler.linear(scale)),
              child: child!,
            ),
            home: entry.value(b),
          ),
        );
        await t.pumpAndSettle();
        expect(t.takeException(), isNull);
        await t.drag(
          find.byType(SingleChildScrollView).first,
          const Offset(0, -2500),
        );
        await t.pumpAndSettle();
        expect(t.takeException(), isNull);
      });
    }
  }
}
