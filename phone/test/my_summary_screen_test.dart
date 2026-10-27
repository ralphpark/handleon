import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:flutter/material.dart';
import 'package:phone/backend.dart';
import 'package:phone/driver/my_summary_screen.dart';
import 'package:phone/family/summary_card.dart';

import 'support/fake_backend.dart';
import 'support/harness.dart';

void main() {
  testWidgets('a summary the car sent while away shows after coming back', (
    t,
  ) async {
    MonthRow month(int m) => MonthRow(
      month: DateTime(2026, m),
      mode: 'maeum',
      stage: 'stable',
      domains: const {},
      top: const [],
    );
    final b = FakeBackend()..months = [month(9)];
    await showScreen(t, DriverHome(backend: b));
    expect(find.byType(SummaryCard), findsOneWidget);
    b.months = [month(10), month(9)];
    for (final state in [
      AppLifecycleState.inactive,
      AppLifecycleState.hidden,
      AppLifecycleState.paused,
      AppLifecycleState.hidden,
      AppLifecycleState.inactive,
      AppLifecycleState.resumed,
    ]) {
      t.binding.handleAppLifecycleStateChanged(state);
    }
    await t.pumpAndSettle();
    expect(find.byType(SummaryCard), findsNWidgets(2));
  });
  testWidgets('two months yield two cards and disclaimer', (t) async {
    final b = FakeBackend()
      ..months = [
        for (final m in [10, 9])
          MonthRow(
            month: DateTime(2026, m),
            mode: 'maeum',
            stage: 'stable',
            domains: const {},
            top: const [],
          ),
      ];
    await showScreen(t, DriverHome(backend: b));
    expect(find.byType(SummaryCard), findsNWidgets(2));
    expect(find.text('핸들온은 의료기기가 아닙니다.'), findsOneWidget);
  });
  testWidgets(
    'condition chart keeps missing dates empty and highlights alert',
    (t) async {
      await showScreen(
        t,
        ConditionsChart(
          today: DateTime(2026, 10, 24),
          rows: [
            DayRow(
              day: DateTime(2026, 10, 23),
              ewma: .8,
              threshold: 1,
              alert: false,
            ),
            DayRow(
              day: DateTime(2026, 10, 24),
              ewma: 1.2,
              threshold: 1,
              alert: true,
            ),
          ],
        ),
      );
      expect(find.text('최근 14일 운전 컨디션'), findsOneWidget);
      expect(find.text('10/11'), findsOneWidget);
      expect(find.text('10/24'), findsOneWidget);
      final semantics = t
          .widgetList<Semantics>(find.byType(Semantics))
          .where((s) => s.properties.label?.contains('컨디션 1.2') ?? false)
          .single;
      expect(semantics.properties.label, contains('쉬어 가시면 좋겠어요'));
      expect(semantics.properties.label, isNot(contains('10월 22일')));
    },
  );
  test('lib contains no forbidden words in literals', () {
    final banned = ['치매', '인지장애', '진단', '위험', '점수', '질환', '판정', '우울'];
    for (final file
        in Directory('lib')
            .listSync(recursive: true)
            .whereType<File>()
            .where((f) => f.path.endsWith('.dart'))) {
      var source = file.readAsStringSync();
      // P5's exact fixed footer is the only approved exception.
      if (file.path == 'lib/driver/clinic_pdf.dart') {
        source = source.replaceAll(
          '이 자료는 진단이 아니에요. 운전 기록의 변화를 쉬운 말로 정리한 참고 자료예요.',
          '',
        );
      }
      for (final word in banned) {
        expect(source, isNot(contains(word)), reason: file.path);
      }
    }
  });
}
