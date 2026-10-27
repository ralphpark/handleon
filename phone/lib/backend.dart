import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:math';
import 'dart:typed_data';

import 'package:supabase_flutter/supabase_flutter.dart';

import 'share_options.dart';
import 'device_push_tokens.dart';

enum Role { driver, family }

enum SignUpResult { signedIn, confirmEmail }

const authRedirect = 'handleon://login';

abstract class Backend {
  Role? get role;
  String? get userId;
  String? get displayName;
  Stream<void> get changes;
  Future<SignUpResult> signUp({
    required String email,
    required String password,
    required Role role,
    required String displayName,
  });
  Future<void> signIn(String email, String password);
  Future<void> signOut();
  Future<void> claimPairing(String code);
  Future<bool> carLinked();
  Future<void> uploadImport(String contractAJson);
  Future<String> createInvite(
    ShareScope scope,
    ShareTiming timing,
    bool sharePattern,
  );
  Future<List<Share>> myShares();
  Future<void> updateShare(
    String familyId,
    ShareScope scope,
    ShareTiming timing,
    bool sharePattern,
  );
  Future<SharingState> sharingState();
  Future<void> setShareOptions(String familyId, ShareOptions options);
  Future<void> setSharingPaused(bool paused);
  Future<FamilyView> previewFamilyView(String familyId);
  Future<void> revokeShare(String familyId);
  Future<InvitePreview> previewInvite(String code);
  Future<void> acceptInvite(String code);
  Future<List<FeedRow>> familyFeed();
  Future<List<DriveEvent>> familyDriveEvents();
  Future<List<WeeklyChange>> familyWeekly();
  Future<List<WeeklyHabit>> familyHabits();
  Future<CoachAdvice> coach(String driverId);
  Future<ClinicSummary> clinicSummary();
  Future<List<PatternRow>> familyPatterns();
  Future<List<FamilyLink>> familyLinks();
  Future<int> sendNote(String driverId, String body);
  Future<List<SentNote>> sentNotes();
  Future<List<MonthRow>> mySummaries();
  Future<List<DayRow>> myConditions();
  Future<void> registerPushToken(String token, String platform);
}

class Share {
  const Share({
    required this.familyId,
    required this.familyName,
    required this.scope,
    required this.timing,
    required this.sharePattern,
  });
  final String familyId, familyName;
  final ShareScope scope;
  final ShareTiming timing;
  final bool sharePattern;
  factory Share.fromJson(Map<String, dynamic> j) => Share(
    familyId: _string(j['family_id']),
    familyName: _string(j['family_name'] ?? '가족'),
    scope: _scope(j['scope']),
    timing: _timing(j['delay_days']),
    sharePattern: _boolean(j['share_pattern']),
  );
}

class InvitePreview {
  const InvitePreview({
    required this.driverName,
    required this.scope,
    required this.timing,
    required this.sharePattern,
  });
  final String driverName;
  final ShareScope scope;
  final ShareTiming timing;
  final bool sharePattern;
  factory InvitePreview.fromJson(Map<String, dynamic> j) => InvitePreview(
    driverName: _string(j['driver_name'] ?? '가족'),
    scope: _scope(j['scope']),
    timing: _timing(j['delay_days']),
    sharePattern: _boolean(j['share_pattern']),
  );
}

ShareScope _scope(Object? v) =>
    ShareScope.fromWire(v) ?? (throw const FormatException());
ShareTiming _timing(Object? v) =>
    ShareTiming.fromWire(_integer(v)) ?? (throw const FormatException());

bool _boolean(Object? value) =>
    value is bool ? value : throw const FormatException();
String _string(Object? value) =>
    value is String ? value : throw const FormatException();
Map<String, dynamic> _object(Object? value) {
  if (value is! Map<String, dynamic>) throw const FormatException();
  return value;
}

double? _optionalNumber(Object? value) {
  if (value == null) return null;
  if (value is! num) throw const FormatException();
  return value.isFinite ? value.toDouble() : null;
}

int? _optionalInteger(Object? value) {
  if (value == null) return null;
  if (value is int) return value;
  if (value is! num) throw const FormatException();
  if (!value.isFinite) return null;
  if (value != value.truncateToDouble()) throw const FormatException();
  return value.toInt();
}

