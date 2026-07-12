import 'package:drift/native.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:google_generative_ai/google_generative_ai.dart';

import 'package:expense_tracker/data/db/database.dart';
import 'package:expense_tracker/data/db/tables.dart';
import 'package:expense_tracker/features/add/add_screen.dart';
import 'package:expense_tracker/providers/providers.dart';
import 'package:expense_tracker/services/ai_parser.dart';

/// Fake parser: no network, returns a canned result. `parse` is the seam.
class _FakeParser extends AiParser {
  _FakeParser(this.result) : super(GenerativeModel(model: 'x', apiKey: 'x'));
  final ParsedExpense? result;
  @override
  Future<ParsedExpense?> parse(String rawInput) async => result;
}

void main() {
  testWidgets('Type mode parses, previews, and saves with source=typed', (tester) async {
    final db = AppDatabase.forTesting(NativeDatabase.memory());
    addTearDown(db.close);
    final food = (await db.getCategories()).firstWhere((c) => c.name == 'Food & dining');

    await tester.pumpWidget(ProviderScope(
      overrides: [
        databaseProvider.overrideWithValue(db),
        // Closing streams: no drift stream timers pending at teardown.
        categoriesProvider.overrideWith((ref) => Stream.value(const <Category>[])),
        expensesProvider.overrideWith((ref) => Stream.value(const <Expense>[])),
        aiParserProvider.overrideWithValue(() async =>
            _FakeParser(const ParsedExpense(item: 'Coffee', amount: 45000, category: 'Food & dining'))),
      ],
      child: const MaterialApp(home: AddScreen()),
    ));

    // Switch to Type, enter text.
    await tester.tap(find.text('Yozish'));
    await tester.pump();
    await tester.enterText(find.byType(TextField), 'coffee 45000');
    await tester.pump();

    // Parse. runAsync lets the real drift/async work complete; the busy spinner
    // rules out pumpAndSettle (it would spin forever).
    await tester.runAsync(() async {
      await tester.tap(find.text('AI bilan tahlil qilish'));
      await Future<void>.delayed(const Duration(milliseconds: 300));
    });
    await tester.pump(); // rebuild with the preview

    expect(find.text('Coffee'), findsOneWidget);
    expect(find.text('45 000'), findsOneWidget);

    // Save persists as a typed expense against the reused seeded category.
    await tester.runAsync(() async {
      await tester.tap(find.text('Saqlash'));
      await Future<void>.delayed(const Duration(milliseconds: 300));
    });

    // Drift reads via runAsync (real clock) — streams need a timer the fake
    // testWidgets clock won't advance.
    final rows = (await tester.runAsync(() => db.watchExpenses().first))!;
    final cats = (await tester.runAsync(() => db.getCategories()))!;
    expect(rows.length, 1);
    expect(rows.first.source, ExpenseSource.typed);
    expect(rows.first.description, 'Coffee');
    expect(rows.first.amount, 45000);
    expect(rows.first.categoryId, food.id); // matcher reused, no duplicate
    expect(rows.first.rawInput, 'coffee 45000');
    expect(cats.length, 5); // no new category created
  });
}
