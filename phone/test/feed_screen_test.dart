import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:phone/family/feed_screen.dart';
import 'package:phone/backend.dart';
import 'package:phone/family/summary_card.dart';
import 'package:phone/share_options.dart';
import 'package:phone/family/drive_event_card.dart';
import 'package:phone/family/weekly_card.dart';
import 'package:phone/family/habit_card.dart';
import 'package:phone/family/feed_text.dart';
import 'package:phone/driver/family_preview_screen.dart';
import 'package:phone/theme.dart';

import 'support/fake_backend.dart';
import 'support/sharing_fixtures.dart';
import 'support/harness.dart';
import 'support/new_sharing_fixtures.dart';
import 'summary_card_test.dart' as fixtures;

void main() {
  testWidgets(
    'feed orders flagged weekly, daily drives, habits and monthly cards',
    (t) async {
      final b = newFeed();
      await showScreen(t, FamilyHome(backend: b));
      expect(find.byType(WeeklyCard), findsOneWidget);
      expect(find.byType(DriveEventCard), findsOneWidget);
      expect(find.byType(HabitCard), findsOneWidget);
      final tops = [
        WeeklyCard,
        DriveEventCard,
        HabitCard,
        SummaryCard,
      ].map((type) => t.getTopLeft(find.byType(type)).dy).toList();
      expect(tops[0], lessThan(tops[1]));
      expect(tops[1], lessThan(tops[2]));
      expect(tops[2], lessThan(tops[3]));
      expect(b.driveEventsCalls, 1);
      expect(b.weeklyCalls, 1);
      expect(b.habitsCalls, 1);
    },
  );
  testWidgets('only the latest habit week per driver sits above the summary', (
    t,
  ) async {
    final b = newFeed()
      ..links = [familyLink(), familyLink(id: 'd2', name: '어머니')]
      ..habits = [
        for (final week in ['2026-10-12', '2026-10-26', '2026-10-19'])
          habit(changes: {'week_start': week}),
        habit(changes: {'driver_id': 'd2', 'driver_name': '어머니'}),
      ];
    await showScreen(t, FamilyHome(backend: b));
    expect(find.byType(HabitCard), findsNWidgets(2));
    Finder inHabit(String text) =>
        find.descendant(of: find.byType(HabitCard), matching: find.text(text));
    expect(inHabit('2026년 10월 26일부터 한 주'), findsNWidgets(2));
    expect(inHabit('2026년 10월 19일부터 한 주'), findsNothing);
  });
  testWidgets(
    'drive cards group by local date and driver including single outings',
    (t) async {
      final now = DateTime.now();
      final day = DateTime(now.year, now.month, now.day);
      final b = newFeed()
        ..links = [familyLink(), familyLink(id: 'd2', name: '어머니')]
        ..driveEvents = [
          driveEvent(at: day.subtract(const Duration(hours: 1)), key: 'one'),
          driveEvent(at: day.subtract(const Duration(hours: 2)), key: 'two'),
          driveEvent(at: day.subtract(const Duration(hours: 25)), key: 'three'),
          driveEvent(
            at: day.subtract(const Duration(hours: 1)),
            id: 'd2',
            key: 'four',
          ),
          driveEvent(at: now.subtract(const Duration(days: 15)), key: 'old'),
          driveEvent(at: now.add(const Duration(days: 1)), key: 'future'),
        ];
      await showScreen(t, FamilyHome(backend: b));
      final cards = t
          .widgetList<DriveEventCard>(find.byType(DriveEventCard))
          .toList();
      expect(cards.map((c) => c.events.length), [2, 1, 1]);
      expect(cards.last.events.single.driverId, 'd2');
      expect(find.textContaining('오늘 '), findsNothing);
      expect(find.textContaining('2번 운전하셨고 모두 무사히 마치셨어요'), findsOneWidget);
      expect(find.textContaining('1번 운전하셨고 모두 무사히 마치셨어요'), findsNWidgets(2));
    },
  );
  testWidgets('preview and family feed use identical new card content', (
    t,
  ) async {
    final b = newFeed();
    b.views['f'] = FamilyView(
      paused: false,
      feed: b.feed,
      patterns: [],
      options: const ShareOptions(
        driveDone: DriveDoneMode.evening,
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
    List<String> copy() => [
      for (final type in [WeeklyCard, DriveEventCard, HabitCard])
        ...t
            .widgetList<Text>(
              find.descendant(
                of: find.byType(type),
                matching: find.byType(Text),
              ),
            )
            .map((t) => t.data!)
            // The family-only action is intentionally absent in preview.
            .where((text) => text != '어떻게 말을 꺼낼까요?'),
    ];
    final expected = copy();
    expect(expected, isNotEmpty);
    await showScreen(t, FamilyHome(backend: b));
    expect(copy(), expected);
  });
  testWidgets('pause revoke and failed refresh remove all new cards', (
    t,
  ) async {
    final b = newFeed();
    await showScreen(t, FamilyHome(backend: b));
    Future<void> refresh() async {
      await t
          .widget<RefreshIndicator>(find.byType(RefreshIndicator))
          .onRefresh();
      await t.pumpAndSettle();
    }

    for (final state in ['paused', 'revoked', 'failed']) {
      b.links = state == 'revoked'
          ? []
          : [familyLink(paused: state == 'paused')];
      if (state == 'failed') b.error = StateError('network');
      await refresh();
      expect(find.byType(WeeklyCard), findsNothing);
      expect(find.byType(DriveEventCard), findsNothing);
      expect(find.byType(HabitCard), findsNothing);
    }
  });
  testWidgets(
    'new card refresh discards late responses after sharing is paused',
    (t) async {
      final b = DelayedDriveFeed()..links = [familyLink()];
      await t.pumpWidget(
        MaterialApp(
          theme: darkTheme,
          home: FamilyHome(backend: b),
        ),
      );
      await t.pump();
      b.links = [familyLink(paused: true)];
      await t
          .widget<RefreshIndicator>(find.byType(RefreshIndicator))
          .onRefresh();
      await t.pumpAndSettle();
      b.pending.complete([driveEvent()]);
      await t.pumpAndSettle();
      expect(find.byType(DriveEventCard), findsNothing);
      expect(find.text('아버지 님이 공유를 잠시 멈췄어요'), findsOneWidget);
    },
  );
  testWidgets('new cards reload on resume and disappear after retraction', (
    t,
  ) async {
    final b = newFeed();
    await showScreen(t, FamilyHome(backend: b));
    b.driveEvents = [];
    for (final state in [
      AppLifecycleState.inactive,
      AppLifecycleState.hidden,
      AppLifecycleState.paused,
      AppLifecycleState.hidden,
      AppLifecycleState.inactive,
      AppLifecycleState.resumed,
    ]) {
      t.binding.handleAppLifecycleStateChanged(state);
    }
    await t.pumpAndSettle();
    expect(b.driveEventsCalls, 2);
    expect(b.weeklyCalls, 2);
    expect(b.habitsCalls, 2);
    expect(find.byType(DriveEventCard), findsNothing);
  });
  testWidgets('drive push target scrolls after cards have loaded', (t) async {
    final b = newFeed()..weekly = [for (var i = 0; i < 5; i++) weekly()];
    await t.binding.setSurfaceSize(const Size(390, 700));
    addTearDown(() => t.binding.setSurfaceSize(null));
    await t.pumpWidget(
      MaterialApp(
        theme: darkTheme,
        home: FamilyHome(backend: b, initialSection: FeedSection.drive),
      ),
    );
    await t.pumpAndSettle();
    final rect = t.getRect(find.byType(DriveEventCard));
    expect(rect.top, greaterThanOrEqualTo(0));
    expect(rect.top, lessThan(100));
  });
  testWidgets('new cards fit a narrow screen with large text', (t) async {
    await t.binding.setSurfaceSize(const Size(320, 700));
    addTearDown(() => t.binding.setSurfaceSize(null));
    await t.pumpWidget(
      MaterialApp(
        theme: darkTheme,
        builder: (context, child) => MediaQuery(
          data: MediaQuery.of(context)
              .copyWith(textScaler: const TextScaler.linear(2)),
          child: child!,
        ),
        home: FamilyHome(backend: newFeed()),
      ),
    );
    await t.pumpAndSettle();
    expect(find.byType(HabitCard), findsOneWidget);
    expect(t.takeException(), isNull);
  });
  testWidgets('feed reloads when the app comes back to the foreground', (
    t,
  ) async {
    final b = FakeBackend();
    await showScreen(t, FamilyHome(backend: b));
    final before = b.feedCalls;
    // 알림을 눌러 백그라운드에서 돌아온 경우: 앞에 있을 때 온 푸시가 아니어도 새로 불러온다.
    for (final state in [
      AppLifecycleState.inactive,
      AppLifecycleState.hidden,
      AppLifecycleState.paused,
      AppLifecycleState.hidden,
      AppLifecycleState.inactive,
      AppLifecycleState.resumed,
    ]) {
      t.binding.handleAppLifecycleStateChanged(state);
    }
    await t.pumpAndSettle();
    expect(b.feedCalls, before + 1);
  });
  testWidgets('empty feed explains delay', (t) async {
    await showScreen(t, FamilyHome(backend: FakeBackend()));
    expect(
      find.textContaining('새 요약은 운전하시는 분이 정한 시점이 지나면 보여요.'),
      findsOneWidget,
    );
    expect(find.text('핸들온은 의료기기가 아닙니다.'), findsOneWidget);
  });
  testWidgets('groups drivers by newest month without duplicate cards', (
    t,
  ) async {
    final b = FakeBackend()
      ..links = [familyLink()]
      ..feed = [
        FeedRow(
          driverId: 'd',
          driverName: '아버지',
          month: DateTime(2026, 9),
          stage: 'stable',
          scope: ShareScope.stage,
        ),
        fixtures.row(ShareScope.detail),
      ];
    await showScreen(t, FamilyHome(backend: b));
    expect(find.byType(SummaryCard), findsOneWidget);
    expect(find.text('2026년 10월 요약'), findsOneWidget);
    expect(find.text('2026년 9월 요약'), findsNothing);
  });
  testWidgets('failed refresh removes previously visible private detail', (
    t,
  ) async {
    final b = FakeBackend()
      ..links = [familyLink()]
      ..feed = [fixtures.row(ShareScope.detail)];
    await showScreen(t, FamilyHome(backend: b));
    b.error = StateError('network failure');
    await t.widget<RefreshIndicator>(find.byType(RefreshIndicator)).onRefresh();
    await t.pumpAndSettle();
    expect(find.text('이번 달 편지'), findsNothing);
    expect(find.text('연결하지 못했어요. 인터넷을 확인해 주세요'), findsOneWidget);
  });
  testWidgets('refresh replaces detail with stage then revoked empty feed', (
    t,
  ) async {
    final b = FakeBackend()
      ..links = [familyLink()]
      ..feed = [fixtures.row(ShareScope.detail)];
    await showScreen(t, FamilyHome(backend: b));
    expect(find.text('이번 달 편지'), findsOneWidget);
    b.feed = [fixtures.row(ShareScope.stage)];
    await t.widget<RefreshIndicator>(find.byType(RefreshIndicator)).onRefresh();
    await t.pumpAndSettle();
    expect(find.byType(DomainBars), findsNothing);
    expect(find.text('이번 달 편지'), findsNothing);
    b.feed = [];
    await t.widget<RefreshIndicator>(find.byType(RefreshIndicator)).onRefresh();
    await t.pumpAndSettle();
    expect(find.byType(SummaryCard), findsNothing);
  });
}

FakeBackend newFeed() => FakeBackend(role: Role.family, userId: 'f')
  ..links = [familyLink()]
  ..feed = [fixtures.row(ShareScope.stage)]
  ..weekly = [
    weekly(),
    weekly(changes: {'flagged': false, 'domains': [], 'top': []}),
  ]
  ..driveEvents = [driveEvent()]
  ..habits = [habit()];

class DelayedDriveFeed extends FakeBackend {
  final pending = Completer<List<DriveEvent>>();
  int calls = 0;
  @override
  Future<List<DriveEvent>> familyDriveEvents() =>
      ++calls == 1 ? pending.future : Future.value([]);
}