int _integer(Object? value) =>
    _optionalInteger(value) ?? (throw const FormatException());

DateTime _month(Object? value) {
  final raw = _string(value);
  if (!RegExp(r'^\d{4}-\d{2}-01$').hasMatch(raw)) {
    throw const FormatException();
  }
  final year = int.parse(raw.substring(0, 4));
  final month = int.parse(raw.substring(5, 7));
  if (year < 1 || month < 1 || month > 12) throw const FormatException();
  return DateTime(year, month);
}

DateTime _timestamp(Object? value) {
  final raw = _string(value);
  final match = RegExp(
    r'^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2}):(\d{2})(?:\.\d+)?(Z|[+-]\d{2}:\d{2})$',
  ).firstMatch(raw);
  if (match == null) throw const FormatException();
  final parts = [for (var i = 1; i <= 6; i++) int.parse(match.group(i)!)];
  final date = DateTime.utc(parts[0], parts[1], parts[2]);
  final zone = match.group(7)!;
  if (parts[0] < 1 ||
      date.year != parts[0] ||
      date.month != parts[1] ||
      date.day != parts[2] ||
      parts[3] > 23 ||
      parts[4] > 59 ||
      parts[5] > 59 ||
      (zone != 'Z' &&
          (int.parse(zone.substring(1, 3)) > 23 ||
              int.parse(zone.substring(4, 6)) > 59))) {
    throw const FormatException();
  }
  return DateTime.parse(raw);
}

class SharingRecipient {
  const SharingRecipient({
    required this.familyName,
    required this.scope,
    required this.timing,
    required this.sharePattern,
    this.options = const ShareOptions(),
  });
  final String familyName;
  final ShareScope scope;
  final ShareTiming timing;
  final bool sharePattern;
  final ShareOptions options;
  factory SharingRecipient.fromJson(Map<String, dynamic> j) => SharingRecipient(
    familyName: _string(j['family_name'] ?? '가족'),
    scope: _scope(j['scope']),
    timing: _timing(j['delay_days']),
    sharePattern: _boolean(j['share_pattern']),
    options: ShareOptions.fromJson(j),
  );
}

class SharingState {
  const SharingState({required this.paused, required this.recipients});
  final bool paused;
  final List<SharingRecipient> recipients;
  factory SharingState.fromJson(Map<String, dynamic> j) => SharingState(
    paused: _boolean(j['paused']),
    recipients: _rows(j['recipients']).map(SharingRecipient.fromJson).toList(),
  );
}

class PatternRow {
  const PatternRow({
    required this.driverId,
    required this.driverName,
    required this.month,
    required this.partial,
    required this.trips,
    required this.driveMinutes,
    required this.driveDays,
    this.nightRatio,
    this.longRatio,
    this.radiusRatio,
    this.placesRatio,
    this.usualTrips,
    this.usualMinutes,
    this.usualNightRatio,
    this.usualLongRatio,
  });
  final String driverId, driverName;
  final DateTime month;
  final bool partial;
  final int trips, driveMinutes, driveDays;
  final double? nightRatio, longRatio, radiusRatio, placesRatio;
  final int? usualTrips, usualMinutes;
  final double? usualNightRatio, usualLongRatio;
  factory PatternRow.fromJson(Map<String, dynamic> j) => PatternRow(
    driverId: _string(j['driver_id']),
    driverName: _string(j['driver_name'] ?? '가족'),
    month: _month(j['month']),
    partial: _boolean(j['partial']),
    trips: _integer(j['trips']),
    driveMinutes: _integer(j['drive_minutes']),
    driveDays: _integer(j['drive_days']),
    nightRatio: _optionalNumber(j['night_ratio']),
    longRatio: _optionalNumber(j['long_ratio']),
    radiusRatio: _optionalNumber(j['radius_ratio']),
    placesRatio: _optionalNumber(j['places_ratio']),
    usualTrips: _optionalInteger(j['usual_trips']),
    usualMinutes: _optionalInteger(j['usual_minutes']),
    usualNightRatio: _optionalNumber(j['usual_night_ratio']),
    usualLongRatio: _optionalNumber(j['usual_long_ratio']),
  );
}

