import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:phone/backend.dart';
import 'package:phone/driver/family_preview_screen.dart';
import 'package:phone/driver/sharing_screen.dart';
import 'package:phone/family/family_content.dart';
import 'package:phone/family/feed_screen.dart';
import 'package:phone/family/note_screen.dart';
import 'package:phone/family/pattern_card.dart';
import 'package:phone/family/summary_card.dart';
import 'package:phone/share_options.dart';
import 'package:phone/theme.dart';

import 'support/fake_backend.dart';
import 'support/harness.dart';
import 'support/sharing_fixtures.dart';
import 'summary_card_test.dart' as summaries;

FakeBackend withData({bool paused = false, bool sharePattern = true}) =>
    FakeBackend(displayName: '아버지')
      ..links = [familyLink(paused: paused, sharePattern: sharePattern)]
      ..feed = [summaries.row(ShareScope.detail)]
      ..patterns = [pattern()];

Widget preview(FakeBackend b) =>
    FamilyPreviewScreen(backend: b, familyId: 'f', familyName: '큰딸');

void main() {
  testWidgets('preview and feed render the same summary and pattern widgets', (
    t,
  ) async {
    final b = withData();
    b.views['f'] = FamilyView(
      paused: false,
      feed: b.feed,
      patterns: b.patterns,
    );
    await showScreen(t, preview(b));
    expect(b.previewFamilyId, 'f');
    expect(find.text('큰딸 님에게 보이는 화면'), findsOneWidget);
    expect(find.text('이 화면은 큰딸 님 휴대폰에 지금 보이는 내용과 같아요.'), findsOneWidget);
    expect(find.byType(SummaryCard), findsOneWidget);
    expect(find.byType(PatternCard), findsOneWidget);
    final previewSummary = t.widget<SummaryCard>(find.byType(SummaryCard)).row;
    final previewPattern = t.widget<PatternCard>(find.byType(PatternCard)).row;
    await showScreen(t, FamilyHome(backend: b));
    expect(
      t.widget<SummaryCard>(find.byType(SummaryCard)).row,
      same(previewSummary),
    );
    expect(
      t.widget<PatternCard>(find.byType(PatternCard)).row,
      same(previewPattern),
    );
    expect(
      find.descendant(
        of: find.byType(SummaryCard),
        matching: find.byType(PatternCard),
      ),
      findsNothing,
    );
  });
  for (final isPreview in [true, false]) {
    testWidgets(
      '${isPreview ? 'preview' : 'feed'} suppresses all paused data',
      (t) async {
        final b = withData(paused: true);
        b.views['f'] = FamilyView(
          paused: true,
          feed: b.feed,
          patterns: b.patterns,
        );
        await showScreen(t, isPreview ? preview(b) : FamilyHome(backend: b));
        expect(find.byType(PausedSharingCard), findsOneWidget);
        expect(find.text('아버지 님이 공유를 잠시 멈췄어요'), findsOneWidget);
        expect(find.text('다시 공유하면 이곳에 보여요.'), findsOneWidget);
        expect(find.byType(SummaryCard), findsNothing);
        expect(find.byType(PatternCard), findsNothing);
        expect(find.text('이번 달 편지'), findsNothing);
        if (!isPreview) {
          await tapText(t, '한마디 보내기');
          expect(find.byType(NoteScreen), findsOneWidget);
          expect(find.text('아버지 님께 한마디'), findsOneWidget);
        }
      },
    );
  }
  testWidgets('empty preview explains delay and absence', (t) async {
    await showScreen(t, preview(FakeBackend()));
    expect(find.text(emptyFamilyView), findsOneWidget);
  });
  testWidgets(
    'preview refresh failure clears previously visible private data',
    (t) async {
      final b = withData();
      b.views['f'] = FamilyView(
        paused: false,
        feed: b.feed,
        patterns: b.patterns,
      );
      await showScreen(t, preview(b));
      b.error = StateError('network');
      await t
          .widget<RefreshIndicator>(find.byType(RefreshIndicator))
          .onRefresh();
      await t.pumpAndSettle();
      expect(find.byType(SummaryCard), findsNothing);
      expect(find.byType(PatternCard), findsNothing);
      expect(find.text('다시 불러오기'), findsOneWidget);
    },
  );
  testWidgets('patterns keep newest two months and consent gates visibility', (
    t,
  ) async {
    final b = withData()
      ..patterns = [
        pattern(changes: {'month': '2026-08-01'}),
        pattern(),
        pattern(changes: {'month': '2026-09-01'}),
      ];
    await showScreen(t, FamilyHome(backend: b));
    expect(
      t
          .widgetList<PatternCard>(find.byType(PatternCard))
          .map((c) => c.row.month.month),
      [10, 9],
    );
    b.links = [familyLink(sharePattern: false)];
    await t.widget<RefreshIndicator>(find.byType(RefreshIndicator)).onRefresh();
    await t.pumpAndSettle();
    expect(find.byType(PatternCard), findsNothing);
    expect(find.byType(SummaryCard), findsOneWidget);
    b.links = [];
    await t.widget<RefreshIndicator>(find.byType(RefreshIndicator)).onRefresh();
    await t.pumpAndSettle();
    expect(find.byType(SummaryCard), findsNothing);
    expect(find.text('한마디 보내기'), findsNothing);
  });
  testWidgets('paused driver is isolated from another active driver', (
    t,
  ) async {
    final b = withData(paused: true)
      ..links = [familyLink(paused: true), familyLink(id: 'd2', name: '어머니')]
      ..patterns = [
        pattern(),
        pattern(changes: {'driver_id': 'd2', 'driver_name': '어머니'}),
      ];
    await showScreen(t, FamilyHome(backend: b));
    expect(find.byType(PausedSharingCard), findsOneWidget);
    expect(find.byType(SummaryCard), findsNothing);
    expect(find.byType(PatternCard), findsOneWidget);
    expect(t.widget<PatternCard>(find.byType(PatternCard)).row.driverId, 'd2');
    expect(find.text('한마디 보내기'), findsNWidgets(2));
  });
  testWidgets('feed shows baseline collection notice with null usual values', (
    t,
  ) async {
    final b = withData()
      ..patterns = [
        pattern(
          changes: {
            'usual_trips': null,
            'usual_minutes': null,
            'usual_night_ratio': null,
            'usual_long_ratio': null,
          },
        ),
      ];
    await showScreen(t, FamilyHome(backend: b));
    expect(find.text('평소 기준을 쌓는 중이에요.'), findsOneWidget);
  });
  testWidgets(
    'sharing pause switches both ways and preview navigation has recipient',
    (t) async {
      final b = withData()
        ..shares = [
          const Share(
            familyId: 'f',
            familyName: '큰딸',
            scope: ShareScope.stage,
            timing: ShareTiming.now,
            sharePattern: true,
          ),
        ];
      await showScreen(t, SharingScreen(backend: b));
      await t.tap(find.byKey(const Key('sharingPaused')));
      await t.pumpAndSettle();
      expect(b.pausedSet, true);
      expect(
        t.widget<SwitchListTile>(find.byKey(const Key('sharingPaused'))).value,
        true,
      );
      await t.tap(find.byKey(const Key('sharingPaused')));
      await t.pumpAndSettle();
      expect(b.pausedSet, false);
      await tapText(t, '가족이 보는 화면');
      expect(b.previewFamilyId, 'f');
      expect(find.text('큰딸 님에게 보이는 화면'), findsOneWidget);
    },
  );
  testWidgets('failed pause keeps last confirmed state and reports error', (
    t,
  ) async {
    final b = withData();
    await showScreen(t, SharingScreen(backend: b));
    b.error = StateError('network');
    await t.tap(find.byKey(const Key('sharingPaused')));
    await t.pumpAndSettle();
    expect(b.pausedSet, isNull);
    expect(
      t.widget<SwitchListTile>(find.byKey(const Key('sharingPaused'))).value,
      false,
    );
    expect(find.text('연결하지 못했어요. 인터넷을 확인해 주세요'), findsOneWidget);
  });
  testWidgets('newer paused response wins over older in-flight feed', (
    t,
  ) async {
    final b = DelayedFeed();
    await t.binding.setSurfaceSize(const Size(390, 1800));
    addTearDown(() => t.binding.setSurfaceSize(null));
    await t.pumpWidget(
      MaterialApp(
        home: FamilyHome(backend: b),
        theme: darkTheme,
      ),
    );
    await t.pump();
    b.links = [familyLink(paused: true)];
    final refresh = t
        .widget<RefreshIndicator>(find.byType(RefreshIndicator))
        .onRefresh();
    await refresh;
    await t.pumpAndSettle();
    b.pending.complete([summaries.row(ShareScope.detail)]);
    await t.pumpAndSettle();
    expect(find.byType(PausedSharingCard), findsOneWidget);
    expect(find.byType(SummaryCard), findsNothing);
  });
}

class DelayedFeed extends FakeBackend {
  DelayedFeed() {
    links = [familyLink()];
  }
  final pending = Completer<List<FeedRow>>();
  int calls = 0;
  @override
  Future<List<FeedRow>> familyFeed() =>
      ++calls == 1 ? pending.future : Future.value([]);
}
