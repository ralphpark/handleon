enum ShareScope {
  stage('stage', '단계만'),
  trend('trend', '추이 그래프까지'),
  detail('detail', '자세한 지표와 편지까지');

  const ShareScope(this.wire, this.label);

  final String wire;
  final String label;

  static ShareScope? fromWire(Object? value) {
    for (final scope in values) {
      if (scope.wire == value) return scope;
    }
    return null;
  }
}

enum ShareTiming {
  now(0, '바로 공유'),
  d3(3, '3일 뒤 공유'),
  d7(7, '7일 뒤 공유'),
  d14(14, '14일 뒤 공유');

  const ShareTiming(this.wire, this.label);

  final int wire;
  final String label;

  static ShareTiming? fromWire(Object? value) {
    if (value is! int) return null;
    for (final timing in values) {
      if (timing.wire == value) return timing;
    }
    return null;
  }
}
