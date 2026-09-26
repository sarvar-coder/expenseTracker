import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:expense_tracker/data/db/database.dart';
import 'package:expense_tracker/features/settings/settings_screen.dart';
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

  testWidgets('Turkumlar splits active and archived', (tester) async {
    SharedPreferences.setMockInitialValues({});
    FlutterSecureStorage.setMockInitialValues({});
    final prefs = await SharedPreferences.getInstance();
    Category cat(int id, String name, {bool archived = false}) => Category(
      id: id,
      name: name,
      iconKey: 'x',
      colorHex: '5B8DB8',
      isArchived: archived,
    );

    await tester.pumpWidget(ProviderScope(
      overrides: [
        sharedPrefsProvider.overrideWithValue(prefs),
        allCategoriesProvider.overrideWith(
          (ref) => Stream.value([cat(1, 'Transport'), cat(2, 'Kitob', archived: true)]),
        ),
      ],
      child: const MaterialApp(home: SettingsScreen()),
    ));
    await tester.tap(find.text('Turkumlar'));
    await tester.pumpAndSettle();

    expect(find.text('Faol'), findsOneWidget);
    expect(find.text('Arxivlangan'), findsOneWidget);
    expect(find.byTooltip('Arxivlash'), findsOneWidget);
    expect(find.byTooltip('Arxivdan chiqarish'), findsOneWidget);
    expect(find.byTooltip('Turkum qo\'shish'), findsOneWidget);
  });
}
