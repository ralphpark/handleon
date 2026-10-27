import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:phone/backend.dart';
import 'package:phone/app.dart';
import 'package:phone/push.dart';
import 'package:phone/family/feed_screen.dart';
import 'package:phone/family/drive_event_card.dart';
import 'package:phone/family/weekly_card.dart';
import 'package:phone/family/summary_card.dart';
import 'package:phone/driver/my_summary_screen.dart';
import 'package:phone/share_options.dart';

import 'support/fake_backend.dart';
import 'feed_screen_test.dart' as feed_fixtures;
import 'support/new_sharing_fixtures.dart';
import 'support/sharing_fixtures.dart';

class FakePushClient implements PushClient {
  final opened = StreamController<Map<String, dynamic>>.broadcast(sync: true);
  Map<String, dynamic>? initialData;
  @override
  Stream<Map<String, dynamic>> get openedMessages => opened.stream;
  @override
  Future<Map<String, dynamic>?> getInitialMessage() async => initialData;
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
  final messages = StreamController<void>.broadcast(sync: true);
  @override
  Stream<void> get foregroundMessages => messages.stream;
}

void main() {
  testWidgets('family account replacement discards old routes and cards', (
    t,
  ) async {
    final b = FakeBackend(role: Role.family, userId: 'family-a')
      ..links = [familyLink(id: 'driver-a', name: '첫 가족')]
      ..driveEvents = [driveEvent(id: 'driver-a')];
    final client = FakePushClient();
    await t.pumpWidget(
      AppRouter(
        backend: b,
        push: Push(b, client: client, platform: TargetPlatform.android),
      ),
    );
    await t.pumpAndSettle();
    expect(find.byType(DriveEventCard), findsOneWidget);
    b.userId = 'family-b';
    b.links = [familyLink(id: 'driver-b', name: '다음 가족')];
    b.driveEvents = [];
    b.events.add(null);
    await t.pumpAndSettle();
    expect(find.text('첫 가족 님'), findsNothing);
    expect(find.text('다음 가족 님'), findsOneWidget);
    expect(find.byType(DriveEventCard), findsNothing);
  });
  testWidgets(
    'family account replacement ignores a pending old account response',
    (t) async {
      final b = PendingAccountBackend();
      final client = FakePushClient();
      await t.pumpWidget(
        AppRouter(
          backend: b,
          push: Push(b, client: client, platform: TargetPlatform.android),
        ),
      );
      await t.pump();
      b.userId = 'family-b';
      b.links = [familyLink(id: 'driver-b', name: '다음 가족')];
      b.events.add(null);
      await t.pump();
      b.pending.complete([
        FeedRow(
          driverId: 'driver-a',
          driverName: '첫 가족',
          month: DateTime(2026, 10),
          stage: 'stable',
          scope: ShareScope.stage,
        ),
      ]);
      await t.pumpAndSettle();
      expect(find.text('첫 가족 님'), findsNothing);
      expect(find.text('다음 가족 님'), findsOneWidget);
      expect(find.byType(SummaryCard), findsNothing);
      expect(t.takeException(), isNull);
    },
  );
  testWidgets('push kind drive_done opens the feed', (t) async {
    final b = feed_fixtures.newFeed();
    final client = FakePushClient();
    await t.pumpWidget(
      AppRouter(
        backend: b,
        initialRoute: '/family/accept',
        push: Push(b, client: client, platform: TargetPlatform.android),
      ),
    );
    await t.pumpAndSettle();
    expect(find.byType(FamilyHome), findsNothing);
    client.opened.add({'kind': 'drive_done'});
    await t.pumpAndSettle();
    expect(find.byType(FamilyHome), findsOneWidget);
    expect(find.byType(DriveEventCard), findsOneWidget);
    expect(b.driveEventsCalls, 1);
  });
  for (final (kind, target) in [
    ('drive_done', DriveEventCard),
    ('drive_evening', DriveEventCard),
    ('weekly_change', WeeklyCard),
    ('stage', SummaryCard),
  ]) {
    testWidgets('cold start $kind opens and scrolls to its card', (t) async {
      final b = feed_fixtures.newFeed()
        ..weekly = [for (var i = 0; i < 5; i++) weekly()];
      final client = FakePushClient()..initialData = {'kind': kind};
      await t.binding.setSurfaceSize(const Size(390, 700));
      addTearDown(() => t.binding.setSurfaceSize(null));
      await t.pumpWidget(
        AppRouter(
          backend: b,
          initialRoute: '/family/accept',
          push: Push(b, client: client, platform: TargetPlatform.android),
        ),
      );
      await t.pumpAndSettle();
      expect(find.byType(FamilyHome), findsOneWidget);
      final rect = t.getRect(find.byType(target).first);
      expect(rect.top, greaterThanOrEqualTo(-1));
      expect(rect.top, lessThan(200));
    });
  }
  for (final data in <Map<String, dynamic>>[
    {},
    {'kind': 'unknown'},
    {'kind': 12},
  ]) {
    testWidgets(
      'missing or unknown push kind $data opens feed without target',
      (t) async {
        final b = feed_fixtures.newFeed();
        final client = FakePushClient()..initialData = data;
        await t.pumpWidget(
          AppRouter(
            backend: b,
            initialRoute: '/family/accept',
            push: Push(b, client: client, platform: TargetPlatform.android),
          ),
        );
        await t.pumpAndSettle();
        expect(find.text('가족 소식'), findsOneWidget);
        expect(t.getTopLeft(find.text('가족 소식')).dy, greaterThanOrEqualTo(0));
      },
    );
  }
  testWidgets('push with no visible target still opens current feed', (
    t,
  ) async {
    final client = FakePushClient()..initialData = {'kind': 'drive_done'};
    final b = FakeBackend(role: Role.family, userId: 'f');
    await t.pumpWidget(
      AppRouter(
        backend: b,
        initialRoute: '/family/accept',
        push: Push(b, client: client, platform: TargetPlatform.android),
      ),
    );
    await t.pumpAndSettle();
    expect(find.byType(FamilyHome), findsOneWidget);
    expect(find.byType(DriveEventCard), findsNothing);
    expect(t.takeException(), isNull);
  });
  testWidgets('driver push never enters family feed', (t) async {
    final client = FakePushClient()..initialData = {'kind': 'drive_done'};
    final b = FakeBackend(role: Role.driver, userId: 'd');
    await t.pumpWidget(
      AppRouter(
        backend: b,
        push: Push(b, client: client, platform: TargetPlatform.android),
      ),
    );
    await t.pumpAndSettle();
    client.opened.add({'kind': 'weekly_change'});
    await t.pumpAndSettle();
    expect(find.byType(DriverHome), findsOneWidget);
    expect(find.byType(FamilyHome), findsNothing);
  });
  testWidgets('opened messages stop navigating after signout', (t) async {
    final client = FakePushClient();
    final b = FakeBackend(role: Role.family, userId: 'f');
    await t.pumpWidget(
      AppRouter(
        backend: b,
        push: Push(b, client: client, platform: TargetPlatform.android),
      ),
    );
    await t.pumpAndSettle();
    await b.signOut();
    await t.pumpAndSettle();
    client.opened.add({'kind': 'drive_done'});
    await t.pumpAndSettle();
    expect(find.byType(FamilyHome), findsNothing);
    expect(find.text('로그인하기'), findsNothing);
    expect(find.text('처음이에요'), findsOneWidget);
  });
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
  test('foreground message is an arrival only while signed in', () async {
    final b = FakeBackend(role: Role.family, userId: 'f');
    final client = FakePushClient();
    final push = Push(b, client: client, platform: TargetPlatform.android);
    var arrivals = 0;
    final sub = push.arrivals.listen((_) => arrivals++);
    await push.init();
    client.messages.add(null);
    await Future<void>.delayed(Duration.zero);
    expect(arrivals, 1);
    await b.signOut();
    await push.init();
    client.messages.add(null);
    await Future<void>.delayed(Duration.zero);
    expect(arrivals, 1);
    await sub.cancel();
    push.dispose();
  });
  testWidgets('open family app shows notice and reloads feed on push', (
    t,
  ) async {
    final b = FakeBackend(role: Role.family, userId: 'f');
    final client = FakePushClient();
    final push = Push(b, client: client, platform: TargetPlatform.android);
    await t.pumpWidget(AppRouter(backend: b, push: push));
    await t.pumpAndSettle();
    final before = b.feedCalls;
    client.messages.add(null);
    await t.pump();
    await t.pump(const Duration(milliseconds: 300));
    expect(find.text(arrivalNotice), findsOneWidget);
    expect(b.feedCalls, before + 1);
    expect(b.linksCalls, before + 1);
    expect(b.patternsCalls, before + 1);
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

class PendingAccountBackend extends FakeBackend {
  PendingAccountBackend() : super(role: Role.family, userId: 'family-a') {
    links = [familyLink(id: 'driver-a', name: '첫 가족')];
  }
  final pending = Completer<List<FeedRow>>();
  @override
  Future<List<FeedRow>> familyFeed() =>
      userId == 'family-a' ? pending.future : Future.value([]);
}
