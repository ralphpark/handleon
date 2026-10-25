import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:phone/family/pattern_card.dart';
import 'package:phone/ui.dart';

import 'support/harness.dart';
import 'support/sharing_fixtures.dart';

void main() {
  testWidgets('pattern labels use rounded percentages and neutral text', (
    t,
  ) async {
    await showScreen(
      t,
      PageFrame(
        title: '패턴',
        children: [PatternCard(row: pattern())],
      ),
    );
    for (final text in [
      '운전 패턴 · 2026년 10월 (이번 달 지금까지)',
      '운전 18회 · 평소 22회',
      '운전 시간 9시간 · 평소 11시간',
      '밤 운전 10% · 평소 15%',
      '30km 넘는 운전 5% · 평소 10%',
      '다니는 범위 · 평소의 70%',
      '가는 곳 가짓수 · 평소의 80%',
    ]) {
      expect(find.text(text), findsOneWidget);
    }
    final text = t.element(find.text('운전 18회 · 평소 22회'));
    expect(DefaultTextStyle.of(text).style.color, colors(text).ink);
    expect(find.text('평소 기준을 쌓는 중이에요.'), findsNothing);
    expect(find.textContaining('장소 이름과 좌표는 보내지 않아요.'), findsOneWidget);
  });
  testWidgets('125 minutes includes remaining minutes and 0 means zero hours', (
    t,
  ) async {
    await showScreen(
      t,
      PageFrame(
        title: '패턴',
        children: [
          PatternCard(
            row: pattern(
              changes: {
                'drive_minutes': 125,
                'usual_minutes': 0,
                'partial': false,
              },
            ),
          ),
        ],
      ),
    );
    expect(find.text('운전 시간 2시간 5분 · 평소 0분'), findsOneWidget);
    expect(find.text('운전 패턴 · 2026년 10월'), findsOneWidget);
  });
  testWidgets(
    'null ratios are hidden and unavailable usual values are omitted',
    (t) async {
      final raw = patternJson();
      for (final key in raw.keys.toList()) {
        if (key.contains('ratio') || key.startsWith('usual_')) raw[key] = null;
      }
      await showScreen(
        t,
        PageFrame(
          title: '패턴',
          children: [PatternCard(row: pattern(changes: raw))],
        ),
      );
      expect(find.text('운전 18회'), findsOneWidget);
      expect(find.text('운전 시간 9시간'), findsOneWidget);
      for (final label in ['밤 운전', '30km 넘는 운전', '다니는 범위', '가는 곳 가짓수']) {
        expect(find.textContaining(label), findsNothing);
      }
      expect(find.text('평소 기준을 쌓는 중이에요.'), findsOneWidget);
    },
  );
}
