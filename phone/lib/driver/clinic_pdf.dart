import 'package:flutter/services.dart';
import 'package:pdf/pdf.dart';
import 'package:pdf/widgets.dart' as pw;

import '../backend.dart';
import '../family/feed_text.dart';

const clinicDisclaimer = '이 자료는 진단이 아니에요. 운전 기록의 변화를 쉬운 말로 정리한 참고 자료예요.';

String _paragraph(String text) => text.replaceAll(RegExp(r'\s+'), ' ').trim();

// Shared by the PDF and its accessible preview. No raw wire fields enter here.
List<String> clinicParagraphs(
  ClinicSummary summary, {
  required String driverName,
  required DateTime made,
}) => [
  '운전으로 본 최근 생활 변화',
  '${String.fromCharCodes(_paragraph(safeFamilyName(driverName)).runes.take(80))} · ${feedDate(made)}',
  _paragraph(summary.summary),
  '의사·상담사에게 물어볼 질문',
  ...summary.questions.map(_paragraph),
  clinicDisclaimer,
];

Future<Uint8List> buildClinicPdf(
  ClinicSummary summary, {
  required String driverName,
  required DateTime made,
}) async {
  final paragraphs = clinicParagraphs(
    summary,
    driverName: driverName,
    made: made,
  );
  final font = pw.Font.ttf(
    await rootBundle.load('assets/fonts/NotoSansKR.ttf'),
  );
  final document = pw.Document(
    theme: pw.ThemeData.withFont(base: font, bold: font),
  );
  document.addPage(
    pw.Page(
      pageFormat: PdfPageFormat.a4,
      margin: const pw.EdgeInsets.all(40),
      build: (_) => pw.Column(
        crossAxisAlignment: pw.CrossAxisAlignment.stretch,
        children: [
          pw.Text(paragraphs[0], style: const pw.TextStyle(fontSize: 20)),
          pw.SizedBox(height: 12),
          pw.Text(paragraphs[1], style: const pw.TextStyle(fontSize: 10)),
          pw.SizedBox(height: 24),
          // Reserve the footer first. Long words and mixed scripts can have
          // different line heights even when their character counts are equal.
          pw.Expanded(
            child: pw.FittedBox(
              fit: pw.BoxFit.scaleDown,
              alignment: pw.Alignment.topLeft,
              child: pw.SizedBox(
                width: PdfPageFormat.a4.width - 80,
                child: pw.Column(
                  mainAxisSize: pw.MainAxisSize.min,
                  crossAxisAlignment: pw.CrossAxisAlignment.stretch,
                  children: [
                    pw.Text(
                      paragraphs[2],
                      style: const pw.TextStyle(fontSize: 12, lineSpacing: 2),
                    ),
                    pw.SizedBox(height: 24),
                    pw.Text(
                      paragraphs[3],
                      style: const pw.TextStyle(fontSize: 14),
                    ),
                    pw.SizedBox(height: 14),
                    for (var i = 0; i < 3; i++)
                      pw.Padding(
                        padding: const pw.EdgeInsets.only(bottom: 14),
                        child: pw.Row(
                          crossAxisAlignment: pw.CrossAxisAlignment.start,
                          children: [
                            pw.SizedBox(
                              width: 20,
                              child: pw.Text(
                                '${i + 1}.',
                                style: const pw.TextStyle(fontSize: 12),
                              ),
                            ),
                            pw.Expanded(
                              child: pw.Text(
                                paragraphs[i + 4],
                                style: const pw.TextStyle(
                                  fontSize: 12,
                                  lineSpacing: 2,
                                ),
                              ),
                            ),
                          ],
                        ),
                      ),
                  ],
                ),
              ),
            ),
          ),
          pw.Divider(color: PdfColors.grey400),
          pw.SizedBox(height: 6),
          pw.Text(
            paragraphs.last,
            style: const pw.TextStyle(fontSize: 10, color: PdfColors.grey800),
          ),
        ],
      ),
    ),
  );
  return document.save();
}
