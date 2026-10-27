import 'package:phone/backend.dart';

Map<String, dynamic> driveEventJson() => {
  'driver_id': 'd',
  'driver_name': '아버지',
  'outing_key': '1793077200000',
  'ended_at': '2026-10-27T05:30:00Z',
  'minutes': 42,
  'rested': true,
};

Map<String, dynamic> weeklyJson() => {
  'driver_id': 'd',
  'driver_name': '아버지',
  'week_start': '2026-10-26',
  'flagged': true,
  'domains': ['MOBILITY'],
  'top': [
    {'feature': 'M1', 'direction': 'down'},
  ],
};

Map<String, dynamic> habitJson() => {
  'driver_id': 'd',
  'driver_name': '아버지',
  'week_start': '2026-10-26',
  'trips': 5,
  'minutes': 140,
  'signal_kept': 19,
  'signal_total': 20,
  'hard_brakes': 1,
  'hard_accels': 2,
  'calm_trips': 3,
  'long_stretches': 0,
  'night_trips': 1,
  'promises_kept': 3,
  'promises_total': 3,
  'story': '운전 약속을 모두 지켰어요',
};

DriveEvent driveEvent({
  DateTime? at,
  String id = 'd',
  String key = '1',
  bool rested = false,
}) => DriveEvent.fromJson({
  ...driveEventJson(),
  'driver_id': id,
  'outing_key': key,
  'ended_at': (at ?? DateTime.now().subtract(const Duration(hours: 1)))
      .toUtc()
      .toIso8601String(),
  'rested': rested,
});

WeeklyChange weekly({Map<String, dynamic> changes = const {}}) =>
    WeeklyChange.fromJson({...weeklyJson(), ...changes});

WeeklyHabit habit({Map<String, dynamic> changes = const {}}) =>
    WeeklyHabit.fromJson({...habitJson(), ...changes});