class FamilyLink {
  const FamilyLink({
    required this.driverId,
    required this.driverName,
    required this.scope,
    required this.timing,
    required this.sharePattern,
    required this.paused,
  });
  final String driverId, driverName;
  final ShareScope scope;
  final ShareTiming timing;
  final bool sharePattern, paused;
  factory FamilyLink.fromJson(Map<String, dynamic> j) => FamilyLink(
    driverId: _string(j['driver_id']),
    driverName: _string(j['driver_name'] ?? '가족'),
    scope: _scope(j['scope']),
    timing: _timing(j['delay_days']),
    sharePattern: _boolean(j['share_pattern']),
    paused: _boolean(j['paused']),
  );
}

class FamilyView {
  const FamilyView({
    required this.paused,
    required this.feed,
    required this.patterns,
    this.options = const ShareOptions(),
    this.driveEvents = const [],
    this.weekly = const [],
    this.habits = const [],
  });
  final bool paused;
  final List<FeedRow> feed;
  final List<PatternRow> patterns;
  final ShareOptions options;
  final List<DriveEvent> driveEvents;
  final List<WeeklyChange> weekly;
  final List<WeeklyHabit> habits;
  factory FamilyView.fromJson(Map<String, dynamic> j) => FamilyView(
    paused: _boolean(j['paused']),
    feed: _rows(j['feed']).map(FeedRow.fromJson).toList(),
    patterns: _rows(j['patterns']).map(PatternRow.fromJson).toList(),
    options: j.containsKey('options')
        ? ShareOptions.fromJson(_object(j['options']))
        : const ShareOptions(),
    driveEvents: _rows(j.containsKey('drive_events') ? j['drive_events'] : [])
        .map(DriveEvent.fromJson)
        .toList(),
    weekly: _rows(j.containsKey('weekly') ? j['weekly'] : [])
        .map(WeeklyChange.fromJson)
        .toList(),
    habits: _rows(j.containsKey('habits') ? j['habits'] : [])
        .map(WeeklyHabit.fromJson)
        .toList(),
  );
}

int _count(Object? value, {int minimum = 0, int? maximum}) {
  final count = _integer(value);
  if (count < minimum || (maximum != null && count > maximum)) {
    throw const FormatException();
  }
  return count;
}

DateTime _weekStart(Object? value) {
  final raw = _string(value);
  if (!RegExp(r'^\d{4}-\d{2}-\d{2}$').hasMatch(raw)) {
    throw const FormatException();
  }
  final date = _timestamp('${raw}T00:00:00Z');
  if (date.weekday != DateTime.monday) throw const FormatException();
  return DateTime(date.year, date.month, date.day);
}

class DriveEvent {
  const DriveEvent({
    required this.driverId,
    required this.driverName,
    required this.outingKey,
    required this.endedAt,
    required this.minutes,
    required this.rested,
  });
  final String driverId, driverName, outingKey;
  final DateTime endedAt;
  final int minutes;
  final bool rested;

  factory DriveEvent.fromJson(Map<String, dynamic> j) => DriveEvent(
    driverId: _string(j['driver_id']),
    driverName: _string(j['driver_name'] ?? '가족'),
    outingKey: _string(j['outing_key']),
    endedAt: _timestamp(j['ended_at']).toLocal(),
    minutes: _count(j['minutes'], minimum: 1, maximum: 1440),
    rested: _boolean(j['rested']),
  );
}

class WeeklyChange {
  const WeeklyChange({
    required this.driverId,
    required this.driverName,
    required this.weekStart,
    required this.flagged,
    required this.domains,
  });
  final String driverId, driverName;
  final DateTime weekStart;
  final bool flagged;
  final List<String> domains;

  factory WeeklyChange.fromJson(Map<String, dynamic> j) {
    final domains = j['domains'];
    if (domains is! List) throw const FormatException();
    return WeeklyChange(
      driverId: _string(j['driver_id']),
      driverName: _string(j['driver_name'] ?? '가족'),
      weekStart: _weekStart(j['week_start']),
      flagged: _boolean(j['flagged']),
      domains: domains
          .whereType<String>()
          .where(const {'MOBILITY', 'WAYFINDING', 'OPERATION'}.contains)
          .toList(),
    );
  }
}

