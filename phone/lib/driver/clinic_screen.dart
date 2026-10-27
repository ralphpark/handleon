import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:pdf/pdf.dart';
import 'package:printing/printing.dart';

import '../backend.dart';
import '../ui.dart';
import 'clinic_pdf.dart';

class ClinicScreen extends StatefulWidget {
  const ClinicScreen({super.key, required this.backend});
  final Backend backend;

  @override
  State<ClinicScreen> createState() => _ClinicScreenState();
}

class _ClinicScreenState extends State<ClinicScreen> {
  bool generating = false;
  bool sharing = false;
  String? error;
  Uint8List? bytes;
  ClinicSummary? summary;
  DateTime? made;
  String? owner;
  String driverName = '';
  int previewVersion = 0;

  bool get isDriver =>
      widget.backend.userId != null && widget.backend.role == Role.driver;
  bool sameDriver(String user) => isDriver && widget.backend.userId == user;

  Future<void> generate() async {
    if (generating || !isDriver) return;
    final user = widget.backend.userId!;
    final name = widget.backend.displayName ?? '운전자';
    setState(() {
      generating = true;
      error = null;
    });
    try {
      final result = await widget.backend.clinicSummary();
      if (!mounted || !sameDriver(user)) return;
      final date = DateTime.now();
      final pdf = await buildClinicPdf(result, driverName: name, made: date);
      if (!mounted || !sameDriver(user)) return;
      setState(() {
        summary = result;
        bytes = pdf;
        made = date;
        owner = user;
        driverName = name;
      });
    } catch (_) {
      if (mounted && sameDriver(user)) {
        setState(() => error = '한 장을 만들지 못했어요. 잠시 뒤 다시 시도해 주세요');
      }
    } finally {
      if (mounted) setState(() => generating = false);
    }
  }

  Future<void> share(BuildContext buttonContext) async {
    if (sharing || bytes == null || owner == null || !sameDriver(owner!)) {
      return;
    }
    final user = owner!;
    final date = made!;
    final filename =
        'handleon-clinic-${date.year.toString().padLeft(4, '0')}-${date.month.toString().padLeft(2, '0')}-${date.day.toString().padLeft(2, '0')}.pdf';
    final box = buttonContext.findRenderObject() as RenderBox?;
    final bounds = box == null
        ? null
        : box.localToGlobal(Offset.zero) & box.size;
    setState(() {
      sharing = true;
      error = null;
    });
    try {
      await Printing.sharePdf(
        bytes: bytes!,
        filename: filename,
        bounds: bounds,
      );
    } catch (_) {
      if (mounted && sameDriver(user)) {
        setState(() => error = '공유 화면을 열지 못했어요. 다시 시도해 주세요');
      }
    } finally {
      if (mounted) setState(() => sharing = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final pdf = bytes;
    return PageFrame(
      title: '진료·상담용 한 장',
      backLabel: '내 운전 요약',
      children: [
        if (!isDriver)
          const Notice('운전자 본인 계정에서 만들 수 있어요')
        else if (pdf == null || owner != widget.backend.userId) ...[
          const Text('최근 운전 기록의 변화와 진료·상담 때 물어볼 질문을 한 장으로 정리해요.'),
          if (generating) const Center(child: CircularProgressIndicator()),
          if (error != null) Notice(error!),
          PrimaryButton(
            generating
                ? '한 장을 만들고 있어요'
                : error != null
                ? '다시 시도'
                : '한 장 만들기',
            onPressed: generating ? null : generate,
          ),
        ] else ...[
          if (summary!.fromFallback) const Muted('기본 요약'),
          Semantics(
            label: clinicParagraphs(
              summary!,
              driverName: driverName,
              made: made!,
            ).join('\n'),
            child: SizedBox(
              height: (MediaQuery.sizeOf(context).height * .65).clamp(360, 650),
              child: PdfPreview(
                key: ValueKey(previewVersion),
                build: (_) => pdf,
                initialPageFormat: PdfPageFormat.a4,
                canChangePageFormat: false,
                canChangeOrientation: false,
                canDebug: false,
                allowPrinting: false,
                allowSharing: false,
                useActions: false,
                loadingWidget: const Center(child: Text('미리보기를 준비하고 있어요')),
                onError: (_, _) => Center(
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      const Notice('미리보기를 열지 못했어요'),
                      SecondaryButton(
                        '미리보기 다시 열기',
                        onPressed: () => setState(() => previewVersion++),
                      ),
                    ],
                  ),
                ),
              ),
            ),
          ),
          const Muted('내용을 확인한 뒤 저장하거나 공유해 주세요.'),
          if (error != null) Notice(error!),
          Builder(
            builder: (buttonContext) => PrimaryButton(
              sharing ? '공유 화면을 열고 있어요' : '저장·공유',
              onPressed: sharing ? null : () => share(buttonContext),
            ),
          ),
        ],
      ],
    );
  }
}
