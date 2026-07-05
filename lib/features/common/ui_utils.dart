import 'package:flutter/material.dart';
import 'package:intl/intl.dart';

final _grouping = NumberFormat('#,###', 'en_US');

/// UZS-style grouped amount with space separators, e.g. 2450000 -> "2 450 000".
String formatMoney(int amount) => _grouping.format(amount).replaceAll(',', ' ');

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
