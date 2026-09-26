import 'package:flutter/material.dart';

/// Semantic color tokens. Read via `context.colors` so light/dark switch
/// automatically. Category colors live on each category row (hex), not here.
@immutable
class AppColors extends ThemeExtension<AppColors> {
  const AppColors({
    required this.accent,
    required this.onAccent,
    required this.hero,
    required this.onHero,
    required this.bg,
    required this.card,
    required this.border,
    required this.text,
    required this.muted,
    required this.danger,
    required this.navInactive,
  });

  final Color accent,
      onAccent,
      hero,
      onHero,
      bg,
      card,
      border,
      text,
      muted,
      danger,
      navInactive;

  static const light = AppColors(
    accent: Color(0xFF1C7A5E),
    onAccent: Color(0xFFFFFFFF),
    hero: Color(0xFF123328),
    onHero: Color(0xFFFFFFFF),
    bg: Color(0xFFF5F3EE),
    card: Color(0xFFFFFFFF),
    border: Color(0xFFEAE7E0),
    text: Color(0xFF1B1A17),
    muted: Color(0xFF6F6B64), // darker than old #8C8880 for AA contrast on bg
    danger: Color(0xFFC6443A),
    navInactive: Color(0xFF6F6B64), // = muted; lighter failed AA on card
  );

  static const dark = AppColors(
    accent: Color(0xFF3FB58C),
    onAccent: Color(0xFF0B1F17),
    hero: Color(0xFF0E2A20),
    onHero: Color(0xFFECEAE5),
    bg: Color(0xFF121412),
    card: Color(0xFF1C1F1D),
    border: Color(0xFF2A2E2B),
    text: Color(0xFFECEAE5),
    muted: Color(0xFF9A968E),
    danger: Color(0xFFE57368),
    navInactive: Color(0xFF9A968E), // = muted; darker failed AA on card
  );

  @override
  AppColors copyWith({
    Color? accent,
    Color? onAccent,
    Color? hero,
    Color? onHero,
    Color? bg,
    Color? card,
    Color? border,
    Color? text,
    Color? muted,
    Color? danger,
    Color? navInactive,
  }) => AppColors(
    accent: accent ?? this.accent,
    onAccent: onAccent ?? this.onAccent,
    hero: hero ?? this.hero,
    onHero: onHero ?? this.onHero,
    bg: bg ?? this.bg,
    card: card ?? this.card,
    border: border ?? this.border,
    text: text ?? this.text,
    muted: muted ?? this.muted,
    danger: danger ?? this.danger,
    navInactive: navInactive ?? this.navInactive,
  );

  @override
  AppColors lerp(AppColors? other, double t) {
    if (other == null) return this;
    Color l(Color a, Color b) => Color.lerp(a, b, t)!;
    return AppColors(
      accent: l(accent, other.accent),
      onAccent: l(onAccent, other.onAccent),
      hero: l(hero, other.hero),
      onHero: l(onHero, other.onHero),
      bg: l(bg, other.bg),
      card: l(card, other.card),
      border: l(border, other.border),
      text: l(text, other.text),
      muted: l(muted, other.muted),
      danger: l(danger, other.danger),
      navInactive: l(navInactive, other.navInactive),
    );
  }
}

extension AppColorsX on BuildContext {
  // Fallback keeps widgets usable under a bare MaterialApp (e.g. tests).
  AppColors get colors {
    final theme = Theme.of(this);
    return theme.extension<AppColors>() ??
        (theme.brightness == Brightness.dark ? AppColors.dark : AppColors.light);
  }
}

/// Corner radii. Hierarchy: bigger surface, rounder corner.
class AppRadii {
  static const sm = 12.0; // small badges
  static const md = 16.0; // tiles, fields, buttons
  static const card = 24.0; // content cards
  static const hero = 32.0; // the one hero card per screen
  static const chip = 999.0; // pills
}

/// Page gutter and vertical rhythm (8pt grid).
class AppSpace {
  static const page = 20.0;
  static const gap = 16.0;
  static const section = 28.0;
}

const _font = 'Manrope';
const _tabular = [FontFeature.tabularFigures()];

/// Type scale (Manrope). Amounts use display/headline styles with tabular
/// figures so digits line up in lists.
TextTheme _textTheme(AppColors c) => TextTheme(
  // hero amount
  displayLarge: TextStyle(fontSize: 44, height: 1.05, fontWeight: FontWeight.w800, letterSpacing: -1.6, color: c.text, fontFeatures: _tabular),
  // secondary big amount (donut center, preview card)
  displaySmall: TextStyle(fontSize: 30, height: 1.1, fontWeight: FontWeight.w800, letterSpacing: -1, color: c.text, fontFeatures: _tabular),
  // screen titles
  headlineMedium: TextStyle(fontSize: 28, height: 1.15, fontWeight: FontWeight.w800, letterSpacing: -0.8, color: c.text),
  titleLarge: TextStyle(fontSize: 20, height: 1.25, fontWeight: FontWeight.w700, letterSpacing: -0.3, color: c.text),
  titleMedium: TextStyle(fontSize: 16, height: 1.3, fontWeight: FontWeight.w700, color: c.text),
  titleSmall: TextStyle(fontSize: 15, height: 1.3, fontWeight: FontWeight.w700, color: c.text, fontFeatures: _tabular),
  bodyLarge: TextStyle(fontSize: 16, height: 1.45, fontWeight: FontWeight.w500, color: c.text),
  bodyMedium: TextStyle(fontSize: 14, height: 1.45, fontWeight: FontWeight.w500, color: c.text),
  bodySmall: TextStyle(fontSize: 13, height: 1.35, fontWeight: FontWeight.w500, color: c.muted),
  labelLarge: TextStyle(fontSize: 15, fontWeight: FontWeight.w700, color: c.text),
  labelMedium: TextStyle(fontSize: 13, fontWeight: FontWeight.w600, color: c.muted),
  labelSmall: TextStyle(fontSize: 12, fontWeight: FontWeight.w600, color: c.muted),
);

