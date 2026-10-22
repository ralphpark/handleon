import 'dart:convert';

const _unreadable = FormatException('읽을 수 없는 파일이에요');

class PastTrip {
  const PastTrip({
    required this.start,
    required this.end,
    required this.startLat,
    required this.startLon,
    required this.endLat,
    required this.endLon,
    required this.distanceKm,
  });

  /// 시간은 절대 시각이며, 날짜·월을 표시하거나 묶을 때는 서울 기준을 쓴다.
  final DateTime start;
  final DateTime end;
  final double startLat;
  final double startLon;
  final double endLat;
  final double endLon;
  final double distanceKm;

  /// 공통 데이터 계약 A. 기기의 현지 시간대에 의존하지 않는다.
  Map<String, dynamic> toJson() => {
    'start': _seoulTimestamp(start),
    'end': _seoulTimestamp(end),
    'startLat': startLat,
    'startLon': startLon,
    'endLat': endLat,
    'endLon': endLon,
    'distanceKm': distanceKm,
  };

  static String _seoulTimestamp(DateTime time) {
    // 지원하는 현대 타임라인 기록의 Asia/Seoul 오프셋은 UTC+09:00이다.
    final seoul = time.toUtc().add(const Duration(hours: 9));
    return '${seoul.toIso8601String().replaceFirst(RegExp(r'Z$'), '')}+09:00';
  }
}

class TimelineParser {
  /// 지원 형식의 차 이동만 읽는다. 파일 손상은 원문 없이 같은 예외로 알린다.
  static List<PastTrip> parse(String json) {
    try {
      final root = _object(jsonDecode(json));
      if (root.containsKey('semanticSegments')) {
        return _readSegments(root['semanticSegments'], _onDevice);
      }
      if (root.containsKey('timelineObjects')) {
        return _readSegments(root['timelineObjects'], _takeout);
      }
      throw _unreadable;
    } on FormatException {
      // JSON 디코더의 예외에는 개인 기록 원문이 포함될 수 있다.
      throw _unreadable;
    }
  }

  static List<PastTrip> _readSegments(
    Object? raw,
    PastTrip? Function(Map<String, dynamic>) read,
  ) {
    if (raw is! List) throw _unreadable;
    final trips = <PastTrip>[];
    for (final item in raw) {
      final trip = read(_object(item));
      if (trip != null) trips.add(trip);
    }
    return trips;
  }

  static PastTrip? _onDevice(Map<String, dynamic> segment) {
    if (!segment.containsKey('activity')) return null;
    final activity = _object(segment['activity']);
    final candidate = _object(activity['topCandidate']);
    final type = candidate['type'];
    if (type is! String) throw _unreadable;
    if (type.toUpperCase().replaceAll(RegExp(r'[\s_]'), '') !=
        'INPASSENGERVEHICLE') {
      return null;
    }
    return _trip(
      start: segment['startTime'],
      end: segment['endTime'],
      from: _latLng(_object(activity['start'])['latLng']),
      to: _latLng(_object(activity['end'])['latLng']),
      meters: activity['distanceMeters'],
    );
  }

  static PastTrip? _takeout(Map<String, dynamic> segment) {
    if (!segment.containsKey('activitySegment')) return null;
    final activity = _object(segment['activitySegment']);
    final type = activity['activityType'];
    if (type is! String) throw _unreadable;
    if (type != 'IN_PASSENGER_VEHICLE') return null;
    final duration = _object(activity['duration']);
    return _trip(
      start: duration['startTimestamp'],
      end: duration['endTimestamp'],
      from: _e7(activity['startLocation']),
      to: _e7(activity['endLocation']),
      meters: activity['distance'],
    );
  }

  static PastTrip? _trip({
    required Object? start,
    required Object? end,
    required (double, double) from,
    required (double, double) to,
    required Object? meters,
  }) {
    final startTime = _timestamp(start);
    final endTime = _timestamp(end);
    if (!endTime.isAfter(startTime)) throw _unreadable;
    final distance = _number(meters);
    if (distance < 0) throw _unreadable;
    if (distance < 300) return null;
    return PastTrip(
      start: startTime,
      end: endTime,
      startLat: from.$1,
      startLon: from.$2,
      endLat: to.$1,
      endLon: to.$2,
      distanceKm: distance / 1000,
    );
  }

  static Map<String, dynamic> _object(Object? value) {
    if (value is! Map<String, dynamic>) throw _unreadable;
    return value;
  }

  static double _number(Object? value) {
    if (value is! num || !value.isFinite) throw _unreadable;
    return value.toDouble();
  }

  static (double, double) _e7(Object? raw) {
    final location = _object(raw);
    return _coordinates(
      _number(location['latitudeE7']) / 1e7,
      _number(location['longitudeE7']) / 1e7,
    );
  }

  static final _coordinatePattern = RegExp(
    r'^\s*([+-]?\d+(?:\.\d+)?)\s*°?\s*,\s*([+-]?\d+(?:\.\d+)?)\s*°?\s*$',
  );

  static (double, double) _latLng(Object? raw) {
    if (raw is! String) throw _unreadable;
    final match = _coordinatePattern.firstMatch(raw);
    if (match == null) throw _unreadable;
    return _coordinates(
      double.parse(match.group(1)!),
      double.parse(match.group(2)!),
    );
  }

  static (double, double) _coordinates(double lat, double lon) {
    if (!lat.isFinite || !lon.isFinite || lat.abs() > 90 || lon.abs() > 180) {
      throw _unreadable;
    }
    return ((lat * 1000).round() / 1000, (lon * 1000).round() / 1000);
  }

  static final _timestampPattern = RegExp(
    r'^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.\d{1,6})?(Z|[+-]\d{2}:\d{2})$',
  );

  static DateTime _timestamp(Object? raw) {
    if (raw is! String) throw _unreadable;
    final match = _timestampPattern.firstMatch(raw);
    if (match == null) throw _unreadable;
    final year = int.parse(match.group(1)!);
    final month = int.parse(match.group(2)!);
    final day = int.parse(match.group(3)!);
    final date = DateTime.utc(year, month, day);
    // DateTime.parse는 2월 30일 같은 값을 자동 보정하므로 먼저 검사한다.
    if (date.year != year ||
        date.month != month ||
        date.day != day ||
        int.parse(match.group(4)!) > 23 ||
        int.parse(match.group(5)!) > 59 ||
        int.parse(match.group(6)!) > 59) {
      throw _unreadable;
    }
    final offset = match.group(7)!;
    if (offset != 'Z' &&
        (int.parse(offset.substring(1, 3)) > 23 ||
            int.parse(offset.substring(4, 6)) > 59)) {
      throw _unreadable;
    }
    return DateTime.parse(raw).toUtc();
  }
}

/// 업로드용 기록을 원래 순서대로 나눈다. 입력 목록은 바꾸지 않는다.
List<List<PastTrip>> chunk(List<PastTrip> trips, {int size = 1000}) {
  if (size <= 0) throw ArgumentError.value(size, 'size', '1 이상이어야 해요');
  final result = <List<PastTrip>>[];
  for (var start = 0; start < trips.length; start += size) {
    final end = start + size;
    result.add(trips.sublist(start, end < trips.length ? end : trips.length));
  }
  return result;
}
