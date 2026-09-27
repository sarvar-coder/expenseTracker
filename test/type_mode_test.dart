import 'package:drift/native.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:supabase_flutter/supabase_flutter.dart' show FunctionsClient;
import 'package:shared_preferences/shared_preferences.dart';

import 'package:expense_tracker/data/db/database.dart';
import 'package:expense_tracker/data/db/tables.dart';
import 'package:expense_tracker/features/add/add_screen.dart';
import 'package:expense_tracker/providers/providers.dart';
import 'package:expense_tracker/services/ai_parser.dart';

/// Fake parser: no network, returns a canned result. `parse` is the seam.
class _FakeParser extends AiParser {
  _FakeParser(this.result) : super(FunctionsClient('http://localhost', {}));
  final ParsedExpense? result;
  @override
  Future<ParsedExpense?> parse(String rawInput, {List<String> categories = const []}) async => result;
}

void main() {
  testWidgets('Type mode parses and saves right away with source=typed', (tester) async {
    final db = AppDatabase.forTesting(NativeDatabase.memory());
    addTearDown(db.close);
    final food = (await db.getCategories()).firstWhere((c) => c.name == 'Food & dining');

    // AddScreen reads/writes lastAddMode → needs real prefs.
    SharedPreferences.setMockInitialValues({});
    final prefs = await SharedPreferences.getInstance();

    await tester.pumpWidget(ProviderScope(
      overrides: [
        sharedPrefsProvider.overrideWithValue(prefs),
        databaseProvider.overrideWithValue(db),
        // Closing streams: no drift stream timers pending at teardown.
        categoriesProvider.overrideWith((ref) => Stream.value(const <Category>[])),
        expensesProvider.overrideWith((ref) => Stream.value(const <Expense>[])),
        aiParserProvider.overrideWithValue(_FakeParser(ParsedExpense(item: 'Coffee', amount: 45000, category: 'Food & dining', date: DateTime(2026, 9, 26)))),
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
      await tester.tap(find.text('AI bilan qo\'shish'));
      await Future<void>.delayed(const Duration(milliseconds: 300));
    });
    await tester.pump();

    // Saved straight away, no preview step; snackbar offers undo.
    expect(find.text('Qo\'shildi: Coffee — 45 000 UZS'), findsOneWidget);

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
    expect(rows.first.date, DateTime(2026, 9, 26)); // AI date, not now
    expect(cats.length, 5); // no new category created
  });
}
