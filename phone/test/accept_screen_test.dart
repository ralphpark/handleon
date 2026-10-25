import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:phone/app.dart';
import 'package:phone/backend.dart';
import 'package:phone/family/accept_screen.dart';
import 'package:phone/share_options.dart';

import 'support/fake_backend.dart';
import 'support/harness.dart';

void main() {
  testWidgets('preview before acceptance, changing code invalidates preview', (
    t,
  ) async {
    final b = FakeBackend(role: Role.family, userId: 'u');
    await t.pumpWidget(AppRouter(backend: b, initialRoute: '/family/accept'));
    await t.pumpAndSettle();
    await t.enterText(find.byType(TextField), 'k7q2m9xd');
    await tapText(t, '초대 확인하기');
    expect(b.previewed, 'K7Q2M9XD');
    expect(b.accepted, isNull);
    expect(find.text('아버지 님이 초대했어요'), findsOneWidget);
    expect(find.text('요약이 나오고 7일 뒤'), findsOneWidget);
    await t.enterText(find.byType(TextField), 'AB12CD34');
    await t.pump();
    expect(find.text('아버지 님이 초대했어요'), findsNothing);
    await tapText(t, '초대 확인하기');
    await tapText(t, '초대 받기');
    expect(b.accepted, 'AB12CD34');
  });
  for (final value in [true, false]) {
    testWidgets('accept preview discloses pattern choice $value', (t) async {
      final b = FakeBackend()
        ..invitation = InvitePreview(
          driverName: '아버지',
          scope: ShareScope.stage,
          timing: ShareTiming.now,
          sharePattern: value,
        );
      await showScreen(t, AcceptScreen(backend: b));
      await t.enterText(find.byType(TextField), 'K7Q2M9XD');
      await tapText(t, '초대 확인하기');
      expect(
        find.text(value ? '운전 패턴도 함께 보여 줘요' : '운전 패턴은 보여 주지 않아요'),
        findsOneWidget,
      );
    });
  }
}
