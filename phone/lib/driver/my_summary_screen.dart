import 'dart:math' as math;

import 'package:flutter/material.dart';

import '../backend.dart';
import '../family/summary_card.dart';
import '../ui.dart';

class DriverHome extends StatefulWidget {
  const DriverHome({super.key, required this.backend});
  final Backend backend;
  @override
  State<DriverHome> createState() => _DriverHomeState();
}

class _DriverHomeState extends State<DriverHome> {
  List<MonthRow> months = [];
  List<DayRow> days = [];
  bool? linked;
  int? shareCount;
  bool loading = true;
  String? error;
  int generation = 0;
  // 앱을 떠나 있는 동안 차가 보낸 요약을 돌아오면 바로 보여 준다.
  late final AppLifecycleListener lifecycle;
  @override
  void initState() {
    super.initState();
    lifecycle = AppLifecycleListener(onResume: load);
    load();
  }

  @override
  void dispose() {
    lifecycle.dispose();
    super.dispose();
  }

  Future<void> load() async {
    final current = ++generation;
    setState(() {
      loading = true;
      error = null;
    });
    try {
      final result = await Future.wait<Object>([
        widget.backend.mySummaries(),
        widget.backend.myConditions(),
        widget.backend.carLinked(),
        widget.backend.myShares(),
      ]);
      if (mounted && current == generation) {
        setState(() {
          months = (result[0] as List<MonthRow>).toList()
            ..sort((a, b) => b.month.compareTo(a.month));
          days = result[1] as List<DayRow>;
          linked = result[2] as bool;
          shareCount = (result[3] as List<Share>).length;
        });
      }
    } catch (e) {
      if (mounted && current == generation) {
        setState(() => error = friendlyError(e));
      }
    } finally {
      if (mounted && current == generation) setState(() => loading = false);
    }
  }

  Future<void> open(String route) async {
    await Navigator.of(context).pushNamed(route);
    if (mounted) await load();
  }

  Widget menu(
    String title,
    String? subtitle,
    String route, {
    bool accent = false,
  }) => Panel(
    radius: 16,
    padding: 0,
    tint: accent ? colors(context).brand : null,
    child: ListTile(
      minTileHeight: 64,
      title: Text(title, style: bodyStyle(context, weight: FontWeight.w600)),
      subtitle: subtitle == null ? null : Muted(subtitle),
      trailing: Icon(accent ? Icons.add : Icons.chevron_right),
      onTap: () => open(route),
    ),
  );
  @override
  Widget build(BuildContext context) => PageFrame(
    title: '내 운전 요약',
    onRefresh: load,
    actions: [AccountMenu(backend: widget.backend)],
    children: [
      if (loading) const Center(child: CircularProgressIndicator()),
      if (error != null) ...[
        Notice(error!),
        SecondaryButton('다시 불러오기', onPressed: load),
      ],
      if (!loading && error == null && months.isEmpty)
        const Notice('차와 연결하면 요약이 여기에 보여요'),
      for (final month in months.take(6)) SummaryCard(row: month),
      if (months.isNotEmpty && months.first.mode == 'condition')
        ConditionsChart(rows: days),
      menu(
        '차 연결',
        linked == null
            ? '확인 중'
            : linked!
            ? '연결됨'
            : '안 됨',
        '/driver/pair',
      ),
      menu('지난 운전 기록 가져오기', '구글 지도 타임라인 파일', '/driver/import'),
      menu('진료·상담용 한 장', '최근 생활 변화와 물어볼 질문', '/driver/clinic'),
      menu(
        '가족 공유 관리',
        shareCount == null ? '확인 중' : '$shareCount명에게 공유 중',
        '/driver/sharing',
      ),
      menu('가족 초대하기', null, '/driver/invite', accent: true),
      const Text(disclaimer, textAlign: TextAlign.center),
    ],
  );
}

typedef MySummaryScreen = DriverHome;

class ConditionsChart extends StatelessWidget {
  const ConditionsChart({super.key, required this.rows, this.today});
  final List<DayRow> rows;
  final DateTime? today;
  @override
  Widget build(BuildContext context) {
    final now = today ?? DateTime.now().toUtc().add(const Duration(hours: 9));
    final end = DateTime.utc(now.year, now.month, now.day);
    final dates = List.generate(
      14,
      (i) => end.subtract(Duration(days: 13 - i)),
    );
    final byDate = {
      for (final row in rows)
        '${row.day.year}-${row.day.month}-${row.day.day}': row,
    };
    final values = [
      for (final date in dates)
        byDate['${date.year}-${date.month}-${date.day}'],
    ];
    return Panel(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: spaced([
          const SectionTitle('최근 14일 운전 컨디션'),
          if (values.every((v) => v == null))
            const Muted('기록을 모으는 중')
          else ...[
            Semantics(
              label: values
                  .whereType<DayRow>()
                  .map(
                    (r) =>
                        '${r.day.month}월 ${r.day.day}일, ${r.alert ? '쉬어 가시면 좋겠어요' : '평소 범위 안'}, 컨디션 ${r.ewma}, 평소 범위 ${r.threshold}',
                  )
                  .join('. '),
              child: SizedBox(
                height: 160,
                child: CustomPaint(
                  painter: _ConditionPainter(
                    values,
                    colors(context).brand,
                    colors(context).watchFill,
                    colors(context).threshold,
                  ),
                ),
              ),
            ),
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                Muted('${dates.first.month}/${dates.first.day}'),
                Muted('${dates.last.month}/${dates.last.day}'),
              ],
            ),
            const Muted('점선: 평소 범위 · 호박색: 쉬어 가시면 좋겠어요'),
          ],
        ], 12),
      ),
    );
  }
}

class _ConditionPainter extends CustomPainter {
  _ConditionPainter(this.rows, this.brand, this.alertColor, this.line);
  final List<DayRow?> rows;
  final Color brand, alertColor, line;
  @override
  void paint(Canvas canvas, Size size) {
    final maximum =
        rows.whereType<DayRow>().fold<double>(
          .01,
          (m, r) => math.max(m, math.max(r.ewma, r.threshold)),
        ) *
        1.15;
    final step = size.width / 14;
    for (var i = 0; i < rows.length; i++) {
      final r = rows[i];
      if (r == null) continue;
      final height = (r.ewma / maximum).clamp(0, 1) * size.height;
      final left = i * step + step * .18;
      final width = step * .64;
      canvas.drawRRect(
        RRect.fromRectAndRadius(
          Rect.fromLTWH(left, size.height - height, width, height),
          const Radius.circular(4),
        ),
        Paint()..color = r.alert ? alertColor : brand,
      );
      final y = size.height - (r.threshold / maximum).clamp(0, 1) * size.height;
      for (double x = i * step; x < (i + 1) * step; x += 6) {
        canvas.drawLine(
          Offset(x, y),
          Offset(math.min(x + 3, (i + 1) * step), y),
          Paint()
            ..color = line
            ..strokeWidth = 2,
        );
      }
    }
  }

  @override
  bool shouldRepaint(covariant _ConditionPainter old) =>
      old.rows != rows ||
      old.brand != brand ||
      old.alertColor != alertColor ||
      old.line != line;
}
