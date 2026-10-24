import 'dart:async';

import 'package:firebase_core/firebase_core.dart';
import 'package:firebase_messaging/firebase_messaging.dart';
import 'package:flutter/foundation.dart';

import 'backend.dart';

abstract class PushClient {
  Future<void> initialize();
  Future<void> requestPermission();
  Future<String?> getToken();
  Stream<String> get tokenRefresh;
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
}

class Push {
  Push(this.backend, {PushClient? client, TargetPlatform? platform})
    : client = client ?? FirebasePushClient(),
      platform = platform ?? defaultTargetPlatform;
  final Backend backend;
  final PushClient client;
  final TargetPlatform platform;
  StreamSubscription<String>? _refresh;
  String? _user;
  int _generation = 0;
  Future<void> init() async {
    final id = backend.userId;
    if (id == _user) return;
    _user = id;
    final generation = ++_generation;
    await _refresh?.cancel();
    _refresh = null;
    if (id == null ||
        backend.role == null ||
        kIsWeb ||
        platform != TargetPlatform.android) {
      return;
    }
    bool current() => generation == _generation && backend.userId == id;
    Future<void> register(String token) async {
      if (!current()) return;
      try {
        await backend.registerPushToken(token, 'android');
      } catch (_) {}
    }

    try {
      await client.initialize();
      if (!current()) return;
      await client.requestPermission();
      if (!current()) return;
      _refresh = client.tokenRefresh.listen((token) {
        unawaited(register(token));
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
    _refresh = null;
  }
}
