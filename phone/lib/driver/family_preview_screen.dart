import 'package:flutter/material.dart';

import '../backend.dart';
import '../family/family_content.dart';
import '../ui.dart';

class FamilyPreviewScreen extends StatefulWidget {
  const FamilyPreviewScreen({
    super.key,
    required this.backend,
    required this.familyId,
    required this.familyName,
  });
  final Backend backend;
  final String familyId, familyName;
  @override
  State<FamilyPreviewScreen> createState() => _FamilyPreviewScreenState();
}

class _FamilyPreviewScreenState extends State<FamilyPreviewScreen> {
  FamilyView? view;
  String? error;
  int request = 0;

  @override
  void initState() {
    super.initState();
    load();
  }

  Future<void> load() async {
    final current = ++request;
    setState(() {
      view = null;
      error = null;
    });
    try {
      final result = await widget.backend.previewFamilyView(widget.familyId);
      if (mounted && current == request) setState(() => view = result);
    } catch (e) {
      if (mounted && current == request) {
        setState(() => error = friendlyError(e));
      }
    }
  }

  @override
  Widget build(BuildContext context) => PageFrame(
    title: '${widget.familyName} 님에게 보이는 화면',
    backLabel: '가족 공유 관리',
    onRefresh: load,
    children: [
      Notice('이 화면은 ${widget.familyName} 님 휴대폰에 지금 보이는 내용과 같아요.'),
      if (view == null && error == null)
        const Center(child: CircularProgressIndicator()),
      if (error != null) ...[
        Notice(error!),
        SecondaryButton('다시 불러오기', onPressed: load),
      ],
      if (view?.options.driveDone != null)
        const Muted('운전 마침 소식은 가족 공유 유예 기간과 상관없이 바로 가요'),
      if (view != null)
        FamilyContent(
          driverName: widget.backend.displayName ?? '가족',
          paused: view!.paused,
          feed: view!.feed,
          patterns: view!.patterns,
          driveEvents: view!.options.driveDone == null ? [] : view!.driveEvents,
          weekly: view!.options.weeklyChange ? view!.weekly : [],
          habits: view!.options.habits ? view!.habits : [],
          // 서버가 이 받는 사람에게 허용된 패턴만 반환한다.
          sharePattern: true,
        ),
    ],
  );
}
