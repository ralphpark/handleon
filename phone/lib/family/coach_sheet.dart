import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../backend.dart';
import '../ui.dart';

String _errorMessage(Object error) => switch (CoachError.fromError(error)) {
  DailyLimit() => '오늘은 여기까지예요. 내일 다시 물어봐 주세요',
  ShareNotFound() => '공유가 끊겨 제안을 만들 수 없어요',
  Unavailable() => '잠시 뒤에 다시 시도해 주세요',
};

class CoachSheet extends StatelessWidget {
  const CoachSheet({super.key, required this.advice});
  final Future<CoachAdvice> advice;

  Future<void> _copy(BuildContext context, String sentence) async {
    String message;
    try {
      await Clipboard.setData(ClipboardData(text: sentence));
      message = '복사했어요';
    } catch (_) {
      message = '복사하지 못했어요. 잠시 뒤에 다시 시도해 주세요';
    }
    if (!context.mounted) return;
    ScaffoldMessenger.of(context)
      ..hideCurrentSnackBar()
      ..showSnackBar(
        SnackBar(content: Text(message), duration: const Duration(seconds: 2)),
      );
  }

  @override
  Widget build(BuildContext context) => ScaffoldMessenger(
    // A sheet-local Scaffold keeps copy feedback above the modal barrier.
    child: Scaffold(
      backgroundColor: colors(context).surfaceRaised,
      body: SafeArea(
        child: FutureBuilder<CoachAdvice>(
          future: advice,
          builder: (context, snapshot) {
            final result = snapshot.data;
            return Padding(
              padding: const EdgeInsets.fromLTRB(20, 8, 20, 16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Row(
                    children: [
                      Expanded(
                        child: Wrap(
                          spacing: 12,
                          crossAxisAlignment: WrapCrossAlignment.center,
                          children: [
                            Text(
                              '대화 코치',
                              style: Theme.of(context).textTheme.titleLarge,
                            ),
                            if (result?.fromFallback ?? false)
                              Text(
                                '기본 제안',
                                style: Theme.of(context).textTheme.bodySmall,
                              ),
                          ],
                        ),
                      ),
                      IconButton(
                        tooltip: '닫기',
                        onPressed: () => Navigator.of(context).pop(),
                        icon: const Icon(Icons.close),
                      ),
                    ],
                  ),
                  const SizedBox(height: 12),
                  Expanded(
                    child: SingleChildScrollView(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.stretch,
                        children: spaced([
                          if (snapshot.hasError)
                            Notice(_errorMessage(snapshot.error!))
                          else if (result == null) ...[
                            const Center(child: CircularProgressIndicator()),
                            Semantics(
                              liveRegion: true,
                              child: const Text(
                                '제안을 준비하고 있어요',
                                textAlign: TextAlign.center,
                              ),
                            ),
                          ] else ...[
                            const SectionTitle('이렇게 말을 꺼내 보세요'),
                            for (final sentence in result.openers)
                              Panel(
                                radius: 16,
                                padding: 16,
                                child: Column(
                                  crossAxisAlignment:
                                      CrossAxisAlignment.stretch,
                                  children: [
                                    Text(sentence),
                                    const SizedBox(height: 8),
                                    Align(
                                      alignment: Alignment.centerRight,
                                      child: TextButton.icon(
                                        onPressed: () =>
                                            _copy(context, sentence),
                                        icon: const Icon(Icons.copy_outlined),
                                        label: const Text('복사'),
                                      ),
                                    ),
                                  ],
                                ),
                              ),
                            const SectionTitle('이런 말은 피하면 좋아요'),
                            for (final sentence in result.avoid) Text(sentence),
                            const SectionTitle('이야깃거리'),
                            Text(result.topic),
                          ],
                          const Muted('AI가 만든 제안이에요. 부모님 마음을 가장 잘 아는 건 가족이에요'),
                        ], 16),
                      ),
                    ),
                  ),
                ],
              ),
            );
          },
        ),
      ),
    ),
  );
}
