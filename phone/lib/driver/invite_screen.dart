import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../backend.dart';
import '../share_options.dart';
import '../share_picker.dart';
import '../ui.dart';

class InviteScreen extends StatefulWidget {
  const InviteScreen({super.key, required this.backend});
  final Backend backend;
  @override
  State<InviteScreen> createState() => _InviteScreenState();
}

class _InviteScreenState extends State<InviteScreen> {
  ShareScope? scope;
  ShareTiming? timing;
  String? code, error;
  bool busy = false;
  Future<void> create() async {
    setState(() {
      busy = true;
      error = null;
    });
    try {
      final result = await widget.backend.createInvite(scope!, timing!);
      if (mounted) setState(() => code = result);
    } catch (e) {
      if (mounted) setState(() => error = friendlyError(e));
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  Future<void> copy() async {
    try {
      await Clipboard.setData(
        ClipboardData(
          text:
              '핸들온에서 내 운전 요약을 함께 살펴봐 주세요. 초대 코드: $code (7일 동안 쓸 수 있어요). ${scope!.label} · ${timing!.label}. 앱에서 가족으로 가입하고 초대 받기에 코드를 넣어 주세요.',
        ),
      );
      if (mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(const SnackBar(content: Text('문장을 복사했어요')));
      }
    } catch (e) {
      if (mounted) setState(() => error = friendlyError(e));
    }
  }

  @override
  Widget build(BuildContext context) => PageFrame(
    title: '가족 초대하기',
    backLabel: '내 운전 요약',
    subtitle: '무엇을, 언제 보여 줄지 직접 골라 주세요. 나중에 언제든 바꾸거나 멈출 수 있어요.',
    children: [
      SharePicker(
        scope: scope,
        timing: timing,
        enabled: !busy,
        onScope: (v) => setState(() {
          scope = v;
          code = null;
        }),
        onTiming: (v) => setState(() {
          timing = v;
          code = null;
        }),
      ),
      if (error != null) Notice(error!),
      PrimaryButton(
        busy ? '만드는 중이에요' : '초대 만들기',
        buttonKey: const Key('createInvite'),
        onPressed: scope == null || timing == null || busy ? null : create,
      ),
      if (scope == null || timing == null)
        const Muted('보여 줄 것과 시점을 둘 다 골라 주세요'),
      if (code != null)
        Panel(
          tint: colors(context).brand,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: spaced([
              const Muted('초대 코드 · 7일 동안 쓸 수 있어요'),
              SelectableText(
                code!,
                style: Theme.of(context).textTheme.headlineLarge!
                    .copyWith(letterSpacing: 4),
              ),
              Text('${scope!.label} · ${timing!.label}'),
              SecondaryButton('가족에게 보낼 문장 복사', onPressed: copy),
            ], 8),
          ),
        ),
    ],
  );
}
