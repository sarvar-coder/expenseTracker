import 'dart:convert';

import 'package:supabase_flutter/supabase_flutter.dart' show FunctionsClient;

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

/// Calls the `parse-expense` Edge Function, which holds the Gemini key.
class AiParser {
  AiParser(this._functions);
  final FunctionsClient _functions;

  Future<ParsedExpense?> parse(String rawInput) async {
    // ponytail: null on any failure (network/auth/bad JSON) — callers fall back
    // to Manual entry. Logic worth testing lives in [parseGeminiJson].
    try {
      final res = await _functions.invoke('parse-expense', body: {'input': rawInput});
      final text = (res.data as Map?)?['text'];
      return text is String ? parseGeminiJson(text) : null;
    } catch (_) {
      return null;
    }
  }
}
