import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:phone/backend.dart';
import 'package:phone/driver/sharing_screen.dart';
import 'package:phone/driver/family_preview_screen.dart';
import 'package:phone/family/drive_event_card.dart';
import 'package:phone/family/weekly_card.dart';
import 'package:phone/family/habit_card.dart';
import 'package:phone/share_options.dart';

import 'support/fake_backend.dart';
import 'support/harness.dart';
import 'support/new_sharing_fixtures.dart';

FakeBackend backend() => FakeBackend()
  ..shares = [
    const Share(
      familyId: 'f',
      familyName: '큰딸',
      scope: ShareScope.trend,
      timing: ShareTiming.d7,
      sharePattern: false,
    ),
  ];
void main() {
  testWidgets('drive done needs an explicit choice', (t) async {
    final b = backend();
    await showScreen(t, SharingScreen(backend: b));
    await tapText(t, '운전 마침 소식');
    expect(
      t
          .widget<RadioGroup<DriveDoneMode>>(
            find.byType(RadioGroup<DriveDoneMode>),
          )
          .groupValue,
      isNull,
    );
    expect(b.optionSaves, isEmpty);
    await tapText(t, '취소');
    expect(b.optionSaves, isEmpty);
    expect(
      t.widget<SwitchListTile>(find.byKey(const Key('driveDone:f'))).value,
      false,
    );
  });
  testWidgets('choosing evening saves evening', (t) async {
    final b = backend();
    await showScreen(t, SharingScreen(backend: b));
    await tapText(t, '운전 마침 소식');
    await tapText(t, '하루 한 번 저녁에 모아서');
    expect(b.optionSaves.single.$1, 'f');
    expect(b.optionSaves.single.$2.driveDone, DriveDoneMode.evening);
    expect(find.text('하루 한 번 저녁에 모아서'), findsOneWidget);
    await tapText(t, '운전 마침 소식');
    expect(b.optionSaves.last.$2.driveDone, isNull);
  });
  testWidgets('new items start off for a new share', (t) async {
    final b = backend();
    await showScreen(t, SharingScreen(backend: b));
    for (final key in ['driveDone:f', 'weeklyChange:f', 'habits:f']) {
      expect(t.widget<SwitchListTile>(find.byKey(Key(key))).value, false);
    }
    expect(find.text('가족 공유 유예 기간과 상관없이 바로 가요'), findsOneWidget);
    expect(find.text('몇 주 사이에 갑자기 달라지면 알려요'), findsOneWidget);
    expect(find.text('매주 방향지시등·급제동 같은 운전 습관 합계를 보내요'), findsOneWidget);
    expect(b.optionSaves, isEmpty);
  });
  testWidgets('per-family option changes preserve other consent and use ids', (
    t,
  ) async {
    final b = backend()
      ..shares.add(
        const Share(
          familyId: 'f2',
          familyName: '큰딸',
          scope: ShareScope.stage,
          timing: ShareTiming.now,
          sharePattern: false,
        ),
      );
    b.options['f'] = const ShareOptions(
      driveDone: DriveDoneMode.each,
      habits: true,
    );
    b.options['f2'] = const ShareOptions(weeklyChange: true);
    await showScreen(t, SharingScreen(backend: b));
    final toggle = find.byKey(const Key('weeklyChange:f'));
    await t.ensureVisible(toggle);
    await t.tap(toggle);
    await t.pumpAndSettle();
    final saved = b.optionSaves.single;
    expect(saved.$1, 'f');
    expect(
      (saved.$2.driveDone, saved.$2.weeklyChange, saved.$2.habits),
      (DriveDoneMode.each, true, true),
    );
    expect(b.options['f2']!.driveDone, isNull);
    expect(b.options['f2']!.habits, false);
  });
  testWidgets('failed new option save keeps confirmed value', (t) async {
    final b = backend();
    await showScreen(t, SharingScreen(backend: b));
    b.error = StateError('network');
    await tapText(t, '운전 습관 요약');
    expect(
      t.widget<SwitchListTile>(find.byKey(const Key('habits:f'))).value,
      false,
    );
    expect(find.text('연결하지 못했어요. 인터넷을 확인해 주세요'), findsOneWidget);
    expect(b.optionSaves, isEmpty);
  });
  testWidgets(
    'unrelated toggle never restores drive notices stopped on the car',
    (t) async {
      final b = backend();
      b.options['f'] = const ShareOptions(driveDone: DriveDoneMode.each);
      await showScreen(t, SharingScreen(backend: b));
      // Another device has revoked drive notices since this screen loaded.
      b.options['f'] = const ShareOptions(weeklyChange: true);
      await tapText(t, '운전 습관 요약');
      final saved = b.optionSaves.single.$2;
      expect(saved.driveDone, isNull);
      expect(saved.weeklyChange, true);
      expect(saved.habits, true);
      expect(
        t.widget<SwitchListTile>(find.byKey(const Key('driveDone:f'))).value,
        false,
      );
    },
  );
  testWidgets(
    'explicit drive choice preserves latest remote weekly and habits choices',
    (t) async {
      final b = backend();
      await showScreen(t, SharingScreen(backend: b));
      await tapText(t, '운전 마침 소식');
      b.options['f'] = const ShareOptions(weeklyChange: true, habits: true);
      await tapText(t, '운전 끝날 때마다');
      final saved = b.optionSaves.single.$2;
      expect(saved.driveDone, DriveDoneMode.each);
      expect(saved.weeklyChange, true);
      expect(saved.habits, true);
    },
  );
  testWidgets('option refresh failure prevents writing cached consent', (
    t,
  ) async {
    final b = FailingOptionsBackend()..shares = backend().shares;
    await showScreen(t, SharingScreen(backend: b));
    b.failPreview = true;
    await tapText(t, '운전 습관 요약');
    expect(b.optionSaves, isEmpty);
    expect(find.text('연결하지 못했어요. 인터넷을 확인해 주세요'), findsOneWidget);
  });
  testWidgets('preview shows drive events when on', (t) async {
    final b = backend();
    b.views['f'] = FamilyView(
      paused: false,
      feed: [],
      patterns: [],
      options: const ShareOptions(
        driveDone: DriveDoneMode.each,
        weeklyChange: true,
        habits: true,
      ),
      driveEvents: [driveEvent()],
      weekly: [weekly()],
      habits: [habit()],
    );
    await showScreen(
      t,
      FamilyPreviewScreen(backend: b, familyId: 'f', familyName: '큰딸'),
    );
    expect(find.byType(DriveEventCard), findsOneWidget);
    expect(find.byType(WeeklyCard), findsOneWidget);
    expect(find.byType(HabitCard), findsOneWidget);
    expect(find.textContaining('가족 공유 유예 기간과 상관없이 바로 가요'), findsOneWidget);
  });
  testWidgets('preview hides disabled new items and all paused data', (
    t,
  ) async {
    final b = backend();
    for (final paused in [false, true]) {
      b.views['f'] = FamilyView(
        paused: paused,
        feed: [],
        patterns: [],
        options: paused
            ? const ShareOptions(
                driveDone: DriveDoneMode.each,
                weeklyChange: true,
                habits: true,
              )
            : const ShareOptions(),
        driveEvents: [driveEvent()],
        weekly: [weekly()],
        habits: [habit()],
      );
      await showScreen(
        t,
        FamilyPreviewScreen(
          key: ValueKey(paused),
          backend: b,
          familyId: 'f',
          familyName: '큰딸',
        ),
      );
      expect(find.byType(DriveEventCard), findsNothing);
      expect(find.byType(WeeklyCard), findsNothing);
      expect(find.byType(HabitCard), findsNothing);
    }
  });
  testWidgets('revoke only after confirmation', (t) async {
    final b = backend();
    await showScreen(t, SharingScreen(backend: b));
    await tapText(t, '공유 끊기');
    expect(b.revoked, isNull);
    expect(find.text('큰딸 님과의 공유를 끊을까요?'), findsOneWidget);
    await tapText(t, '그대로 두기');
    expect(b.revoked, isNull);
    await tapText(t, '공유 끊기');
    await tapText(t, '공유 끊기');
    expect(b.revoked, 'f');
    expect(find.text('큰딸'), findsNothing);
  });
  testWidgets('edit prefills and sends new scope timing', (t) async {
    final b = backend();
    await showScreen(t, SharingScreen(backend: b));
    await tapText(t, '바꾸기');
    expect(
      t
          .widget<RadioGroup<ShareScope>>(find.byType(RadioGroup<ShareScope>))
          .groupValue,
      ShareScope.trend,
    );
    expect(
      t
          .widget<RadioGroup<ShareTiming>>(find.byType(RadioGroup<ShareTiming>))
          .groupValue,
      ShareTiming.d7,
    );
    expect(
      t.widget<RadioGroup<bool>>(find.byType(RadioGroup<bool>)).groupValue,
      false,
    );
    await tapText(t, '단계만');
    await tapText(t, '3일 뒤 공유');
    await tapText(t, '함께 보여 주기');
    await tapText(t, '저장');
    expect(b.updated, ('f', ShareScope.stage, ShareTiming.d3, true));
  });
}

class FailingOptionsBackend extends FakeBackend {
  bool failPreview = false;
  @override
  Future<FamilyView> previewFamilyView(String familyId) {
    if (failPreview) return Future.error(StateError('network'));
    return super.previewFamilyView(familyId);
  }
}
