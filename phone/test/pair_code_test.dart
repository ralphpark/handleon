import 'package:flutter_test/flutter_test.dart';
import 'package:phone/driver/pair_code.dart';

void main() {
  test('연결 링크에서 코드를 읽는다', () {
    expect(parsePairCode('handleon://pair?code=AB12CD'), 'AB12CD');
  });
  test('직접 입력한 소문자를 정규화한다', () {
    expect(parsePairCode(' ab12cd '), 'AB12CD');
  });
  test('여섯 자리가 아닌 코드는 거부한다', () {
    for (final raw in ['', 'AB12', 'AB12CDE']) {
      expect(parsePairCode(raw), isNull);
    }
  });
  test('소문자와 공백 및 하이픈이 섞인 코드를 정규화한다', () {
    expect(parsePairCode(' aB-1 2-cD\t'), 'AB12CD');
    expect(parsePairCode('handleon://pair?code=ab-12%20cd'), 'AB12CD');
  });
  test('다른 스킴의 URL은 거부한다', () {
    for (final raw in [
      'https://pair?code=AB12CD',
      'other://pair?code=AB12CD',
    ]) {
      expect(parsePairCode(raw), isNull);
    }
  });
  test('잘못된 연결 링크와 모호한 코드는 거부한다', () {
    for (final raw in [
      'handleon://invite?code=AB12CD',
      'handleon://pair/path?code=AB12CD',
      'handleon://pair',
      'handleon://user@pair?code=AB12CD',
      'handleon://pair:123?code=AB12CD',
      'handleon://pair?code=AB12CD&code=EF34GH',
      'handleon://pair?code=%ZZ',
      'AB!2CD',
      '가나12CD',
      'ab12cſ',
    ]) {
      expect(parsePairCode(raw), isNull, reason: raw);
    }
  });
}
