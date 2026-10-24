import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:phone/backend.dart';
import 'package:phone/push.dart';

import 'support/fake_backend.dart';

class FakePushClient implements PushClient {
  final refresh = StreamController<String>.broadcast(sync: true);
  int initialized = 0, requested = 0;
  bool fail = false;
  @override
  Future<void> initialize() async {
    initialized++;
    if (fail) throw StateError('unconfigured');
  }

  @override
  Future<void> requestPermission() async {
    requested++;
  }

  @override
  Future<String?> getToken() async => 'first-token';
  @override
  Stream<String> get tokenRefresh => refresh.stream;
}

void main() {
  test(
    'Android registers once per login and refresh, stops after signout',
    () async {
      final b = FakeBackend(role: Role.family, userId: 'f');
      final client = FakePushClient();
      final push = Push(b, client: client, platform: TargetPlatform.android);
      await push.init();
      await push.init();
      expect(client.initialized, 1);
      expect(client.requested, 1);
      expect(b.tokens, [('first-token', 'android')]);
      client.refresh.add('new-token');
      await Future<void>.delayed(Duration.zero);
      expect(b.tokens.last, ('new-token', 'android'));
      await b.signOut();
      await push.init();
      client.refresh.add('after-logout');
      await Future<void>.delayed(Duration.zero);
      expect(b.tokens, hasLength(2));
      push.dispose();
      await client.refresh.close();
      await b.events.close();
    },
  );
  test('iOS skips Firebase completely', () async {
    final client = FakePushClient();
    final push = Push(
      FakeBackend(role: Role.driver, userId: 'd'),
      client: client,
      platform: TargetPlatform.iOS,
    );
    await push.init();
    expect(client.initialized, 0);
    push.dispose();
    await client.refresh.close();
  });
  test('Firebase failure is optional and does not escape', () async {
    final client = FakePushClient()..fail = true;
    final b = FakeBackend(role: Role.driver, userId: 'd');
    final push = Push(b, client: client, platform: TargetPlatform.android);
    await push.init();
    expect(b.tokens, isEmpty);
    push.dispose();
    await client.refresh.close();
  });
}
