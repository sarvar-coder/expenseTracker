import 'dart:io';

import 'package:csv/csv.dart';
import 'package:intl/intl.dart';
import 'package:share_plus/share_plus.dart';

import '../data/db/database.dart';

/// Builds an expenses CSV. Pure — directly unit-testable. The `csv` package
/// handles quoting/escaping of commas, quotes and newlines.
String expensesCsv(List<Expense> rows, Map<int, String> catNames) {
  final df = DateFormat('yyyy-MM-dd');
  final data = <List<dynamic>>[
    ['date', 'description', 'category', 'amount', 'source'],
    for (final e in rows)
      [
        df.format(e.date),
        e.description,
        catNames[e.categoryId] ?? '',
        e.amount,
        e.source.name,
      ],
  ];
  return const CsvEncoder().convert(data);
}

/// Writes [csv] to a temp file and opens the platform share sheet.
Future<void> shareExpensesCsv(String csv) async {
  final ts = DateTime.now().millisecondsSinceEpoch;
  final path = '${Directory.systemTemp.path}/expenses_$ts.csv';
  await File(path).writeAsString(csv);
  await SharePlus.instance.share(
    ShareParams(files: [XFile(path)], text: 'Xarajatlar eksporti'),
  );
}
