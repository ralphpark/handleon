import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:phone/backend.dart';
import 'package:phone/driver/sharing_screen.dart';
import 'package:phone/share_options.dart';

import 'support/fake_backend.dart';
import 'support/harness.dart';

FakeBackend backend() => FakeBackend()
  ..shares = [
    const Share(
      familyId: 'f',
      familyName: '큰딸',
      scope: ShareScope.trend,
      timing: ShareTiming.d7,
    ),
  ];
void main() {
  testWidgets('revoke only after confirmation', (t) async {
    final b = backend();
    await showScreen(t, SharingScreen(backend: b));
    await tapText(t, '공유 멈추기');
    expect(b.revoked, isNull);
    expect(find.text('큰딸에게 공유를 멈출까요?'), findsOneWidget);
    await tapText(t, '그대로 두기');
    expect(b.revoked, isNull);
    await tapText(t, '공유 멈추기');
    await tapText(t, '공유 멈추기');
    expect(b.revoked, 'f');
    expect(find.text('큰딸'), findsNothing);
  });
  testWidgets('edit prefills and sends new scope timing', (t) async {
    final b = backend();
    await showScreen(t, SharingScreen(backend: b));
    await tapText(t, '바꾸기');
    expect(
      t
          .widget<RadioGroup<ShareScope>>(find.byType(RadioGroup<ShareScope>))
          .groupValue,
      ShareScope.trend,
    );
    expect(
      t
          .widget<RadioGroup<ShareTiming>>(find.byType(RadioGroup<ShareTiming>))
          .groupValue,
      ShareTiming.d7,
    );
    await tapText(t, '단계만');
    await tapText(t, '3일 뒤 공유');
    await tapText(t, '바꾸기');
    expect(b.updated, ('f', ShareScope.stage, ShareTiming.d3));
  });
}
