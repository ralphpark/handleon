import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:phone/driver/invite_screen.dart';
import 'package:phone/share_options.dart';

import 'support/fake_backend.dart';
import 'support/harness.dart';

void main() {
  testWidgets('nine choices unselected; needs all three; chosen arguments', (
    t,
  ) async {
    final b = FakeBackend();
    await showScreen(t, InviteScreen(backend: b));
    expect(find.byType(Radio<ShareScope>), findsNWidgets(3));
    expect(find.byType(Radio<ShareTiming>), findsNWidgets(4));
    expect(
      t
          .widget<RadioGroup<ShareScope>>(find.byType(RadioGroup<ShareScope>))
          .groupValue,
      isNull,
    );
    expect(
      t
          .widget<RadioGroup<ShareTiming>>(find.byType(RadioGroup<ShareTiming>))
          .groupValue,
      isNull,
    );
    expect(
      t.widget<FilledButton>(find.byKey(const Key('createInvite'))).onPressed,
      isNull,
    );
    await tapText(t, '추이 그래프까지');
    expect(
      t.widget<FilledButton>(find.byKey(const Key('createInvite'))).onPressed,
      isNull,
    );
    await tapText(t, '7일 뒤 공유');
    expect(
      t.widget<RadioGroup<bool>>(find.byType(RadioGroup<bool>)).groupValue,
      isNull,
    );
    expect(
      t.widget<FilledButton>(find.byKey(const Key('createInvite'))).onPressed,
      isNull,
    );
    await tapText(t, '함께 보여 주기');
    await tapText(t, '초대 코드 만들기');
    expect(b.invited, (ShareScope.trend, ShareTiming.d7, true));
    expect(find.text('K7Q2M9XD'), findsOneWidget);
    expect(find.textContaining('7일 동안 쓸 수 있어요'), findsOneWidget);
  });
  testWidgets(
    'explicit pattern false is sent and changing choice clears code',
    (t) async {
      final b = FakeBackend();
      await showScreen(t, InviteScreen(backend: b));
      await tapText(t, '보여 주지 않기');
      expect(
        t.widget<FilledButton>(find.byKey(const Key('createInvite'))).onPressed,
        isNull,
      );
      await tapText(t, '단계만');
      expect(
        t.widget<FilledButton>(find.byKey(const Key('createInvite'))).onPressed,
        isNull,
      );
      await tapText(t, '바로 공유');
      await tapText(t, '초대 코드 만들기');
      expect(b.invited, (ShareScope.stage, ShareTiming.now, false));
      await tapText(t, '함께 보여 주기');
      expect(find.text('K7Q2M9XD'), findsNothing);
    },
  );
}
