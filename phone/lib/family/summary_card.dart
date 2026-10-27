import 'dart:math' as math;

import 'package:flutter/material.dart';

import '../backend.dart';
import '../share_options.dart';
import '../ui.dart';

const stageTitles = {
  'building': '운전 지문을 모으는 중이에요',
  'stable': '평소와 비슷해요',
  'watch': '평소와 조금 달라진 점이 있어요',
  'talk': '가족과 이야기 나눠 보시면 좋겠어요',
};
const stageLabels = {
  'building': '적립 중',
  'stable': '안정 단계',
  'watch': '관찰 단계',
  'talk': '대화 권유 단계',
};
const domainNames = {
  'mobility': '생활 반경',
  'wayfinding': '길찾기',
  'operation': '조작',
};
const domainColors = {
  'mobility': Color(0xff4fe3c1),
  'wayfinding': Color(0xffa99cff),
  'operation': Color(0xffe8d5a3),
};
String monthLabel(DateTime date) => '${date.year}년 ${date.month}월';

class SummaryCard extends StatelessWidget {
  const SummaryCard({
    super.key,
    required this.row,
    this.showCoach = false,
    this.onCoach,
  });
  final MonthRow row;
  final bool showCoach;
  final VoidCallback? onCoach;
  @override
  Widget build(BuildContext context) {
    final family = row is FeedRow ? row as FeedRow : null;
    final showDomains = family == null || family.scope != ShareScope.stage;
    final showDetail = family == null || family.scope == ShareScope.detail;
    final c = colors(context);
    final stageColor = switch (row.stage) {
      'talk' => c.talkFill,
      'watch' => c.watchFill,
      'stable' => c.stable,
      _ => c.inkMuted,
    };
    final heading = family == null
        ? '${monthLabel(row.month)} · ${row.mode == 'condition' ? '운전 컨디션' : '마음 운전'}'
        : '${monthLabel(row.month)} 요약';
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: spaced([
        Panel(
          radius: family == null ? 36 : 28,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: spaced([
              if (family != null)
                Text(
                  family.driverName,
                  style: bodyStyle(context, weight: FontWeight.w600),
                ),
              Muted(heading),
              if (row.source == 'replay')
                Panel(
                  radius: 16,
                  padding: 12,
                  tint: c.replay,
                  child: Text(
                    '재생 중 · 가상 인물 데이터',
                    style: bodyStyle(context, color: c.replay),
                  ),
                ),
              Row(
                children: [
                  Container(
                    width: 14,
                    height: 14,
                    decoration: BoxDecoration(
                      color: stageColor,
                      shape: BoxShape.circle,
                      boxShadow: [
                        BoxShadow(
                          color: stageColor.withValues(alpha: .25),
                          blurRadius: 18,
                          spreadRadius: 4,
                        ),
                      ],
                    ),
                  ),
                  const SizedBox(width: 10),
                  Expanded(
                    child: Text(
                      stageLabels[row.stage] ?? '적립 중',
                      style: bodyStyle(
                        context,
                        color: stageColor,
                        weight: FontWeight.w600,
                      ),
                    ),
                  ),
                ],
              ),
              Text(
                stageTitles[row.stage] ?? stageTitles['building']!,
                style: Theme.of(context).textTheme.titleLarge,
              ),
              if (showDomains && row.domains != null) ...[
                const SectionTitle('영역별 변화'),
                DomainBars(domains: row.domains!),
                const Muted('점선은 건강하셨던 기준 기간의 나와 견준 평소 범위예요.'),
              ],
              if (family != null && row.stage == 'talk')
                const Text('운전 이야기로 가볍게 말을 꺼내 보세요. 요즘 어디를 다니시는지 여쭤보는 것부터 좋아요.'),
              if (family != null) ...[
                const Divider(height: 1),
                Muted('${family.driverName} 님이 정한 범위: ${family.scope.label}'),
              ],
              if (showCoach && family != null)
                SecondaryButton('어떻게 말을 꺼낼까요?', onPressed: onCoach),
            ], 16),
          ),
        ),
        if (showDetail && row.top != null && row.top!.isNotEmpty) ...[
          const SectionTitle('이번 달 달라진 점'),
          for (final item in row.top!.take(3))
            Panel(
              padding: 18,
              child: Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Container(
                    width: 48,
                    height: 48,
                    decoration: BoxDecoration(
                      color: c.brand.withValues(alpha: .12),
                      borderRadius: BorderRadius.circular(16),
                    ),
                    child: Icon(Icons.route_outlined, color: c.brand),
                  ),
                  const SizedBox(width: 14),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(item.text),
                        const SizedBox(height: 4),
                        Muted(
                          item.grade == 'C' ? 'Pleos 지표, 검증 중' : '연구 근거 있는 지표',
                        ),
                      ],
                    ),
                  ),
                ],
              ),
            ),
        ],
        if (showDetail && row.letter != null && row.letter!.trim().isNotEmpty)
          Panel(
            tint: c.brand,
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                SectionTitle('${row.month.month}월의 편지'),
                const SizedBox(height: 10),
                Text(
                  row.letter!,
                  style: bodyStyle(context).copyWith(height: 26 / 16),
                ),
              ],
            ),
          ),
      ], 16),
    );
  }
}

