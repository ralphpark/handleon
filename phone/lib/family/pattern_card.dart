import 'package:flutter/material.dart';

import '../backend.dart';
import '../ui.dart';
import 'summary_card.dart';

String _duration(int minutes) => minutes < 60
    ? '$minutes분'
    : minutes % 60 == 0
    ? '${minutes ~/ 60}시간'
    : '${minutes ~/ 60}시간 ${minutes % 60}분';
// 0.565 * 100 이 56.4999… 가 되는 이진 소수 오차만 보정한다(두 번 반올림하면 0.9949 가 100% 가 된다).
String _percent(double ratio) => '${(ratio * 100 + 1e-9).round()}%';
String _usual(String? value) => value == null ? '' : ' · 평소 $value';

class PatternCard extends StatelessWidget {
  const PatternCard({super.key, required this.row});
  final PatternRow row;

  @override
  Widget build(BuildContext context) => Panel(
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: spaced([
        SectionTitle(
          '운전 패턴 · ${monthLabel(row.month)}${row.partial ? ' (이번 달 지금까지)' : ''}',
        ),
        Text(
          '운전 ${row.trips}회${_usual(row.usualTrips == null ? null : '${row.usualTrips}회')}',
        ),
        Text(
          '운전 시간 ${_duration(row.driveMinutes)}${_usual(row.usualMinutes == null ? null : _duration(row.usualMinutes!))}',
        ),
        if (row.nightRatio != null)
          Text(
            '밤 운전 ${_percent(row.nightRatio!)}${_usual(row.usualNightRatio == null ? null : _percent(row.usualNightRatio!))}',
          ),
        if (row.longRatio != null)
          Text(
            '30km 넘는 운전 ${_percent(row.longRatio!)}${_usual(row.usualLongRatio == null ? null : _percent(row.usualLongRatio!))}',
          ),
        if (row.radiusRatio != null)
          Text('다니는 범위 · 평소의 ${_percent(row.radiusRatio!)}'),
        if (row.placesRatio != null)
          Text('가는 곳 가짓수 · 평소의 ${_percent(row.placesRatio!)}'),
        if (row.usualTrips == null &&
            row.usualMinutes == null &&
            row.usualNightRatio == null &&
            row.usualLongRatio == null)
          const Muted('평소 기준을 쌓는 중이에요.'),
        const Muted(
          '장소 이름과 좌표는 보내지 않아요. 운전이 줄거나 늘어난 이유는 날씨·일정·건강 등 여러 가지일 수 있어요.',
        ),
      ], 12),
    ),
  );
}
