import 'package:flutter/material.dart';

/// 디자인 시스템의 주간·야간 색 전체. Theme.of(context).extension 으로 읽는다.
class HandleOnColors extends ThemeExtension<HandleOnColors> {
  const HandleOnColors({
    required this.surface,
    required this.surfaceRaised,
    required this.surfaceSunken,
    required this.line,
    required this.ink,
    required this.inkMuted,
    required this.brand,
    required this.onBrand,
    required this.brandSoft,
    required this.stable,
    required this.watchFill,
    required this.onWatch,
    required this.watchInk,
    required this.talkFill,
    required this.onTalk,
    required this.talkInk,
    required this.threshold,
    required this.replay,
    required this.replaySoft,
    required this.focus,
  });

  final Color surface;
  final Color surfaceRaised;
  final Color surfaceSunken;
  final Color line;
  final Color ink;
  final Color inkMuted;
  final Color brand;
  final Color onBrand;
  final Color brandSoft;
  final Color stable;
  final Color watchFill;
  final Color onWatch;
  final Color watchInk;
  final Color talkFill;
  final Color onTalk;
  final Color talkInk;
  final Color threshold;
  final Color replay;
  final Color replaySoft;
  final Color focus;

  static const light = HandleOnColors(
    surface: Color(0xfff3f5f7),
    surfaceRaised: Color(0xffffffff),
    surfaceSunken: Color(0xffe7ebf0),
    line: Color(0xffdce2e8),
    ink: Color(0xff0f1720),
    inkMuted: Color(0xff56616d),
    brand: Color(0xff0b7f6a),
    onBrand: Color(0xffffffff),
    brandSoft: Color(0xffddf4ee),
    stable: Color(0xff0b7f6a),
    watchFill: Color(0xffe9a23b),
    onWatch: Color(0xff1a1205),
    watchInk: Color(0xff8a5600),
    talkFill: Color(0xffe0663f),
    onTalk: Color(0xff1c0d07),
    talkInk: Color(0xffa63f1c),
    threshold: Color(0xff56616d),
    replay: Color(0xff2457c5),
    replaySoft: Color(0xffe3ecfa),
    focus: Color(0xff2457c5),
  );

  static const dark = HandleOnColors(
    surface: Color(0xff0a0d12),
    surfaceRaised: Color(0xff14181e),
    surfaceSunken: Color(0xff070a0e),
    line: Color(0xff262c34),
    ink: Color(0xfff2f5f7),
    inkMuted: Color(0xff9aa7b2),
    brand: Color(0xff4fe3c1),
    onBrand: Color(0xff06231d),
    brandSoft: Color(0xff133a35),
    stable: Color(0xff4fe3c1),
    watchFill: Color(0xffffc46b),
    onWatch: Color(0xff1a1205),
    watchInk: Color(0xffffd38f),
    talkFill: Color(0xffff8a65),
    onTalk: Color(0xff1c0d07),
    talkInk: Color(0xffffb59d),
    threshold: Color(0xffc9d2da),
    replay: Color(0xff7fb2ff),
    replaySoft: Color(0xff16233d),
    focus: Color(0xff7fb2ff),
  );

  @override
  HandleOnColors copyWith({
    Color? surface,
    Color? surfaceRaised,
    Color? surfaceSunken,
    Color? line,
    Color? ink,
    Color? inkMuted,
    Color? brand,
    Color? onBrand,
    Color? brandSoft,
    Color? stable,
    Color? watchFill,
    Color? onWatch,
    Color? watchInk,
    Color? talkFill,
    Color? onTalk,
    Color? talkInk,
    Color? threshold,
    Color? replay,
    Color? replaySoft,
    Color? focus,
  }) => HandleOnColors(
    surface: surface ?? this.surface,
    surfaceRaised: surfaceRaised ?? this.surfaceRaised,
    surfaceSunken: surfaceSunken ?? this.surfaceSunken,
    line: line ?? this.line,
    ink: ink ?? this.ink,
    inkMuted: inkMuted ?? this.inkMuted,
    brand: brand ?? this.brand,
    onBrand: onBrand ?? this.onBrand,
    brandSoft: brandSoft ?? this.brandSoft,
    stable: stable ?? this.stable,
    watchFill: watchFill ?? this.watchFill,
    onWatch: onWatch ?? this.onWatch,
    watchInk: watchInk ?? this.watchInk,
    talkFill: talkFill ?? this.talkFill,
    onTalk: onTalk ?? this.onTalk,
    talkInk: talkInk ?? this.talkInk,
    threshold: threshold ?? this.threshold,
    replay: replay ?? this.replay,
    replaySoft: replaySoft ?? this.replaySoft,
    focus: focus ?? this.focus,
  );

