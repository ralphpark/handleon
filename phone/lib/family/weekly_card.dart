import 'package:flutter/material.dart';

import '../backend.dart';
import '../ui.dart';
import 'feed_text.dart';

class WeeklyCard extends StatelessWidget {
  const WeeklyCard({
    super.key,
    required this.row,
    this.showCoach = false,
    this.onCoach,
  });
  final WeeklyChange row;
  final bool showCoach;
  final VoidCallback? onCoach;

  @override
  Widget build(BuildContext context) => Panel(
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: spaced([
        const SectionTitle('최근 몇 주 변화'),
        Muted('${feedDate(row.weekStart)}부터 한 주'),
        Text(weeklyFamilyLine(row.driverName, row.domains)),
        if (showCoach) SecondaryButton('어떻게 말을 꺼낼까요?', onPressed: onCoach),
      ], 12),
    ),
  );
}
