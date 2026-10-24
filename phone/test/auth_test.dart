import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:phone/app.dart';
import 'package:phone/auth/auth_screen.dart';
import 'package:phone/backend.dart';

import 'support/fake_backend.dart';
import 'support/harness.dart';

void main() {
  test('missing profile uses metadata and never changes existing role', () {
    expect(
      profileToCreate(
        id: 'u',
        existing: null,
        metadata: {'role': 'family', 'display_name': '큰딸'},
      ),
      {'id': 'u', 'role': 'family', 'display_name': '큰딸'},
    );
    expect(
      profileToCreate(
        id: 'u',
        existing: {'role': 'driver'},
        metadata: {'role': 'family'},
      ),
      isNull,
    );
    expect(
      () => profileToCreate(id: 'u', existing: null, metadata: {}),
      throwsFormatException,
    );
  });
  testWidgets('signup waits for a role and explains email confirmation', (
    t,
  ) async {
    final b = FakeBackend()..signupResult = SignUpResult.confirmEmail;
    await showScreen(t, AuthScreen(backend: b));
    await t.enterText(find.byKey(const Key('email')), 'a@example.com');
    await t.enterText(find.byKey(const Key('password')), 'password8');
    await t.enterText(find.byKey(const Key('displayName')), '큰딸');
    expect(
      t.widget<FilledButton>(find.byKey(const Key('authSubmit'))).onPressed,
      isNull,
    );
    await tapText(t, '가족이에요');
    await tapText(t, '가입하기');
    expect(find.text('이메일로 보낸 확인 링크를 누른 뒤 로그인해 주세요'), findsOneWidget);
  });
  for (final role in Role.values) {
    testWidgets('$role home and opposite route guard', (t) async {
      final b = FakeBackend(role: role, userId: 'u');
      await t.pumpWidget(
        AppRouter(
          backend: b,
          initialRoute: role == Role.family
              ? '/driver/invite'
              : '/family/accept',
        ),
      );
      await t.pumpAndSettle();
      expect(
        find.text(role == Role.driver ? '내 운전 요약' : '가족 소식'),
        findsOneWidget,
      );
      expect(find.byType(AuthScreen), findsNothing);
    });
  }
  testWidgets('signout clears protected navigation stack', (t) async {
    final b = FakeBackend(role: Role.driver, userId: 'u');
    await t.pumpWidget(AppRouter(backend: b, initialRoute: '/driver/invite'));
    await t.pumpAndSettle();
    await b.signOut();
    await t.pumpAndSettle();
    expect(find.byType(AuthScreen), findsOneWidget);
    expect(find.text('가족 초대하기'), findsNothing);
  });
}