ThemeData buildTheme(Brightness brightness) {
  final dark = brightness == Brightness.dark;
  final c = dark ? AppColors.dark : AppColors.light;
  final scheme = ColorScheme.fromSeed(
    seedColor: c.accent,
    brightness: brightness,
    primary: c.accent,
    onPrimary: c.onAccent,
    surface: c.card,
    onSurface: c.text,
    onSurfaceVariant: c.muted,
    outline: c.border,
    outlineVariant: c.border,
    error: c.danger,
  );
  // apply: component themes below read these styles directly, and ThemeData.fontFamily doesn't reach them.
  final text = _textTheme(c).apply(fontFamily: _font);
  final md = RoundedRectangleBorder(borderRadius: BorderRadius.circular(AppRadii.md));
  final fieldBorder = OutlineInputBorder(
    borderRadius: BorderRadius.circular(AppRadii.md),
    borderSide: BorderSide(color: c.border),
  );

  return ThemeData(
    useMaterial3: true,
    brightness: brightness,
    colorScheme: scheme,
    scaffoldBackgroundColor: c.bg,
    fontFamily: _font,
    textTheme: text,
    extensions: [c],
    splashFactory: InkSparkle.splashFactory,
    appBarTheme: AppBarTheme(
      backgroundColor: c.bg,
      foregroundColor: c.text,
      elevation: 0,
      scrolledUnderElevation: 0,
      centerTitle: false,
      titleTextStyle: text.titleLarge,
    ),
    cardTheme: CardThemeData(
      color: c.card,
      elevation: 0,
      margin: EdgeInsets.zero,
      clipBehavior: Clip.antiAlias,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(AppRadii.card),
        // light: tonal only; dark: hairline so cards don't melt into bg
        side: dark ? BorderSide(color: c.border) : BorderSide.none,
      ),
    ),
    dividerTheme: DividerThemeData(color: c.border, thickness: 1, space: 1),
    listTileTheme: ListTileThemeData(
      minVerticalPadding: 12,
      titleTextStyle: text.titleSmall,
      subtitleTextStyle: text.bodySmall,
      iconColor: c.muted,
    ),
    inputDecorationTheme: InputDecorationTheme(
      filled: true,
      fillColor: c.card,
      hintStyle: text.bodyLarge!.copyWith(color: c.muted),
      labelStyle: text.bodyMedium!.copyWith(color: c.muted),
      contentPadding: const EdgeInsets.symmetric(horizontal: 18, vertical: 16),
      border: fieldBorder,
      enabledBorder: fieldBorder,
      focusedBorder: fieldBorder.copyWith(
        borderSide: BorderSide(color: c.accent, width: 2),
      ),
    ),
    segmentedButtonTheme: SegmentedButtonThemeData(
      style: SegmentedButton.styleFrom(
        backgroundColor: c.card,
        foregroundColor: c.muted,
        selectedBackgroundColor: c.accent,
        selectedForegroundColor: c.onAccent,
        side: BorderSide(color: c.border),
        minimumSize: const Size(0, 48),
        textStyle: text.labelLarge,
      ),
    ),
    filledButtonTheme: FilledButtonThemeData(
      style: FilledButton.styleFrom(
        backgroundColor: c.accent,
        foregroundColor: c.onAccent,
        minimumSize: const Size(0, 56),
        shape: md,
        textStyle: text.labelLarge,
      ),
    ),
    outlinedButtonTheme: OutlinedButtonThemeData(
      style: OutlinedButton.styleFrom(
        foregroundColor: c.text,
        minimumSize: const Size(0, 56),
        side: BorderSide(color: c.border, width: 1.5),
        shape: md,
        textStyle: text.labelLarge,
      ),
    ),
    textButtonTheme: TextButtonThemeData(
      style: TextButton.styleFrom(
        foregroundColor: c.accent,
        minimumSize: const Size(48, 48),
        textStyle: text.labelLarge,
      ),
    ),
    chipTheme: ChipThemeData(
      backgroundColor: c.card,
      selectedColor: c.accent,
      labelStyle: text.labelLarge!.copyWith(fontSize: 14),
      secondaryLabelStyle: text.labelLarge!.copyWith(fontSize: 14, color: c.onAccent),
      side: BorderSide(color: c.border),
      shape: const StadiumBorder(),
      showCheckmark: false,
      padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 8),
    ),
    snackBarTheme: SnackBarThemeData(
      behavior: SnackBarBehavior.floating,
      backgroundColor: c.hero,
      contentTextStyle: text.bodyMedium!.copyWith(color: c.onHero),
      actionTextColor: c.onHero,
      shape: md,
    ),
    dialogTheme: DialogThemeData(
      backgroundColor: c.card,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(AppRadii.card)),
      titleTextStyle: text.titleLarge,
      contentTextStyle: text.bodyMedium,
    ),
    progressIndicatorTheme: ProgressIndicatorThemeData(
      color: c.accent,
      linearTrackColor: c.border,
    ),
  );
}
