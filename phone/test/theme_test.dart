import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:phone/theme.dart';

void main() {
  test('주간 테마의 브랜드와 바탕이 토큰과 같다', () {
    expect(lightTheme.brightness, Brightness.light);
    expect(lightTheme.colorScheme.primary, const Color(0xff0b7f6a));
    expect(lightTheme.colorScheme.surface, const Color(0xfff3f5f7));
    expect(lightTheme.scaffoldBackgroundColor, const Color(0xfff3f5f7));
    expect(
      lightTheme.extension<HandleOnColors>()!.brand,
      const Color(0xff0b7f6a),
    );
  });

  test('야간 테마의 브랜드와 바탕이 토큰과 같다', () {
    expect(darkTheme.brightness, Brightness.dark);
    expect(darkTheme.colorScheme.primary, const Color(0xff4fe3c1));
    expect(darkTheme.colorScheme.surface, const Color(0xff0a0d12));
    expect(darkTheme.scaffoldBackgroundColor, const Color(0xff0a0d12));
    expect(
      darkTheme.extension<HandleOnColors>()!.brand,
      const Color(0xff4fe3c1),
    );
  });

  test('휴대폰 글자 크기와 줄 높이 및 글꼴을 두 테마에 적용한다', () {
    for (final theme in [lightTheme, darkTheme]) {
      final styles = [
        (theme.textTheme.headlineLarge!, 26.0, 34.0, FontWeight.w800),
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
