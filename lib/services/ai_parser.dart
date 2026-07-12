import 'dart:convert';

import 'package:google_generative_ai/google_generative_ai.dart';

import '../features/common/ui_utils.dart' show parseAmount;

/// AI-derived expense fields. `amount` is whole UZS units.
class ParsedExpense {
  final String item;
  final int amount;
  final String category;
  const ParsedExpense({required this.item, required this.amount, required this.category});
}

/// Validates a Gemini reply into a [ParsedExpense]. Tolerates markdown fences /
/// surrounding prose (extracts the first `{...}` block) and amount as number or
/// separator-formatted string. Returns null on anything invalid so callers fall
/// back to Manual entry.
ParsedExpense? parseGeminiJson(String text) {
  final start = text.indexOf('{');
  final end = text.lastIndexOf('}');
  if (start < 0 || end <= start) return null;
  try {
    final m = jsonDecode(text.substring(start, end + 1)) as Map<String, dynamic>;
    final item = (m['item'] as String?)?.trim() ?? '';
    final category = (m['category'] as String?)?.trim() ?? '';
    final rawAmount = m['amount'];
    final amount =
        rawAmount is num ? rawAmount.toInt() : parseAmount(rawAmount?.toString() ?? '');
    if (item.isEmpty || category.isEmpty || amount == null || amount <= 0) return null;
    return ParsedExpense(item: item, amount: amount, category: category);
  } catch (_) {
    return null;
  }
}

/// Thin Gemini wrapper. The network call is intentionally untested; all logic
/// worth testing lives in [parseGeminiJson].
class AiParser {
  AiParser(this._model);
  final GenerativeModel _model;

  factory AiParser.gemini(String apiKey) =>
      AiParser(GenerativeModel(model: 'gemini-2.0-flash', apiKey: apiKey));

  Future<ParsedExpense?> parse(String rawInput) async {
    // ponytail: null on any failure (network/auth/bad JSON) — callers fall back
    // to Manual entry. Not unit-tested: would need to mock GenerativeModel.
    try {
      final res = await _model.generateContent([Content.text(_prompt(rawInput))]);
      final text = res.text;
      return text == null ? null : parseGeminiJson(text);
    } catch (_) {
      return null;
    }
  }

  String _prompt(String rawInput) =>
      'Extract an expense from the user text. Reply with ONLY strict JSON: '
      '{"item": string, "amount": integer whole UZS units, "category": string}. '
      'Pick a concise, reusable category name. No prose, no markdown.\n\n'
      'User text: "$rawInput"';
}
