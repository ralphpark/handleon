import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:phone/backend.dart';
import 'package:phone/share_options.dart';
import 'package:supabase_flutter/supabase_flutter.dart';

import 'support/sharing_fixtures.dart';
import 'support/new_sharing_fixtures.dart';
import 'support/coach_fixtures.dart';

const optionalPatternKeys = [
  'night_ratio',
  'long_ratio',
  'radius_ratio',
  'places_ratio',
  'usual_trips',
  'usual_minutes',
  'usual_night_ratio',
  'usual_long_ratio',
];

SentNote noteAt(String createdAt) =>
    SentNote.fromJson({...noteJson(), 'created_at': createdAt});

void main() {
  test(
    'coach rejects wrong counts blank non-string and overlong sentences',
    () {
      for (final key in ['openers', 'avoid', 'topic']) {
        final count = key == 'openers' ? 3 : 2;
        for (final bad in <Object?>[
          null,
          1,
          true,
          '',
          ' \n\t',
          '가' * 81,
          '😀' * 81,
        ]) {
          final json = coachJson();
          final advice = json['advice'] as Map<String, dynamic>;
          advice[key] = key == 'topic'
              ? bad
              : [bad, ...List.filled(count - 1, '이야기해요')];
          expect(
            () => CoachAdvice.fromJson(json),
            throwsA(isA<Unavailable>()),
            reason: '$key: $bad',
          );
        }
        if (key != 'topic') {
          for (final bad in <Object?>[
            null,
            {},
            '문장',
            List.filled(count - 1, '이야기해요'),
            List.filled(count + 1, '이야기해요'),
          ]) {
            final json = coachJson();
            (json['advice'] as Map<String, dynamic>)[key] = bad;
            expect(
              () => CoachAdvice.fromJson(json),
              throwsA(isA<Unavailable>()),
            );
          }
        }
      }
    },
  );
  test(
    'coach accepts exactly 80 Unicode code points and requires a known source',
    () {
      final sentence = '😀' * 80;
      final result = CoachAdvice.fromJson({
        'advice': {
          'openers': [sentence, sentence, sentence],
          'avoid': [sentence, sentence],
          'topic': sentence,
        },
        'source': 'ai',
      });
      expect(result.topic, sentence);
      for (final source in [null, '', 'AI', 'cached', 1]) {
        expect(
          () => CoachAdvice.fromJson({...coachJson(), 'source': source}),
          throwsA(isA<Unavailable>()),
        );
      }
      for (final body in <Object?>[
        null,
        [],
        {},
        {'advice': null, 'source': 'ai'},
      ]) {
        expect(() => CoachAdvice.fromJson(body), throwsA(isA<Unavailable>()));
      }
    },
  );
  test('new sharing parsers reject malformed consent and dates', () {
    for (final value in [null, 1, 'false']) {
      expect(
        () => ShareOptions.fromJson({
          'drive_done': null,
          'weekly_change': value,
          'habits': false,
        }),
        throwsFormatException,
      );
      expect(
        () => ShareOptions.fromJson({
          'drive_done': null,
          'weekly_change': false,
          'habits': value,
        }),
        throwsFormatException,
      );
    }
    for (final date in ['2026-02-30', '2026-10-27', '2026-10-26T00:00:00Z']) {
      expect(
        () => weekly(changes: {'week_start': date}),
        throwsFormatException,
      );
    }
    expect(
      () => DriveEvent.fromJson({
        ...driveEventJson(),
        'ended_at': '2026-10-27T14:30:00',
      }),
      throwsFormatException,
    );
    expect(
      () => DriveEvent.fromJson({...driveEventJson(), 'minutes': 1.5}),
      throwsFormatException,
    );
  });
  test(
    'habit counters are nonnegative and zero totals never imply success',
    () {
      final h = habit(
        changes: {
          'signal_total': 0,
          'signal_kept': 2,
          'promises_total': 0,
          'promises_kept': 4,
        },
      );
      expect(h.signalKept, 0);
      expect(h.promisesKept, 0);
      for (final key in [
        'trips',
        'minutes',
        'signal_total',
        'hard_brakes',
        'hard_accels',
        'night_trips',
      ]) {
        expect(() => habit(changes: {key: -1}), throwsFormatException);
        expect(() => habit(changes: {key: 0.5}), throwsFormatException);
      }
      expect(habit(changes: {'story': null}).story, isNull);
    },
  );
  test('PatternRow accepts whole-number doubles in every integer column', () {
    final row = pattern(
      changes: {
        'trips': 2000.0,
        'drive_minutes': 44640.0,
        'drive_days': 31.0,
        'usual_trips': 0.0,
        'usual_minutes': -0.0,
      },
    );
    expect(row.trips, 2000);
    expect(row.driveMinutes, 44640);
    expect(row.driveDays, 31);
    expect(row.usualTrips, 0);
    expect(row.usualMinutes, 0);
  });
  test('PatternRow widens integer ratios at the 0, 1 and 20 server limits', () {
    final row = pattern(
      changes: {
        'night_ratio': 0,
        'long_ratio': 1,
        'radius_ratio': 20,
        'places_ratio': 0,
        'usual_night_ratio': 1,
        'usual_long_ratio': 0,
      },
    );
    expect(row.nightRatio, 0.0);
    expect(row.longRatio, 1.0);
    expect(row.radiusRatio, 20.0);
    expect(row.placesRatio, 0.0);
    expect(row.usualNightRatio, 1.0);
    expect(row.usualLongRatio, 0.0);
  });
  test('PatternRow rejects non-finite required integers and fractional '
      'optional integers', () {
    for (final change in [
      {'trips': double.infinity},
      {'drive_minutes': double.negativeInfinity},
      {'drive_days': double.nan},
      {'drive_days': 30.5},
      {'usual_trips': 21.5},
      {'usual_minutes': 0.5},
    ]) {
      expect(
        () => pattern(changes: change),
        throwsFormatException,
        reason: '$change',
      );
    }
  });
  test('PatternRow treats absent optional keys like explicit nulls', () {
    final raw = patternJson()
      ..removeWhere((key, _) => optionalPatternKeys.contains(key));
    final row = PatternRow.fromJson(raw);
    expect([
      row.nightRatio,
      row.longRatio,
      row.radiusRatio,
      row.placesRatio,
      row.usualTrips,
      row.usualMinutes,
      row.usualNightRatio,
      row.usualLongRatio,
    ], everyElement(isNull));
    // driver_name 은 비어 있으면 '가족'으로 보여 주므로 필수 키가 아니다.
    for (final key in [
      'driver_id',
      'month',
      'partial',
      'trips',
      'drive_minutes',
      'drive_days',
    ]) {
      expect(
        () => PatternRow.fromJson(patternJson()..remove(key)),
        throwsFormatException,
        reason: key,
      );
    }
  });
  test('PatternRow month accepts calendar limits as local midnight', () {
    for (final (raw, expected) in [
      ('0001-01-01', DateTime(1)),
      ('2026-01-01', DateTime(2026)),
      ('2026-12-01', DateTime(2026, 12)),
      ('9999-12-01', DateTime(9999, 12)),
    ]) {
      final month = pattern(changes: {'month': raw}).month;
      expect(month, expected, reason: raw);
      expect(month.isUtc, false, reason: raw);
    }
  });
  test('PatternRow month rejects zero parts, short parts and extra text', () {
    for (final month in [
      '0000-01-01',
      '2026-00-01',
      '2026-1-01',
      '26-10-01',
      '2026-10-1',
      ' 2026-10-01',
      '2026-10-01 ',
      '2026-10-01\n',
      '2026/10/01',
      '２０２６-10-01',
    ]) {
      expect(
        () => pattern(changes: {'month': month}),
        throwsFormatException,
        reason: month,
      );
    }
  });
  // profiles.display_name 은 null 이 될 수 있다(0001_schema.sql:8, update 권한 있음).
  test('Share, InvitePreview and FeedRow fall back to 가족 for a null name', () {
    expect(
      Share.fromJson({
        'family_id': 'f',
        'family_name': null,
        'scope': 'stage',
        'delay_days': 0,
        'share_pattern': false,
      }).familyName,
      '가족',
    );
    expect(
      InvitePreview.fromJson({
        'driver_name': null,
        'scope': 'stage',
        'delay_days': 0,
        'share_pattern': false,
      }).driverName,
      '가족',
    );
    expect(
      FeedRow.fromJson({...feedJson(), 'driver_name': null}).driverName,
      '가족',
    );
  });
  test('SharingState keeps every recipient when one family_name is null', () {
    final state = SharingState.fromJson({
      'paused': false,
      'recipients': [
        {
          'family_name': '큰딸',
          'scope': 'detail',
          'delay_days': 0,
          'share_pattern': true,
        },
        {
          'family_name': null,
          'scope': 'stage',
          'delay_days': 7,
          'share_pattern': false,
        },
      ],
    });
    expect(state.recipients.map((r) => r.familyName), ['큰딸', '가족']);
  });
  test('PatternRow, FamilyLink and SentNote fall back to 가족 for a null '
      'driver_name', () {
    expect(pattern(changes: {'driver_name': null}).driverName, '가족');
    expect(
      FamilyLink.fromJson({...linkJson(), 'driver_name': null}).driverName,
      '가족',
    );
    expect(
      SentNote.fromJson({...noteJson(), 'driver_name': null}).driverName,
      '가족',
    );
  });
  test('SentNote reads Z, +00:00, -00:00 and a space separator as one UTC '
      'instant', () {
    for (final raw in [
      '2026-10-25T05:05:00Z',
      '2026-10-25T05:05:00+00:00',
      '2026-10-25T05:05:00-00:00',
      '2026-10-25 05:05:00+00:00',
      '2026-10-25T14:05:00+09:00',
    ]) {
      final createdAt = noteAt(raw).createdAt;
      expect(createdAt, DateTime.utc(2026, 10, 25, 5, 5), reason: raw);
      expect(createdAt.isUtc, true, reason: raw);
    }
  });
  test('SentNote keeps fractional seconds to the microsecond', () {
    for (final (raw, expected) in [
      ('2026-10-25T05:05:00.1Z', DateTime.utc(2026, 10, 25, 5, 5, 0, 100)),
      (
        '2026-10-25T05:05:00.123456+00:00',
        DateTime.utc(2026, 10, 25, 5, 5, 0, 123, 456),
      ),
      (
        '2026-10-25T14:05:00.000001+09:00',
        DateTime.utc(2026, 10, 25, 5, 5, 0, 0, 1),
      ),
      // Postgres 는 6자리까지만 보낸다. 그 이상은 DateTime.parse 가 잘라 낸다.
      (
        '2026-10-25T05:05:00.9999999Z',
        DateTime.utc(2026, 10, 25, 5, 5, 0, 999, 999),
      ),
    ]) {
      expect(noteAt(raw).createdAt, expected, reason: raw);
    }
  });
  test('SentNote converts offsets to UTC across day, month and year '
      'boundaries and back to the same local instant', () {
    for (final (raw, expected) in [
      ('2026-10-31T23:30:00-01:00', DateTime.utc(2026, 11, 1, 0, 30)),
      ('2027-01-01T08:59:59+09:00', DateTime.utc(2026, 12, 31, 23, 59, 59)),
      ('2026-10-25T05:05:00+23:59', DateTime.utc(2026, 10, 24, 5, 6)),
      ('2028-02-29T00:00:00Z', DateTime.utc(2028, 2, 29)),
    ]) {
      final note = SentNote.fromJson({
        ...noteJson(),
        'created_at': raw,
        'seen_at': raw,
      });
      expect(note.createdAt, expected, reason: raw);
      expect(note.seenAt, expected, reason: raw);
      final local = note.createdAt.toLocal();
      expect(local.isUtc, false, reason: raw);
      expect(local.isAtSameMomentAs(expected), true, reason: raw);
    }
  });
  test('SentNote rejects timestamps without a zone or with out-of-range '
      'fields', () {
    for (final raw in [
      '2026-10-25T05:05:00',
      '2026-10-25T05:05Z',
      '2026-10-25T24:00:00Z',
      '2026-10-25T23:60:00Z',
      '2026-10-25T23:59:60Z',
      '2026-10-25T05:05:00+24:00',
      '2026-10-25T05:05:00+09:60',
      '2026-10-25T05:05:00+09',
      '2026-10-25T05:05:00+0900',
      '2026-10-25T05:05:00.Z',
      '2026-10-25T05:05:00,5Z',
      '2026-10-25t05:05:00z',
      '2026-02-29T00:00:00Z',
      '2026-13-01T00:00:00Z',
      '0000-01-01T00:00:00Z',
    ]) {
      expect(() => noteAt(raw), throwsFormatException, reason: raw);
      expect(
        () => SentNote.fromJson({...noteJson(), 'seen_at': raw}),
        throwsFormatException,
        reason: 'seen_at $raw',
      );
    }
  });
  test('FamilyView requires paused, feed and patterns even when empty', () {
    final raw = {'paused': false, 'feed': [], 'patterns': []};
    for (final key in raw.keys) {
      expect(
        () => FamilyView.fromJson({...raw}..remove(key)),
        throwsFormatException,
        reason: 'missing $key',
      );
      expect(
        () => FamilyView.fromJson({...raw, key: null}),
        throwsFormatException,
        reason: 'null $key',
      );
    }
    for (final change in [
      {'feed': <String, dynamic>{}},
      {'patterns': <String, dynamic>{}},
      {
        'patterns': [null],
      },
      {
        'feed': ['row'],
      },
    ]) {
      expect(
        () => FamilyView.fromJson({...raw, ...change}),
        throwsFormatException,
        reason: '$change',
      );
    }
  });
  test('FamilyView fails as a whole when one pattern row is malformed', () {
    expect(
      () => FamilyView.fromJson({
        'paused': false,
        'feed': [feedJson()],
        'patterns': [
          patternJson(),
          {...patternJson(), 'month': '2026-09'},
        ],
      }),
      throwsFormatException,
    );
  });
  test('FamilyView keeps server row order and ignores unknown keys', () {
    final view = FamilyView.fromJson({
      'paused': false,
      'generated_at': '2026-10-26T00:00:00Z',
      'feed': [
        {...feedJson(), 'month': '2026-10-01', 'extra': 1},
        {...feedJson(), 'month': '2026-09-01'},
      ],
      'patterns': [
        {...patternJson(), 'month': '2026-10-01', 'source': 'car'},
        {...patternJson(), 'month': '2026-09-01', 'partial': false},
      ],
    });
    expect(view.feed.map((r) => r.month), [
      DateTime(2026, 10),
      DateTime(2026, 9),
    ]);
    expect(view.patterns.map((r) => r.month), [
      DateTime(2026, 10),
      DateTime(2026, 9),
    ]);
    expect(view.patterns.map((r) => r.partial), [true, false]);
  });
  test('FamilyView decodes preview_family_view JSON text with a zero-trip '
      'month', () {
    // 이번 달 운전이 없으면 차는 횟수 0, 비율 null 을 보낸다. real 열의 1 은 정수로 온다.
    const body = '''
{"paused": false,
 "feed": [{"driver_id": "d", "driver_name": "아버지", "month": "2026-10-01",
   "stage": "stable", "domains": null, "top": null, "letter": null,
   "scope": "stage"}],
 "patterns": [{"driver_id": "d", "driver_name": "아버지", "month": "2026-10-01",
   "partial": true, "trips": 0, "drive_minutes": 0, "drive_days": 0,
   "night_ratio": null, "long_ratio": null, "radius_ratio": 0,
   "places_ratio": 20, "usual_trips": 22, "usual_minutes": 660,
   "usual_night_ratio": 1, "usual_long_ratio": 0.145}]}
''';
    final view = FamilyView.fromJson(jsonDecode(body) as Map<String, dynamic>);
    expect(view.paused, false);
    expect(view.feed.single.scope, ShareScope.stage);
    final row = view.patterns.single;
    expect([row.trips, row.driveMinutes, row.driveDays], [0, 0, 0]);
    expect(row.nightRatio, isNull);
    expect(row.longRatio, isNull);
    expect(row.radiusRatio, 0.0);
    expect(row.placesRatio, 20.0);
    expect(row.usualNightRatio, 1.0);
    expect(row.usualLongRatio, 0.145);
  });
  test('NoteError passes existing NoteError values through unchanged', () {
    for (final error in NoteError.values) {
      expect(NoteError.fromError(error), error);
    }
  });
  test('NoteError matches the exact message and ignores code, hint and '
      'details', () {
    for (final (message, code, expected) in [
      ('invalid_note', 'P0001', NoteError.invalid),
      ('too_many_unseen', 'P0001', NoteError.tooManyUnseen),
      ('share_not_found', null, NoteError.shareEnded),
      ('family_required', 'P0001', NoteError.other),
      ('P0001', 'invalid_note', NoteError.other),
      ('', null, NoteError.other),
      ('Invalid_note', null, NoteError.other),
      (' too_many_unseen', null, NoteError.other),
      ('share_not_found\n', null, NoteError.other),
      ('error: invalid_note', null, NoteError.other),
    ]) {
      expect(
        NoteError.fromError(
          PostgrestException(
            message: message,
            code: code,
            hint: 'invalid_note',
          ),
        ),
        expected,
        reason: '$message / $code',
      );
    }
    for (final error in [
      AuthException('share_not_found'),
      const FormatException('too_many_unseen'),
      'invalid_note',
    ]) {
      expect(NoteError.fromError(error), NoteError.other, reason: '$error');
    }
  });
}
