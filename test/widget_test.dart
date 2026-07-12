import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:expense_tracker/data/db/database.dart';
import 'package:expense_tracker/main.dart';
import 'package:expense_tracker/providers/providers.dart';

void main() {
  testWidgets('shell shows tabs, switches, and FAB opens Add', (tester) async {
    SharedPreferences.setMockInitialValues({});
    final prefs = await SharedPreferences.getInstance();

    await tester.pumpWidget(ProviderScope(
      overrides: [
        sharedPrefsProvider.overrideWithValue(prefs),
        categoriesProvider.overrideWith((ref) => Stream.value(const <Category>[])),
        expensesProvider.overrideWith((ref) => Stream.value(const <Expense>[])),
      ],
      child: const ExpenseTrackerApp(),
    ));

    // Nav labels present; Home tab active by default.
    expect(find.text('Sozlamalar'), findsOneWidget); // nav label only
    expect(find.text('Asosiy'), findsWidgets); // nav label

    // Switch to Insights tab.
    await tester.tap(find.text('Tahlil'));
    await tester.pumpAndSettle();
    expect(find.text('Tahlil'), findsNWidgets(2)); // body + nav label

    // Center FAB pushes the Add screen.
    await tester.tap(find.byIcon(Icons.add));
    await tester.pumpAndSettle();
    expect(find.text('Xarajat qo\'shish'), findsWidgets);
  });
}
