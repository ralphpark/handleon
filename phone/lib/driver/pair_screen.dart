import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:mobile_scanner/mobile_scanner.dart';

import '../backend.dart';
import '../ui.dart';
import 'pair_code.dart';

class PairScreen extends StatefulWidget {
  const PairScreen({
    super.key,
    required this.backend,
    this.cameraEnabled = true,
  });
  final Backend backend;
  final bool cameraEnabled;
  @override
  State<PairScreen> createState() => _PairScreenState();
}

class _PairScreenState extends State<PairScreen> {
  final input = TextEditingController();
  bool busy = false, success = false, cameraFailed = false;
  String? error;
  String? lastScan;
  @override
  void dispose() {
    input.dispose();
    super.dispose();
  }

  Future<void> claim(String raw) async {
    if (busy || success) return;
    final code = parsePairCode(raw);
    if (code == null) {
      setState(() => error = '코드가 맞지 않거나 시간이 지났어요');
      return;
    }
    setState(() {
      busy = true;
      error = null;
    });
    try {
      await widget.backend.claimPairing(code);
      if (mounted) setState(() => success = true);
    } catch (e) {
      if (mounted) setState(() => error = friendlyError(e));
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  void unavailable() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted && !cameraFailed) setState(() => cameraFailed = true);
    });
  }

  @override
  Widget build(BuildContext context) {
    final camera =
        widget.cameraEnabled &&
        !cameraFailed &&
        !success &&
        !kIsWeb &&
        (defaultTargetPlatform == TargetPlatform.android ||
            defaultTargetPlatform == TargetPlatform.iOS);
    return PageFrame(
      title: '차와 연결하기',
      backLabel: '내 운전 요약',
      subtitle:
          '차 화면의 핸들온 › 설정 › 휴대폰 연결을 누르면 QR 과 6자리 코드가 나와요. 코드는 10분 동안 쓸 수 있어요.',
      children: [
        if (camera)
          ClipRRect(
            borderRadius: BorderRadius.circular(28),
            child: SizedBox(
              height: 280,
              child: Stack(
                fit: StackFit.expand,
                children: [
                  MobileScanner(
                    onDetect: (capture) {
                      if (busy || success) return;
                      for (final barcode in capture.barcodes) {
                        final raw = barcode.rawValue;
                        if (raw != null) {
                          final code = parsePairCode(raw);
                          if (code != null && code != lastScan) {
                            lastScan = code;
                            input.text = code;
                            claim(code);
                            break;
                          }
                        }
                      }
                    },
                    errorBuilder: (context, error) {
                      unavailable();
                      return const SizedBox.shrink();
                    },
                  ),
                  IgnorePointer(
                    child: Center(
                      child: Container(
                        width: 184,
                        height: 184,
                        decoration: BoxDecoration(
                          border: Border.all(
                            color: colors(context).brand,
                            width: 3,
                          ),
                          borderRadius: BorderRadius.circular(16),
                        ),
                      ),
                    ),
                  ),
                  const Positioned(
                    bottom: 16,
                    left: 0,
                    right: 0,
                    child: Text(
                      'QR 을 네모 안에 맞춰 주세요',
                      textAlign: TextAlign.center,
                    ),
                  ),
                ],
              ),
            ),
          ),
        const Muted('카메라가 없으면 코드를 직접 넣어 주세요'),
        LabeledField(
          label: '6자리 연결 코드',
          controller: input,
          code: true,
          enabled: !busy && !success,
        ),
        if (error != null) Notice(error!),
        PrimaryButton(
          busy ? '연결하는 중이에요' : '연결하기',
          onPressed: busy || success ? null : () => claim(input.text),
        ),
        if (success) const Notice('차와 연결됐어요', success: true),
      ],
    );
  }
}
