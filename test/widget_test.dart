import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'package:expense_tracker/main.dart';

void main() {
  testWidgets('shell shows tabs, switches, and FAB opens Add', (tester) async {
    await tester.pumpWidget(const ProviderScope(child: ExpenseTrackerApp()));

    // Nav labels present; Home tab active by default.
    expect(find.text('Settings'), findsOneWidget); // nav label only
    expect(find.text('Home'), findsWidgets); // screen body + nav label

    // Switch to Insights tab.
    await tester.tap(find.text('Insights'));
    await tester.pumpAndSettle();
    expect(find.text('Insights'), findsNWidgets(2)); // body + nav label

    // Center FAB pushes the Add screen.
    await tester.tap(find.byIcon(Icons.add));
    await tester.pumpAndSettle();
    expect(find.text('Add expense'), findsWidgets);
  });
}
