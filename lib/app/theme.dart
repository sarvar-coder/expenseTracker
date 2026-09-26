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
    required this.success,
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
      success,
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
    success: Color(0xFF2E8B57),
    navInactive: Color(0xFF8C8880),
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
    success: Color(0xFF5CC08A),
    navInactive: Color(0xFF6E6A63),
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
    Color? success,
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
    success: success ?? this.success,
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
      success: l(success, other.success),
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

/// Corner radii. Same in light and dark, so plain consts.
class AppRadii {
  static const sm = 9.0;
  static const md = 12.0;
  static const field = 13.0;
  static const card = 18.0;
  static const chip = 20.0;
}

ThemeData buildTheme(Brightness brightness) {
  final c = brightness == Brightness.dark ? AppColors.dark : AppColors.light;
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
  final fieldBorder = OutlineInputBorder(
    borderRadius: BorderRadius.circular(AppRadii.field),
    borderSide: BorderSide(color: c.border),
  );

  final base = ThemeData(
    colorScheme: scheme,
    scaffoldBackgroundColor: c.bg,
    useMaterial3: true,
    fontFamily: 'Roboto',
    extensions: [c],
  );

  return base.copyWith(
    textTheme: base.textTheme.apply(bodyColor: c.text, displayColor: c.text),
    appBarTheme: AppBarTheme(
      backgroundColor: c.bg,
      foregroundColor: c.text,
      elevation: 0,
      scrolledUnderElevation: 0,
      centerTitle: false,
    ),
    cardTheme: CardThemeData(
      color: c.card,
      elevation: 0,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(AppRadii.card),
        side: BorderSide(color: c.border),
      ),
    ),
    inputDecorationTheme: InputDecorationTheme(
      filled: true,
      fillColor: c.card,
      hintStyle: TextStyle(color: c.muted),
      border: fieldBorder,
      enabledBorder: fieldBorder,
      focusedBorder: fieldBorder.copyWith(
        borderSide: BorderSide(color: c.accent, width: 1.5),
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
      ),
    ),
    filledButtonTheme: FilledButtonThemeData(
      style: FilledButton.styleFrom(
        backgroundColor: c.accent,
        foregroundColor: c.onAccent,
        minimumSize: const Size(0, 48),
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(AppRadii.field),
        ),
        padding: const EdgeInsets.symmetric(vertical: 14),
      ),
    ),
  );
}
