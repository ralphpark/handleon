import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:phone/backend.dart';
import 'package:supabase_flutter/supabase_flutter.dart';

import 'support/coach_fixtures.dart';

void main() {
  late HttpServer server;
  late StreamSubscription<HttpRequest> subscription;
  late SupabaseClient client;
  late SupabaseBackend backend;
  late Object? body;
  var status = 200;
  var brokenJson = false;
  final calls = <(String, String, String?, Object?)>[];
  const driver = '11111111-2222-3333-4444-555555555555';

  setUp(() async {
    status = 200;
    brokenJson = false;
    body = coachJson();
    calls.clear();
    server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    subscription = server.listen((request) async {
      calls.add((
        request.method,
        request.uri.path,
        request.headers.value(HttpHeaders.authorizationHeader),
        jsonDecode(await utf8.decoder.bind(request).join()),
      ));
      request.response.headers.contentType = ContentType.json;
      request.response.statusCode = status;
      request.response.write(brokenJson ? '{' : jsonEncode(body));
      await request.response.close();
    });
    client = SupabaseClient(
      'http://127.0.0.1:${server.port}',
      'local-test-key',
      headers: {'Authorization': 'Bearer local-family-jwt'},
      authOptions: const AuthClientOptions(autoRefreshToken: false),
    );
    backend = SupabaseBackend(client);
  });

  tearDown(() async {
    await backend.dispose();
    await client.dispose();
    await server.close(force: true);
    await subscription.cancel();
  });

  test(
    'coach invokes POST with driver id and the family authorization',
    () async {
      final advice = await backend.coach(driver);
      expect(calls, hasLength(1));
      expect(
        (calls.single.$1, calls.single.$2, calls.single.$3),
        ('POST', '/functions/v1/conversation-coach', 'Bearer local-family-jwt'),
      );
      expect(calls.single.$4, {'driver_id': driver});
      expect(advice.openers, [
        '요즘 운전 다니시는 건 어떠세요?',
        '이번 주말에 같이 드라이브 갈까요?',
        '운전하시다 불편한 점은 없으셨어요?',
      ]);
      expect(advice.avoid, ['이제 운전 그만하세요', '왜 그렇게 운전하셨어요']);
      expect(advice.topic, '요즘 자주 가시는 곳 이야기');
      expect(advice.fromFallback, false);
    },
  );

  test('coach HTTP errors require the exact status and error code', () async {
    for (final (code, payload, matcher) in <(int, Object?, Matcher)>[
      (429, {'error': 'daily_limit'}, isA<DailyLimit>()),
      (404, {'error': 'share_not_found'}, isA<ShareNotFound>()),
      (401, {'error': 'unauthorized'}, isA<Unavailable>()),
      (403, {'error': 'family_required'}, isA<Unavailable>()),
      (400, {'error': 'invalid_request'}, isA<Unavailable>()),
      (500, {'error': 'daily_limit'}, isA<Unavailable>()),
      (429, {'error': 'share_not_found'}, isA<Unavailable>()),
      (404, {'error': 'daily_limit'}, isA<Unavailable>()),
      (429, null, isA<Unavailable>()),
    ]) {
      status = code;
      body = payload;
      await expectLater(backend.coach(driver), throwsA(matcher));
    }
    expect(calls, hasLength(9));
  });

  test('coach malformed success and broken JSON become Unavailable', () async {
    for (final malformed in [
      null,
      [],
      {'source': 'ai'},
      {...coachJson(), 'source': 'unknown'},
      {
        'advice': {'openers': [], 'avoid': [], 'topic': ''},
        'source': 'ai',
      },
    ]) {
      body = malformed;
      await expectLater(backend.coach(driver), throwsA(isA<Unavailable>()));
    }
    brokenJson = true;
    await expectLater(backend.coach(driver), throwsA(isA<Unavailable>()));
  });

  test('coach network failure becomes Unavailable without retry', () async {
    await server.close(force: true);
    await expectLater(backend.coach(driver), throwsA(isA<Unavailable>()));
    expect(calls, isEmpty);
  });

  test('coach preserves fallback through the Edge Function response', () async {
    body = coachJson(source: 'fallback');
    expect((await backend.coach(driver)).fromFallback, true);
  });
}
