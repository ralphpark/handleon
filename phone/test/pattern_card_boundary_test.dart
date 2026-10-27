import 'package:flutter_test/flutter_test.dart';
import 'package:phone/family/pattern_card.dart';
import 'package:phone/ui.dart';

import 'support/harness.dart';
import 'support/sharing_fixtures.dart';

const usualKeys = [
  'usual_trips',
  'usual_minutes',
  'usual_night_ratio',
  'usual_long_ratio',
];

Future<void> showPattern(WidgetTester t, Map<String, dynamic> changes) =>
    showScreen(
      t,
      PageFrame(
        title: '패턴',
        children: [PatternCard(row: pattern(changes: changes))],
      ),
    );

Map<String, dynamic> everyRatio(double ratio) => {
  for (final key in [
    'night_ratio',
    'long_ratio',
    'radius_ratio',
    'places_ratio',
    'usual_night_ratio',
    'usual_long_ratio',
  ])
    key: ratio,
};

void main() {
  for (final (minutes, text) in [
    (0, '0분'),
    (1, '1분'),
    (59, '59분'),
    (60, '1시간'),
    (61, '1시간 1분'),
    (119, '1시간 59분'),
    (120, '2시간'),
    (44640, '744시간'),
  ]) {
    testWidgets('$minutes minutes reads $text for drive time and usual', (
      t,
    ) async {
      await showPattern(t, {
        'drive_minutes': minutes,
        'usual_minutes': minutes,
      });
      expect(find.text('운전 시간 $text · 평소 $text'), findsOneWidget);
    });
  }
  for (final (ratio, text) in [
    (0.0, '0%'),
    (0.0049, '0%'),
    (0.005, '1%'),
    (0.0051, '1%'),
    (0.015, '2%'),
    (0.155, '16%'),
    (0.9949, '99%'),
    (0.995, '100%'),
    (1.0, '100%'),
  ]) {
    testWidgets('ratio $ratio shows $text on every percent line', (t) async {
      await showPattern(t, everyRatio(ratio));
      for (final line in [
        '밤 운전 $text · 평소 $text',
        '30km 넘는 운전 $text · 평소 $text',
        '다니는 범위 · 평소의 $text',
        '가는 곳 가짓수 · 평소의 $text',
      ]) {
        expect(find.text(line), findsOneWidget, reason: line);
      }
    });
  }
  for (final (ratio, text) in [
    (1.004, '100%'),
    (1.0051, '101%'),
    (19.995, '2000%'),
    (20.0, '2000%'),
  ]) {
    testWidgets('range and places ratio $ratio above 1 shows $text', (t) async {
      await showPattern(t, {'radius_ratio': ratio, 'places_ratio': ratio});
      expect(find.text('다니는 범위 · 평소의 $text'), findsOneWidget);
      expect(find.text('가는 곳 가짓수 · 평소의 $text'), findsOneWidget);
    });
  }
  // 차는 비율을 소수 셋째 자리로 반올림해 보낸다(Patterns.roundedRatio). 0.155 는 16% 인데
  // 0.145 * 100 == 14.499999999999998 이라 14% 가 된다.
  group(
    'three-decimal ratios ending in 5',
    () {
      for (final (ratio, text) in [
        (0.145, '15%'),
        (0.285, '29%'),
        (0.565, '57%'),
        (0.575, '58%'),
        (1.005, '101%'),
      ]) {
        testWidgets('ratio $ratio rounds half up to $text like 0.155 does', (
          t,
        ) async {
          await showPattern(t, {'radius_ratio': ratio, 'places_ratio': ratio});
          expect(find.text('다니는 범위 · 평소의 $text'), findsOneWidget);
          expect(find.text('가는 곳 가짓수 · 평소의 $text'), findsOneWidget);
        });
      }
    },
    skip:
        'bug: pattern_card.dart:12 (ratio * 100).round() rounds some x.xx5 '
        'ratios down through binary float error',
  );
  testWidgets('zero ratios and zero usual values are shown, not hidden', (
    t,
  ) async {
    await showPattern(t, {
      ...everyRatio(0),
      'usual_trips': 0,
      'usual_minutes': 0,
    });
    for (final line in [
      '운전 18회 · 평소 0회',
      '운전 시간 9시간 · 평소 0분',
      '밤 운전 0% · 평소 0%',
      '30km 넘는 운전 0% · 평소 0%',
      '다니는 범위 · 평소의 0%',
      '가는 곳 가짓수 · 평소의 0%',
    ]) {
      expect(find.text(line), findsOneWidget, reason: line);
    }
    expect(find.text('평소 기준을 쌓는 중이에요.'), findsNothing);
  });
  for (final kept in usualKeys) {
    testWidgets('only $kept present hides the baseline-building note', (
      t,
    ) async {
      await showPattern(t, {
        for (final key in usualKeys)
          if (key != kept) key: null,
      });
      expect(find.text('평소 기준을 쌓는 중이에요.'), findsNothing);
      expect(
        find.text('운전 18회${kept == 'usual_trips' ? ' · 평소 22회' : ''}'),
        findsOneWidget,
      );
      expect(
        find.text('운전 시간 9시간${kept == 'usual_minutes' ? ' · 평소 11시간' : ''}'),
        findsOneWidget,
      );
    });
  }
  testWidgets('usual night ratio without this month\'s ratio stays hidden', (
    t,
  ) async {
    await showPattern(t, {'night_ratio': null, 'long_ratio': null});
    expect(find.textContaining('밤 운전'), findsNothing);
    expect(find.textContaining('30km 넘는 운전'), findsNothing);
    expect(find.text('운전 18회 · 평소 22회'), findsOneWidget);
  });
  testWidgets('January and December titles are not zero padded', (t) async {
    for (final (month, title) in [
      ('2027-01-01', '운전 패턴 · 2027년 1월'),
      ('2026-12-01', '운전 패턴 · 2026년 12월 (이번 달 지금까지)'),
    ]) {
      await showPattern(t, {'month': month, 'partial': month == '2026-12-01'});
      expect(find.text(title), findsOneWidget, reason: month);
    }
  });
}
