import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:phone/backend.dart';
import 'package:phone/share_options.dart';
import 'package:supabase_flutter/supabase_flutter.dart';

import 'support/new_sharing_fixtures.dart';

void main() {
  test('share options parse null drive_done as off', () {
    final options = ShareOptions.fromJson({
      'drive_done': null,
      'weekly_change': false,
      'habits': false,
    });
    expect(options.driveDone, isNull);
    expect(options.weeklyChange, false);
    expect(options.habits, false);
    final recipient = SharingRecipient.fromJson({
      'family_name': '큰딸',
      'scope': 'stage',
      'delay_days': 7,
      'share_pattern': false,
      'drive_done': 'evening',
      'weekly_change': true,
      'habits': true,
    });
    expect(recipient.options.driveDone, DriveDoneMode.evening);
    expect(recipient.options.weeklyChange, true);
    expect(recipient.options.habits, true);
  });
  test('unknown drive_done value is rejected', () {
    for (final value in ['daily', '', 'EACH', 1, false]) {
      expect(
        () => ShareOptions.fromJson({
          'drive_done': value,
          'weekly_change': false,
          'habits': false,
        }),
        throwsFormatException,
      );
    }
  });
  test('drive event time is parsed as local', () {
    final e = DriveEvent.fromJson(driveEventJson());
    expect(e.endedAt.isUtc, false);
    expect(e.endedAt.isAtSameMomentAs(DateTime.utc(2026, 10, 27, 5, 30)), true);
    expect(
      (e.driverId, e.driverName, e.outingKey, e.minutes, e.rested),
      ('d', '아버지', '1793077200000', 42, true),
    );
  });
  test('weekly domains keep known names only', () {
    final row = weekly(
      changes: {
        'domains': ['unknown', 'MOBILITY', 'WAYFINDING', 'OPERATION'],
      },
    );
    expect(row.domains, ['MOBILITY', 'WAYFINDING', 'OPERATION']);
    expect(row.flagged, true);
    expect(row.weekStart, DateTime(2026, 10, 26));
    final quiet = weekly(changes: {'flagged': false, 'domains': [], 'top': []});
    expect(quiet.flagged, false);
    expect(quiet.domains, isEmpty);
  });
  test('habit kept never exceeds total after parse', () {
    final h = habit(changes: {'signal_kept': 21, 'promises_kept': 5});
    expect(h.signalKept, 20);
    expect(h.promisesKept, 3);
    expect(h.hardAccels, 2);
    expect(h.nightTrips, 1);
    expect(h.story, '운전 약속을 모두 지켰어요');
  });
  test('preview options and data arrays are decoded independently', () {
    final view = FamilyView.fromJson({
      'paused': false,
      'feed': [],
      'patterns': [],
      'drive_done': null,
      'weekly_change': false,
      'options': {'drive_done': 'each', 'weekly_change': true, 'habits': true},
      'drive_events': [driveEventJson()],
      'weekly': [weeklyJson()],
      'habits': [habitJson()],
    });
    expect(view.options.driveDone, DriveDoneMode.each);
    expect(view.options.weeklyChange, true);
    expect(view.options.habits, true);
    expect(view.driveEvents.single.minutes, 42);
    expect(view.weekly.single.flagged, true);
    expect(view.habits.single.hardAccels, 2);
  });
  test('server and network errors never show internal details', () {
    expect(
      friendlyError(
        const PostgrestException(message: 'invalid_or_expired_code'),
      ),
      '코드가 맞지 않거나 시간이 지났어요',
    );
    expect(
      friendlyError(
        const PostgrestException(message: 'scope_and_timing_required'),
      ),
      '보여 줄 범위와 시점, 운전 패턴 공유 여부를 모두 골라 주세요',
    );
    expect(
      friendlyError(const SocketException('host 192.0.2.1')),
      '연결하지 못했어요. 인터넷을 확인해 주세요',
    );
    expect(friendlyError(StateError('private detail')), '잠시 뒤 다시 해 주세요');
  });
  test('RPC 0006 names scopes and delays decode without defaults', () {
    final share = Share.fromJson({
      'family_id': 'f',
      'family_name': '큰딸',
      'scope': 'trend',
      'delay_days': 7,
      'share_pattern': false,
    });
    expect(share.familyName, '큰딸');
    expect(share.scope, ShareScope.trend);
    expect(share.timing, ShareTiming.d7);
    final row = FeedRow.fromJson({
      'driver_id': 'd',
      'driver_name': '아버지',
      'month': '2026-10-01',
      'stage': 'stable',
      'scope': 'stage',
      'domains': null,
      'top': null,
      'letter': null,
    });
    expect(row.domains, isNull);
    expect(row.top, isNull);
    expect(row.letter, isNull);
    final trend = FeedRow.fromJson({
      'driver_id': 'd',
      'driver_name': '아버지',
      'month': '2026-10-01',
      'stage': 'talk',
      'scope': 'trend',
      'domains': {'mobility': 1.132, 'operation': -0.022},
      'top': null,
      'letter': null,
    });
    expect(trend.domains, {'mobility': 1.132, 'operation': -0.022});
    expect(
      () => InvitePreview.fromJson({
        'driver_name': '아버지',
        'scope': 'unknown',
        'delay_days': 7,
        'share_pattern': false,
      }),
      throwsFormatException,
    );
  });
}
