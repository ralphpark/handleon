import 'package:flutter_test/flutter_test.dart';
import 'package:phone/backend.dart';
import 'package:phone/share_options.dart';
import 'package:supabase_flutter/supabase_flutter.dart';

import 'support/sharing_fixtures.dart';

void main() {
  test('PatternRow parses num values and every nullable measure', () {
    final row = pattern(changes: {'trips': 18.0, 'night_ratio': 0});
    expect(row.trips, 18);
    expect(row.driveMinutes, 540);
    expect(row.driveDays, 12);
    expect(row.nightRatio, 0.0);
    expect(row.usualMinutes, 660);
    expect(row.month, DateTime(2026, 10));
    for (final value in [
      null,
      double.nan,
      double.infinity,
      double.negativeInfinity,
    ]) {
      final raw = patternJson();
      for (final key in raw.keys.toList()) {
        if (key.contains('ratio') || key.startsWith('usual_')) raw[key] = value;
      }
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
    }
  });
  test('PatternRow rejects malformed month and types with FormatException', () {
    for (final month in [
      null,
      202610,
      '',
      '2026-13-01',
      '2026-02-30',
      '2026-10-02',
      '2026-10',
      '2026-10-01T00:00:00Z',
    ]) {
      expect(() => pattern(changes: {'month': month}), throwsFormatException);
    }
    for (final change in [
      {'trips': '18'},
      {'trips': 1.5},
      {'drive_minutes': double.nan},
      {'partial': 1},
      {'driver_id': null},
      {'night_ratio': '0.1'},
    ]) {
      expect(() => pattern(changes: change), throwsFormatException);
    }
  });
  test('FamilyLink parses explicit choices and pause without defaults', () {
    final link = FamilyLink.fromJson(linkJson());
    expect(link.driverId, 'd');
    expect(link.driverName, '아버지');
    expect(link.scope, ShareScope.trend);
    expect(link.timing, ShareTiming.d7);
    expect(link.sharePattern, true);
    expect(link.paused, false);
    for (final key in [
      'paused',
      'share_pattern',
      'scope',
      'delay_days',
      'driver_id',
    ]) {
      expect(
        () => FamilyLink.fromJson({...linkJson(), key: null}),
        throwsFormatException,
      );
    }
  });
  test('SharingState decodes recipients and rejects malformed collections', () {
    final raw = {
      'paused': true,
      'recipients': [
        {
          'family_name': '큰딸',
          'scope': 'detail',
          'delay_days': 3.0,
          'share_pattern': false,
        },
      ],
    };
    final state = SharingState.fromJson(raw);
    expect(state.paused, true);
    expect(state.recipients.single.familyName, '큰딸');
    expect(state.recipients.single.scope, ShareScope.detail);
    expect(state.recipients.single.timing, ShareTiming.d3);
    expect(state.recipients.single.sharePattern, false);
    expect(
      SharingState.fromJson({'paused': false, 'recipients': []}).recipients,
      isEmpty,
    );
    for (final value in [
      null,
      {},
      [3],
    ]) {
      expect(
        () => SharingState.fromJson({...raw, 'recipients': value}),
        throwsFormatException,
      );
    }
  });
  test('FamilyView decodes the same feed and pattern rows', () {
    final view = FamilyView.fromJson({
      'paused': false,
      'feed': [feedJson()],
      'patterns': [patternJson()],
    });
    expect(view.paused, false);
    expect(view.feed.single.driverId, 'd');
    expect(view.patterns.single.driveMinutes, 540);
    final empty = FamilyView.fromJson({
      'paused': true,
      'feed': [],
      'patterns': [],
    });
    expect(empty.paused, true);
    expect(empty.feed, isEmpty);
    expect(empty.patterns, isEmpty);
    expect(
      () => FamilyView.fromJson({'paused': 'true', 'feed': [], 'patterns': []}),
      throwsFormatException,
    );
  });
  test('SentNote parses bigint, timestamps and nullable seenAt', () {
    final note = SentNote.fromJson(noteJson());
    expect(note.id, 1);
    expect(note.body, '늘 응원해요');
    expect(note.driverName, '아버지');
    expect(note.createdAt, DateTime.utc(2026, 10, 25, 5, 5));
    expect(note.seenAt, isNull);
    expect(
      SentNote.fromJson({
        ...noteJson(),
        'id': 2.0,
        'seen_at': '2026-10-25T15:05:00+09:00',
      }).seenAt,
      DateTime.utc(2026, 10, 25, 6, 5),
    );
    for (final change in [
      {'id': '1'},
      {'body': 1},
      {'created_at': '2026-02-30T10:00:00Z'},
      {'seen_at': 'bad'},
    ]) {
      expect(
        () => SentNote.fromJson({...noteJson(), ...change}),
        throwsFormatException,
      );
    }
  });
  test('NoteError maps only PostgrestException.message', () {
    for (final (message, expected) in [
      ('invalid_note', NoteError.invalid),
      ('too_many_unseen', NoteError.tooManyUnseen),
      ('share_not_found', NoteError.shareEnded),
      ('private detail', NoteError.other),
    ]) {
      expect(
        NoteError.fromError(PostgrestException(message: message)),
        expected,
      );
    }
    expect(NoteError.fromError(StateError('invalid_note')), NoteError.other);
    expect(
      NoteError.fromError(
        const PostgrestException(message: 'other', details: 'invalid_note'),
      ),
      NoteError.other,
    );
  });
  test('Share and InvitePreview require explicit pattern consent', () {
    final raw = {
      'family_id': 'f',
      'family_name': '큰딸',
      'driver_name': '아버지',
      'scope': 'stage',
      'delay_days': 0,
    };
    expect(() => Share.fromJson(raw), throwsFormatException);
    expect(() => InvitePreview.fromJson(raw), throwsFormatException);
    for (final value in [true, false]) {
      expect(
        Share.fromJson({...raw, 'share_pattern': value}).sharePattern,
        value,
      );
      expect(
        InvitePreview.fromJson({...raw, 'share_pattern': value}).sharePattern,
        value,
      );
    }
  });
}
