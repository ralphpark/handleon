import 'dart:async';

import 'package:firebase_core/firebase_core.dart';
import 'package:firebase_messaging/firebase_messaging.dart';
import 'package:flutter/foundation.dart';

import 'backend.dart';
import 'family/feed_text.dart';

abstract class PushClient {
  Future<void> initialize();
  Future<void> requestPermission();
  Future<String?> getToken();
  Stream<String> get tokenRefresh;

  /// 앱이 열려 있을 때 받은 메시지. 안드로이드는 이때 알림창에 띄우지 않는다.
  Stream<void> get foregroundMessages;
  Stream<Map<String, dynamic>> get openedMessages;
  Future<Map<String, dynamic>?> getInitialMessage();
}

class FirebasePushClient implements PushClient {
  @override
  Future<void> initialize() async {
    await Firebase.initializeApp();
  }

  @override
  Future<void> requestPermission() async {
    await FirebaseMessaging.instance.requestPermission();
  }

  // 서버 register_push_token 이 받는 FCM 토큰 API 를 그대로 쓴다.
  @override
  // ignore: deprecated_member_use
  Future<String?> getToken() => FirebaseMessaging.instance.getToken();
  @override
  Stream<String> get tokenRefresh =>
      // ignore: deprecated_member_use
      FirebaseMessaging.instance.onTokenRefresh;
  @override
  Stream<void> get foregroundMessages =>
      FirebaseMessaging.onMessage.map((_) {});

  @override
  Stream<Map<String, dynamic>> get openedMessages =>
      FirebaseMessaging.onMessageOpenedApp.map((message) => message.data);

  @override
  Future<Map<String, dynamic>?> getInitialMessage() async =>
      (await FirebaseMessaging.instance.getInitialMessage())?.data;
}

class Push {
  Push(this.backend, {PushClient? client, TargetPlatform? platform})
    : client = client ?? FirebasePushClient(),
      platform = platform ?? defaultTargetPlatform;
  final Backend backend;
  final PushClient client;
  final TargetPlatform platform;
  StreamSubscription<String>? _refresh;
  StreamSubscription<void>? _messages;
  StreamSubscription<Map<String, dynamic>>? _opened;
  final _arrivals = StreamController<void>.broadcast();
  final _opens = StreamController<FeedSection?>.broadcast();
  String? _user;
  int _generation = 0;

  /// 로그인한 사람에게 앱이 열린 채로 새 알림이 왔을 때.
  Stream<void> get arrivals => _arrivals.stream;
  Stream<FeedSection?> get opens => _opens.stream;

  Future<void> init() async {
    final id = backend.userId;
    if (id == _user) return;
    _user = id;
    final generation = ++_generation;
    await _refresh?.cancel();
    await _messages?.cancel();
    await _opened?.cancel();
    _refresh = null;
    _messages = null;
    _opened = null;
    if (id == null ||
        backend.role == null ||
        kIsWeb ||
        platform != TargetPlatform.android) {
      return;
    }
    bool current() => generation == _generation && backend.userId == id;
    void open(Map<String, dynamic> data) {
      if (!current() || _opens.isClosed) return;
      _opens.add(switch (data['kind']) {
        'drive_done' || 'drive_evening' => FeedSection.drive,
        'weekly_change' => FeedSection.weekly,
        'stage' => FeedSection.stage,
        _ => null,
      });
    }

    Future<void> register(String token) async {
      if (!current()) return;
      try {
        await backend.registerPushToken(token, 'android');
      } catch (_) {}
    }

    try {
      await client.initialize();
      if (!current()) return;
      // Subscribe before permission/token work so an opened notification is not
      // lost while those platform calls are still pending.
      _opened = client.openedMessages.listen(open, onError: (Object _) {});
      final initial = await client.getInitialMessage();
      if (!current()) return;
      if (initial != null) open(initial);
      await client.requestPermission();
      if (!current()) return;
      _refresh = client.tokenRefresh.listen((token) {
        unawaited(register(token));
      }, onError: (Object _) {});
      _messages = client.foregroundMessages.listen((_) {
        if (current() && !_arrivals.isClosed) _arrivals.add(null);
      }, onError: (Object _) {});
      final token = await client.getToken();
      if (token != null && current()) await register(token);
    } catch (_) {
      /* Optional platform configuration must never prevent login. */
    }
  }

  void dispose() {
    _generation++;
    unawaited(_refresh?.cancel());
    unawaited(_messages?.cancel());
    unawaited(_opened?.cancel());
    _refresh = null;
    _messages = null;
    _opened = null;
    unawaited(_arrivals.close());
    unawaited(_opens.close());
  }
}
