import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:phone/backend.dart';
import 'package:phone/driver/clinic_pdf.dart';

import 'support/clinic_fixtures.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  test('pdf contains the fixed disclaimer', () {
    final paragraphs = clinicParagraphs(
      clinicSummary(fallback: true),
      driverName: '아버지',
      made: DateTime(2026, 10, 27),
    );
    expect(paragraphs.first, '운전으로 본 최근 생활 변화');
    expect(paragraphs[1], '아버지 · 2026년 10월 27일');
    expect(paragraphs[2], '최근 운전 기록을 보며 평소와 달라진 점과 편안했던 점을 함께 이야기해 보세요.');
    expect(paragraphs[3], '의사·상담사에게 물어볼 질문');
    expect(paragraphs.sublist(4, 7), [
      '최근 운전하면서 느낀 변화를 어떻게 살펴보면 좋을까요?',
      '운전 중 편안했던 점을 어떻게 이어 갈까요?',
      '쉬어 가는 시간을 어떻게 정하면 좋을까요?',
    ]);
    expect(paragraphs.last, '이 자료는 진단이 아니에요. 운전 기록의 변화를 쉬운 말로 정리한 참고 자료예요.');
    expect(paragraphs.join(), isNot(contains('기본 요약')));
  });

  test(
    'clinic PDF embeds Korean font on a single A4 page at maximum lengths',
    () async {
      for (final (name, summary, driverName) in [
        ('clinic-standard', clinicSummary(), '아버지'),
        (
          'clinic-maximum',
          ClinicSummary(
            summary: '가' * 600,
            questions: List.filled(3, '나' * 80),
            fromFallback: true,
          ),
          '다' * 80,
        ),
        (
          'clinic-newlines',
          ClinicSummary(
            summary: '함께\n' * 200,
            questions: List.filled(3, '말\n' * 40),
            fromFallback: false,
          ),
          '아버지\n',
        ),
      ]) {
        final bytes = await buildClinicPdf(
          summary,
          driverName: driverName,
          made: DateTime(2026, 10, 27),
        );
        final pdf = latin1.decode(bytes);
        expect(pdf, startsWith('%PDF-'));
        expect(RegExp(r'/Type\s*/Page\b').allMatches(pdf), hasLength(1));
        expect(pdf, contains('/FontFile2'));
        expect(
          pdf,
          matches(
            RegExp(
              r'/MediaBox\s*\[\s*0(?:\.0+)?\s+0(?:\.0+)?\s+595\.\d+\s+841\.\d+\s*\]',
            ),
          ),
        );
        const output = String.fromEnvironment('P5_PDF_OUTPUT');
        if (output.isNotEmpty) {
          await Directory(output).create(recursive: true);
          await File('$output/$name.pdf').writeAsBytes(bytes);
        }
      }
    },
  );
}
