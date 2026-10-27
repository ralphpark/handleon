import 'package:flutter_test/flutter_test.dart';
import 'package:phone/family/feed_text.dart';

import 'support/new_sharing_fixtures.dart';

void main() {
  test('rough time rounds at half past', () {
    for (final (hour, minute, expected) in [
      (14, 29, '오후 2시쯤'),
      (14, 30, '오후 3시쯤'),
      (23, 40, '밤 12시쯤'),
      (0, 0, '밤 12시쯤'),
      (11, 30, '오후 12시쯤'),
      (0, 30, '오전 1시쯤'),
    ]) {
      expect(roughTime(DateTime(2026, 10, 27, hour, minute)), expected);
    }
  });
  test('drive line omits rest when not rested', () {
    expect(
      driveEventLine(driveEvent(at: DateTime(2026, 10, 27, 14, 30))),
      '오후 3시쯤 · 42분',
    );
    expect(
      driveEventLine(
        driveEvent(at: DateTime(2026, 10, 27, 14, 30), rested: true),
      ),
      '오후 3시쯤 · 42분 · 중간에 한 번 쉬셨어요',
    );
  });
  test('evening line counts only that day', () {
    expect(
      eveningLine('아버지', [
        driveEvent(at: DateTime(2026, 10, 27, 15)),
        driveEvent(at: DateTime(2026, 10, 26, 23, 59)),
        driveEvent(at: DateTime(2026, 10, 27, 10)),
      ]),
      '오늘 2번 운전하셨고 모두 무사히 마치셨어요',
    );
  });
  test(
    'weekly family line prioritizes mobility and handles unknown domains',
    () {
      expect(
        weeklyFamilyLine('아버지', ['OPERATION', 'MOBILITY']),
        '아버지님이 요즘 몇 주 평소보다 운전을 덜 하셨어요. 안부 전화 한 통 어떠세요?',
      );
      expect(weeklyFamilyLine('아버지', ['WAYFINDING']), contains('길 찾기'));
      expect(weeklyFamilyLine('아버지', ['OPERATION']), contains('운전 습관'));
      expect(weeklyFamilyLine('아버지', ['unknown']), contains('안부 전화 한 통 어떠세요?'));
    },
  );
  test('habit line uses totals without inventing zero-denominator rates', () {
    expect(habitLine(habit()), '이번 주 5번 운전 · 방향지시등 95% · 급제동 1번');
    final zero = habitLine(
      habit(changes: {'trips': 0, 'signal_total': 0, 'signal_kept': 0}),
    );
    expect(zero, contains('이번 주 0번 운전'));
    expect(zero, isNot(contains('%')));
    expect(zero, contains('방향지시등 기록 없음'));
  });
  test('family lines never use forbidden words', () {
    const forbidden = [
      '치매',
      '인지장애',
      '진단',
      '위험',
      '점수',
      '질환',
      '판정',
      '우울',
      '등급',
      '순위',
      '알츠하이머',
      '반납',
      '고령운전자',
    ];
    for (final word in forbidden) {
      for (final name in [
        word,
        word.split('').join(' '),
        word.split('').join('\u200b'),
      ]) {
        final lines = [
          weeklyFamilyLine(name, ['MOBILITY']),
          weeklyFamilyLine(name, ['WAYFINDING']),
          weeklyFamilyLine(name, ['OPERATION']),
          eveningLine(name, [driveEvent()]),
          driveEventLine(driveEvent()),
          habitLine(habit()),
          safeFamilyName(name),
          habitStory(word) ?? '',
        ];
        for (final line in lines) {
          expect(
            line.replaceAll(RegExp(r'[\s\u200b]'), ''),
            isNot(contains(word)),
          );
        }
      }
    }
    expect(safeFamilyName('치매'), '가족');
    expect(safeFamilyName('아버지'), '아버지');
  });
  test('habit story only shows server location-free templates', () {
    expect(habitStory('이번 주 12번 운전했어요'), '이번 주 12번 운전했어요');
    expect(habitStory('운전 약속을 모두 지켰어요'), '운전 약속을 모두 지켰어요');
    expect(habitStory('서울역에 다녀왔어요'), isNull);
    expect(habitStory(''), isNull);
    expect(habitStory(null), isNull);
  });
}
