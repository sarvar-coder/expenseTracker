import 'package:drift/drift.dart' show Value;

import '../data/db/database.dart';

/// Palette for auto-created categories (6-hex, no '#'), cycled by category count.
/// Mirrors the design tokens' spare hues; icon defaults to the generic 'category'.
const _autoColors = ['E08A5B', '6FA86A', 'C07FA6', '5B8DB8', 'D9A24E', '7E88C3'];

String _norm(String s) => s.trim().toLowerCase();

/// Keyword -> iconKey (keys must exist in ui_utils.iconForKey's map). First
/// substring hit wins; unknown names fall back to the generic 'category'.
const _iconKeywords = <String, String>{
  'food': 'restaurant', 'restaurant': 'restaurant', 'dining': 'restaurant',
  'cafe': 'restaurant', 'coffee': 'restaurant', 'lunch': 'restaurant',
  'grocery': 'shopping_cart', 'groceries': 'shopping_cart', 'market': 'shopping_cart',
  'shop': 'shopping_bag', 'clothes': 'shopping_bag', 'clothing': 'shopping_bag',
  'transport': 'directions_car', 'taxi': 'directions_car', 'car': 'directions_car',
  'fuel': 'directions_car', 'gas': 'directions_car', 'bus': 'directions_car',
  'bill': 'receipt_long', 'utilit': 'receipt_long', 'rent': 'receipt_long',
  'movie': 'movie', 'cinema': 'movie', 'entertain': 'movie',
  'health': 'medical_services', 'pharmacy': 'medical_services', 'medical': 'medical_services',
  'doctor': 'medical_services', 'gym': 'fitness_center', 'fitness': 'fitness_center',
  'sport': 'fitness_center', 'flight': 'flight', 'travel': 'flight', 'hotel': 'flight',
  'pet': 'pets', 'school': 'school', 'education': 'school', 'course': 'school',
};

String _guessIcon(String name) {
  final n = name.toLowerCase();
  for (final e in _iconKeywords.entries) {
    if (n.contains(e.key)) return e.value;
  }
  return 'category';
}

/// Finds an existing category whose name matches [rawName] (trim + case
/// insensitive) and returns its id; otherwise creates one and returns the new id.
/// Keeps categories unique — the AI never spawns duplicates.
Future<int> matchOrCreateCategory(AppDatabase db, String rawName) async {
  final name = rawName.trim();
  final target = _norm(rawName);
  final existing = await db.getCategories();
  for (final c in existing) {
    if (_norm(c.name) == target) return c.id;
  }
  final color = _autoColors[existing.length % _autoColors.length];
  return db.insertCategory(CategoriesCompanion.insert(
    name: name,
    colorHex: color,
    iconKey: Value(_guessIcon(name)),
  ));
}