class WeeklyHabit {
  const WeeklyHabit({
    required this.driverId,
    required this.driverName,
    required this.weekStart,
    required this.trips,
    required this.minutes,
    required this.signalKept,
    required this.signalTotal,
    required this.hardBrakes,
    required this.hardAccels,
    required this.calmTrips,
    required this.longStretches,
    required this.nightTrips,
    required this.promisesKept,
    required this.promisesTotal,
    this.story,
  });
  final String driverId, driverName;
  final DateTime weekStart;
  final int trips,
      minutes,
      signalKept,
      signalTotal,
      hardBrakes,
      hardAccels,
      calmTrips,
      longStretches,
      nightTrips,
      promisesKept,
      promisesTotal;
  final String? story;

  factory WeeklyHabit.fromJson(Map<String, dynamic> j) {
    final trips = _count(j['trips'], maximum: 200);
    final signalTotal = _count(j['signal_total']);
    final promisesTotal = _count(j['promises_total']);
    return WeeklyHabit(
      driverId: _string(j['driver_id']),
      driverName: _string(j['driver_name'] ?? '가족'),
      weekStart: _weekStart(j['week_start']),
      trips: trips,
      minutes: _count(j['minutes'], maximum: 10080),
      signalKept: _integer(j['signal_kept']).clamp(0, signalTotal),
      signalTotal: signalTotal,
      hardBrakes: _count(j['hard_brakes']),
      hardAccels: _count(j['hard_accels']),
      calmTrips: _count(j['calm_trips']).clamp(0, trips),
      longStretches: _count(j['long_stretches']),
      nightTrips: _count(j['night_trips']).clamp(0, trips),
      promisesKept: _integer(j['promises_kept']).clamp(0, promisesTotal),
      promisesTotal: promisesTotal,
      story: j['story'] == null ? null : _string(j['story']),
    );
  }
}

enum ClinicError implements Exception { unavailable }

class ClinicSummary {
  const ClinicSummary({
    required this.summary,
    required this.questions,
    required this.fromFallback,
  });
  final String summary;
  final List<String> questions;
  final bool fromFallback;

  factory ClinicSummary.fromJson(Object? value) {
    String sentence(Object? raw, int maximum) {
      if (raw is! String || raw.trim().isEmpty || raw.runes.length > maximum) {
        throw ClinicError.unavailable;
      }
      return raw;
    }

    if (value is! Map<String, dynamic> ||
        (value['source'] != 'ai' && value['source'] != 'fallback')) {
      throw ClinicError.unavailable;
    }
    final questions = value['questions'];
    if (questions is! List || questions.length != 3) {
      throw ClinicError.unavailable;
    }
    return ClinicSummary(
      summary: sentence(value['summary'], 600),
      questions: List.unmodifiable(questions.map((q) => sentence(q, 80))),
      fromFallback: value['source'] == 'fallback',
    );
  }
}

class CoachAdvice {
  const CoachAdvice({
    required this.openers,
    required this.avoid,
    required this.topic,
    required this.fromFallback,
  });
  final List<String> openers, avoid;
  final String topic;
  final bool fromFallback;

  factory CoachAdvice.fromJson(Object? value) {
    String sentence(Object? raw) {
      if (raw is! String || raw.trim().isEmpty || raw.runes.length > 80) {
        throw const Unavailable();
      }
      return raw;
    }

    List<String> sentences(Object? raw, int count) {
      if (raw is! List || raw.length != count) throw const Unavailable();
      return List.unmodifiable(raw.map(sentence));
    }

    if (value is! Map<String, dynamic> ||
        (value['source'] != 'ai' && value['source'] != 'fallback')) {
      throw const Unavailable();
    }
    final advice = value['advice'];
    if (advice is! Map<String, dynamic>) throw const Unavailable();
    return CoachAdvice(
      openers: sentences(advice['openers'], 3),
      avoid: sentences(advice['avoid'], 2),
      topic: sentence(advice['topic']),
      fromFallback: value['source'] == 'fallback',
    );
  }
}

sealed class CoachError implements Exception {
  const CoachError();

