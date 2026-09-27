import 'package:flutter/painting.dart' show HSLColor;

import '../data/db/database.dart';

/// Distinct hues for new categories (6-hex, no '#'). Seed colors first so they
/// are skipped (already used); the rest keep pie-chart slices easy to tell apart.
const _palette = [
  'E08A5B', '6FA86A', 'C07FA6', '5B8DB8', 'D9A24E', '7E88C3',
  'D0655F', '4FA3A0', '9C7A56', 'A3B84F', '8D6BC4', 'E07FA0',
];

String _norm(String s) => s.trim().toLowerCase();

/// First palette color not taken by any category (archived included, so
/// unarchiving can't clash); past the palette, golden-angle hues.
String uniqueColor(Set<String> used) {
  for (final c in _palette) {
    if (!used.contains(c)) return c;
  }
  for (var n = 0;; n++) {
    final argb = HSLColor.fromAHSL(1, (n * 137.508) % 360, 0.45, 0.55).toColor().toARGB32();
    final hex = (argb & 0xFFFFFF).toRadixString(16).padLeft(6, '0').toUpperCase();
    if (!used.contains(hex)) return hex;
  }
}

/// Finds an existing category whose name matches [rawName] (trim + case
/// insensitive) and returns its id; otherwise creates one and returns the new id.
/// Keeps categories unique — the AI never spawns duplicates.
Future<String> matchOrCreateCategory(AppDatabase db, String rawName) async {
  final hit = _find(await db.getCategories(), rawName);
  if (hit != null) return hit;
  final used = {for (final c in await db.getAllCategories()) c.colorHex.toUpperCase()};
  return db.insertCategory(
      CategoriesCompanion.insert(name: rawName.trim(), colorHex: uniqueColor(used)));
}

/// Like [matchOrCreateCategory], but a family member (not admin) can't create:
/// an unknown name lands in Boshqa with [pending] set, and the server files a
/// request to the admin when the expense syncs.
Future<({String id, String? pending})> resolveCategory(
  AppDatabase db,
  String rawName, {
  required bool canCreate,
}) async {
  final boshqa = canCreate ? null : _find(await db.getAllCategories(), 'Boshqa');
  // ponytail: no Boshqa yet (family not synced) — create locally; the next
  // sync's adoptLocal turns it into Boshqa + request.
  if (boshqa == null) return (id: await matchOrCreateCategory(db, rawName), pending: null);
  final hit = _find(await db.getCategories(), rawName);
  return hit != null ? (id: hit, pending: null) : (id: boshqa, pending: rawName.trim());
}

String? _find(List<Category> cats, String name) =>
    cats.where((c) => _norm(c.name) == _norm(name)).firstOrNull?.id;
