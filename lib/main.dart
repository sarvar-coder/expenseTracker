import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'app/theme.dart';

void main() {
  runApp(const ProviderScope(child: ExpenseTrackerApp()));
}

class ExpenseTrackerApp extends StatelessWidget {
  const ExpenseTrackerApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Expense Tracker',
      debugShowCheckedModeBanner: false,
      theme: buildTheme(),
      // ponytail: placeholder home for Step 1; real shell arrives in Step 4.
      home: const Scaffold(
        body: Center(
          child: Text('Expense Tracker', style: TextStyle(fontSize: 20)),
        ),
      ),
    );
  }
}
