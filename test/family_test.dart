import 'package:drift/drift.dart' show Value;
import 'package:drift/native.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:expense_tracker/data/db/database.dart';
import 'package:expense_tracker/data/db/tables.dart';
import 'package:expense_tracker/features/common/widgets.dart';
import 'package:expense_tracker/services/family_service.dart';
import 'package:expense_tracker/services/sync_service.dart';

void main() {
  test('hideForeignCategories hides only other families\' categories', () async {
    final db = AppDatabase.forTesting(NativeDatabase.memory());
    addTearDown(db.close);
    Future<void> add(String name, {String? family, String? owner}) =>
        db.insertCategory(CategoriesCompanion.insert(
          name: name,
          colorHex: 'AAAAAA',
          familyId: Value(family),
          ownerId: Value(owner),
        ));
    await add('Old', family: 'old');
    await add('Cur', family: 'cur');
    await add('Mine', owner: 'u1');

    await hideForeignCategories(db, 'cur');
    var names = (await db.getCategories()).map((c) => c.name).toSet();
    expect(names.containsAll({'Cur', 'Mine'}), isTrue);
    expect(names.contains('Old'), isFalse);

    await hideForeignCategories(db, null); // left every family
    names = (await db.getCategories()).map((c) => c.name).toSet();
    expect(names.contains('Cur'), isFalse);
    expect(names.contains('Mine'), isTrue);
    final hidden = (await db.select(db.categories).get()).where((c) => c.deletedAt != null);
    expect(hidden.every((c) => !c.dirty), isTrue, reason: 'never pushed');
  });

  test('familyErrorText maps server codes', () {
    expect(familyErrorText('transfer_admin_first'), contains('adminlik'));
    expect(familyErrorText('already_invited'), contains('taklif qilingan'));
    expect(familyErrorText('boom'), contains('Qayta'));
  });

  testWidgets('frozen expense tile cannot be opened or swiped away', (tester) async {
    final db = AppDatabase.forTesting(NativeDatabase.memory());
    late Expense frozen;
    await tester.runAsync(() async {
      final cat = (await db.getCategories()).first;
      await db.insertExpense(ExpensesCompanion.insert(
        description: 'Non',
        amount: 5000,
        categoryId: cat.id,
        date: DateTime.now(),
        source: ExpenseSource.manual,
        frozen: const Value(true),
      ));
      frozen = (await db.getExpenses()).single;
      await db.close();
    });

    await tester.pumpWidget(ProviderScope(
      child: MaterialApp(
        home: Scaffold(body: ExpenseTile(expense: frozen, category: null)),
      ),
    ));

    expect(find.byIcon(Icons.lock_outline), findsOneWidget);
    expect(tester.widget<Dismissible>(find.byType(Dismissible)).direction,
        DismissDirection.none);
    expect(tester.widget<InkWell>(find.byType(InkWell).first).onTap, isNull);
  });
}
