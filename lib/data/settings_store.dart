import 'package:shared_preferences/shared_preferences.dart';

/// Plain settings held in shared_preferences.
class Settings {
  final int monthlyBudget;
  final String sttLocale;

  const Settings({required this.monthlyBudget, required this.sttLocale});

  Settings copyWith({int? monthlyBudget, String? sttLocale}) => Settings(
    monthlyBudget: monthlyBudget ?? this.monthlyBudget,
    sttLocale: sttLocale ?? this.sttLocale,
  );
}

class SettingsStore {
  SettingsStore(this._prefs);

  final SharedPreferences _prefs;

  static const _kBudget = 'monthlyBudget';
  static const _kLocale = 'sttLocale';
  static const _kAddMode = 'lastAddMode';

  Settings load() => Settings(
    monthlyBudget: _prefs.getInt(_kBudget) ?? 0,
    sttLocale: _prefs.getString(_kLocale) ?? 'uz_UZ',
  );

  Future<void> setBudget(int v) => _prefs.setInt(_kBudget, v);
  Future<void> setLocale(String v) => _prefs.setString(_kLocale, v);

  /// Last Add mode the user picked (enum name), so Add reopens in it.
  String? get lastAddMode => _prefs.getString(_kAddMode);
  Future<void> setLastAddMode(String v) => _prefs.setString(_kAddMode, v);
}
