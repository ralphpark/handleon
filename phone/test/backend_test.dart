import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:phone/backend.dart';
import 'package:phone/share_options.dart';
import 'package:supabase_flutter/supabase_flutter.dart';

void main() {
  test('server and network errors never show internal details', () {
    expect(
      friendlyError(
        const PostgrestException(message: 'invalid_or_expired_code'),
      ),
      '코드가 맞지 않거나 시간이 지났어요',
    );
    expect(
      friendlyError(
        const PostgrestException(message: 'scope_and_timing_required'),
      ),
      '보여 줄 것과 시점을 둘 다 골라 주세요',
    );
    expect(
      friendlyError(const SocketException('host 192.0.2.1')),
      '연결하지 못했어요. 인터넷을 확인해 주세요',
    );
    expect(friendlyError(StateError('private detail')), '잠시 뒤 다시 해 주세요');
  });
  test('RPC 0006 names scopes and delays decode without defaults', () {
    final share = Share.fromJson({
      'family_id': 'f',
      'family_name': '큰딸',
      'scope': 'trend',
      'delay_days': 7,
    });
    expect(share.familyName, '큰딸');
    expect(share.scope, ShareScope.trend);
    expect(share.timing, ShareTiming.d7);
    final row = FeedRow.fromJson({
      'driver_id': 'd',
      'driver_name': '아버지',
      'month': '2026-10-01',
      'stage': 'stable',
      'scope': 'stage',
      'domains': null,
      'top': null,
      'letter': null,
    });
    expect(row.domains, isNull);
    expect(row.top, isNull);
    expect(row.letter, isNull);
    expect(
      () => InvitePreview.fromJson({
        'driver_name': '아버지',
        'scope': 'unknown',
        'delay_days': 7,
      }),
      throwsFormatException,
    );
  });
}