  static CoachError fromError(Object error) {
    if (error is CoachError) return error;
    if (error is FunctionException && error.details is Map) {
      final code = (error.details as Map)['error'];
      if (error.status == 429 && code == 'daily_limit') {
        return const DailyLimit();
      }
      if (error.status == 404 && code == 'share_not_found') {
        return const ShareNotFound();
      }
    }
    return const Unavailable();
  }
}

final class DailyLimit extends CoachError {
  const DailyLimit();
}

final class ShareNotFound extends CoachError {
  const ShareNotFound();
}

final class Unavailable extends CoachError {
  const Unavailable();
}

class SentNote {
  const SentNote({
    required this.id,
    required this.driverId,
    required this.driverName,
    required this.body,
    required this.createdAt,
    required this.seenAt,
  });
  final int id;
  final String driverId, driverName, body;
  final DateTime createdAt;
  final DateTime? seenAt;
  factory SentNote.fromJson(Map<String, dynamic> j) => SentNote(
    id: _integer(j['id']),
    driverId: _string(j['driver_id']),
    driverName: _string(j['driver_name'] ?? '가족'),
    body: _string(j['body']),
    createdAt: _timestamp(j['created_at']),
    seenAt: j['seen_at'] == null ? null : _timestamp(j['seen_at']),
  );
}

enum NoteError implements Exception {
  invalid,
  tooManyUnseen,
  shareEnded,
  other;

  static NoteError fromError(Object error) {
    if (error is NoteError) return error;
    if (error is! PostgrestException) return other;
    return switch (error.message) {
      'invalid_note' => invalid,
      'too_many_unseen' => tooManyUnseen,
      'share_not_found' => shareEnded,
      _ => other,
    };
  }
}

class TopItem {
  const TopItem({
    required this.text,
    required this.grade,
    required this.feature,
  });
  final String text, grade, feature;
  factory TopItem.fromJson(Map<String, dynamic> j) => TopItem(
    text: _string(j['text']),
    grade: _string(j['grade']),
    feature: _string(j['feature']),
  );
}

Map<String, double>? _domains(Object? raw) => raw == null
    ? null
    : {
        for (final entry in _object(raw).entries)
          // 개인 임계값 대비 비율이라 0 보다 작아도 실제 값이다(평소보다 덜 달라짐).
          if (entry.value is num && (entry.value as num).isFinite)
            entry.key: (entry.value as num).toDouble(),
      };
List<TopItem>? _top(Object? raw) =>
    raw == null ? null : _rows(raw).take(3).map(TopItem.fromJson).toList();

class MonthRow {
  const MonthRow({
    required this.month,
    required this.mode,
    required this.stage,
    this.domains,
    this.top,
    this.letter,
    this.source = 'car',
  });
  final DateTime month;
  final String mode, stage, source;
  final Map<String, double>? domains;
  final List<TopItem>? top;
  final String? letter;
  factory MonthRow.fromJson(Map<String, dynamic> j) => MonthRow(
    month: _month(j['month']),
    mode: j['mode'] as String,
    stage: _string(j['stage']),
    domains: _domains(j['domains']),
    top: _top(j['top']),
    letter: j['letter'] == null ? null : _string(j['letter']),
    source: j['source'] as String,
  );
}

class FeedRow extends MonthRow {
  const FeedRow({
    required this.driverId,
    required this.driverName,
    required super.month,
    required super.stage,
    required this.scope,
    super.domains,
    super.top,
    super.letter,
  }) : super(mode: '');
  final String driverId, driverName;
  final ShareScope scope;
  factory FeedRow.fromJson(Map<String, dynamic> j) => FeedRow(
    driverId: _string(j['driver_id']),
    driverName: _string(j['driver_name'] ?? '가족'),
    month: _month(j['month']),
    stage: _string(j['stage']),
    scope: _scope(j['scope']),
    domains: _domains(j['domains']),
    top: _top(j['top']),
    letter: j['letter'] == null ? null : _string(j['letter']),
  );
}