class DomainBars extends StatelessWidget {
  const DomainBars({super.key, required this.domains});
  final Map<String, double> domains;
  @override
  Widget build(BuildContext context) {
    final valid = domains.values.where((v) => v.isFinite && v >= 0);
    final maxValue = valid.fold<double>(
      1.5625,
      (m, v) => math.max(m, v * 1.08),
    );
    return Panel(
      radius: 16,
      padding: 16,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: spaced([
          for (final entry in domainNames.entries)
            Builder(
              builder: (context) {
                final raw = domains[entry.key];
                // 0 보다 작은 비율도 실제 값(평소보다 덜 달라짐)이다. 막대만 0 에서 멈춘다.
                final value = raw != null && raw.isFinite
                    ? math.max(0.0, raw)
                    : null;
                final status = value == null
                    ? '기록을 모으는 중'
                    : value > 1
                    ? '평소 범위 넘음'
                    : '평소 범위 안';
                return Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    Wrap(
                      alignment: WrapAlignment.spaceBetween,
                      spacing: 8,
                      children: [SectionTitle(entry.value), Muted(status)],
                    ),
                    const SizedBox(height: 8),
                    if (value != null)
                      Semantics(
                        label: '${entry.value}, $status',
                        child: SizedBox(
                          height: 18,
                          child: CustomPaint(
                            painter: RatioBarPainter(
                              value: value,
                              maximum: maxValue,
                              threshold: 1,
                              color: value > 1
                                  ? domainColors[entry.key]!
                                  : const Color(0xff7f8fa0),
                              lineColor: colors(context).threshold,
                            ),
                          ),
                        ),
                      ),
                  ],
                );
              },
            ),
          const Muted('┆ 평소 범위'),
        ], 14),
      ),
    );
  }
}

class RatioBarPainter extends CustomPainter {
  const RatioBarPainter({
    required this.value,
    required this.maximum,
    required this.threshold,
    required this.color,
    required this.lineColor,
  });
  final double value, maximum, threshold;
  final Color color, lineColor;
  @override
  void paint(Canvas canvas, Size size) {
    final background = RRect.fromRectAndRadius(
      Rect.fromLTWH(0, 4, size.width, 10),
      const Radius.circular(5),
    );
    canvas.drawRRect(
      background,
      Paint()..color = Colors.white.withValues(alpha: .07),
    );
    canvas.drawRRect(
      RRect.fromRectAndRadius(
        Rect.fromLTWH(0, 4, size.width * (value / maximum).clamp(0, 1), 10),
        const Radius.circular(5),
      ),
      Paint()..color = color,
    );
    final x = size.width * (threshold / maximum).clamp(0, 1);
    for (double y = 0; y < size.height; y += 6) {
      canvas.drawLine(
        Offset(x, y),
        Offset(x, math.min(y + 3, size.height)),
        Paint()
          ..color = lineColor
          ..strokeWidth = 2,
      );
    }
  }

  @override
  bool shouldRepaint(covariant RatioBarPainter old) =>
      old.value != value ||
      old.maximum != maximum ||
      old.threshold != threshold ||
      old.color != color ||
      old.lineColor != lineColor;
}
