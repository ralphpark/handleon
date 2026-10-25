import 'package:flutter/material.dart';

import 'share_options.dart';
import 'ui.dart';

const scopeDescriptions = {
  ShareScope.stage: '평소와 비슷한지, 달라졌는지만 보여요',
  ShareScope.trend: '영역별 변화 그래프도 함께 보여요',
  ShareScope.detail: '달라진 점 문구와 월간 편지까지 보여요',
};

class SharePicker extends StatelessWidget {
  const SharePicker({
    super.key,
    required this.scope,
    required this.timing,
    required this.sharePattern,
    required this.onScope,
    required this.onTiming,
    required this.onPattern,
    this.enabled = true,
  });
  final ShareScope? scope;
  final ShareTiming? timing;
  final bool? sharePattern;
  final ValueChanged<ShareScope?> onScope;
  final ValueChanged<ShareTiming?> onTiming;
  final ValueChanged<bool?> onPattern;
  final bool enabled;
  @override
  Widget build(BuildContext context) => Column(
    crossAxisAlignment: CrossAxisAlignment.stretch,
    children: spaced([
      const SectionTitle('무엇을 보여 줄까요?'),
      RadioGroup<ShareScope>(
        groupValue: scope,
        onChanged: (v) {
          if (enabled) onScope(v);
        },
        child: Column(
          children: spaced([
            for (final s in ShareScope.values)
              Panel(
                radius: 16,
                padding: 0,
                tint: scope == s ? colors(context).brand : null,
                child: RadioListTile<ShareScope>(
                  value: s,
                  enabled: enabled,
                  minTileHeight: 48,
                  controlAffinity: ListTileControlAffinity.trailing,
                  title: Text(
                    s.label,
                    style: bodyStyle(context, weight: FontWeight.w600),
                  ),
                  subtitle: Muted(scopeDescriptions[s]!),
                ),
              ),
          ], 8),
        ),
      ),
      const SectionTitle('언제 보여 줄까요?'),
      RadioGroup<ShareTiming>(
        groupValue: timing,
        onChanged: (v) {
          if (enabled) onTiming(v);
        },
        child: LayoutBuilder(
          builder: (context, constraints) {
            final columns = MediaQuery.textScalerOf(context).scale(16) > 20
                ? 1
                : 2;
            return Wrap(
              spacing: 8,
              runSpacing: 8,
              children: [
                for (final t in ShareTiming.values)
                  SizedBox(
                    width: (constraints.maxWidth - (columns - 1) * 8) / columns,
                    child: Panel(
                      radius: 16,
                      padding: 0,
                      tint: timing == t ? colors(context).brand : null,
                      child: RadioListTile<ShareTiming>(
                        value: t,
                        enabled: enabled,
                        minTileHeight: 48,
                        contentPadding: const EdgeInsets.symmetric(
                          horizontal: 4,
                        ),
                        title: Text(t.label, style: bodyStyle(context)),
                      ),
                    ),
                  ),
              ],
            );
          },
        ),
      ),
      const Muted('시간을 두면 요약이 나온 뒤 내가 먼저 살펴볼 수 있어요.'),
      const SectionTitle('운전 패턴도 함께 보여 줄까요?'),
      const Muted(
        '운전 횟수와 시간, 밤·장거리 운전 비율, 다니는 범위와 가는 곳 가짓수가 평소와 얼마나 다른지를 달마다 보여 줘요. 장소 이름과 좌표는 보내지 않아요.',
      ),
      RadioGroup<bool>(
        groupValue: sharePattern,
        onChanged: (v) {
          if (enabled) onPattern(v);
        },
        child: Column(
          children: spaced([
            for (final value in [true, false])
              Panel(
                radius: 16,
                padding: 0,
                tint: sharePattern == value ? colors(context).brand : null,
                child: RadioListTile<bool>(
                  value: value,
                  enabled: enabled,
                  minTileHeight: 48,
                  controlAffinity: ListTileControlAffinity.trailing,
                  title: Text(
                    value ? '함께 보여 주기' : '보여 주지 않기',
                    style: bodyStyle(context, weight: FontWeight.w600),
                  ),
                ),
              ),
          ], 8),
        ),
      ),
      const Notice('위치는 어떤 경우에도 공유되지 않아요'),
    ], 12),
  );
}
