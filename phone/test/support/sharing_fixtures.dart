import 'package:phone/backend.dart';
import 'package:phone/share_options.dart';

Map<String, dynamic> patternJson() => {
  'driver_id': 'd',
  'driver_name': '아버지',
  'month': '2026-10-01',
  'partial': true,
  'trips': 18,
  'drive_minutes': 540,
  'drive_days': 12,
  'night_ratio': .104,
  'long_ratio': .05,
  'radius_ratio': .7,
  'places_ratio': .8,
  'usual_trips': 22,
  'usual_minutes': 660,
  'usual_night_ratio': .15,
  'usual_long_ratio': .1,
};

PatternRow pattern({Map<String, dynamic> changes = const {}}) =>
    PatternRow.fromJson({...patternJson(), ...changes});

FamilyLink familyLink({
  String id = 'd',
  String name = '아버지',
  bool paused = false,
  bool sharePattern = true,
}) => FamilyLink(
  driverId: id,
  driverName: name,
  scope: ShareScope.detail,
  timing: ShareTiming.now,
  sharePattern: sharePattern,
  paused: paused,
);

Map<String, dynamic> noteJson() => {
  'id': 1,
  'driver_id': 'd',
  'driver_name': '아버지',
  'body': '늘 응원해요',
  'created_at': '2026-10-25T14:05:00+09:00',
  'seen_at': null,
};

Map<String, dynamic> linkJson() => {
  'driver_id': 'd',
  'driver_name': '아버지',
  'scope': 'trend',
  'delay_days': 7,
  'share_pattern': true,
  'paused': false,
};

Map<String, dynamic> feedJson() => {
  'driver_id': 'd',
  'driver_name': '아버지',
  'month': '2026-10-01',
  'stage': 'stable',
  'scope': 'stage',
  'domains': null,
  'top': null,
  'letter': null,
};
