import 'dart:async';

import 'package:flutter/material.dart';

import '../backend.dart';
import '../ui.dart';
import 'summary_card.dart';

const feedNotice =
    '새 요약은 운전하시는 분이 정한 시점이 지나면 보여요. 도착하면 "새 운전 요약이 도착했어요" 알림을 보내 드려요.';

class FamilyHome extends StatefulWidget {
  const FamilyHome({super.key, required this.backend, this.arrivals});
  final Backend backend;

  /// 앱이 열린 채로 새 요약 알림이 오면 피드를 다시 불러온다.
  final Stream<void>? arrivals;
  @override
  State<FamilyHome> createState() => _FamilyHomeState();
}

class _FamilyHomeState extends State<FamilyHome> {
  List<FeedRow> rows = [];
  bool loading = true;
  String? error;
  int request = 0;
  StreamSubscription<void>? arrivals;
  @override
  void initState() {
    super.initState();
    arrivals = widget.arrivals?.listen((_) => load());
    load();
  }

  @override
  void dispose() {
    unawaited(arrivals?.cancel());
    super.dispose();
  }

  Future<void> load() async {
    final current = ++request;
    setState(() {
      loading = true;
      rows = [];
      error = null;
    });
    try {
      final result = await widget.backend.familyFeed();
      final latest = <String, FeedRow>{};
      for (final row in result) {
        if (!latest.containsKey(row.driverId) ||
            row.month.isAfter(latest[row.driverId]!.month)) {
          latest[row.driverId] = row;
        }
      }
      if (mounted && current == request) {
        setState(
          () =>
              rows = latest.values.toList()
                ..sort((a, b) => b.month.compareTo(a.month)),
        );
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
      for (final row in rows)
        SummaryCard(key: ValueKey(row.driverId), row: row),
      if (!loading && error == null) const Notice(feedNotice),
      PrimaryButton('초대 받기', onPressed: accept),
      const Text(disclaimer, textAlign: TextAlign.center),
    ],
  );
}

typedef FeedScreen = FamilyHome;
