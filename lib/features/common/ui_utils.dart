import 'package:flutter/material.dart';
import 'package:intl/intl.dart';

final _grouping = NumberFormat('#,###', 'en_US');

/// UZS-style grouped amount with space separators, e.g. 2450000 -> "2 450 000".
String formatMoney(int amount) => _grouping.format(amount).replaceAll(',', ' ');

/// Uzbek month names (hardcoded — avoids intl locale-data init).
const uzMonths = [
  'Yanvar', 'Fevral', 'Mart', 'Aprel', 'May', 'Iyun',
  'Iyul', 'Avgust', 'Sentabr', 'Oktabr', 'Noyabr', 'Dekabr'
];

/// "Iyul 2026"
String uzMonthYear(DateTime d) => '${uzMonths[d.month - 1]} ${d.year}';

/// "5 Iyul"
String uzDayMonth(DateTime d) => '${d.day} ${uzMonths[d.month - 1]}';

/// "FF" alpha prefixed 6-digit hex -> Color, e.g. "E08A5B".
Color colorFromHex(String hex) => Color(int.parse('FF$hex', radix: 16));

const _iconByKey = <String, IconData>{
  'restaurant': Icons.restaurant,
  'shopping_cart': Icons.shopping_cart,
  'shopping_bag': Icons.shopping_bag,
  'directions_car': Icons.directions_car,
  'receipt_long': Icons.receipt_long,
  'category': Icons.category,
  'movie': Icons.movie,
  'medical_services': Icons.medical_services,
  'fitness_center': Icons.fitness_center,
  'flight': Icons.flight,
  'pets': Icons.pets,
  'school': Icons.school,
};

/// Map a stored iconKey to a Material icon; unknown keys fall back to a generic.
IconData iconForKey(String key) => _iconByKey[key] ?? Icons.category;

/// Parse a user-entered amount, ignoring spaces/separators. "45 000" -> 45000.
/// Returns null when no digits are present.
int? parseAmount(String raw) {
  final digits = raw.replaceAll(RegExp(r'[^0-9]'), '');
  if (digits.isEmpty) return null;
  return int.tryParse(digits);
}
