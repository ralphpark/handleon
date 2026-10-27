import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:phone/backend.dart';
import 'package:supabase_flutter/supabase_flutter.dart';

import 'support/clinic_fixtures.dart';

void main() {
  test('clinic parser preserves fallback and rejects malformed summaries', () {
    expect(
      ClinicSummary.fromJson(clinicJson(source: 'fallback')).fromFallback,
      true,
    );
    expect(ClinicSummary.fromJson(clinicJson()).fromFallback, false);
    for (final value in <Object?>[
      null,
      [],
      {},
      {...clinicJson(), 'summary': null},
      {...clinicJson(), 'summary': ' \n'},
      {...clinicJson(), 'summary': 12},
      {...clinicJson(), 'summary': '가' * 601},
      {
        ...clinicJson(),
        'questions': ['하나', '둘'],
      },
      {
        ...clinicJson(),
        'questions': ['하나', '둘', '셋', '넷'],
      },
      {
        ...clinicJson(),
        'questions': ['하나', '', '셋'],
      },
      {
        ...clinicJson(),
        'questions': ['하나', ' \n', '셋'],
      },
      {
        ...clinicJson(),
        'questions': ['하나', 1, '셋'],
      },
      {
        ...clinicJson(),
        'questions': ['하나', null, '셋'],
      },
      {
        ...clinicJson(),
        'questions': ['하나', '가' * 81, '셋'],
      },
      {...clinicJson(), 'questions': null},
      {...clinicJson(), 'questions': '셋'},
      {...clinicJson(), 'source': null},
      {...clinicJson(), 'source': 'cached'},
    ]) {
      expect(
        () => ClinicSummary.fromJson(value),
        throwsA(ClinicError.unavailable),
      );
    }
  });

  test('clinic parser counts Unicode code points at server limits', () {
    final s = ClinicSummary.fromJson({
      'summary': '😀' * 600,
      'questions': List.filled(3, '😀' * 80),
      'source': 'ai',
      'location': 'not part of the contract',
    });
    expect(s.summary.runes.length, 600);
    expect(s.questions.map((q) => q.runes.length), [80, 80, 80]);
    expect(
      () => ClinicSummary.fromJson({...clinicJson(), 'summary': '😀' * 601}),
      throwsA(ClinicError.unavailable),
    );
    expect(
      () => ClinicSummary.fromJson({
        ...clinicJson(),
        'questions': List.filled(3, '😀' * 81),
      }),
      throwsA(ClinicError.unavailable),
    );
  });

  group('clinic Edge Function', () {
    late HttpServer server;
    late StreamSubscription<HttpRequest> subscription;
    late SupabaseClient client;
    late SupabaseBackend backend;
    Object? body;
    var status = 200;
    var brokenJson = false;
    Completer<void>? pendingResponse;
    Completer<void>? requestSeen;
    final calls = <(String, String, String?, Object?)>[];

    setUp(() async {
      status = 200;
      brokenJson = false;
      pendingResponse = null;
      requestSeen = null;
      body = clinicJson();
      calls.clear();
      server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
      subscription = server.listen((request) async {
        calls.add((
          request.method,
          request.uri.path,
          request.headers.value(HttpHeaders.authorizationHeader),
          jsonDecode(await utf8.decoder.bind(request).join()),
        ));
        final held = pendingResponse;
        final responseBody = body;
        if (held != null) {
          requestSeen?.complete();
          await held.future;
        }
        request.response.headers.contentType = ContentType.json;
        request.response.statusCode = status;
        request.response.write(brokenJson ? '{' : jsonEncode(responseBody));
        await request.response.close();
      });
      client = SupabaseClient(
        'http://127.0.0.1:${server.port}',
        'local-test-key',
        headers: {'Authorization': 'Bearer local-driver-jwt'},
        authOptions: const AuthClientOptions(autoRefreshToken: false),
      );
      backend = SupabaseBackend(client);
    });

    tearDown(() async {
      if (pendingResponse != null && !pendingResponse!.isCompleted) {
        pendingResponse!.complete();
      }
      await backend.dispose();
      await client.dispose();
      await server.close(force: true);
      await subscription.cancel();
    });

    test(
      'clinic invokes POST with empty body and driver authorization',
      () async {
        body = clinicJson(source: 'fallback');
        final result = await backend.clinicSummary();
        expect(calls, hasLength(1));
        expect(
          (calls.single.$1, calls.single.$2, calls.single.$3),
          ('POST', '/functions/v1/clinic-summary', 'Bearer local-driver-jwt'),
        );
        expect(calls.single.$4, <String, dynamic>{});
        expect(result.fromFallback, true);
        expect(result.summary, '최근 운전 기록을 보며 평소와 달라진 점과 편안했던 점을 함께 이야기해 보세요.');
        expect(result.questions, hasLength(3));
      },
    );

    test(
      'clinic rejects HTTP errors malformed success and invalid JSON',
      () async {
        for (final code in [401, 403, 400, 429, 500, 201]) {
          status = code;
          body = {'error': 'driver_required'};
          await expectLater(
            backend.clinicSummary(),
            throwsA(ClinicError.unavailable),
          );
        }
        status = 200;
        for (final malformed in [
          null,
          [],
          {'summary': '한 줄'},
          {...clinicJson(), 'source': 'bad'},
        ]) {
          body = malformed;
          await expectLater(
            backend.clinicSummary(),
            throwsA(ClinicError.unavailable),
          );
        }
        brokenJson = true;
        await expectLater(
          backend.clinicSummary(),
          throwsA(ClinicError.unavailable),
        );
        expect(calls, hasLength(11));
      },
    );

    test(
      'clinic deadline aborts a stalled response and allows a fresh attempt',
      () async {
        await backend.dispose();
        backend = SupabaseBackend(
          client,
          clinicTimeout: const Duration(seconds: 1),
        );
        final pending = Completer<void>();
        pendingResponse = pending;
        requestSeen = Completer<void>();
        body = clinicJson(source: 'fallback');
        final first = backend.clinicSummary();
        final failed = expectLater(
          first.timeout(const Duration(seconds: 5)),
          throwsA(ClinicError.unavailable),
        );
        await requestSeen!.future.timeout(const Duration(seconds: 5));
        await failed;
        expect(calls, hasLength(1));
        // The timed-out server response can arrive after a new attempt begins.
        pendingResponse = null;
        body = clinicJson();
        final retry = backend.clinicSummary();
        pending.complete();
        expect((await retry).fromFallback, false);
        expect(calls, hasLength(2));
      },
    );

    test(
      'clinic network failure returns a fixed error without retry',
      () async {
        await server.close(force: true);
        await expectLater(
          backend.clinicSummary(),
          throwsA(ClinicError.unavailable),
        );
        expect(calls, isEmpty);
      },
    );
  });
}
