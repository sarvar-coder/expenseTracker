import 'dart:convert';

import 'package:firebase_ai/firebase_ai.dart';

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

/// Gemini through Firebase AI Logic (the key stays in Firebase).
/// ponytail: gemini-2.0-flash is retired; this is the Flash model the
/// firebase_ai 4.0 example targets. Swap the name when it ages out.
/// ponytail: no App Check yet; add before public release.
class AiParser {
  static const model = 'gemini-3.1-flash-lite';

  Future<ParsedExpense?> parse(String rawInput) async {
    // ponytail: null on any failure (network/auth/bad JSON) — callers fall back
    // to Manual entry. Logic worth testing lives in [parseGeminiJson].
    final input = rawInput.trim();
    if (input.isEmpty || input.length > 500) return null;
    try {
      final res = await FirebaseAI.googleAI()
          .generativeModel(
            model: model,
            generationConfig: GenerationConfig(responseMimeType: 'application/json'),
          )
          .generateContent([
        Content.text(
          'Extract an expense from the user text. Reply with ONLY strict JSON: '
          '{"item": string, "amount": integer whole UZS units, "category": string}. '
          'Pick a concise, reusable category name. No prose, no markdown.\n\n'
          'User text: ${jsonEncode(input)}',
        ),
      ]);
      final text = res.text;
      return text == null ? null : parseGeminiJson(text);
    } catch (_) {
      return null;
    }
  }
}
