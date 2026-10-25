import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:phone/backend.dart';
import 'package:phone/share_options.dart';
import 'package:supabase_flutter/supabase_flutter.dart';

import 'support/sharing_fixtures.dart';

// 외부 서버를 호출하지 않고 루프백에서 실제 RPC 요청 이름·본문·응답 파서를 확인한다.
void main() {
  late HttpServer server;
  late StreamSubscription<HttpRequest> subscription;
  late SupabaseClient client;
  late SupabaseBackend backend;
  final calls = <(String, Map<String, dynamic>)>[];
  final responses = <String, Object?>{};
  String? failure;
  void expectCall(String method, Map<String, dynamic> params) {
    expect(calls.last.$1, method);
    expect(calls.last.$2, params);
  }

  setUp(() async {
    calls.clear();
    responses.clear();
    failure = null;
    server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    subscription = server.listen((request) async {
      final method = request.uri.pathSegments.last;
      final body =
          (jsonDecode(await utf8.decoder.bind(request).join())
              as Map<String, dynamic>?) ??
          <String, dynamic>{};
      calls.add((method, body));
      request.response.headers.contentType = ContentType.json;
      if (failure != null) {
        request.response.statusCode = 400;
        request.response.write(
          jsonEncode({
            'code': 'P0001',
            'message': failure,
            'details': null,
            'hint': null,
          }),
        );
      } else {
        request.response.write(jsonEncode(responses[method]));
      }
      await request.response.close();
    });
    client = SupabaseClient(
      'http://127.0.0.1:${server.port}',
      'local-test-key',
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
    '0008 RPC names, explicit consent parameters and response shapes',
    () async {
      responses.addAll({
        'create_invite': 'K7Q2M9XD',
        'get_my_shares': [
          {
            'family_id': 'f',
            'family_name': '큰딸',
            'scope': 'trend',
            'delay_days': 7,
            'share_pattern': true,
          },
        ],
        'preview_invite': [
          {
            'driver_name': '아버지',
            'scope': 'stage',
            'delay_days': 3,
            'share_pattern': false,
          },
        ],
        'get_sharing_state': {'paused': true, 'recipients': []},
        'preview_family_view': {
          'paused': false,
          'feed': [feedJson()],
          'patterns': [patternJson()],
        },
        'get_family_feed': [feedJson()],
        'get_family_patterns': [patternJson()],
        'get_family_links': [linkJson()],
        'send_family_note': 42,
        'get_sent_notes': [noteJson()],
      });
      for (final value in [true, false]) {
        expect(
          await backend.createInvite(ShareScope.trend, ShareTiming.d7, value),
          'K7Q2M9XD',
        );
        expectCall('create_invite', {
          'p_scope': 'trend',
          'p_delay_days': 7,
          'p_share_pattern': value,
        });
        await backend.updateShare(
          'f',
          ShareScope.detail,
          ShareTiming.d3,
          value,
        );
        expectCall('update_share', {
          'p_family_id': 'f',
          'p_scope': 'detail',
          'p_delay_days': 3,
          'p_share_pattern': value,
        });
      }
      expect((await backend.myShares()).single.sharePattern, true);
      expect((await backend.previewInvite('K7Q2M9XD')).sharePattern, false);
      expectCall('preview_invite', {'p_code': 'K7Q2M9XD'});
      expect((await backend.sharingState()).paused, true);
      for (final value in [true, false]) {
        await backend.setSharingPaused(value);
        expectCall('set_sharing_paused', {'p_paused': value});
      }
      final view = await backend.previewFamilyView('f');
      expectCall('preview_family_view', {'p_family_id': 'f'});
      expect(view.feed.single.driverId, 'd');
      expect(view.patterns.single.trips, 18);
      expect((await backend.familyFeed()).single.driverId, 'd');
      expect((await backend.familyPatterns()).single.driveMinutes, 540);
      expect((await backend.familyLinks()).single.sharePattern, true);
      expect(await backend.sendNote('d', '늘 응원해요'), 42);
      expectCall('send_family_note', {'p_driver_id': 'd', 'p_body': '늘 응원해요'});
      expect((await backend.sentNotes()).single.seenAt, isNull);
      expectCall('get_sent_notes', <String, dynamic>{});
    },
  );
  test('sendNote converts Postgrest errors to NoteError', () async {
    for (final (message, expected) in [
      ('invalid_note', NoteError.invalid),
      ('too_many_unseen', NoteError.tooManyUnseen),
      ('share_not_found', NoteError.shareEnded),
      ('internal details', NoteError.other),
    ]) {
      failure = message;
      await expectLater(backend.sendNote('d', '늘 응원해요'), throwsA(expected));
    }
  });
}
