import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// Plain settings held in shared_preferences (the Gemini key lives in secure
/// storage and is read on demand, not part of this immutable snapshot).
class Settings {
  final String currencyCode;
  final int monthlyBudget;
  final String sttLocale;

  const Settings({
    required this.currencyCode,
    required this.monthlyBudget,
    required this.sttLocale,
  });

  Settings copyWith({String? currencyCode, int? monthlyBudget, String? sttLocale}) =>
      Settings(
        currencyCode: currencyCode ?? this.currencyCode,
        monthlyBudget: monthlyBudget ?? this.monthlyBudget,
        sttLocale: sttLocale ?? this.sttLocale,
      );
}

class SettingsStore {
  SettingsStore(this._prefs, this._secure);

  final SharedPreferences _prefs;
  final FlutterSecureStorage _secure;

  static const _kCurrency = 'currencyCode';
  static const _kBudget = 'monthlyBudget';
  static const _kLocale = 'sttLocale';
  static const _kApiKey = 'geminiApiKey';

  Settings load() => Settings(
        currencyCode: _prefs.getString(_kCurrency) ?? 'UZS',
        monthlyBudget: _prefs.getInt(_kBudget) ?? 0,
        sttLocale: _prefs.getString(_kLocale) ?? 'uz_UZ',
      );

  Future<void> setCurrency(String v) => _prefs.setString(_kCurrency, v);
  Future<void> setBudget(int v) => _prefs.setInt(_kBudget, v);
  Future<void> setLocale(String v) => _prefs.setString(_kLocale, v);

  // API key — secure storage, read on demand.
  Future<String?> getApiKey() => _secure.read(key: _kApiKey);
  Future<void> setApiKey(String v) => _secure.write(key: _kApiKey, value: v);
  Future<bool> hasApiKey() async => (await getApiKey())?.isNotEmpty ?? false;
}
