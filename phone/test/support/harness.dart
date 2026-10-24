import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:phone/theme.dart';

Future<void> showScreen(WidgetTester t, Widget screen) async {
  await t.binding.setSurfaceSize(const Size(390, 1800));
  addTearDown(() => t.binding.setSurfaceSize(null));
  await t.pumpWidget(MaterialApp(theme: darkTheme, home: screen));
  await t.pumpAndSettle();
}

Future<void> tapText(WidgetTester t, String text) async {
  final finder = find.text(text).last;
  await t.ensureVisible(finder);
  await t.tap(finder);
  await t.pumpAndSettle();
}
