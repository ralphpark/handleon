import 'package:flutter/material.dart';

import '../backend.dart';
import '../ui.dart';
import 'pattern_card.dart';
import 'summary_card.dart';
import 'drive_event_card.dart';
import 'weekly_card.dart';
import 'habit_card.dart';
import 'feed_text.dart';

const emptyFamilyView = '지금은 보이는 내용이 없어요. 고른 공유 시점이 아직 지나지 않았거나 올라온 요약이 없어요.';

class PausedSharingCard extends StatelessWidget {
  const PausedSharingCard({super.key, required this.driverName});
  final String driverName;

  @override
  Widget build(BuildContext context) => Panel(
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: spaced([
        SectionTitle('$driverName 님이 공유를 잠시 멈췄어요'),
        const Muted('다시 공유하면 이곳에 보여요.'),
      ], 12),
    ),
  );
}

/// 실제 가족 피드와 운전자 미리보기에 동일한 선택·정렬·카드를 적용한다.
class FamilyContent extends StatelessWidget {
  const FamilyContent({
    super.key,
    required this.driverName,
    required this.paused,
    required this.feed,
    required this.patterns,
    required this.sharePattern,
    this.driveEvents = const [],
    this.weekly = const [],
    this.habits = const [],
    this.sectionKeys = const {},
  });
  final String driverName;
  final bool paused, sharePattern;
  final List<FeedRow> feed;
  final List<PatternRow> patterns;
  final List<DriveEvent> driveEvents;
  final List<WeeklyChange> weekly;
  final List<WeeklyHabit> habits;
  final Map<FeedSection, Key> sectionKeys;

  @override
  Widget build(BuildContext context) {
    if (paused) return PausedSharingCard(driverName: driverName);
    final summaries = feed.toList()..sort((a, b) => b.month.compareTo(a.month));
    final months = sharePattern
        ? (patterns.toList()..sort((a, b) => b.month.compareTo(a.month)))
        : <PatternRow>[];
    final changes = weekly.where((w) => w.flagged).toList()
      ..sort((a, b) => b.weekStart.compareTo(a.weekStart));
    final habitRows = habits.toList()
      ..sort((a, b) => b.weekStart.compareTo(a.weekStart));
    final now = DateTime.now();
    final cutoff = now.subtract(const Duration(days: 14));
    final events =
        driveEvents
            .where(
              (e) => !e.endedAt.isBefore(cutoff) && !e.endedAt.isAfter(now),
            )
            .toList()
          ..sort((a, b) => b.endedAt.compareTo(a.endedAt));
    final days = <(String, DateTime), List<DriveEvent>>{};
    for (final event in events) {
      days
          .putIfAbsent((event.driverId, localDay(event.endedAt)), () => [])
          .add(event);
    }
    if (summaries.isEmpty &&
        months.isEmpty &&
        changes.isEmpty &&
        days.isEmpty &&
        habitRows.isEmpty) {
      return const Notice(emptyFamilyView);
    }
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: spaced([
        if (changes.isNotEmpty)
          Column(
            key: sectionKeys[FeedSection.weekly],
            children: spaced([
              for (final row in changes) WeeklyCard(row: row),
            ], 16),
          ),
        if (days.isNotEmpty)
          Column(
            key: sectionKeys[FeedSection.drive],
            children: spaced([
              for (final events in days.values) DriveEventCard(events: events),
            ], 16),
          ),
        if (habitRows.isNotEmpty)
          Column(
            key: sectionKeys[FeedSection.habits],
            children: spaced([
              for (final row in habitRows) HabitCard(row: row),
            ], 16),
          ),
        // 기존 피드처럼 운전자별 최신 요약 하나를 보여 준다.
        if (summaries.isNotEmpty)
          SummaryCard(
            key: sectionKeys[FeedSection.stage],
            row: summaries.first,
          ),
        for (final row in months.take(2)) PatternCard(row: row),
      ], 16),
    );
  }
}
