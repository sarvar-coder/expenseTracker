import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'package:expense_tracker/main.dart';

void main() {
  testWidgets('app boots and shows title', (tester) async {
    await tester.pumpWidget(const ProviderScope(child: ExpenseTrackerApp()));
    expect(find.text('Expense Tracker'), findsOneWidget);
  });
}
