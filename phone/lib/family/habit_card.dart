import 'package:flutter/material.dart';

import '../backend.dart';
import '../ui.dart';
import 'feed_text.dart';

class HabitCard extends StatelessWidget {
  const HabitCard({
    super.key,
    required this.row,
    this.showCoach = false,
    this.onCoach,
  });
  final WeeklyHabit row;
  final bool showCoach;
  final VoidCallback? onCoach;

  @override
  Widget build(BuildContext context) {
    final today = localDay(DateTime.now());
    final currentWeek = today.subtract(Duration(days: today.weekday - 1));
    final thisWeek = row.weekStart == currentWeek;
    final story = habitStory(row.story);
    String period(String text) =>
        thisWeek ? text : text.replaceAll('이번 주', '이 주');
    return Panel(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: spaced([
          SectionTitle('${safeFamilyName(row.driverName)} 님 · 운전 습관 요약'),
          Muted('${feedDate(row.weekStart)}부터 한 주'),
          Text(period(habitLine(row))),
          Text(
            row.longStretches == 0
                ? '2시간 넘게 쉬지 않은 운전 없음'
                : '2시간 넘게 쉬지 않은 운전 ${row.longStretches}번',
          ),
          if (row.promisesTotal > 0)
            Text(
              row.promisesKept == row.promisesTotal
                  ? period('이번 주 약속 ${row.promisesTotal}번 모두 지키셨어요')
                  : period(
                      '이번 주 약속 ${row.promisesTotal}번 중 ${row.promisesKept}번 지키셨어요',
                    ),
            ),
          if (story != null) Text(period(story)),
          if (showCoach) SecondaryButton('어떻게 말을 꺼낼까요?', onPressed: onCoach),
        ], 12),
      ),
    );
  }
}
