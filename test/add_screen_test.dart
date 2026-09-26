import 'package:drift/native.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:expense_tracker/data/db/database.dart';
import 'package:expense_tracker/data/db/tables.dart';
import 'package:expense_tracker/features/add/add_screen.dart';
import 'package:expense_tracker/providers/providers.dart';

void main() {
  late AppDatabase db;
  late SharedPreferences prefs;

  setUp(() async {
    db = AppDatabase.forTesting(NativeDatabase.memory());
    SharedPreferences.setMockInitialValues({});
    prefs = await SharedPreferences.getInstance();
  });
  tearDown(() => db.close());

  Widget app(Widget home) => ProviderScope(
        overrides: [
          sharedPrefsProvider.overrideWithValue(prefs),
          databaseProvider.overrideWithValue(db),
          categoriesProvider.overrideWith((ref) => Stream.value(const <Category>[])),
          expensesProvider.overrideWith((ref) => Stream.value(const <Expense>[])),
        ],
        child: MaterialApp(home: home),
      );

  bool selected(WidgetTester tester, String label) => tester
      .widget<SegmentedButton<AddMode>>(find.byType(SegmentedButton<AddMode>))
      .selected
      .contains({'Yozish': AddMode.type, 'Aytish': AddMode.speak, 'Qo\'lda': AddMode.manual}[label]);

  testWidgets('opens in Yozish, then reopens in the last picked mode', (tester) async {
    await tester.pumpWidget(app(const AddScreen()));
    expect(selected(tester, 'Yozish'), isTrue);
    expect(find.byIcon(Icons.close), findsNothing, reason: 'no extra X');

    await tester.tap(find.text('Aytish'));
    await tester.pump();
    expect(find.byIcon(Icons.mic), findsOneWidget, reason: 'one mic only');
    expect(prefs.getString('lastAddMode'), 'speak');

    await tester.pumpWidget(app(const SizedBox()));
    await tester.pumpWidget(app(const AddScreen()));
    expect(selected(tester, 'Aytish'), isTrue);
  });

  testWidgets('edit screen deletes after confirm', (tester) async {
    final food = (await db.getCategories()).first;
    await db.insertExpense(ExpensesCompanion.insert(
      description: 'Coffee',
      amount: 45000,
      categoryId: food.id,
      date: DateTime(2026, 7, 1),
      source: ExpenseSource.manual,
    ));
    final expense = (await db.getExpenses()).single;

    await tester.pumpWidget(app(AddScreen(editing: expense)));
    expect(selected(tester, 'Qo\'lda'), isTrue);

    await tester.tap(find.text('O\'chirish'));
    await tester.pumpAndSettle();
    expect(find.text('Xarajat o\'chirilsinmi?'), findsOneWidget);

    await tester.runAsync(() async {
      await tester.tap(find.text('O\'chirish').last);
      await Future<void>.delayed(const Duration(milliseconds: 200));
    });
    await tester.pump();
    expect(await tester.runAsync(db.getExpenses), isEmpty);
  });
}
