import 'dart:async';

import 'package:flutter/material.dart';

import '../backend.dart';
import '../ui.dart';
import 'family_content.dart';
import 'feed_text.dart';
import 'note_screen.dart';
import 'coach_sheet.dart';

const feedNotice =
    '새 요약은 운전하시는 분이 정한 시점이 지나면 보여요. 도착하면 "새 운전 요약이 도착했어요" 알림을 보내 드려요.';

class FamilyHome extends StatefulWidget {
  const FamilyHome({
    super.key,
    required this.backend,
    this.arrivals,
    this.initialSection,
  });
  final Backend backend;
  final FeedSection? initialSection;

  /// 앱이 열린 채로 새 요약 알림이 오면 피드를 다시 불러온다.
  final Stream<void>? arrivals;
  @override
  State<FamilyHome> createState() => _FamilyHomeState();
}

class _FamilyHomeState extends State<FamilyHome> {
  List<FeedRow> rows = [];
  List<FamilyLink> links = [];
  List<PatternRow> patterns = [];
  List<DriveEvent> driveEvents = [];
  List<WeeklyChange> weekly = [];
  List<WeeklyHabit> habits = [];
  final sectionKeys = <String, Map<FeedSection, GlobalKey>>{};
  final coachRequests = <String, Future<CoachAdvice>>{};
  bool coachSheetOpen = false;
  FeedSection? pendingSection;
  bool loading = true;
  String? error;
  int request = 0;
  StreamSubscription<void>? arrivals;
  // 백그라운드에서 알림을 눌러 돌아오거나 다른 앱에서 돌아오면 새로 불러온다(앞에 있을 때 온 푸시만으로는 부족하다).
  late final AppLifecycleListener lifecycle;
  @override
  void initState() {
    super.initState();
    pendingSection = widget.initialSection;
    arrivals = widget.arrivals?.listen((_) => load());
    lifecycle = AppLifecycleListener(onResume: load);
    load();
  }

  @override
  void dispose() {
    unawaited(arrivals?.cancel());
    lifecycle.dispose();
    super.dispose();
  }

  Future<void> load() async {
    final current = ++request;
    setState(() {
      loading = true;
      rows = [];
      links = [];
      patterns = [];
      driveEvents = [];
      weekly = [];
      habits = [];
      error = null;
    });
    try {
      final result = await Future.wait<Object>([
        widget.backend.familyLinks(),
        widget.backend.familyFeed(),
        widget.backend.familyPatterns(),
        widget.backend.familyDriveEvents(),
        widget.backend.familyWeekly(),
        widget.backend.familyHabits(),
      ]);
      if (mounted && current == request) {
        setState(() {
          links = result[0] as List<FamilyLink>;
          rows = result[1] as List<FeedRow>;
          patterns = result[2] as List<PatternRow>;
          driveEvents = result[3] as List<DriveEvent>;
          weekly = result[4] as List<WeeklyChange>;
          habits = result[5] as List<WeeklyHabit>;
          sectionKeys.removeWhere(
            (id, _) => !links.any((link) => link.driverId == id),
          );
          for (final link in links) {
            sectionKeys.putIfAbsent(
              link.driverId,
              () => {
                for (final section in FeedSection.values) section: GlobalKey(),
              },
            );
          }
        });
        WidgetsBinding.instance.addPostFrameCallback((_) {
          if (!mounted || current != request || pendingSection == null) return;
          final section = pendingSection!;
          pendingSection = null;
          for (final link in links) {
            final target = sectionKeys[link.driverId]?[section]?.currentContext;
            if (target != null) {
              unawaited(
                Scrollable.ensureVisible(
                  target,
                  alignment: 0,
                  duration: const Duration(milliseconds: 300),
                ),
              );
              break;
            }
          }
        });
      }
    } catch (e) {
      if (mounted && current == request) {
        setState(() => error = friendlyError(e));
      }
    } finally {
      if (mounted && current == request) setState(() => loading = false);
    }
  }

  Future<void> accept() async {
    await Navigator.of(context).pushNamed('/family/accept');
    if (mounted) await load();
  }

  Future<void> openCoach(String driverId) async {
    if (coachSheetOpen) return;
    coachSheetOpen = true;
    final advice = coachRequests.putIfAbsent(driverId, () {
      final pending = Future<CoachAdvice>.sync(
        () => widget.backend.coach(driverId),
      );
      // Keep an in-flight request after dismissal or feed refresh, but never
      // cache finished advice: sharing and the server's context may change.
      unawaited(
        pending.then<void>(
          (_) => coachRequests.remove(driverId),
          onError: (Object _, StackTrace _) {
            coachRequests.remove(driverId);
          },
        ),
      );
      return pending;
    });
    try {
      await showModalBottomSheet<void>(
        context: context,
        isScrollControlled: true,
        useSafeArea: true,
        showDragHandle: true,
        clipBehavior: Clip.antiAlias,
        builder: (_) => FractionallySizedBox(
          heightFactor: .9,
          child: CoachSheet(advice: advice),
        ),
      );
    } finally {
      coachSheetOpen = false;
    }
  }

  @override
  Widget build(BuildContext context) => PageFrame(
    title: '가족 소식',
    subtitle: '아래로 당기면 새로 불러와요',
    onRefresh: load,
    actions: [
      IconButton(
        tooltip: '초대 코드 넣기',
        onPressed: accept,
        icon: const Icon(Icons.add),
      ),
      AccountMenu(backend: widget.backend),
    ],
    children: [
      if (loading) const Center(child: CircularProgressIndicator()),
      if (error != null) ...[
        Notice(error!),
        SecondaryButton('다시 불러오기', onPressed: load),
      ],
      for (final link in links)
        Column(
          key: ValueKey(link.driverId),
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: spaced([
            SectionTitle('${link.driverName} 님'),
            FamilyContent(
              driverName: link.driverName,
              paused: link.paused,
              sharePattern: link.sharePattern,
              feed: rows.where((r) => r.driverId == link.driverId).toList(),
              patterns: patterns
                  .where((r) => r.driverId == link.driverId)
                  .toList(),
              driveEvents: driveEvents
                  .where((r) => r.driverId == link.driverId)
                  .toList(),
              weekly: weekly.where((r) => r.driverId == link.driverId).toList(),
              habits: habits.where((r) => r.driverId == link.driverId).toList(),
              sectionKeys: sectionKeys[link.driverId] ?? const {},
              showCoach: true,
              onCoach: openCoach,
            ),
            SecondaryButton(
              '한마디 보내기',
              onPressed: () => Navigator.of(context).push(
                MaterialPageRoute<void>(
                  builder: (_) => NoteScreen(
                    backend: widget.backend,
                    driverId: link.driverId,
                    driverName: link.driverName,
                  ),
                ),
              ),
            ),
          ], 16),
        ),
      if (!loading && error == null) const Notice(feedNotice),
      PrimaryButton('초대 받기', onPressed: accept),
      const Text(disclaimer, textAlign: TextAlign.center),
    ],
  );
}

typedef FeedScreen = FamilyHome;
