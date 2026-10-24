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
  Future<String> createInvite(ShareScope scope, ShareTiming timing);
  Future<List<Share>> myShares();
  Future<void> updateShare(
    String familyId,
    ShareScope scope,
    ShareTiming timing,
  );
  Future<void> revokeShare(String familyId);
  Future<InvitePreview> previewInvite(String code);
  Future<void> acceptInvite(String code);
  Future<List<FeedRow>> familyFeed();
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
  });
  final String familyId, familyName;
  final ShareScope scope;
  final ShareTiming timing;
  factory Share.fromJson(Map<String, dynamic> j) => Share(
    familyId: j['family_id'] as String,
    familyName: j['family_name'] as String? ?? '가족',
    scope: _scope(j['scope']),
    timing: _timing(j['delay_days']),
  );
}

class InvitePreview {
  const InvitePreview({
    required this.driverName,
    required this.scope,
    required this.timing,
  });
  final String driverName;
  final ShareScope scope;
  final ShareTiming timing;
  factory InvitePreview.fromJson(Map<String, dynamic> j) => InvitePreview(
    driverName: j['driver_name'] as String? ?? '가족',
    scope: _scope(j['scope']),
    timing: _timing(j['delay_days']),
  );
}

ShareScope _scope(Object? v) =>
    ShareScope.fromWire(v) ?? (throw const FormatException());
ShareTiming _timing(Object? v) =>
    ShareTiming.fromWire(v) ?? (throw const FormatException());

class TopItem {
  const TopItem({
    required this.text,
    required this.grade,
    required this.feature,
  });
  final String text, grade, feature;
  factory TopItem.fromJson(Map<String, dynamic> j) => TopItem(
    text: j['text'] as String,
    grade: j['grade'] as String,
    feature: j['feature'] as String,
  );
}

Map<String, double>? _domains(Object? raw) => raw == null
    ? null
    : {
        for (final entry in (raw as Map).entries)
          if (entry.value is num &&
              (entry.value as num).isFinite &&
              (entry.value as num) >= 0)
            entry.key as String: (entry.value as num).toDouble(),
      };
List<TopItem>? _top(Object? raw) => raw == null
    ? null
    : (raw as List)
          .take(3)
          .map((e) => TopItem.fromJson(Map<String, dynamic>.from(e as Map)))
          .toList();

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
    month: DateTime.parse(j['month'] as String),
    mode: j['mode'] as String,
    stage: j['stage'] as String,
    domains: _domains(j['domains']),
    top: _top(j['top']),
    letter: j['letter'] as String?,
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
    driverId: j['driver_id'] as String,
    driverName: j['driver_name'] as String? ?? '가족',
    month: DateTime.parse(j['month'] as String),
    stage: j['stage'] as String,
    scope: _scope(j['scope']),
    domains: _domains(j['domains']),
    top: _top(j['top']),
    letter: j['letter'] as String?,
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
    return '보여 줄 것과 시점을 둘 다 골라 주세요';
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
  SupabaseBackend(this.client) {
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
  Future<String> createInvite(ShareScope scope, ShareTiming timing) async =>
      await client.rpc(
        'create_invite',
        params: {'p_scope': scope.wire, 'p_delay_days': timing.wire},
      ) as String;
  @override
  Future<List<Share>> myShares() async =>
      _rows(await client.rpc('get_my_shares')).map(Share.fromJson).toList();
  @override
  Future<void> updateShare(
    String familyId,
    ShareScope scope,
    ShareTiming timing,
  ) async {
    await client.rpc(
      'update_share',
      params: {
        'p_family_id': familyId,
        'p_scope': scope.wire,
        'p_delay_days': timing.wire,
      },
    );
  }

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

List<Map<String, dynamic>> _rows(dynamic result) =>
    (result as List).map((r) => Map<String, dynamic>.from(r as Map)).toList();
String _uuid() {
  final random = Random.secure();
  final bytes = List.generate(16, (_) => random.nextInt(256));
  bytes[6] = (bytes[6] & 15) | 64;
  bytes[8] = (bytes[8] & 63) | 128;
  final h = bytes.map((b) => b.toRadixString(16).padLeft(2, '0')).join();
  return '${h.substring(0, 8)}-${h.substring(8, 12)}-${h.substring(12, 16)}-${h.substring(16, 20)}-${h.substring(20)}';
}
