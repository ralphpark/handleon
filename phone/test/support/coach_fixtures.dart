import 'package:phone/backend.dart';

Map<String, dynamic> coachJson({String source = 'ai'}) => {
  'advice': <String, dynamic>{
    'openers': [
      '요즘 운전 다니시는 건 어떠세요?',
      '이번 주말에 같이 드라이브 갈까요?',
      '운전하시다 불편한 점은 없으셨어요?',
    ],
    'avoid': ['이제 운전 그만하세요', '왜 그렇게 운전하셨어요'],
    'topic': '요즘 자주 가시는 곳 이야기',
  },
  'source': source,
};

CoachAdvice coachAdvice({bool fallback = false}) =>
    CoachAdvice.fromJson(coachJson(source: fallback ? 'fallback' : 'ai'));
