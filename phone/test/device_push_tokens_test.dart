import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:phone/device_push_tokens.dart';

void main() {
  test('signout removes this device tokens before ending session', () async {
    final events = <String>[];
    final tokens = DevicePushTokens(
      registerToken: (token, platform) async {
        events.add('register:$token');
      },
      removeTokens: (values) async {
        events.add('remove:${values.join(',')}');
      },
      endSession: () async {
        events.add('signout');
      },
    );
    await tokens.register('old', 'android');
    await tokens.register('new', 'android');
    await tokens.signOut();
    expect(events, [
      'register:old',
      'register:new',
      'remove:old,new',
      'signout',
    ]);
  });
  test('in-flight registration finishes before removal, late registration is ignored', () async {
    final gate = Completer<void>();
    final events = <String>[];
    final tokens = DevicePushTokens(
      registerToken: (token, platform) async {
        await gate.future;
        events.add('registered');
      },
      removeTokens: (values) async {
        events.add('removed');
      },
      endSession: () async {
        events.add('signout');
      },
    );
    final register = tokens.register('token', 'android');
    final signout = tokens.signOut();
    await tokens.register('late', 'android');
    expect(events, isEmpty);
    gate.complete();
    await register;
    await signout;
    expect(events, ['registered', 'removed', 'signout']);
  });
  test('failed token removal keeps session and can retry', () async {
    var fail = true;
    var signedOut = false;
    final tokens = DevicePushTokens(
      registerToken: (_, _) async {},
      removeTokens: (_) async {
        if (fail) throw StateError('offline');
      },
      endSession: () async {
        signedOut = true;
      },
    );
    await tokens.register('token', 'android');
    await expectLater(tokens.signOut(), throwsStateError);
    expect(signedOut, isFalse);
    fail = false;
    await tokens.signOut();
    expect(signedOut, isTrue);
  });
}
