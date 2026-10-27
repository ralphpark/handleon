import 'package:flutter/material.dart';

import '../backend.dart';
import '../share_options.dart';
import '../share_picker.dart';
import '../ui.dart';
import 'family_preview_screen.dart';

class SharingScreen extends StatefulWidget {
  const SharingScreen({super.key, required this.backend});
  final Backend backend;
  @override
  State<SharingScreen> createState() => _SharingScreenState();
}

class _SharingScreenState extends State<SharingScreen> {
  List<Share>? shares;
  SharingState? sharing;
  Map<String, ShareOptions> options = {};
  int request = 0;
  String? error;
  bool busy = false;
  @override
  void initState() {
    super.initState();
    load();
  }

  Future<void> load() async {
    final current = ++request;
    setState(() {
      shares = null;
      sharing = null;
      options = {};
      error = null;
    });
    try {
      final result = await Future.wait<Object>([
        widget.backend.myShares(),
        widget.backend.sharingState(),
      ]);
      final loadedShares = result[0] as List<Share>;
      // get_sharing_state recipients have names but no family IDs. Read the
      // authoritative options by get_my_shares ID, never by name or list order.
      final views = await Future.wait([
        for (final share in loadedShares)
          widget.backend.previewFamilyView(share.familyId),
      ]);
      if (mounted && current == request) {
        setState(() {
          shares = loadedShares;
          sharing = result[1] as SharingState;
          options = {
            for (var i = 0; i < loadedShares.length; i++)
              loadedShares[i].familyId: views[i].options,
          };
          error = null;
        });
      }
    } catch (e) {
      if (mounted && current == request) {
        setState(() => error = friendlyError(e));
      }
    }
  }

  Future<void> chooseDriveDone(Share share, bool enabled) async {
    DriveDoneMode? mode;
    if (enabled) {
      final current = request;
      setState(() => busy = true);
      mode = await showDialog<DriveDoneMode>(
        context: context,
        builder: (context) => AlertDialog(
          title: const Text('운전 마침 소식'),
          scrollable: true,
          content: RadioGroup<DriveDoneMode>(
            groupValue: null,
            onChanged: (choice) => Navigator.pop(context, choice),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                for (final choice in DriveDoneMode.values)
                  RadioListTile<DriveDoneMode>(
                    value: choice,
                    title: Text(choice.label),
                  ),
              ],
            ),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(context),
              child: const Text('취소'),
            ),
          ],
        ),
      );
      if (!mounted) return;
      setState(() => busy = false);
      if (mode == null || current != request) return;
    }
    await changeOptions(
      share,
      (latest) => ShareOptions(
        driveDone: mode,
        weeklyChange: latest.weeklyChange,
        habits: latest.habits,
      ),
    );
  }

  Future<void> changeOptions(
    Share share,
    ShareOptions Function(ShareOptions) update,
  ) => change(() async {
    final user = widget.backend.userId;
    // The car or another phone may have changed consent while this screen was
    // open. Merge just the explicit choice into the latest recipient options.
    final latest = await widget.backend.previewFamilyView(share.familyId);
    if (!mounted || widget.backend.userId != user) return;
    await widget.backend.setShareOptions(
      share.familyId,
      update(latest.options),
    );
  });

  Widget optionControls(Share share) {
    final choice = options[share.familyId]!;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        SwitchListTile(
          key: Key('driveDone:${share.familyId}'),
          contentPadding: EdgeInsets.zero,
          title: const Text('운전 마침 소식'),
          subtitle: const Muted('가족 공유 유예 기간과 상관없이 바로 가요'),
          value: choice.driveDone != null,
          onChanged: busy ? null : (enabled) => chooseDriveDone(share, enabled),
        ),
        if (choice.driveDone != null) Muted(choice.driveDone!.label),
        SwitchListTile(
          key: Key('weeklyChange:${share.familyId}'),
          contentPadding: EdgeInsets.zero,
          title: const Text('최근 몇 주 변화'),
          subtitle: const Muted('몇 주 사이에 갑자기 달라지면 알려요'),
          value: choice.weeklyChange,
          onChanged: busy
              ? null
              : (enabled) => changeOptions(
                  share,
                  (latest) => ShareOptions(
                    driveDone: latest.driveDone,
                    weeklyChange: enabled,
                    habits: latest.habits,
                  ),
                ),
        ),
        SwitchListTile(
          key: Key('habits:${share.familyId}'),
          contentPadding: EdgeInsets.zero,
          title: const Text('운전 습관 요약'),
          subtitle: const Muted('매주 방향지시등·급제동 같은 운전 습관 합계를 보내요'),
          value: choice.habits,
          onChanged: busy
              ? null
              : (enabled) => changeOptions(
                  share,
                  (latest) => ShareOptions(
                    driveDone: latest.driveDone,
                    weeklyChange: latest.weeklyChange,
                    habits: enabled,
                  ),
                ),
        ),
      ],
    );
  }

  Future<void> edit(Share share) async {
    final selection =
        await showModalBottomSheet<(ShareScope, ShareTiming, bool)>(
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
        selection.$3,
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
              '${share.familyName} 님과의 공유를 끊을까요?',
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
                    '공유 끊기',
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
      if (mounted) await load();
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
      if (sharing != null)
        Panel(
          padding: 0,
          child: SwitchListTile(
            key: const Key('sharingPaused'),
            title: Text(
              '가족 공유 잠시 멈추기',
              style: bodyStyle(context, weight: FontWeight.w600),
            ),
            subtitle: const Muted(
              '멈춘 동안 가족에게는 아무것도 보이지 않고 알림도 가지 않아요. 차 화면에서도 멈출 수 있어요.',
            ),
            value: sharing!.paused,
            onChanged: busy
                ? null
                : (value) =>
                      change(() => widget.backend.setSharingPaused(value)),
          ),
        ),
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
              Muted(
                share.sharePattern ? '운전 패턴도 함께 보여 줘요' : '운전 패턴은 보여 주지 않아요',
              ),
              SecondaryButton(
                '가족이 보는 화면',
                onPressed: busy
                    ? null
                    : () => Navigator.of(context).push(
                        MaterialPageRoute<void>(
                          builder: (_) => FamilyPreviewScreen(
                            backend: widget.backend,
                            familyId: share.familyId,
                            familyName: share.familyName,
                          ),
                        ),
                      ),
              ),
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
                      '공유 끊기',
                      onPressed: busy ? null : () => revoke(share),
                    ),
                  ),
                ],
              ),
              optionControls(share),
            ], 12),
          ),
        ),
      PrimaryButton(
        '가족 더 초대하기',
        onPressed: () => Navigator.of(context).pushNamed('/driver/invite'),
      ),
      const Muted('핸들온은 운전 기록을 보험사·면허 기관·광고·데이터 판매에 넘기지 않아요.'),
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
  late bool sharePattern = widget.share.sharePattern;
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
          sharePattern: sharePattern,
          onPattern: (v) => setState(() => sharePattern = v!),
          onScope: (v) => setState(() => scope = v!),
          onTiming: (v) => setState(() => timing = v!),
        ),
        PrimaryButton(
          '저장',
          onPressed: () =>
              Navigator.pop(context, (scope, timing, sharePattern)),
        ),
      ], 16),
    ),
  );
}
