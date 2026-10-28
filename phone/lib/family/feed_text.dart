import '../backend.dart';

enum FeedSection { weekly, drive, habits, stage }

// letter.ts FORBIDDEN + dispatch.ts display-name restrictions.
const _forbidden = [
  '\uce58\ub9e4',
  '\uc778\uc9c0\uc7a5\uc560',
  '\uc9c4\ub2e8',
  '\uc704\ud5d8',
  '\uc810\uc218',
  '\uc9c8\ud658',
  '\ud310\uc815',
  '\uc6b0\uc6b8',
  '\ub4f1\uae09',
  '\uc21c\uc704',
  '\uc54c\uce20\ud558\uc774\uba38',
  '\ubc18\ub0a9',
  '\uace0\ub839\uc6b4\uc804\uc790',
];

String safeFamilyName(String name) {
  final compact = name.replaceAll(
    RegExp(r'[\s\u00ad\u200b-\u200f\u202a-\u202e\u2060-\u206f\ufeff]'),
    '',
  );
  // Decomposed/compatibility jamo can disguise a prohibited word. Use the same
  // neutral fallback as dispatch rather than adding a normalization dependency.
  if (compact.isEmpty ||
      RegExp(r'[\u1100-\u11ff\u3130-\u318f\ua960-\ua97f\ud7b0-\ud7ff]')
          .hasMatch(compact) ||
      _forbidden.any(compact.contains)) {
    return '가족';
  }
  return name.trim();
}

String roughTime(DateTime local) {
  final hour = (local.hour + (local.minute < 30 ? 0 : 1)) % 24;
  if (hour == 0) return '밤 12시쯤';
  if (hour < 12) return '오전 $hour시쯤';
  return '오후 ${hour == 12 ? 12 : hour - 12}시쯤';
}

String driveEventLine(DriveEvent e) =>
    '${roughTime(e.endedAt.toLocal())} · ${e.minutes}분${e.rested ? ' · 중간에 한 번 쉬셨어요' : ''}';

DateTime localDay(DateTime time) {
  final local = time.toLocal();
  return DateTime(local.year, local.month, local.day);
}

/// The first event identifies the calendar day; ignore other days/drivers even
/// if a caller accidentally includes them. Rendering labels historical dates.
String eveningLine(String name, List<DriveEvent> sameDay) {
  if (sameDay.isEmpty) return '아직 운전 소식이 없어요';
  final first = sameDay.first;
  final count = sameDay
      .where(
        (e) =>
            e.driverId == first.driverId &&
            localDay(e.endedAt) == localDay(first.endedAt),
      )
      .length;
  return '오늘 $count번 운전하셨고 모두 무사히 마치셨어요';
}

String weeklyFamilyLine(String name, List<String> domains) {
  final change = domains.contains('MOBILITY')
      ? '요즘 몇 주 평소보다 운전을 덜 하셨어요'
      : domains.contains('WAYFINDING')
      ? '요즘 몇 주 길 찾기에 평소와 다른 점이 있어요'
      : domains.contains('OPERATION')
      ? '요즘 몇 주 운전 습관에 평소와 다른 점이 있어요'
      : '요즘 몇 주 운전에 평소와 다른 점이 있어요';
  return '${safeFamilyName(name)}님이 $change. 안부 전화 한 통 어떠세요?';
}

String habitLine(WeeklyHabit h) {
  final signal = h.signalTotal == 0
      ? '방향지시등 기록 없음'
      : '방향지시등 ${(100 * h.signalKept.clamp(0, h.signalTotal) / h.signalTotal).round()}%';
  return '이번 주 ${h.trips}번 운전 · $signal · 급제동 ${h.hardBrakes}번';
}

String? habitStory(String? story) {
  if (story == null) return null;
  const allowed = {
    '이번 주 운전 기록을 함께 돌아보세요',
    '이번 주에도 쉬어 가며 운전했어요',
    '방향지시등을 모두 켰어요',
    '운전 약속을 모두 지켰어요',
  };
  // 차가 보내는 "이번 주 N번 운전했어요"는 habitLine 과 같은 정보라서 보이지 않는다.
  return allowed.contains(story) ? story : null;
}

String feedDate(DateTime date) => '${date.year}년 ${date.month}월 ${date.day}일';
