import 'package:phone/backend.dart';

Map<String, dynamic> clinicJson({String source = 'ai'}) => {
  'summary': '최근 운전 기록을 보며 평소와 달라진 점과 편안했던 점을 함께 이야기해 보세요.',
  'questions': [
    '최근 운전하면서 느낀 변화를 어떻게 살펴보면 좋을까요?',
    '운전 중 편안했던 점을 어떻게 이어 갈까요?',
    '쉬어 가는 시간을 어떻게 정하면 좋을까요?',
  ],
  'source': source,
};

ClinicSummary clinicSummary({bool fallback = false}) =>
    ClinicSummary.fromJson(clinicJson(source: fallback ? 'fallback' : 'ai'));
