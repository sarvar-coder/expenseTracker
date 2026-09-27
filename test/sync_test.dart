import 'package:drift/native.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:expense_tracker/data/db/database.dart';
import 'package:expense_tracker/data/db/tables.dart';
import 'package:expense_tracker/data/settings_store.dart';
import 'package:expense_tracker/services/sync_service.dart';

void main() {
  late AppDatabase db;

  setUp(() => db = AppDatabase.forTesting(NativeDatabase.memory()));
  tearDown(() => db.close());

  Future<String> addExpense(String categoryId) => db.insertExpense(
        ExpensesCompanion.insert(
          description: 'Non',
          amount: 5000,
          categoryId: categoryId,
          date: DateTime(2026, 9, 1),
          source: ExpenseSource.manual,
        ),
      );

  Map<String, dynamic> serverCategory(String id, String name) => {
        'id': id,
        'owner_id': 'u1',
        'family_id': null,
        'name': name,
        'icon_key': 'category',
        'color_hex': 'E08A5B',
        'is_archived': false,
        'updated_at': '2026-09-01T00:00:00Z',
        'deleted_at': null,
      };

  test('adoptLocal merges seeded twins into server categories and claims the rest',
      () async {
    final seededFood = (await db.getCategories()).firstWhere((c) => c.name == 'Food & dining');
    final expenseId = await addExpense(seededFood.id);
    await applyCategories(db, [serverCategory('srv-food', 'food & DINING ')]);

    await adoptLocal(db, 'u1', null);

    final cats = await db.getAllCategories();
    expect(cats.where((c) => c.name.toLowerCase().contains('food')).map((c) => c.id),
        ['srv-food'], reason: 'local twin merged away');
    expect(cats.every((c) => c.ownerId == 'u1'), isTrue);
    final e = (await db.getExpenses()).single;
    expect(e.id, expenseId);
    expect((e.categoryId, e.ownerId, e.dirty), ('srv-food', 'u1', true));
  });

  test('adoptLocal in a family gives new categories to the family', () async {
    await adoptLocal(db, 'u1', 'fam');
    final cats = await db.getAllCategories();
    expect(cats.every((c) => c.familyId == 'fam' && c.ownerId == null), isTrue);
  });

  test('adoptLocal as a family member turns unknown categories into requests', () async {
    final gym = await db.insertCategory(CategoriesCompanion.insert(name: 'Gym', colorHex: 'AAAAAA'));
    final expenseId = await addExpense(gym);
    await applyCategories(db, [
      {...serverCategory('srv-boshqa', 'Boshqa'), 'owner_id': null, 'family_id': 'fam'},
    ]);

    await adoptLocal(db, 'u1', 'fam', canCreate: false);

    final e = (await db.getExpenses()).firstWhere((e) => e.id == expenseId);
    expect((e.categoryId, e.pendingCategory, e.dirty), ('srv-boshqa', 'Gym', true));
    final left = (await db.getAllCategories()).map((c) => c.id);
    expect(left, ['srv-boshqa'], reason: 'member creates no categories');
  });

  test('applyExpenses: newer server row wins, unpushed newer local edit is kept',
      () async {
    final cat = (await db.getCategories()).first;
    final id = await addExpense(cat.id);
    final local = (await db.getExpenses()).single;
    Map<String, dynamic> row(String desc, DateTime updated) => {
          'id': id,
          'owner_id': 'u1',
          'family_id': null,
          'category_id': cat.id,
          'description': desc,
          'amount': 7000,
          'date': '2026-09-01T00:00:00Z',
          'source': 'manual',
          'raw_input': null,
          'is_private': false,
          'pending_category': null,
          'frozen': false,
          'created_at': '2026-09-01T00:00:00Z',
          'updated_at': updated.toUtc().toIso8601String(),
          'deleted_at': null,
        };

    await applyExpenses(db, [row('stale', local.updatedAt.subtract(const Duration(hours: 1)))]);
    expect((await db.getExpenses()).single.description, 'Non');

    await applyExpenses(db, [row('server', local.updatedAt.add(const Duration(hours: 1)))]);
    final e = (await db.getExpenses()).single;
    expect((e.description, e.amount, e.dirty), ('server', 7000, false));
  });

  test('resetLocal drops rows and reseeds defaults', () async {
    await addExpense((await db.getCategories()).first.id);
    await applyCategories(db, [serverCategory('srv-x', 'Kitoblar')]);
    await db.resetLocal();
    expect(await db.getExpenses(), isEmpty);
    expect((await db.getCategories()).length, 5);
  });

  test('shouldPushProfile: local edits and unsynced budgets win, else server', () {
    expect(shouldPushProfile(dirty: true, serverBudget: null, localBudget: 0), isTrue);
    expect(shouldPushProfile(dirty: false, serverBudget: 0, localBudget: 900000), isTrue);
    expect(shouldPushProfile(dirty: false, serverBudget: 500000, localBudget: 900000), isFalse);
    expect(shouldPushProfile(dirty: false, serverBudget: 0, localBudget: 0), isFalse);
  });

  test('settings edits mark the profile for push; pulled values do not', () async {
    SharedPreferences.setMockInitialValues({});
    final store = SettingsStore(await SharedPreferences.getInstance());
    await store.applyProfile(budget: 500000, defaultPrivate: true);
    expect(store.profileDirty, isFalse);
    expect(store.load().monthlyBudget, 500000);
    await store.setBudget(700000);
    expect(store.profileDirty, isTrue);
    await store.markProfileClean();
    await store.setDefaultPrivate(false);
    expect(store.profileDirty, isTrue);
  });
}
