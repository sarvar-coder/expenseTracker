import 'package:drift/native.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:expense_tracker/data/db/database.dart';
import 'package:expense_tracker/services/category_matcher.dart';

void main() {
  late AppDatabase db;

  setUp(() => db = AppDatabase.forTesting(NativeDatabase.memory()));
  tearDown(() => db.close());

  test('reuses seeded category ignoring case and surrounding whitespace', () async {
    final cats = await db.getCategories();
    final food = cats.firstWhere((c) => c.name == 'Food & dining');
    final transport = cats.firstWhere((c) => c.name == 'Transport');

    expect(await matchOrCreateCategory(db, 'food & dining'), food.id);
    expect(await matchOrCreateCategory(db, '  Transport '), transport.id);
    expect((await db.getCategories()).length, cats.length); // no new rows
  });

  test('creates a new category once, then reuses it (no duplicates)', () async {
    final before = (await db.getCategories()).length;

    final id1 = await matchOrCreateCategory(db, 'Coffee shops');
    expect((await db.getCategories()).length, before + 1);

    final id2 = await matchOrCreateCategory(db, 'coffee shops '); // same, normalized
    expect(id2, id1);
    expect((await db.getCategories()).length, before + 1); // still just one new

    final created = (await db.getCategories()).firstWhere((c) => c.id == id1);
    expect(created.name, 'Coffee shops'); // stores the original display name
    expect(RegExp(r'^[0-9A-Fa-f]{6}$').hasMatch(created.colorHex), isTrue);
  });

  test('every category gets its own color, beyond the palette too', () async {
    for (var i = 0; i < 20; i++) {
      await matchOrCreateCategory(db, 'Cat $i');
    }
    final cats = await db.getAllCategories();
    expect(cats.map((c) => c.colorHex).toSet().length, cats.length);
  });

  test('family member: known name matches, unknown goes to Boshqa as pending', () async {
    final food = (await db.getCategories()).firstWhere((c) => c.name == 'Food & dining');
    final boshqa = await db.insertCategory(CategoriesCompanion.insert(name: 'Boshqa', colorHex: '9E9E9E'));
    final before = (await db.getAllCategories()).length;

    expect(await resolveCategory(db, 'food & dining', canCreate: false), (id: food.id, pending: null));
    expect(await resolveCategory(db, ' Gym ', canCreate: false), (id: boshqa, pending: 'Gym'));
    expect((await db.getAllCategories()).length, before, reason: 'nothing created');
  });

  test('can create (admin / no family): creates as before', () async {
    final r = await resolveCategory(db, 'Gym', canCreate: true);
    expect(r.pending, isNull);
    expect((await db.getCategories()).any((c) => c.id == r.id && c.name == 'Gym'), isTrue);
  });
}
