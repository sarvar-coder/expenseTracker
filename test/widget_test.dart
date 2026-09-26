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
    expect(find.text('Asosiy'), findsWidgets); // nav label
    expect(find.text('Tarix'), findsOneWidget);
    expect(find.text('Sozlamalar'), findsNothing); // moved to Home header

    // Nav targets meet the 48dp minimum.
    for (final label in ['Asosiy', 'Tarix', 'Tahlil']) {
      final size = tester.getSize(find
          .ancestor(of: find.text(label).last, matching: find.byType(InkWell))
          .first);
      expect(size.width, greaterThanOrEqualTo(48));
      expect(size.height, greaterThanOrEqualTo(48));
    }

    // Home header: gear replaces the dead bell; no duplicate "Hammasi" link.
    expect(find.byTooltip('Sozlamalar'), findsOneWidget);
    expect(find.byIcon(Icons.notifications_none), findsNothing);
    expect(find.text('Hammasi'), findsNothing);

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
