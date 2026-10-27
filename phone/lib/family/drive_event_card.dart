import 'package:flutter/material.dart';

import '../backend.dart';
import '../ui.dart';
import 'feed_text.dart';

class DriveEventCard extends StatelessWidget {
  const DriveEventCard({
    super.key,
    required this.events,
    this.showCoach = false,
    this.onCoach,
  });
  final List<DriveEvent> events;
  final bool showCoach;
  final VoidCallback? onCoach;

  @override
  Widget build(BuildContext context) {
    if (events.isEmpty) return const SizedBox.shrink();
    final day = localDay(events.first.endedAt);
    final today = day == localDay(DateTime.now());
    final heading = eveningLine(events.first.driverName, events);
    return Panel(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: spaced([
          SectionTitle(
            '${safeFamilyName(events.first.driverName)} 님 · 운전 마침 소식',
          ),
          Muted(feedDate(day)),
          Text(
            today
                ? heading
                : heading.replaceFirst('오늘', '${day.month}월 ${day.day}일'),
          ),
          for (final event in events) Text(driveEventLine(event)),
          if (showCoach) SecondaryButton('어떻게 말을 꺼낼까요?', onPressed: onCoach),
        ], 12),
      ),
    );
  }
}
