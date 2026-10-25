import 'package:flutter/material.dart';

import '../backend.dart';
import '../ui.dart';
import 'pattern_card.dart';
import 'summary_card.dart';

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
  });
  final String driverName;
  final bool paused, sharePattern;
  final List<FeedRow> feed;
  final List<PatternRow> patterns;

  @override
  Widget build(BuildContext context) {
    if (paused) return PausedSharingCard(driverName: driverName);
    final summaries = feed.toList()..sort((a, b) => b.month.compareTo(a.month));
    final months = sharePattern
        ? (patterns.toList()..sort((a, b) => b.month.compareTo(a.month)))
        : <PatternRow>[];
    if (summaries.isEmpty && months.isEmpty) {
      return const Notice(emptyFamilyView);
    }
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: spaced([
        // 기존 피드처럼 운전자별 최신 요약 하나를 보여 준다.
        if (summaries.isNotEmpty) SummaryCard(row: summaries.first),
        for (final row in months.take(2)) PatternCard(row: row),
      ], 16),
    );
  }
}
