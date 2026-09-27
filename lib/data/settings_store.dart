import 'package:shared_preferences/shared_preferences.dart';

/// Plain settings held in shared_preferences.
class Settings {
  final int monthlyBudget;
  final String sttLocale;

  /// New expenses start private (hidden from the family) when set.
  final bool defaultPrivate;

  const Settings({
    required this.monthlyBudget,
    required this.sttLocale,
    this.defaultPrivate = false,
  });

  Settings copyWith({
    int? monthlyBudget,
    String? sttLocale,
    bool? defaultPrivate,
  }) => Settings(
    monthlyBudget: monthlyBudget ?? this.monthlyBudget,
    sttLocale: sttLocale ?? this.sttLocale,
    defaultPrivate: defaultPrivate ?? this.defaultPrivate,
  );
}

class SettingsStore {
  SettingsStore(this._prefs);

  final SharedPreferences _prefs;

  static const _kBudget = 'monthlyBudget';
  static const _kLocale = 'sttLocale';
  static const _kAddMode = 'lastAddMode';
  static const _kPrivate = 'defaultPrivate';

  Settings load() => Settings(
    monthlyBudget: _prefs.getInt(_kBudget) ?? 0,
    sttLocale: _prefs.getString(_kLocale) ?? 'uz_UZ',
    defaultPrivate: _prefs.getBool(_kPrivate) ?? false,
  );

  Future<void> setBudget(int v) => _prefs.setInt(_kBudget, v);
  Future<void> setLocale(String v) => _prefs.setString(_kLocale, v);
  Future<void> setDefaultPrivate(bool v) => _prefs.setBool(_kPrivate, v);

  /// Last Add mode the user picked (enum name), so Add reopens in it.
  String? get lastAddMode => _prefs.getString(_kAddMode);
  Future<void> setLastAddMode(String v) => _prefs.setString(_kAddMode, v);
}