class DayRow {
  const DayRow({
    required this.day,
    required this.ewma,
    required this.threshold,
    required this.alert,
  });
  final DateTime day;
  final double ewma, threshold;
  final bool alert;
  factory DayRow.fromJson(Map<String, dynamic> j) => DayRow(
    day: DateTime.parse(j['day'] as String),
    ewma: (j['ewma'] as num).toDouble(),
    threshold: (j['threshold'] as num).toDouble(),
    alert: j['alert'] as bool,
  );
}

/// A missing row is created from signup metadata only; an existing role is immutable.
Map<String, dynamic>? profileToCreate({
  required String id,
  required Map<String, dynamic>? existing,
  required Map<String, dynamic>? metadata,
}) {
  if (existing != null) return null;
  final role = metadata?['role'];
  final name = metadata?['display_name'];
  if ((role != 'driver' && role != 'family') ||
      name is! String ||
      name.trim().isEmpty ||
      name.runes.length > 80) {
    throw const FormatException();
  }
  return {'id': id, 'role': role, 'display_name': name.trim()};
}

String friendlyError(Object error) {
  final message = error is PostgrestException
      ? error.message
      : error is AuthException
      ? error.message
      : error.toString();
  if (message.contains('invalid_or_expired_code')) return '코드가 맞지 않거나 시간이 지났어요';
  if (message.contains('scope_and_timing_required')) {
    return '보여 줄 범위와 시점, 운전 패턴 공유 여부를 모두 골라 주세요';
  }
  if (error is SocketException ||
      error is TimeoutException ||
      error is AuthRetryableFetchException ||
      RegExp(
        r'network|socket|failed host lookup|connection|ClientException|Failed to fetch',
        caseSensitive: false,
      ).hasMatch(message)) {
    return '연결하지 못했어요. 인터넷을 확인해 주세요';
  }
  return '잠시 뒤 다시 해 주세요';
}

class SupabaseBackend implements Backend {
  SupabaseBackend(
    this.client, {
    this.clinicTimeout = const Duration(seconds: 45),
  }) {
    _pushTokens = DevicePushTokens(
      registerToken: (token, platform) async {
        await client.rpc(
          'register_push_token',
          params: {'p_token': token, 'p_platform': platform},
        );
      },
      removeTokens: (tokens) async {
        await client
            .from('push_tokens')
            .delete()
            .eq('user_id', _id)
            .inFilter('token', tokens.toList());
      },
      endSession: () async {
        await client.auth.signOut();
      },
    );
    _subscription = client.auth.onAuthStateChange.listen((event) {
      if (event.event == AuthChangeEvent.signedOut) {
        _profile = null;
        _events.add(null);
      } else {
        // Profile recovery errors are surfaced by explicit sign-in / initialize.
        unawaited(_syncProfile().catchError((Object _) {}));
      }
    });
  }
  final SupabaseClient client;
  final Duration clinicTimeout;
  late final DevicePushTokens _pushTokens;
  final _events = StreamController<void>.broadcast();
  late final StreamSubscription<AuthState> _subscription;
  Map<String, dynamic>? _profile;
  Future<void>? _syncing;
  String? _syncingId;
  @override
  Role? get role => switch (_profile?['role']) {
    'driver' => Role.driver,
    'family' => Role.family,
    _ => null,
  };
  @override
  String? get userId => client.auth.currentUser?.id;
  @override
  String? get displayName => _profile?['display_name'] as String?;
  @override
  Stream<void> get changes => _events.stream;
  Future<void> initialize() => _syncProfile();
  Future<void> _syncProfile() {
    final user = client.auth.currentUser;
    if (user == null) {
      _profile = null;
      _events.add(null);
      return Future.value();
    }
    if (_syncing != null && _syncingId == user.id) return _syncing!;
    _syncingId = user.id;
    final future = _loadProfile(user);
    _syncing = future;
    return future.whenComplete(() {
      if (identical(_syncing, future)) _syncing = null;
    });
  }

  Future<void> _loadProfile(User user) async {
    var profile = await client
        .from('profiles')
        .select('id,role,display_name')
        .eq('id', user.id)
        .maybeSingle();
    final insert = profileToCreate(
      id: user.id,
      existing: profile,
      metadata: user.userMetadata,
    );
    if (insert != null) {
      try {
        await client.from('profiles').insert(insert);
      } on PostgrestException catch (e) {
        if (e.code != '23505') rethrow;
      }
      profile = await client
          .from('profiles')
          .select('id,role,display_name')
          .eq('id', user.id)
          .single();
    }
    if (client.auth.currentUser?.id == user.id) {
      _profile = profile;
      _events.add(null);
    }
  }

