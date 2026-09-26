import 'package:drift/native.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:expense_tracker/data/db/database.dart';
import 'package:expense_tracker/data/db/tables.dart';
import 'package:expense_tracker/features/activity/activity_screen.dart';
import 'package:expense_tracker/providers/providers.dart';

void main() {
  testWidgets('chips + search filter rows; day total; empty state', (tester) async {
    // Build real rows once, then feed them as closing streams (no drift timers).
    final db = AppDatabase.forTesting(NativeDatabase.memory());
    late List<Category> cats;
    late List<Expense> expenses;
    await tester.runAsync(() async {
      cats = await db.getCategories();
      final food = cats.firstWhere((c) => c.name == 'Food & dining');
      final transport = cats.firstWhere((c) => c.name == 'Transport');
      for (final (d, c) in [('Coffee', food), ('Taxi', transport)]) {
        await db.insertExpense(ExpensesCompanion.insert(
          description: d,
          amount: 10000,
          categoryId: c.id,
          date: DateTime.now(),
          source: ExpenseSource.manual,
        ));
      }
      expenses = await db.getExpenses();
      await db.close();
    });

    await tester.pumpWidget(ProviderScope(
      overrides: [
        categoriesProvider.overrideWith((ref) => Stream.value(cats)),
        expensesProvider.overrideWith((ref) => Stream.value(expenses)),
      ],
      child: const MaterialApp(home: Scaffold(body: ActivityScreen())),
    ));
    await tester.pump();

    expect(find.text('Coffee'), findsOneWidget);
    expect(find.text('Taxi'), findsOneWidget);
    expect(find.text('Bugun'), findsOneWidget);
    expect(find.text('20 000'), findsOneWidget); // day total
    expect(tester.getSize(find.widgetWithText(ChoiceChip, 'Hammasi')).height,
        greaterThanOrEqualTo(48));

    final chipRow = find.descendant(
        of: find.byWidgetPredicate(
            (w) => w is ListView && w.scrollDirection == Axis.horizontal),
        matching: find.byType(Scrollable));
    await tester.scrollUntilVisible(
        find.widgetWithText(ChoiceChip, 'Transport'), 100,
        scrollable: chipRow);
    await tester.ensureVisible(find.widgetWithText(ChoiceChip, 'Transport'));
    await tester.pump();
    await tester.tap(find.widgetWithText(ChoiceChip, 'Transport'));
    await tester.pump();
    expect(find.text('Coffee'), findsNothing);
    expect(find.text('Taxi'), findsOneWidget);

    await tester.scrollUntilVisible(
        find.widgetWithText(ChoiceChip, 'Hammasi'), -100,
        scrollable: chipRow);
    await tester.ensureVisible(find.widgetWithText(ChoiceChip, 'Hammasi'));
    await tester.pump();
    await tester.tap(find.widgetWithText(ChoiceChip, 'Hammasi'));
    await tester.enterText(find.byType(TextField), 'cof');
    await tester.pump();
    expect(find.text('Coffee'), findsOneWidget);
    expect(find.text('Taxi'), findsNothing);

    await tester.enterText(find.byType(TextField), 'zzz');
    await tester.pump();
    expect(find.text('Mos keladigani yo\'q'), findsOneWidget);
  });
}
