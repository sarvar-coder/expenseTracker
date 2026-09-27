import 'package:flutter_test/flutter_test.dart';

import 'package:expense_tracker/services/ai_parser.dart';

void main() {
  final today = DateTime(2026, 9, 27);

  test('parses clean JSON', () {
    final p = pj(today, '{"item":"Coffee","amount":45000,"category":"Food & dining"}');
    expect(p, isNotNull);
    expect(p!.item, 'Coffee');
    expect(p.amount, 45000);
    expect(p.category, 'Food & dining');
  });

  test('parses markdown-fenced JSON (Gemini often wraps)', () {
    final p = pj(today, '```json\n{"item":"Bus","amount":2000,"category":"Transport"}\n```');
    expect(p?.amount, 2000);
    expect(p?.category, 'Transport');
  });

  test('accepts amount as a string with separators', () {
    final p = pj(today, '{"item":"Lunch","amount":"128,000","category":"Food"}');
    expect(p?.amount, 128000);
  });

  test('returns null on missing amount, non-numeric amount, or malformed JSON', () {
    expect(pj(today, '{"item":"x","category":"Food"}'), isNull);
    expect(pj(today, '{"item":"x","amount":"free","category":"Food"}'), isNull);
    expect(pj(today, '{"item":"","amount":100,"category":"Food"}'), isNull);
    expect(pj(today, 'not json at all'), isNull);
  });

  test('reads date; missing, garbage or future date becomes today', () {
    expect(pj(today, '{"item":"qurt","amount":10000,"category":"Oziq-ovqat","date":"2026-09-26"}')?.date,
        DateTime(2026, 9, 26));
    expect(pj(today, '{"item":"qurt","amount":10000,"category":"Oziq-ovqat"}')?.date, today);
    expect(pj(today, '{"item":"qurt","amount":10000,"category":"Oziq-ovqat","date":"kecha"}')?.date, today);
    expect(pj(today, '{"item":"qurt","amount":10000,"category":"Oziq-ovqat","date":"2026-10-01"}')?.date, today);
  });

  test('accepts space-grouped amount string', () {
    expect(pj(today, '{"item":"qurt","amount":"10 000","category":"Oziq-ovqat"}')?.amount, 10000);
  });
}

ParsedExpense? pj(DateTime today, String text) => parseGeminiJson(text, today: today);
