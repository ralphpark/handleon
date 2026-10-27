import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:phone/backend.dart';
import 'package:phone/driver/family_preview_screen.dart';
import 'package:phone/family/coach_sheet.dart';
import 'package:phone/family/drive_event_card.dart';
import 'package:phone/family/feed_screen.dart';
import 'package:phone/family/habit_card.dart';
import 'package:phone/family/summary_card.dart';
import 'package:phone/family/weekly_card.dart';
import 'package:phone/share_options.dart';
import 'package:phone/theme.dart';

import 'support/coach_fixtures.dart';
import 'support/fake_backend.dart';
import 'support/harness.dart';
import 'support/new_sharing_fixtures.dart';
import 'support/sharing_fixtures.dart';

const prompt = '어떻게 말을 꺼낼까요?';
const footer = 'AI가 만든 제안이에요. 부모님 마음을 가장 잘 아는 건 가족이에요';

FakeBackend coachFeed() => FakeBackend(role: Role.family, userId: 'f')
  ..links = [familyLink()]
  ..feed = [FeedRow.fromJson(feedJson())]
  ..weekly = [weekly()]
  ..driveEvents = [driveEvent()]
  ..habits = [habit()];

Future<void> openCoach(
  WidgetTester t, {
  Type card = WeeklyCard,
  bool pending = false,
}) async {
  final button = find.descendant(
    of: find.byType(card),
    matching: find.text(prompt),
  );
  await t.ensureVisible(button);
  await t.tap(button);
  if (pending) {
    await t.pump();
    await t.pump(const Duration(milliseconds: 400));
  } else {
    await t.pumpAndSettle();
  }
}

Future<void> closeCoach(WidgetTester t) async {
  await t.tap(find.byTooltip('닫기'));
  await t.pumpAndSettle();
}