  String get _id => userId ?? (throw const FormatException());
  @override
  Future<SignUpResult> signUp({
    required String email,
    required String password,
    required Role role,
    required String displayName,
  }) async {
    final result = await client.auth.signUp(
      email: email.trim(),
      password: password,
      data: {'role': role.name, 'display_name': displayName.trim()},
      // 확인 메일 링크를 이 휴대폰에서 누르면 앱이 열리고 바로 로그인된다(서버 Redirect URLs 에 등록 필요).
      emailRedirectTo: authRedirect,
    );
    if (result.session == null) return SignUpResult.confirmEmail;
    await _syncProfile();
    return SignUpResult.signedIn;
  }

  @override
  Future<void> signIn(String email, String password) async {
    await client.auth.signInWithPassword(
      email: email.trim(),
      password: password,
    );
    await _syncProfile();
  }

  @override
  Future<void> signOut() async {
    await _pushTokens.signOut();
    _profile = null;
    _events.add(null);
  }

  @override
  Future<void> claimPairing(String code) async {
    await client.rpc('claim_pairing', params: {'p_code': code});
  }

  @override
  Future<bool> carLinked() async =>
      (await client
              .from('car_links')
              .select('car_id')
              .eq('driver_id', _id)
              .limit(1))
          .isNotEmpty;
  @override
  Future<void> uploadImport(String contractAJson) async {
    final bytes = Uint8List.fromList(utf8.encode(contractAJson));
    if (bytes.length > 5000000) throw const FormatException();
    await client.storage
        .from('imports')
        .uploadBinary(
          '$_id/${_uuid()}.json',
          bytes,
          fileOptions: const FileOptions(
            contentType: 'application/json',
            upsert: false,
          ),
        );
  }

  @override
  Future<String> createInvite(
    ShareScope scope,
    ShareTiming timing,
    bool sharePattern,
  ) async => await client.rpc(
    'create_invite',
    params: {
      'p_scope': scope.wire,
      'p_delay_days': timing.wire,
      'p_share_pattern': sharePattern,
    },
  ) as String;
  @override
  Future<List<Share>> myShares() async =>
      _rows(await client.rpc('get_my_shares')).map(Share.fromJson).toList();
  @override
  Future<void> updateShare(
    String familyId,
    ShareScope scope,
    ShareTiming timing,
    bool sharePattern,
  ) async {
    await client.rpc(
      'update_share',
      params: {
        'p_family_id': familyId,
        'p_scope': scope.wire,
        'p_delay_days': timing.wire,
        'p_share_pattern': sharePattern,
      },
    );
  }

  @override
  Future<SharingState> sharingState() async =>
      SharingState.fromJson(_object(await client.rpc('get_sharing_state')));

  @override
  Future<void> setShareOptions(String familyId, ShareOptions options) async {
    await client.rpc(
      'set_share_options',
      params: {
        'p_family_id': familyId,
        'p_drive_done': options.driveDone?.name,
        'p_weekly_change': options.weeklyChange,
        'p_habits': options.habits,
      },
    );
  }

  @override
  Future<List<DriveEvent>> familyDriveEvents() async =>
      _rows(await client.rpc('get_family_drive_events'))
          .map(DriveEvent.fromJson)
          .toList();

  @override
  Future<List<WeeklyChange>> familyWeekly() async =>
      _rows(await client.rpc('get_family_weekly'))
          .map(WeeklyChange.fromJson)
          .toList();

  @override
  Future<List<WeeklyHabit>> familyHabits() async =>
      _rows(await client.rpc('get_family_habits'))
          .map(WeeklyHabit.fromJson)
          .toList();

  @override
  Future<CoachAdvice> coach(String driverId) async {
    try {
      final response = await client.functions.invoke(
        'conversation-coach',
        body: {'driver_id': driverId},
      );
      if (response.status != 200) throw const Unavailable();
      return CoachAdvice.fromJson(response.data);
    } catch (error) {
      throw CoachError.fromError(error);
    }
  }

