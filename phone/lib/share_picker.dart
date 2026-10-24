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
    required this.onScope,
    required this.onTiming,
    this.enabled = true,
  });
  final ShareScope? scope;
  final ShareTiming? timing;
  final ValueChanged<ShareScope?> onScope;
  final ValueChanged<ShareTiming?> onTiming;
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
      const Notice('위치는 어떤 경우에도 공유되지 않아요'),
    ], 12),
  );
}
