import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../backend.dart';
import '../ui.dart';

const quickNotes = ['오늘도 안전 운전하세요', '주말에 같이 식사해요?', '늘 응원해요'];

String _noteError(NoteError error) => switch (error) {
  NoteError.tooManyUnseen => '아직 읽지 않은 한마디가 3개 있어요. 읽고 나면 더 보낼 수 있어요.',
  NoteError.shareEnded => '공유가 끝나 보낼 수 없어요.',
  NoteError.invalid => '1~120자로 적어 주세요.',
  NoteError.other => '보내지 못했어요. 잠시 후 다시 시도해 주세요.',
};

String _sentTime(DateTime time) {
  final local = time.toLocal();
  String two(int value) => value.toString().padLeft(2, '0');
  return '${local.month}월 ${local.day}일 ${two(local.hour)}:${two(local.minute)}';
}

class NoteScreen extends StatefulWidget {
  const NoteScreen({
    super.key,
    required this.backend,
    required this.driverId,
    required this.driverName,
  });
  final Backend backend;
  final String driverId, driverName;
  @override
  State<NoteScreen> createState() => _NoteScreenState();
}

class _NoteScreenState extends State<NoteScreen> {
  final input = TextEditingController();
  List<SentNote> notes = [];
  String? error, listError;
  bool busy = false, loading = true, sent = false;
  int request = 0;

  @override
  void initState() {
    super.initState();
    load();
  }

  @override
  void dispose() {
    input.dispose();
    super.dispose();
  }

  Future<void> load() async {
    final current = ++request;
    setState(() {
      loading = true;
      notes = [];
      listError = null;
    });
    try {
      final result = await widget.backend.sentNotes();
      if (mounted && current == request) {
        setState(
          () => notes =
              result.where((n) => n.driverId == widget.driverId).toList()
                ..sort((a, b) {
                  final order = b.createdAt.compareTo(a.createdAt);
                  return order == 0 ? b.id.compareTo(a.id) : order;
                }),
        );
      }
    } catch (e) {
      if (mounted && current == request) {
        setState(() => listError = friendlyError(e));
      }
    } finally {
      if (mounted && current == request) setState(() => loading = false);
    }
  }

  Future<void> send() async {
    if (busy) return;
    final body = input.text.trim();
    if (body.isEmpty || body.runes.length > 120) {
      setState(() => error = _noteError(NoteError.invalid));
      return;
    }
    setState(() {
      busy = true;
      error = null;
      sent = false;
    });
    try {
      await widget.backend.sendNote(widget.driverId, body);
      if (!mounted) return;
      input.clear();
      setState(() => sent = true);
      await load();
    } catch (e) {
      if (mounted) setState(() => error = _noteError(NoteError.fromError(e)));
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  @override
  Widget build(BuildContext context) => PageFrame(
    title: '${widget.driverName} 님께 한마디',
    backLabel: '가족 소식',
    onRefresh: load,
    children: [
      TextField(
        key: const Key('noteBody'),
        controller: input,
        enabled: !busy,
        minLines: 3,
        maxLines: 5,
        maxLength: 120,
        style: bodyStyle(context),
        inputFormatters: [
          TextInputFormatter.withFunction((oldValue, newValue) {
            // 서버 length(text)와 같은 유니코드 코드 포인트 수. 한글 조합 중에는 자르지 않는다.
            if (!newValue.composing.isCollapsed ||
                newValue.text.runes.length <= 120) {
              return newValue;
            }
            final text = String.fromCharCodes(newValue.text.runes.take(120));
            return TextEditingValue(
              text: text,
              selection: TextSelection.collapsed(offset: text.length),
            );
          }),
        ],
        buildCounter:
            (
              context, {
              required currentLength,
              required isFocused,
              required maxLength,
            }) => Text(
              '${input.text.runes.length}/120',
              style: bodyStyle(context, color: colors(context).inkMuted),
            ),
        decoration: InputDecoration(
          labelText: '보낼 한마디',
          labelStyle: bodyStyle(context),
          alignLabelWithHint: true,
          filled: true,
          border: OutlineInputBorder(borderRadius: BorderRadius.circular(16)),
        ),
        onChanged: (_) => setState(() {
          error = null;
          sent = false;
        }),
      ),
      Wrap(
        spacing: 8,
        runSpacing: 8,
        children: [
          for (final text in quickNotes)
            ConstrainedBox(
              constraints: const BoxConstraints(minHeight: 48),
              child: ActionChip(
                label: Text(text, style: bodyStyle(context)),
                materialTapTargetSize: MaterialTapTargetSize.padded,
                onPressed: busy
                    ? null
                    : () => setState(() {
                        input.value = TextEditingValue(
                          text: text,
                          selection: TextSelection.collapsed(
                            offset: text.length,
                          ),
                        );
                        error = null;
                        sent = false;
                      }),
              ),
            ),
        ],
      ),
      const Muted('한마디는 차가 멈춘 뒤에만 차 화면에 보여요. 읽지 않은 한마디는 3개까지 보낼 수 있어요.'),
      if (error != null) Notice(error!),
      PrimaryButton(busy ? '보내는 중이에요' : '보내기', onPressed: busy ? null : send),
      if (sent) const Notice('한마디를 보냈어요.', success: true),
      const SectionTitle('보낸 한마디'),
      if (loading) const Center(child: CircularProgressIndicator()),
      if (listError != null) ...[
        Notice(listError!),
        SecondaryButton('다시 불러오기', onPressed: load),
      ],
      if (!loading && listError == null && notes.isEmpty)
        const Muted('아직 보낸 한마디가 없어요.'),
      for (final note in notes)
        Panel(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: spaced([
              Text(note.body),
              Muted(_sentTime(note.createdAt)),
              Text(note.seenAt == null ? '아직 안 읽음' : '읽음'),
            ], 8),
          ),
        ),
    ],
  );
}
