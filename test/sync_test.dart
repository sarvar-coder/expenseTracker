import 'package:cloud_firestore/cloud_firestore.dart' show Timestamp;
import 'package:drift/drift.dart' show Value;
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
        'ownerId': 'u1',
        'familyId': null,
        'name': name,
        'iconKey': 'category',
        'colorHex': 'E08A5B',
        'isArchived': false,
        'updatedAt': Timestamp.fromDate(DateTime.utc(2026, 9)),
        'deletedAt': null,
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
      {...serverCategory('srv-boshqa', 'Boshqa'), 'ownerId': null, 'familyId': 'fam'},
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
          'ownerId': 'u1',
          'familyId': null,
          'categoryId': cat.id,
          'description': desc,
          'amount': 7000,
          'date': Timestamp.fromDate(DateTime.utc(2026, 9)),
          'source': 'manual',
          'rawInput': null,
          'isPrivate': false,
          'pendingCategory': null,
          'frozen': false,
          'createdAt': Timestamp.fromDate(DateTime.utc(2026, 9)),
          'updatedAt': Timestamp.fromDate(updated),
          'deletedAt': null,
        };

    await applyExpenses(db, [row('stale', local.updatedAt.subtract(const Duration(hours: 1)))]);
    expect((await db.getExpenses()).single.description, 'Non');

    await applyExpenses(db, [row('server', local.updatedAt.add(const Duration(hours: 1)))]);
    final e = (await db.getExpenses()).single;
    expect((e.description, e.amount, e.dirty), ('server', 7000, false));
  });

  test('expenseDoc round-trips through applyExpenses; nameLower is normalized', () async {
    final cat = (await db.getCategories()).first;
    await addExpense(cat.id);
    final local = (await db.getExpenses()).single;
    final doc = expenseDoc(local);
    expect(doc['date'], isA<Timestamp>());
    expect(categoryDoc(cat.copyWith(name: ' Food & Dining '))['nameLower'], 'food & dining');

    await db.resetLocal();
    await applyExpenses(db, [{...doc, 'description': 'Qaytdi'}]);
    final back = (await db.getExpenses()).single;
    expect((back.id, back.date, back.amount, back.description, back.dirty),
        (local.id, local.date, local.amount, 'Qaytdi', false));
  });

  test('detachForeignExpenses: unpushed rows of a left family become personal', () async {
    final cat = (await db.getCategories()).first;
    final old = await addExpense(cat.id);
    final cur = await addExpense(cat.id);
    final frozen = await addExpense(cat.id);
    await (db.update(db.expenses)..where((e) => e.id.equals(old))).write(const ExpensesCompanion(familyId: Value('old')));
    await (db.update(db.expenses)..where((e) => e.id.equals(cur))).write(const ExpensesCompanion(familyId: Value('new')));
    await (db.update(db.expenses)..where((e) => e.id.equals(frozen)))
        .write(const ExpensesCompanion(familyId: Value('old'), frozen: Value(true)));

    await detachForeignExpenses(db, 'new');

    final fam = {for (final e in await db.getExpenses()) e.id: e.familyId};
    expect((fam[old], fam[cur], fam[frozen]), (null, 'new', 'old'));
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