  @override
  Future<ClinicSummary> clinicSummary() async {
    final abort = Completer<void>();
    try {
      final response = await client.functions
          .invoke('clinic-summary', body: {}, abortSignal: abort.future)
          .timeout(
            clinicTimeout,
            onTimeout: () {
              abort.complete();
              throw ClinicError.unavailable;
            },
          );
      if (response.status != 200) throw ClinicError.unavailable;
      return ClinicSummary.fromJson(response.data);
    } catch (_) {
      throw ClinicError.unavailable;
    }
  }

  @override
  Future<void> setSharingPaused(bool paused) async {
    await client.rpc('set_sharing_paused', params: {'p_paused': paused});
  }

  @override
  Future<FamilyView> previewFamilyView(String familyId) async =>
      FamilyView.fromJson(
        _object(
          await client.rpc(
            'preview_family_view',
            params: {'p_family_id': familyId},
          ),
        ),
      );

  @override
  Future<List<PatternRow>> familyPatterns() async =>
      _rows(await client.rpc('get_family_patterns'))
          .map(PatternRow.fromJson)
          .toList();

  @override
  Future<List<FamilyLink>> familyLinks() async =>
      _rows(await client.rpc('get_family_links'))
          .map(FamilyLink.fromJson)
          .toList();

  @override
  Future<int> sendNote(String driverId, String body) async {
    try {
      return _integer(
        await client.rpc(
          'send_family_note',
          params: {'p_driver_id': driverId, 'p_body': body},
        ),
      );
    } catch (error) {
      throw NoteError.fromError(error);
    }
  }

  @override
  Future<List<SentNote>> sentNotes() async =>
      _rows(await client.rpc('get_sent_notes')).map(SentNote.fromJson).toList();

  @override
  Future<void> revokeShare(String familyId) async {
    await client.rpc('revoke_share', params: {'p_family_id': familyId});
  }

  @override
  Future<InvitePreview> previewInvite(String code) async =>
      InvitePreview.fromJson(
        _rows(await client.rpc('preview_invite', params: {'p_code': code}))
            .single,
      );
  @override
  Future<void> acceptInvite(String code) async {
    await client.rpc('accept_invite', params: {'p_code': code});
  }

  @override
  Future<List<FeedRow>> familyFeed() async =>
      _rows(await client.rpc('get_family_feed')).map(FeedRow.fromJson).toList();
  @override
  Future<List<MonthRow>> mySummaries() async =>
      (await client
              .from('monthly_summaries')
              .select('month,mode,stage,domains,top,letter,source')
              .eq('driver_id', _id)
              .order('month', ascending: false)
              .limit(6))
          .map(MonthRow.fromJson)
          .toList();
  @override
  Future<List<DayRow>> myConditions() async {
    final today = DateTime.now().toUtc().add(const Duration(hours: 9));
    final last = DateTime.utc(today.year, today.month, today.day);
    String date(DateTime d) => d.toIso8601String().substring(0, 10);
    return (await client
            .from('daily_conditions')
            .select('day,ewma,threshold,alert')
            .eq('driver_id', _id)
            .gte('day', date(last.subtract(const Duration(days: 13))))
            .lte('day', date(last))
            .order('day')
            .limit(14))
        .map(DayRow.fromJson)
        .toList();
  }

  @override
  Future<void> registerPushToken(String token, String platform) =>
      _pushTokens.register(token, platform);
  Future<void> dispose() async {
    await _subscription.cancel();
    await _events.close();
  }
}

List<Map<String, dynamic>> _rows(Object? result) {
  if (result is! List) throw const FormatException();
  return result.map(_object).toList();
}

String _uuid() {
  final random = Random.secure();
  final bytes = List.generate(16, (_) => random.nextInt(256));
  bytes[6] = (bytes[6] & 15) | 64;
  bytes[8] = (bytes[8] & 63) | 128;
  final h = bytes.map((b) => b.toRadixString(16).padLeft(2, '0')).join();
  return '${h.substring(0, 8)}-${h.substring(8, 12)}-${h.substring(12, 16)}-${h.substring(16, 20)}-${h.substring(20)}';
}
