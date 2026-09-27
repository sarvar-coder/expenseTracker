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
  var cats = const <Category>[];

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
          categoriesProvider.overrideWith((ref) => Stream.value(cats)),
          expensesProvider.overrideWith((ref) => Stream.value(const <Expense>[])),
        ],
        child: MaterialApp(home: home),
      );

  // The Manual form (with Maxfiy) is taller than the default 800x600 surface.
  void tall(WidgetTester tester) {
    tester.view.physicalSize = const Size(800, 1200);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
  }

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
    tall(tester);
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

  testWidgets('Qo\'lda validates, then saves amount/desc/chip category/Kecha', (tester) async {
    tall(tester);
    cats = (await tester.runAsync(db.getCategories))!;
    await prefs.setString('lastAddMode', 'manual');
    await tester.pumpWidget(app(const AddScreen()));
    await tester.pump();

    await tester.ensureVisible(find.text('Saqlash'));
    await tester.pump();
    await tester.tap(find.text('Saqlash'));
    await tester.pump();
    expect(find.text('To\'g\'ri summa kiriting'), findsOneWidget);

    await tester.enterText(find.byType(TextField).first, '45000');
    await tester.enterText(find.byType(TextField).last, 'Coffee');
    await tester.tap(find.widgetWithText(ChoiceChip, cats[1].name));
    await tester.tap(find.widgetWithText(ChoiceChip, 'Kecha'));
    await tester.pump();
    expect(tester.getSize(find.widgetWithText(ChoiceChip, 'Kecha')).height,
        greaterThanOrEqualTo(48));

    await tester.ensureVisible(find.text('Saqlash'));
    await tester.pump();
    await tester.runAsync(() async {
      await tester.tap(find.text('Saqlash'));
      await Future<void>.delayed(const Duration(milliseconds: 200));
    });
    final row = (await tester.runAsync(db.getExpenses))!.single;
    expect(row.amount, 45000);
    expect(row.description, 'Coffee');
    expect(row.categoryId, cats[1].id);
    expect(row.source, ExpenseSource.manual);
    final yesterday = DateTime.now().subtract(const Duration(days: 1));
    expect(DateUtils.isSameDay(row.date, yesterday), isTrue);
  });

  testWidgets('Maxfiy starts from the Settings default and is saved', (tester) async {
    tall(tester);
    cats = (await tester.runAsync(db.getCategories))!;
    await prefs.setString('lastAddMode', 'manual');
    await prefs.setBool('defaultPrivate', true);
    await tester.pumpWidget(app(const AddScreen()));
    await tester.pump();

    Future<Expense> save(String desc) async {
      await tester.enterText(find.byType(TextField).first, '1000');
      await tester.enterText(find.byType(TextField).last, desc);
      await tester.tap(find.widgetWithText(ChoiceChip, cats[0].name));
      await tester.ensureVisible(find.text('Saqlash'));
      await tester.pump();
      await tester.runAsync(() async {
        await tester.tap(find.text('Saqlash'));
        await Future<void>.delayed(const Duration(milliseconds: 200));
      });
      return (await tester.runAsync(db.getExpenses))!
          .firstWhere((e) => e.description == desc);
    }

    bool switchOn() =>
        tester.widget<SwitchListTile>(find.widgetWithText(SwitchListTile, 'Maxfiy')).value;
    expect(switchOn(), isTrue);
    expect((await save('Secret')).isPrivate, isTrue);

    // Editing keeps the row's own flag; flip it off and save.
    await tester.pumpWidget(app(const SizedBox()));
    final secret = (await tester.runAsync(db.getExpenses))!.single;
    await tester.pumpWidget(app(AddScreen(editing: secret)));
    await tester.pump();
    expect(switchOn(), isTrue);
    await tester.ensureVisible(find.text('Maxfiy'));
    await tester.tap(find.text('Maxfiy'));
    await tester.pump();
    expect((await save('Secret')).isPrivate, isFalse);
  });
}
