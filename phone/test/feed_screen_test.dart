import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:phone/family/feed_screen.dart';
import 'package:phone/backend.dart';
import 'package:phone/family/summary_card.dart';
import 'package:phone/share_options.dart';

import 'support/fake_backend.dart';
import 'support/harness.dart';
import 'summary_card_test.dart' as fixtures;

void main() {
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
    final b = FakeBackend()..feed = [fixtures.row(ShareScope.detail)];
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
    final b = FakeBackend()..feed = [fixtures.row(ShareScope.detail)];
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
