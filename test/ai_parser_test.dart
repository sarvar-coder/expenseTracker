import 'package:flutter_test/flutter_test.dart';

import 'package:expense_tracker/services/ai_parser.dart';

void main() {
  test('parses clean JSON', () {
    final p = parseGeminiJson('{"item":"Coffee","amount":45000,"category":"Food & dining"}');
    expect(p, isNotNull);
    expect(p!.item, 'Coffee');
    expect(p.amount, 45000);
    expect(p.category, 'Food & dining');
  });

  test('parses markdown-fenced JSON (Gemini often wraps)', () {
    final p = parseGeminiJson('```json\n{"item":"Bus","amount":2000,"category":"Transport"}\n```');
    expect(p?.amount, 2000);
    expect(p?.category, 'Transport');
  });

  test('accepts amount as a string with separators', () {
    final p = parseGeminiJson('{"item":"Lunch","amount":"128,000","category":"Food"}');
    expect(p?.amount, 128000);
  });

  test('returns null on missing amount, non-numeric amount, or malformed JSON', () {
    expect(parseGeminiJson('{"item":"x","category":"Food"}'), isNull);
    expect(parseGeminiJson('{"item":"x","amount":"free","category":"Food"}'), isNull);
    expect(parseGeminiJson('{"item":"","amount":100,"category":"Food"}'), isNull);
    expect(parseGeminiJson('not json at all'), isNull);
  });
}
