import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:phone/main.dart' as app;

void main() {
  testWidgets('서버 설정 없이 시작하면 핸들온 제목이 보인다', (tester) async {
    await Future<void>.sync(app.main);
    await tester.pumpAndSettle();

    expect(find.text('핸들온'), findsOneWidget);
    expect(tester.widget<MaterialApp>(find.byType(MaterialApp)).title, '핸들온');
    expect(find.byType(FloatingActionButton), findsNothing);
    expect(tester.takeException(), isNull);
  });
}
