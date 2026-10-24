import 'package:flutter_test/flutter_test.dart';
import 'package:phone/backend.dart';
import 'package:phone/family/summary_card.dart';
import 'package:phone/share_options.dart';

import 'support/harness.dart';

FeedRow row(ShareScope scope) => FeedRow(
  driverId: 'd',
  driverName: '아버지',
  month: DateTime(2026, 10),
  stage: 'talk',
  scope: scope,
  domains: {'mobility': 1.2, 'wayfinding': .8},
  top: const [TopItem(text: '길을 다시 찾는 일이 늘었어요', grade: 'C', feature: 'W3')],
  letter: '이번 달 편지',
);
void main() {
  testWidgets('stage scope never reveals chart or letter', (t) async {
    await showScreen(t, SummaryCard(row: row(ShareScope.stage)));
    expect(find.byType(DomainBars), findsNothing);
    expect(find.text('이번 달 편지'), findsNothing);
    expect(find.text('가족과 이야기 나눠 보시면 좋겠어요'), findsOneWidget);
  });
  testWidgets('detail shows ratios missing data evidence and letter', (
    t,
  ) async {
    await showScreen(t, SummaryCard(row: row(ShareScope.detail)));
    expect(find.text('이번 달 편지'), findsOneWidget);
    expect(find.text('평소 범위 넘음'), findsOneWidget);
    expect(find.text('평소 범위 안'), findsOneWidget);
    expect(find.text('기록을 모으는 중'), findsOneWidget);
    expect(find.text('Pleos 지표, 검증 중'), findsOneWidget);
  });
  testWidgets('negative ratio is a real value inside the usual range', (
    t,
  ) async {
    final negative = FeedRow(
      driverId: 'd',
      driverName: '아버지',
      month: DateTime(2026, 10),
      stage: 'stable',
      scope: ShareScope.trend,
      domains: {'operation': -0.03},
      top: null,
      letter: null,
    );
    await showScreen(t, SummaryCard(row: negative));
    expect(find.text('평소 범위 안'), findsOneWidget);
    expect(find.text('기록을 모으는 중'), findsNWidgets(2)); // 값이 없는 두 영역
  });
}
