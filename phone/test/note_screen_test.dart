import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:phone/backend.dart';
import 'package:phone/family/note_screen.dart';

import 'support/fake_backend.dart';
import 'support/harness.dart';
import 'support/sharing_fixtures.dart';

Widget screen(FakeBackend b) =>
    NoteScreen(backend: b, driverId: 'd', driverName: '아버지');

void main() {
  testWidgets(
    'each quick chip fills input and a successful note refreshes list',
    (t) async {
      final b = FakeBackend()..links = [familyLink(paused: true)];
      await showScreen(t, screen(b));
      for (final phrase in quickNotes) {
        await tapText(t, phrase);
        expect(
          t.widget<TextField>(find.byType(TextField)).controller!.text,
          phrase,
        );
        expect(find.text('${phrase.runes.length}/120'), findsOneWidget);
        final chip = find.widgetWithText(ActionChip, phrase);
        expect(t.getSize(chip).height, greaterThanOrEqualTo(48));
      }
      await tapText(t, '보내기');
      expect(b.sentNote, ('d', '늘 응원해요'));
      expect(b.notes, hasLength(1));
      expect(find.text('한마디를 보냈어요.'), findsOneWidget);
      expect(find.text('아직 안 읽음'), findsOneWidget);
      expect(
        t.widget<TextField>(find.byType(TextField)).controller!.text,
        isEmpty,
      );
    },
  );
  for (final (error, message) in [
    (NoteError.tooManyUnseen, '아직 읽지 않은 한마디가 3개 있어요. 읽고 나면 더 보낼 수 있어요.'),
    (NoteError.shareEnded, '공유가 끝나 보낼 수 없어요.'),
    (NoteError.invalid, '1~120자로 적어 주세요.'),
    (NoteError.other, '보내지 못했어요. 잠시 후 다시 시도해 주세요.'),
  ]) {
    testWidgets('send error $error retains draft and shows exact copy', (
      t,
    ) async {
      final b = FakeBackend()..noteError = error;
      await showScreen(t, screen(b));
      await tapText(t, '늘 응원해요');
      await tapText(t, '보내기');
      expect(find.text(message), findsOneWidget);
      expect(
        t.widget<TextField>(find.byType(TextField)).controller!.text,
        '늘 응원해요',
      );
      expect(b.notes, isEmpty);
    });
  }
  testWidgets(
    'sent notes filter recipient, order newest first, and show read state',
    (t) async {
      final b = FakeBackend()
        ..notes = [
          SentNote(
            id: 1,
            driverId: 'd',
            driverName: '아버지',
            body: '먼저 보낸 글',
            createdAt: DateTime(2026, 10, 24, 9),
            seenAt: null,
          ),
          SentNote(
            id: 2,
            driverId: 'd',
            driverName: '아버지',
            body: '나중에 보낸 글',
            createdAt: DateTime(2026, 10, 25, 14, 5),
            seenAt: DateTime(2026, 10, 25, 15),
          ),
          SentNote.fromJson({
            ...noteJson(),
            'driver_id': 'other',
            'body': '다른 분께 보낸 글',
          }),
        ];
      await showScreen(t, screen(b));
      expect(find.text('10월 25일 14:05'), findsOneWidget);
      expect(find.text('읽음'), findsOneWidget);
      expect(find.text('아직 안 읽음'), findsOneWidget);
      expect(find.text('다른 분께 보낸 글'), findsNothing);
      expect(
        t.getTopLeft(find.text('나중에 보낸 글')).dy,
        lessThan(t.getTopLeft(find.text('먼저 보낸 글')).dy),
      );
    },
  );
  testWidgets(
    'empty input stays local and 121 Unicode characters are limited',
    (t) async {
      final b = FakeBackend()..links = [familyLink()];
      await showScreen(t, screen(b));
      await t.enterText(find.byType(TextField), '   ');
      await tapText(t, '보내기');
      expect(find.text('1~120자로 적어 주세요.'), findsOneWidget);
      expect(b.sentNote, isNull);
      await t.enterText(find.byType(TextField), '가' * 121);
      expect(
        t
            .widget<TextField>(find.byType(TextField))
            .controller!
            .text
            .runes
            .length,
        120,
      );
      await t.enterText(find.byType(TextField), '🚗' * 121);
      expect(
        t
            .widget<TextField>(find.byType(TextField))
            .controller!
            .text
            .runes
            .length,
        120,
      );
      await tapText(t, '보내기');
      expect(b.notes.single.body.runes.length, 120);
    },
  );
  testWidgets('fake enforces three unseen notes and ended links', (t) async {
    final b = FakeBackend()
      ..links = [familyLink()]
      ..notes = [
        for (var i = 1; i <= 3; i++)
          SentNote.fromJson({...noteJson(), 'id': i}),
      ];
    await showScreen(t, screen(b));
    await tapText(t, '오늘도 안전 운전하세요');
    await tapText(t, '보내기');
    expect(
      find.text('아직 읽지 않은 한마디가 3개 있어요. 읽고 나면 더 보낼 수 있어요.'),
      findsOneWidget,
    );
    b.links = [];
    await tapText(t, '보내기');
    expect(find.text('공유가 끝나 보낼 수 없어요.'), findsOneWidget);
  });
}
