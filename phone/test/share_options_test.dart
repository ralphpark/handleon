import 'package:flutter_test/flutter_test.dart';
import 'package:phone/share_options.dart';

void main() {
  test('drive done choices have no implicit default', () {
    expect(const ShareOptions().driveDone, isNull);
    expect(const ShareOptions().weeklyChange, false);
    expect(const ShareOptions().habits, false);
    expect(DriveDoneMode.values.map((v) => v.label), [
      '운전 끝날 때마다',
      '하루 한 번 저녁에 모아서',
    ]);
  });
  test('범위는 정해진 문구와 서버 값으로 매핑한다', () {
    expect(ShareScope.values.map((value) => value.label), [
      '단계만',
      '추이 그래프까지',
      '자세한 지표와 편지까지',
    ]);
    expect(ShareScope.values.map((value) => value.wire), [
      'stage',
      'trend',
      'detail',
    ]);
    expect(ShareScope.fromWire('stage'), ShareScope.stage);
    expect(ShareScope.fromWire('trend'), ShareScope.trend);
    expect(ShareScope.fromWire('detail'), ShareScope.detail);
  });
  test('시점은 정해진 문구와 서버 일수로 매핑한다', () {
    expect(ShareTiming.values.map((value) => value.label), [
      '바로 공유',
      '3일 뒤 공유',
      '7일 뒤 공유',
      '14일 뒤 공유',
    ]);
    expect(ShareTiming.values.map((value) => value.wire), [0, 3, 7, 14]);
    expect(ShareTiming.fromWire(0), ShareTiming.now);
    expect(ShareTiming.fromWire(3), ShareTiming.d3);
    expect(ShareTiming.fromWire(7), ShareTiming.d7);
    expect(ShareTiming.fromWire(14), ShareTiming.d14);
  });
  test('모르는 범위는 기본 선택 없이 null이다', () {
    for (final value in [null, '', 'STAGE', 'unknown', 0]) {
      expect(ShareScope.fromWire(value), isNull);
    }
  });
  test('모르는 시점과 문자열 숫자는 기본 선택 없이 null이다', () {
    for (final value in [null, -1, 1, 30, '7', 7.0]) {
      expect(ShareTiming.fromWire(value), isNull);
    }
  });
}
