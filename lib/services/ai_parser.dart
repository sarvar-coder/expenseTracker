import 'dart:convert';

import 'package:flutter/foundation.dart' show debugPrint;
import 'package:supabase_flutter/supabase_flutter.dart' show FunctionsClient;

import '../features/common/ui_utils.dart' show parseAmount;

/// AI-derived expense fields. `amount` is whole UZS units.
class ParsedExpense {
  final String item;
  final int amount;
  final String category;
  final DateTime date;
  const ParsedExpense({
    required this.item,
    required this.amount,
    required this.category,
    required this.date,
  });
}

/// Validates a Gemini reply into a [ParsedExpense]. Tolerates markdown fences /
/// surrounding prose (extracts the first `{...}` block) and amount as number or
/// separator-formatted string. A missing, invalid or future `date` becomes
/// [today]. Returns null on anything else invalid so callers fall back to
/// Manual entry.
ParsedExpense? parseGeminiJson(String text, {required DateTime today}) {
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
    final d = DateTime.tryParse(m['date']?.toString() ?? '');
    final day = d == null ? null : DateTime(d.year, d.month, d.day);
    final date = day == null || day.isAfter(today) ? today : day;
    return ParsedExpense(item: item, amount: amount, category: category, date: date);
  } catch (_) {
    return null;
  }
}

/// Calls the `parse-expense` Edge Function, which holds the Gemini key.
class AiParser {
  AiParser(this._functions);
  final FunctionsClient _functions;

  /// [categories] are existing names the model should reuse.
  Future<ParsedExpense?> parse(String rawInput, {List<String> categories = const []}) async {
    // ponytail: null on any failure (network/auth/bad JSON) — callers fall back
    // to Manual entry. Logic worth testing lives in [parseGeminiJson].
    final now = DateTime.now();
    final today = DateTime(now.year, now.month, now.day);
    try {
      final res = await _functions.invoke('parse-expense', body: {
        'input': rawInput,
        'today': today.toIso8601String().substring(0, 10),
        'categories': categories,
      });
      final text = (res.data as Map?)?['text'];
      final p = text is String ? parseGeminiJson(text, today: today) : null;
      if (p == null) debugPrint('parse-expense: unusable reply ${res.data}');
      return p;
    } catch (e) {
      debugPrint('parse-expense failed: $e');
      return null;
    }
  }
}
