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
  // 'sync.' prefix: wiped with the other sync keys when the account changes.
  static const _kProfileDirty = 'sync.profileDirty';

  Settings load() => Settings(
    monthlyBudget: _prefs.getInt(_kBudget) ?? 0,
    sttLocale: _prefs.getString(_kLocale) ?? 'uz_UZ',
    defaultPrivate: _prefs.getBool(_kPrivate) ?? false,
  );

  // Budget and private default also live on the server profile (the family
  // contribution needs the budget), so edits wait there for the next sync.
  Future<void> setBudget(int v) async {
    await _prefs.setInt(_kBudget, v);
    await _prefs.setBool(_kProfileDirty, true);
  }

  Future<void> setDefaultPrivate(bool v) async {
    await _prefs.setBool(_kPrivate, v);
    await _prefs.setBool(_kProfileDirty, true);
  }

  Future<void> setLocale(String v) => _prefs.setString(_kLocale, v);

  bool get profileDirty => _prefs.getBool(_kProfileDirty) ?? false;
  Future<void> markProfileClean() => _prefs.remove(_kProfileDirty);

  /// Server values pulled by sync; not marked for push.
  Future<void> applyProfile({required int budget, required bool defaultPrivate}) async {
    await _prefs.setInt(_kBudget, budget);
    await _prefs.setBool(_kPrivate, defaultPrivate);
  }

  /// Last Add mode the user picked (enum name), so Add reopens in it.
  String? get lastAddMode => _prefs.getString(_kAddMode);
  Future<void> setLastAddMode(String v) => _prefs.setString(_kAddMode, v);
}
