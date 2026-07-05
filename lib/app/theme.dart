import 'package:flutter/material.dart';

/// Design tokens from the mockup (expense_tracker_flutter_mockup.html).
class AppColors {
  static const accent = Color(0xFF1C7A5E); // primary green
  static const hero = Color(0xFF123328); // dark hero card
  static const bg = Color(0xFFF5F3EE); // cream background
  static const card = Color(0xFFFFFFFF);
  static const border = Color(0xFFEAE7E0);
  static const text = Color(0xFF1B1A17);
  static const muted = Color(0xFF8C8880);

  /// Default category colors (extendable; AI-created categories get one too).
  static const food = Color(0xFFE08A5B);
  static const groceries = Color(0xFF6FA86A);
  static const shopping = Color(0xFFC07FA6);
  static const transport = Color(0xFF5B8DB8);
  static const bills = Color(0xFFD9A24E);
}

/// Rounded-corner radii used across cards/chips.
class AppRadii {
  static const card = 18.0;
  static const chip = 20.0;
  static const field = 13.0;
}

ThemeData buildTheme() {
  final scheme = ColorScheme.fromSeed(
    seedColor: AppColors.accent,
    primary: AppColors.accent,
    surface: AppColors.card,
    brightness: Brightness.light,
  );

  final base = ThemeData(
    colorScheme: scheme,
    scaffoldBackgroundColor: AppColors.bg,
    useMaterial3: true,
    fontFamily: 'Roboto',
  );

  return base.copyWith(
    textTheme: base.textTheme.apply(
      bodyColor: AppColors.text,
      displayColor: AppColors.text,
    ),
    appBarTheme: const AppBarTheme(
      backgroundColor: AppColors.bg,
      foregroundColor: AppColors.text,
      elevation: 0,
      centerTitle: false,
    ),
    cardTheme: CardThemeData(
      color: AppColors.card,
      elevation: 0,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(AppRadii.card),
        side: const BorderSide(color: AppColors.border),
      ),
    ),
    filledButtonTheme: FilledButtonThemeData(
      style: FilledButton.styleFrom(
        backgroundColor: AppColors.accent,
        foregroundColor: Colors.white,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(AppRadii.field),
        ),
        padding: const EdgeInsets.symmetric(vertical: 14),
      ),
    ),
  );
}
