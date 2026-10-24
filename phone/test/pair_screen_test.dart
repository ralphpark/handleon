import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:phone/driver/pair_screen.dart';

import 'support/fake_backend.dart';
import 'support/harness.dart';

void main() {
  testWidgets('manual code works without a camera', (t) async {
    final b = FakeBackend();
    await showScreen(t, PairScreen(backend: b, cameraEnabled: false));
    await t.enterText(find.byType(TextField), ' ab12cd ');
    await tapText(t, '연결하기');
    expect(b.claimed, 'AB12CD');
    expect(find.text('차와 연결됐어요'), findsOneWidget);
  });
  testWidgets('bad code shows a friendly error', (t) async {
    final b = FakeBackend();
    await showScreen(t, PairScreen(backend: b, cameraEnabled: false));
    await t.enterText(find.byType(TextField), 'ABC');
    await tapText(t, '연결하기');
    expect(b.claimed, isNull);
    expect(find.text('코드가 맞지 않거나 시간이 지났어요'), findsOneWidget);
  });
}
