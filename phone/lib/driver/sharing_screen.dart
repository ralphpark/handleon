import 'package:flutter/material.dart';

import '../backend.dart';
import '../share_options.dart';
import '../share_picker.dart';
import '../ui.dart';

class SharingScreen extends StatefulWidget {
  const SharingScreen({super.key, required this.backend});
  final Backend backend;
  @override
  State<SharingScreen> createState() => _SharingScreenState();
}

class _SharingScreenState extends State<SharingScreen> {
  List<Share>? shares;
  String? error;
  bool busy = false;
  @override
  void initState() {
    super.initState();
    load();
  }

  Future<void> load() async {
    try {
      final result = await widget.backend.myShares();
      if (mounted) {
        setState(() {
          shares = result;
          error = null;
        });
      }
    } catch (e) {
      if (mounted) setState(() => error = friendlyError(e));
    }
  }

  Future<void> edit(Share share) async {
    final selection = await showModalBottomSheet<(ShareScope, ShareTiming)>(
      context: context,
      isScrollControlled: true,
      useSafeArea: true,
      showDragHandle: true,
      builder: (_) => _EditShare(share: share),
    );
    if (selection == null || !mounted) return;
    await change(
      () => widget.backend.updateShare(
        share.familyId,
        selection.$1,
        selection.$2,
      ),
    );
  }

  Future<void> revoke(Share share) async {
    final confirmed = await showModalBottomSheet<bool>(
      context: context,
      isScrollControlled: true,
      useSafeArea: true,
      showDragHandle: true,
      builder: (context) => SingleChildScrollView(
        padding: const EdgeInsets.fromLTRB(16, 0, 16, 32),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: spaced([
            Text(
              '${share.familyName}에게 공유를 멈출까요?',
              style: Theme.of(context).textTheme.titleLarge,
            ),
            const Muted(
              '지금까지 보낸 요약도 더는 보이지 않고, 아직 보내지 않은 알림도 취소돼요. 다시 공유하려면 새로 초대하면 돼요.',
            ),
            Row(
              children: [
                Expanded(
                  child: SecondaryButton(
                    '그대로 두기',
                    onPressed: () => Navigator.pop(context, false),
                  ),
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: PrimaryButton(
                    '공유 멈추기',
                    onPressed: () => Navigator.pop(context, true),
                  ),
                ),
              ],
            ),
          ], 12),
        ),
      ),
    );
    if (confirmed == true && mounted) {
      await change(() => widget.backend.revokeShare(share.familyId));
    }
  }

  Future<void> change(Future<void> Function() action) async {
    setState(() {
      busy = true;
      error = null;
    });
    try {
      await action();
      await load();
    } catch (e) {
      if (mounted) setState(() => error = friendlyError(e));
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  @override
  Widget build(BuildContext context) => PageFrame(
    title: '가족 공유 관리',
    backLabel: '내 운전 요약',
    subtitle: '가족마다 보여 줄 것과 시점을 따로 정할 수 있어요.',
    onRefresh: load,
    children: [
      if (shares == null && error == null)
        const Center(child: CircularProgressIndicator()),
      if (error != null) ...[
        Notice(error!),
        SecondaryButton('다시 불러오기', onPressed: load),
      ],
      if (shares?.isEmpty ?? false) const Notice('아직 공유 중인 가족이 없어요'),
      for (final share in shares ?? <Share>[])
        Panel(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: spaced([
              Text(
                share.familyName,
                style: Theme.of(context).textTheme.titleLarge,
              ),
              Muted('${share.scope.label} · ${share.timing.label}'),
              Row(
                children: [
                  Expanded(
                    child: SecondaryButton(
                      '바꾸기',
                      onPressed: busy ? null : () => edit(share),
                    ),
                  ),
                  const SizedBox(width: 8),
                  Expanded(
                    child: SecondaryButton(
                      '공유 멈추기',
                      onPressed: busy ? null : () => revoke(share),
                    ),
                  ),
                ],
              ),
            ], 12),
          ),
        ),
      PrimaryButton(
        '가족 더 초대하기',
        onPressed: () => Navigator.of(context).pushNamed('/driver/invite'),
      ),
    ],
  );
}

class _EditShare extends StatefulWidget {
  const _EditShare({required this.share});
  final Share share;
  @override
  State<_EditShare> createState() => _EditShareState();
}

class _EditShareState extends State<_EditShare> {
  late ShareScope scope = widget.share.scope;
  late ShareTiming timing = widget.share.timing;
  @override
  Widget build(BuildContext context) => SingleChildScrollView(
    padding: EdgeInsets.fromLTRB(
      16,
      0,
      16,
      32 + MediaQuery.viewInsetsOf(context).bottom,
    ),
    child: Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: spaced([
        Text(
          '${widget.share.familyName}에게 보여 줄 내용',
          style: Theme.of(context).textTheme.titleLarge,
        ),
        SharePicker(
          scope: scope,
          timing: timing,
          onScope: (v) => setState(() => scope = v!),
          onTiming: (v) => setState(() => timing = v!),
        ),
        PrimaryButton(
          '바꾸기',
          onPressed: () => Navigator.pop(context, (scope, timing)),
        ),
      ], 16),
    ),
  );
}
