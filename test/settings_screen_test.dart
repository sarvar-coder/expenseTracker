import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:expense_tracker/data/db/database.dart';
import 'package:expense_tracker/main.dart';
import 'package:expense_tracker/providers/providers.dart';

void main() {
  testWidgets('Home gear opens Settings: no currency row, Uzbek labels', (tester) async {
    SharedPreferences.setMockInitialValues({});
    FlutterSecureStorage.setMockInitialValues({});
    final prefs = await SharedPreferences.getInstance();

    await tester.pumpWidget(ProviderScope(
      overrides: [
        sharedPrefsProvider.overrideWithValue(prefs),
        categoriesProvider.overrideWith((ref) => Stream.value(const <Category>[])),
        expensesProvider.overrideWith((ref) => Stream.value(const <Expense>[])),
      ],
      child: const ExpenseTrackerApp(),
    ));

    await tester.tap(find.byTooltip('Sozlamalar'));
    await tester.pumpAndSettle();

    expect(find.text('Sozlamalar'), findsOneWidget); // app bar title
    expect(find.text('Valyuta'), findsNothing);
    expect(find.text('Ovoz tili'), findsOneWidget);
    expect(find.text('Voice language'), findsNothing);
    expect(find.byType(BackButton), findsOneWidget);
  });
}
