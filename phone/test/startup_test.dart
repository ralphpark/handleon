import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:phone/main.dart';
import 'package:phone/backend.dart';

import 'support/fake_backend.dart';

void main() {
  testWidgets('startup failure recovers via retry without relaunch', (t) async {
    var calls = 0;
    final b = FakeBackend(role: Role.driver, userId: 'u');
    await t.pumpWidget(
      BackendStartup(
        load: () async {
          calls++;
          if (calls == 1) throw StateError('network');
          return b;
        },
      ),
    );
    await t.pumpAndSettle();
    expect(find.text('연결하지 못했어요. 인터넷을 확인해 주세요'), findsOneWidget);
    await t.tap(find.text('다시 연결하기'));
    await t.pumpAndSettle();
    expect(find.text('내 운전 요약'), findsOneWidget);
    expect(calls, 2);
    expect(find.byType(CircularProgressIndicator), findsNothing);
  });
}
