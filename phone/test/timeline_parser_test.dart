import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:phone/driver/timeline_parser.dart';

String fixture(String name) => File('test/fixtures/$name').readAsStringSync();

Map<String, dynamic> onDevice() =>
    jsonDecode(fixture('timeline_ondevice.json')) as Map<String, dynamic>;

Map<String, dynamic> takeout() =>
    jsonDecode(fixture('timeline_takeout.json')) as Map<String, dynamic>;

void main() {
  test('기기 내보내기에서 차 이동 세 건만 남긴다', () {
    final trips = TimelineParser.parse(fixture('timeline_ondevice.json'));
    expect(trips, hasLength(3));
    expect(trips.map((trip) => trip.distanceKm), [12.3, 1.6, 0.3]);
    expect(trips.first.start, DateTime.utc(2026, 10, 21, 23, 12));
    expect(trips.first.end, DateTime.utc(2026, 10, 21, 23, 40));
  });

  test('예전 Takeout에서 차 이동 두 건을 읽는다', () {
    final trips = TimelineParser.parse(fixture('timeline_takeout.json'));
    expect(trips, hasLength(2));
    expect(trips.map((trip) => trip.distanceKm), [5.5, 0.8]);
    expect(trips.first.start, DateTime.utc(2026, 10, 22, 0, 12));
    expect(trips.first.end, DateTime.utc(2026, 10, 22, 0, 30));
  });

  test('두 형식의 좌표 네 개를 소수점 셋째 자리로 반올림한다', () {
    final modern = TimelineParser.parse(fixture('timeline_ondevice.json'))
        .first;
    final legacy = TimelineParser.parse(fixture('timeline_takeout.json')).first;
    expect(
      [modern.startLat, modern.startLon, modern.endLat, modern.endLon],
      [37.413, 127.095, 37.395, 127.111],
    );
    expect(
      [legacy.startLat, legacy.startLon, legacy.endLat, legacy.endLon],
      [37.413, 127.095, 37.410, 127.103],
    );
  });

  test('알 수 없는 형식은 FormatException이다', () {
    expect(() => TimelineParser.parse('{"x":1}'), throwsFormatException);
  });

  test('직렬화된 트립의 필드는 공통 계약 A와 같다', () {
    final trip = TimelineParser.parse(fixture('timeline_ondevice.json')).first;
    expect(trip.toJson().keys.toSet(), {
      'start',
      'end',
      'startLat',
      'startLon',
      'endLat',
      'endLon',
      'distanceKm',
    });
    expect(jsonDecode(jsonEncode(trip.toJson())), {
      'start': '2026-10-22T08:12:00.000+09:00',
      'end': '2026-10-22T08:40:00.000+09:00',
      'startLat': 37.413,
      'startLon': 127.095,
      'endLat': 37.395,
      'endLon': 127.111,
      'distanceKm': 12.3,
    });
  });

  test('서울 날짜 경계를 기기 시간대와 무관하게 직렬화한다', () {
    final data = onDevice();
    data['semanticSegments'][0]['startTime'] = '2026-10-21T15:05:00Z';
    data['semanticSegments'][0]['endTime'] = '2026-10-21T15:10:00Z';
    final trip = TimelineParser.parse(jsonEncode(data)).first;
    expect(trip.toJson()['start'], '2026-10-22T00:05:00.000+09:00');
    expect(DateTime.parse(trip.toJson()['start'] as String), trip.start);
  });

  test('깨진 JSON은 원문을 노출하지 않는 FormatException이다', () {
    for (final text in ['', '{', '{"semanticSegments":[', '{"x":NaN}']) {
      expect(
        () => TimelineParser.parse(text),
        throwsA(
          isA<FormatException>()
              .having((error) => error.message, '안내 문구', '읽을 수 없는 파일이에요')
              .having((error) => error.source, '원문 미포함', isNull),
        ),
      );
    }
  });

  test('잘못된 루트와 배열 구조도 FormatException이다', () {
    for (final text in [
      'null',
      '[]',
      '42',
      '{"semanticSegments":null}',
      '{"semanticSegments":{}}',
      '{"timelineObjects":"x"}',
      '{"semanticSegments":[null]}',
      '{"timelineObjects":[7]}',
    ]) {
      expect(
        () => TimelineParser.parse(text),
        throwsFormatException,
        reason: text,
      );
    }
  });

  test('빈 기록과 방문 기록은 빈 목록이다', () {
    for (final text in [
      '{"semanticSegments":[]}',
      '{"timelineObjects":[]}',
      '{"semanticSegments":[{"visit":{}}]}',
      '{"timelineObjects":[{"placeVisit":{}}]}',
    ]) {
      expect(TimelineParser.parse(text), isEmpty);
    }
  });

  test('두 형식 모두 0.3km 미만을 버리고 경계값은 남긴다', () {
    for (final meters in [0, 299.9, 300]) {
      final modern = onDevice();
      modern['semanticSegments'] = [modern['semanticSegments'][0]];
      modern['semanticSegments'][0]['activity']['distanceMeters'] = meters;
      final legacy = takeout();
      legacy['timelineObjects'] = [legacy['timelineObjects'][0]];
      legacy['timelineObjects'][0]['activitySegment']['distance'] = meters;
      for (final data in [modern, legacy]) {
        expect(
          TimelineParser.parse(jsonEncode(data)),
          hasLength(meters < 300 ? 0 : 1),
        );
      }
    }
  });

  test('차 구간의 필수 필드 누락과 잘못된 자료형을 거부한다', () {
    final broken = <Map<String, dynamic>>[];
    var data = onDevice();
    data['semanticSegments'][0]['activity'].remove('start');
    broken.add(data);
    data = onDevice();
    data['semanticSegments'][0]['activity']['distanceMeters'] = '12300';
    broken.add(data);
    data = takeout();
    data['timelineObjects'][0]['activitySegment']['duration'] = [];
    broken.add(data);
    data = takeout();
    data['timelineObjects'][0]['activitySegment']['startLocation']['latitudeE7'] =
        null;
    broken.add(data);
    for (final value in broken) {
      expect(
        () => TimelineParser.parse(jsonEncode(value)),
        throwsFormatException,
      );
    }
  });

  test('좌표 형식과 범위가 잘못되면 거부한다', () {
    for (final coordinate in ['오류', '91°, 127°', '37°, 181°', 'NaN, 127']) {
      final data = onDevice();
      data['semanticSegments'][0]['activity']['start']['latLng'] = coordinate;
      expect(
        () => TimelineParser.parse(jsonEncode(data)),
        throwsFormatException,
      );
    }
    final data = takeout();
    data['timelineObjects'][0]['activitySegment']['endLocation']['longitudeE7'] =
        -1810000000;
    expect(() => TimelineParser.parse(jsonEncode(data)), throwsFormatException);
  });

  test('음수 또는 유한하지 않은 거리를 거부한다', () {
    final data = onDevice();
    data['semanticSegments'][0]['activity']['distanceMeters'] = -1;
    expect(() => TimelineParser.parse(jsonEncode(data)), throwsFormatException);
    final overflow = fixture('timeline_ondevice.json')
        .replaceFirst('12300', '1e999');
    expect(() => TimelineParser.parse(overflow), throwsFormatException);
  });

  test('잘못된 시간과 역전된 구간은 거부한다', () {
    for (final timestamp in [
      '오류',
      '2026-02-30T08:00:00Z',
      '2026-10-22T08:12:00',
    ]) {
      final data = onDevice();
      data['semanticSegments'][0]['startTime'] = timestamp;
      expect(
        () => TimelineParser.parse(jsonEncode(data)),
        throwsFormatException,
      );
    }
    final data = takeout();
    data['timelineObjects'][0]['activitySegment']['duration']['endTimestamp'] =
        '2026-10-21T00:00:00Z';
    expect(() => TimelineParser.parse(jsonEncode(data)), throwsFormatException);
  });

  group('트립 나누기', () {
    final seed = TimelineParser.parse(fixture('timeline_ondevice.json')).first;
    test('기본 1000건씩 나누며 순서와 모든 기록을 보존한다', () {
      final trips = List.generate(
        2501,
        (i) => PastTrip(
          start: seed.start.add(Duration(days: i)),
          end: seed.end.add(Duration(days: i)),
          startLat: seed.startLat,
          startLon: seed.startLon,
          endLat: seed.endLat,
          endLon: seed.endLon,
          distanceKm: seed.distanceKm,
        ),
      );
      final chunks = chunk(trips);
      expect(chunks.map((part) => part.length), [1000, 1000, 501]);
      expect(chunks.expand((part) => part), orderedEquals(trips));
      for (final part in chunks) {
        final envelope = {
          'version': 1,
          'source': 'google_timeline',
          'trips': part.map((trip) => trip.toJson()).toList(),
        };
        expect(utf8.encode(jsonEncode(envelope)).length, lessThan(5000000));
      }
    });
    test('빈 목록은 묶음이 없다', () => expect(chunk([]), isEmpty));
    test('정확히 1000건이면 빈 꼬리 묶음이 없다', () {
      expect(chunk(List.filled(1000, seed)).map((part) => part.length), [1000]);
    });
    test('크기 지정과 원본 목록 분리를 지원한다', () {
      final trips = List.filled(5, seed);
      final parts = chunk(trips, size: 2);
      expect(parts.map((part) => part.length), [2, 2, 1]);
      parts.first.clear();
      expect(trips, hasLength(5));
    });
    test('0 이하 크기는 빈 입력에서도 거부한다', () {
      expect(() => chunk([], size: 0), throwsArgumentError);
      expect(() => chunk([seed], size: -1), throwsArgumentError);
    });
  });
}
