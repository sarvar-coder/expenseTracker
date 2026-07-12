import 'package:drift/native.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:google_generative_ai/google_generative_ai.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:expense_tracker/data/db/database.dart';
import 'package:expense_tracker/data/db/tables.dart';
import 'package:expense_tracker/features/add/add_screen.dart';
import 'package:expense_tracker/providers/providers.dart';
import 'package:expense_tracker/services/ai_parser.dart';
import 'package:expense_tracker/services/speech_service.dart';

/// Fake parser: no network, returns a canned result. `parse` is the seam.
class _FakeParser extends AiParser {
  _FakeParser(this.result) : super(GenerativeModel(model: 'x', apiKey: 'x'));
  final ParsedExpense? result;
  @override
  Future<ParsedExpense?> parse(String rawInput) async => result;
}

/// Fake STT: `listen` immediately emits a canned transcript, no microphone.
class _FakeSpeech extends SpeechService {
  _FakeSpeech(this.transcript);
  final String transcript;
  @override
  Future<bool> init() async => true;
  @override
  Future<void> listen({required void Function(String) onResult, String? localeId}) async =>
      onResult(transcript);
  @override
  Future<void> stop() async {}
}

void main() {
  testWidgets('Speak mode transcribes, parses, and saves with source=voice', (tester) async {
    final db = AppDatabase.forTesting(NativeDatabase.memory());
    addTearDown(db.close);
    final food = (await db.getCategories()).firstWhere((c) => c.name == 'Food & dining');

    // Speak mode reads settingsProvider (sttLocale) → needs real prefs.
    SharedPreferences.setMockInitialValues({});
    final prefs = await SharedPreferences.getInstance();

    await tester.pumpWidget(ProviderScope(
      overrides: [
        sharedPrefsProvider.overrideWithValue(prefs),
        databaseProvider.overrideWithValue(db),
        categoriesProvider.overrideWith((ref) => Stream.value(const <Category>[])),
        expensesProvider.overrideWith((ref) => Stream.value(const <Expense>[])),
        speechServiceProvider.overrideWithValue(_FakeSpeech('coffee 45000')),
        aiParserProvider.overrideWithValue(() async =>
            _FakeParser(const ParsedExpense(item: 'Coffee', amount: 45000, category: 'Food & dining'))),
      ],
      child: const MaterialApp(home: AddScreen()),
    ));

    // Switch to Speak, tap the mic — the fake fills the field.
    await tester.tap(find.text('Aytish'));
    await tester.pump();
    await tester.runAsync(() async {
      await tester.tap(find.byIcon(Icons.mic));
      await Future<void>.delayed(const Duration(milliseconds: 50));
    });
    await tester.pump();

    // Parse, then Save.
    await tester.runAsync(() async {
      await tester.tap(find.text('AI bilan tahlil qilish'));
      await Future<void>.delayed(const Duration(milliseconds: 300));
    });
    await tester.pump();
    expect(find.text('Coffee'), findsOneWidget);
    expect(find.text('45 000'), findsOneWidget);

    await tester.runAsync(() async {
      await tester.tap(find.text('Saqlash'));
      await Future<void>.delayed(const Duration(milliseconds: 300));
    });

    final rows = (await tester.runAsync(() => db.watchExpenses().first))!;
    final cats = (await tester.runAsync(() => db.getCategories()))!;
    expect(rows.length, 1);
    expect(rows.first.source, ExpenseSource.voice);
    expect(rows.first.description, 'Coffee');
    expect(rows.first.amount, 45000);
    expect(rows.first.categoryId, food.id);
    expect(rows.first.rawInput, 'coffee 45000');
    expect(cats.length, 5); // no new category
  });
}
