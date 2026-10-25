import 'package:flutter/material.dart';

import '../backend.dart';
import '../ui.dart';

class AcceptScreen extends StatefulWidget {
  const AcceptScreen({super.key, required this.backend});
  final Backend backend;
  @override
  State<AcceptScreen> createState() => _AcceptScreenState();
}

class _AcceptScreenState extends State<AcceptScreen> {
  final input = TextEditingController();
  InvitePreview? preview;
  String? checkedCode, error;
  bool busy = false;
  @override
  void dispose() {
    input.dispose();
    super.dispose();
  }

  String? normalize() {
    final c = input.text.replaceAll(RegExp(r'[\s-]'), '');
    return RegExp(r'^[a-zA-Z0-9]{8}$').hasMatch(c) ? c.toUpperCase() : null;
  }

  Future<void> check() async {
    final code = normalize();
    if (code == null) {
      setState(() => error = '코드가 맞지 않거나 시간이 지났어요');
      return;
    }
    setState(() {
      busy = true;
      error = null;
      preview = null;
    });
    try {
      final result = await widget.backend.previewInvite(code);
      if (mounted) {
        setState(() {
          preview = result;
          checkedCode = code;
        });
      }
    } catch (e) {
      if (mounted) setState(() => error = friendlyError(e));
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  Future<void> accept() async {
    setState(() {
      busy = true;
      error = null;
    });
    try {
      await widget.backend.acceptInvite(checkedCode!);
      if (mounted) {
        if (Navigator.of(context).canPop()) {
          Navigator.pop(context, true);
        } else {
          Navigator.of(context)
              .pushNamedAndRemoveUntil('/family', (_) => false);
        }
      }
    } catch (e) {
      if (mounted) setState(() => error = friendlyError(e));
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  @override
  Widget build(BuildContext context) => PageFrame(
    title: '초대 받기',
    backLabel: '가족 소식',
    subtitle: '가족에게 받은 8자리 초대 코드를 넣어 주세요.',
    children: [
      LabeledField(
        label: '초대 코드',
        controller: input,
        code: true,
        enabled: !busy,
        onChanged: (_) => setState(() {
          preview = null;
          checkedCode = null;
          error = null;
        }),
      ),
      if (error != null) Notice(error!),
      if (preview == null)
        PrimaryButton(
          busy ? '확인하는 중이에요' : '초대 확인하기',
          onPressed: busy ? null : check,
        ),
      if (preview != null) ...[
        Panel(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: spaced([
              Text(
                '${preview!.driverName} 님이 초대했어요',
                style: Theme.of(context).textTheme.titleLarge,
              ),
              const Muted('보이는 것'),
              SectionTitle(preview!.scope.label),
              const Muted('보이는 때'),
              SectionTitle(
                preview!.timing.wire == 0
                    ? '바로'
                    : '요약이 나오고 ${preview!.timing.wire}일 뒤',
              ),
              const Divider(height: 1),
              Text(
                preview!.sharePattern ? '운전 패턴도 함께 보여 줘요' : '운전 패턴은 보여 주지 않아요',
              ),
              Muted(
                '위치는 공유되지 않아요. 보여 줄 범위와 시점은 ${preview!.driverName} 님이 정하고 언제든 바꿀 수 있어요.',
              ),
            ], 12),
          ),
        ),
        PrimaryButton(
          busy ? '받는 중이에요' : '초대 받기',
          onPressed: busy ? null : accept,
        ),
      ],
    ],
  );
}
