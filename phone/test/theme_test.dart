import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:phone/theme.dart';

void main() {
  test('주간 테마의 브랜드와 바탕이 토큰과 같다', () {
    expect(lightTheme.brightness, Brightness.light);
    expect(lightTheme.colorScheme.primary, const Color(0xff1f5c4a));
    expect(lightTheme.colorScheme.surface, const Color(0xfff6f5f1));
    expect(lightTheme.scaffoldBackgroundColor, const Color(0xfff6f5f1));
    expect(
      lightTheme.extension<HandleOnColors>()!.brand,
      const Color(0xff1f5c4a),
    );
  });

  test('야간 테마의 브랜드와 바탕이 토큰과 같다', () {
    expect(darkTheme.brightness, Brightness.dark);
    expect(darkTheme.colorScheme.primary, const Color(0xff7cc4a5));
    expect(darkTheme.colorScheme.surface, const Color(0xff121513));
    expect(darkTheme.scaffoldBackgroundColor, const Color(0xff121513));
    expect(
      darkTheme.extension<HandleOnColors>()!.brand,
      const Color(0xff7cc4a5),
    );
  });

  test('휴대폰 글자 크기와 줄 높이 및 글꼴을 두 테마에 적용한다', () {
    for (final theme in [lightTheme, darkTheme]) {
      final styles = [
        (theme.textTheme.headlineLarge!, 26.0, 34.0, FontWeight.w700),
        (theme.textTheme.titleLarge!, 20.0, 28.0, FontWeight.w600),
        (theme.textTheme.bodyMedium!, 16.0, 24.0, FontWeight.w400),
        (theme.textTheme.labelLarge!, 14.0, 20.0, FontWeight.w600),
        (theme.textTheme.bodySmall!, 13.0, 18.0, FontWeight.w400),
      ];
      for (final (style, size, lineHeight, weight) in styles) {
        expect(style.fontSize, size);
        expect(style.height! * size, closeTo(lineHeight, 0.000001));
        expect(style.fontWeight, weight);
        expect(style.fontFamily, 'Asta Sans');
        expect(style.fontFamilyFallback, contains('Noto Sans KR'));
      }
    }
  });
}
