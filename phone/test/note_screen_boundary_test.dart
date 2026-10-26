import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:phone/backend.dart';
import 'package:phone/family/note_screen.dart';
import 'package:supabase_flutter/supabase_flutter.dart';

import 'support/fake_backend.dart';
import 'support/harness.dart';
import 'support/sharing_fixtures.dart';

Widget screen(FakeBackend b) =>
    NoteScreen(backend: b, driverId: 'd', driverName: '아버지');

SentNote note(int id, String body, String createdAt) => SentNote.fromJson({
  ...noteJson(),
  'id': id,
  'body': body,
  'created_at': createdAt,
});

/// 화면과 같은 형식으로, 실행하는 기기의 시간대에서 기대값을 만든다.
String localLabel(DateTime utc) {
  final local = utc.toLocal();
  String two(int value) => value.toString().padLeft(2, '0');
  return '${local.month}월 ${local.day}일 ${two(local.hour)}:${two(local.minute)}';
}

void main() {
  for (final (message, copy) in [
    ('invalid_note', '1~120자로 적어 주세요.'),
    ('too_many_unseen', '아직 읽지 않은 한마디가 3개 있어요. 읽고 나면 더 보낼 수 있어요.'),
    ('share_not_found', '공유가 끝나 보낼 수 없어요.'),
    ('family_required', '보내지 못했어요. 잠시 후 다시 시도해 주세요.'),
  ]) {
    testWidgets('server message $message shows its copy and keeps the draft', (
      t,
    ) async {
      final b = FakeBackend()..links = [familyLink()];
      await showScreen(t, screen(b));
      b.error = PostgrestException(message: message, code: 'P0001');
      await tapText(t, '늘 응원해요');
      await tapText(t, '보내기');
      expect(find.text(copy), findsOneWidget);
      expect(
        t.widget<TextField>(find.byType(TextField)).controller!.text,
        '늘 응원해요',
      );
    });
  }
  testWidgets('sent time shows the device-local clock for offset timestamps', (
    t,
  ) async {
    final b = FakeBackend()
      ..notes = [
        note(1, '서울 시각', '2026-10-25T14:05:00+09:00'),
        note(2, '자정 넘김', '2026-10-31T23:30:00.5-01:00'),
      ];
    await showScreen(t, screen(b));
    for (final utc in [
      DateTime.utc(2026, 10, 25, 5, 5),
      DateTime.utc(2026, 11, 1, 0, 30),
    ]) {
      expect(find.text(localLabel(utc)), findsOneWidget, reason: '$utc');
    }
  });
  testWidgets('sent notes order by instant across offsets and microseconds, '
      'then by id', (t) async {
    final b = FakeBackend()
      ..notes = [
        note(1, '같은 순간 먼저', '2026-10-25T14:30:00+09:00'),
        note(3, '1마이크로초 뒤', '2026-10-25T05:30:00.000001Z'),
        note(2, '가장 나중', '2026-10-25T06:00:00+00:00'),
        note(4, '같은 순간 나중', '2026-10-25T05:30:00Z'),
      ];
    await showScreen(t, screen(b));
    final tops = [
      for (final body in ['가장 나중', '1마이크로초 뒤', '같은 순간 나중', '같은 순간 먼저'])
        t.getTopLeft(find.text(body)).dy,
    ];
    for (var i = 1; i < tops.length; i++) {
      expect(tops[i - 1], lessThan(tops[i]), reason: 'position $i');
    }
  });
}