void main() {
  testWidgets('shows three openers and two avoid lines', (t) async {
    final b = coachFeed()..coachResult = coachAdvice();
    await showScreen(t, FamilyHome(backend: b));
    await openCoach(t);
    for (final text in [
      '이렇게 말을 꺼내 보세요',
      '이런 말은 피하면 좋아요',
      '이야깃거리',
      '요즘 운전 다니시는 건 어떠세요?',
      '이번 주말에 같이 드라이브 갈까요?',
      '운전하시다 불편한 점은 없으셨어요?',
      '이제 운전 그만하세요',
      '왜 그렇게 운전하셨어요',
      '요즘 자주 가시는 곳 이야기',
      footer,
    ]) {
      expect(find.text(text), findsOneWidget);
    }
    expect(find.text('복사'), findsNWidgets(3));
    expect(find.text('기본 제안'), findsNothing);
    expect(b.coachCalls, ['d']);
  });

  testWidgets('daily limit shows the polite message', (t) async {
    final b = coachFeed()..coachError = const DailyLimit();
    await showScreen(t, FamilyHome(backend: b));
    await openCoach(t);
    expect(find.text('오늘은 여기까지예요. 내일 다시 물어봐 주세요'), findsOneWidget);
    expect(find.text('복사'), findsNothing);
  });

  testWidgets('fallback is labelled', (t) async {
    final b = coachFeed()..coachResult = coachAdvice(fallback: true);
    await showScreen(t, FamilyHome(backend: b));
    await openCoach(t);
    expect(find.text('기본 제안'), findsOneWidget);
    expect(find.text('요즘 자주 가시는 곳 이야기'), findsOneWidget);
  });

  testWidgets('copy puts the sentence on the clipboard', (t) async {
    final writes = <Object?>[];
    final completed = Completer<void>();
    t.binding.defaultBinaryMessenger.setMockMethodCallHandler(
      SystemChannels.platform,
      (call) async {
        if (call.method == 'Clipboard.setData') {
          writes.add(call.arguments);
          await completed.future;
        }
        return null;
      },
    );
    addTearDown(
      () => t.binding.defaultBinaryMessenger.setMockMethodCallHandler(
        SystemChannels.platform,
        null,
      ),
    );
    await showScreen(t, FamilyHome(backend: coachFeed()));
    await openCoach(t);
    await t.tap(find.text('복사').first);
    await t.pump();
    expect(writes, [
      {'text': '요즘 운전 다니시는 건 어떠세요?'},
    ]);
    expect(find.text('복사했어요'), findsNothing);
    completed.complete();
    await t.pumpAndSettle();
    expect(find.text('복사했어요'), findsOneWidget);
    expect(
      t.widget<SnackBar>(find.byType(SnackBar)).duration,
      const Duration(seconds: 2),
    );
    await t.pump(const Duration(seconds: 3));
    await t.pumpAndSettle();
    for (final (index, sentence) in [
      (1, '이번 주말에 같이 드라이브 갈까요?'),
      (2, '운전하시다 불편한 점은 없으셨어요?'),
    ]) {
      await t.tap(find.text('복사').at(index));
      await t.pumpAndSettle();
      expect(writes.last, {'text': sentence});
    }
  });

  testWidgets('share missing and unavailable errors show only fixed messages', (
    t,
  ) async {
    final b = coachFeed();
    await showScreen(t, FamilyHome(backend: b));
    for (final (error, message) in <(Object, String)>[
      (const ShareNotFound(), '공유가 끊겨 제안을 만들 수 없어요'),
      (const Unavailable(), '잠시 뒤에 다시 시도해 주세요'),
      (StateError('private server detail'), '잠시 뒤에 다시 시도해 주세요'),
    ]) {
      b.coachError = error;
      await openCoach(t);
      expect(find.text(message), findsOneWidget);
      expect(find.textContaining('private server detail'), findsNothing);
      expect(find.text('복사'), findsNothing);
      await closeCoach(t);
    }
  });

  testWidgets(
    'all four family cards open coach and driver preview hides buttons',
    (t) async {
      final b = coachFeed();
      await showScreen(t, FamilyHome(backend: b));
      expect(find.text(prompt), findsNWidgets(4));
      for (final card in [WeeklyCard, DriveEventCard, HabitCard, SummaryCard]) {
        await openCoach(t, card: card);
        expect(find.byType(CoachSheet), findsOneWidget);
        await closeCoach(t);
      }
      expect(b.coachCalls, ['d', 'd', 'd', 'd']);
      b.views['f'] = FamilyView(
        paused: false,
        feed: b.feed,
        patterns: [],
        options: const ShareOptions(
          driveDone: DriveDoneMode.each,
          weeklyChange: true,
          habits: true,
        ),
        driveEvents: b.driveEvents,
        weekly: b.weekly,
        habits: b.habits,
      );
      await showScreen(
        t,
        FamilyPreviewScreen(backend: b, familyId: 'f', familyName: '큰딸'),
      );
      for (final card in [WeeklyCard, DriveEventCard, HabitCard, SummaryCard]) {
        expect(find.byType(card), findsOneWidget);
      }
      expect(find.text(prompt), findsNothing);
      expect(b.coachCalls, hasLength(4));
    },
  );

  testWidgets(
    'loading reuses the pending driver request across cards and refresh',
    (t) async {
      final pending = Completer<CoachAdvice>();
      final b = coachFeed()..pendingCoach = pending;
      await showScreen(t, FamilyHome(backend: b));
      await openCoach(t, pending: true);
      expect(find.byType(CircularProgressIndicator), findsOneWidget);
      expect(find.text('제안을 준비하고 있어요'), findsOneWidget);
      expect(b.coachCalls, ['d']);
      await closeCoach(t);
      await t
          .widget<RefreshIndicator>(find.byType(RefreshIndicator))
          .onRefresh();
      await t.pumpAndSettle();
      await openCoach(t, card: DriveEventCard, pending: true);
      expect(b.coachCalls, ['d']);
      pending.complete(coachAdvice());
      await t.pumpAndSettle();
      expect(find.byType(CircularProgressIndicator), findsNothing);
      expect(find.text('요즘 자주 가시는 곳 이야기'), findsOneWidget);
      await closeCoach(t);
      b.pendingCoach = null;
      await openCoach(t);
      expect(b.coachCalls, ['d', 'd']);
    },
  );

  testWidgets('different drivers do not share a pending coach result', (
    t,
  ) async {
    final pending = Completer<CoachAdvice>();
    final b = coachFeed()
      ..pendingCoach = pending
      ..links = [familyLink(), familyLink(id: 'd2', name: '어머니')]
      ..weekly = [
        weekly(),
        weekly(changes: {'driver_id': 'd2', 'driver_name': '어머니'}),
      ];
    await showScreen(t, FamilyHome(backend: b));
    final first = find.descendant(
      of: find.byType(WeeklyCard).first,
      matching: find.text(prompt),
    );
    await t.tap(first);
    await t.pump();
    await t.pump(const Duration(milliseconds: 400));
    await closeCoach(t);
    b.pendingCoach = null;
    final second = find.descendant(
      of: find.byType(WeeklyCard).last,
      matching: find.text(prompt),
    );
    await t.ensureVisible(second);
    await t.tap(second);
    await t.pumpAndSettle();
    expect(b.coachCalls, ['d', 'd2']);
    await closeCoach(t);
    pending.completeError(const Unavailable());
    await t.pumpAndSettle();
    expect(t.takeException(), isNull);
  });

  testWidgets('coach fits narrow large text and keeps the footer reachable', (
    t,
  ) async {
    await t.binding.setSurfaceSize(const Size(320, 700));
    addTearDown(() => t.binding.setSurfaceSize(null));
    final long = '가' * 80;
    final b = coachFeed()
      ..coachResult = CoachAdvice(
        openers: [long, long, long],
        avoid: [long, long],
        topic: long,
        fromFallback: true,
      );
    await t.pumpWidget(
      MaterialApp(
        theme: darkTheme,
        builder: (context, child) => MediaQuery(
          data: MediaQuery.of(context)
              .copyWith(textScaler: const TextScaler.linear(2)),
          child: child!,
        ),
        home: FamilyHome(backend: b),
      ),
    );
    await t.pumpAndSettle();
    await openCoach(t);
    await t.ensureVisible(find.text(footer));
    await t.pumpAndSettle();
    expect(find.text(footer).hitTestable(), findsOneWidget);
    expect(t.takeException(), isNull);
  });
}