  @override
  HandleOnColors lerp(covariant HandleOnColors? other, double t) {
    if (other == null) return this;
    return HandleOnColors(
      surface: Color.lerp(surface, other.surface, t)!,
      surfaceRaised: Color.lerp(surfaceRaised, other.surfaceRaised, t)!,
      surfaceSunken: Color.lerp(surfaceSunken, other.surfaceSunken, t)!,
      line: Color.lerp(line, other.line, t)!,
      ink: Color.lerp(ink, other.ink, t)!,
      inkMuted: Color.lerp(inkMuted, other.inkMuted, t)!,
      brand: Color.lerp(brand, other.brand, t)!,
      onBrand: Color.lerp(onBrand, other.onBrand, t)!,
      brandSoft: Color.lerp(brandSoft, other.brandSoft, t)!,
      stable: Color.lerp(stable, other.stable, t)!,
      watchFill: Color.lerp(watchFill, other.watchFill, t)!,
      onWatch: Color.lerp(onWatch, other.onWatch, t)!,
      watchInk: Color.lerp(watchInk, other.watchInk, t)!,
      talkFill: Color.lerp(talkFill, other.talkFill, t)!,
      onTalk: Color.lerp(onTalk, other.onTalk, t)!,
      talkInk: Color.lerp(talkInk, other.talkInk, t)!,
      threshold: Color.lerp(threshold, other.threshold, t)!,
      replay: Color.lerp(replay, other.replay, t)!,
      replaySoft: Color.lerp(replaySoft, other.replaySoft, t)!,
      focus: Color.lerp(focus, other.focus, t)!,
    );
  }
}

abstract final class HandleOnSpacing {
  static const space1 = 8.0;
  static const space2 = 16.0;
  static const space3 = 24.0;
  static const space4 = 32.0;
  static const space5 = 48.0;
  static const space6 = 64.0;
}

abstract final class HandleOnRadius {
  static const radiusSm = 12.0;
  static const radiusMd = 20.0;
  static const radiusLg = 28.0;
  static const radiusPill = 999.0;
}

abstract final class HandleOnTypography {
  static const phoneTitle = TextStyle(
    fontFamily: 'Asta Sans',
    fontFamilyFallback: ['Noto Sans KR'],
    fontSize: 26,
    height: 34 / 26,
    fontWeight: FontWeight.w800,
  );
  static const phoneHeadline = TextStyle(
    fontFamily: 'Asta Sans',
    fontFamilyFallback: ['Noto Sans KR'],
    fontSize: 20,
    height: 28 / 20,
    fontWeight: FontWeight.w600,
  );
  static const phoneBody = TextStyle(
    fontFamily: 'Asta Sans',
    fontFamilyFallback: ['Noto Sans KR'],
    fontSize: 16,
    height: 24 / 16,
    fontWeight: FontWeight.w400,
  );
  static const phoneLabel = TextStyle(
    fontFamily: 'Asta Sans',
    fontFamilyFallback: ['Noto Sans KR'],
    fontSize: 14,
    height: 20 / 14,
    fontWeight: FontWeight.w600,
  );
  static const phoneCaption = TextStyle(
    fontFamily: 'Asta Sans',
    fontFamilyFallback: ['Noto Sans KR'],
    fontSize: 13,
    height: 18 / 13,
    fontWeight: FontWeight.w400,
  );
}

final ThemeData lightTheme = _theme(Brightness.light, HandleOnColors.light);
final ThemeData darkTheme = _theme(Brightness.dark, HandleOnColors.dark);

ThemeData _theme(Brightness brightness, HandleOnColors colors) {
  final text = const TextTheme(
    displayLarge: HandleOnTypography.phoneTitle,
    displayMedium: HandleOnTypography.phoneTitle,
    displaySmall: HandleOnTypography.phoneTitle,
    headlineLarge: HandleOnTypography.phoneTitle,
    headlineMedium: HandleOnTypography.phoneHeadline,
    headlineSmall: HandleOnTypography.phoneHeadline,
    titleLarge: HandleOnTypography.phoneHeadline,
    titleMedium: HandleOnTypography.phoneLabel,
    titleSmall: HandleOnTypography.phoneLabel,
    bodyLarge: HandleOnTypography.phoneBody,
    bodyMedium: HandleOnTypography.phoneBody,
    bodySmall: HandleOnTypography.phoneCaption,
    labelLarge: HandleOnTypography.phoneLabel,
    labelMedium: HandleOnTypography.phoneLabel,
    labelSmall: HandleOnTypography.phoneCaption,
  ).apply(bodyColor: colors.ink, displayColor: colors.ink);

  return ThemeData(
    useMaterial3: true,
    brightness: brightness,
    fontFamily: 'Asta Sans',
    fontFamilyFallback: const ['Noto Sans KR'],
    textTheme: text,
    scaffoldBackgroundColor: colors.surface,
    cardColor: colors.surfaceRaised,
    dividerColor: colors.line,
    focusColor: colors.focus,
    colorScheme: ColorScheme(
      brightness: brightness,
      primary: colors.brand,
      onPrimary: colors.onBrand,
      primaryContainer: colors.brandSoft,
      onPrimaryContainer: colors.ink,
      secondary: colors.brand,
      onSecondary: colors.onBrand,
      secondaryContainer: colors.brandSoft,
      onSecondaryContainer: colors.ink,
      tertiary: colors.replay,
      onTertiary: colors.surface,
      tertiaryContainer: colors.replaySoft,
      onTertiaryContainer: colors.replay,
      error: colors.talkInk,
      onError: colors.surface,
      errorContainer: colors.talkFill,
      onErrorContainer: colors.onTalk,
      surface: colors.surface,
      onSurface: colors.ink,
      surfaceDim: colors.surfaceSunken,
      surfaceBright: colors.surfaceRaised,
      surfaceContainerLowest: colors.surfaceSunken,
      surfaceContainerLow: colors.surface,
      surfaceContainer: colors.surfaceRaised,
      surfaceContainerHigh: colors.surfaceRaised,
      surfaceContainerHighest: colors.surfaceRaised,
      onSurfaceVariant: colors.inkMuted,
      outline: colors.line,
      outlineVariant: colors.line,
    ),
    extensions: [colors],
  );
}
