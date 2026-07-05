import 'package:flutter/material.dart';

import '../../app/theme.dart';

/// Pushed as a full route from the shell's center FAB.
class AddScreen extends StatelessWidget {
  const AddScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Add expense'),
        actions: [
          IconButton(
            icon: const Icon(Icons.close),
            onPressed: () => Navigator.of(context).maybePop(),
          ),
        ],
      ),
      // ponytail: placeholder; Type/Speak/Manual tabs arrive in Steps 6/8/9.
      body: const Center(
        child: Text('Add expense', style: TextStyle(fontSize: 20, color: AppColors.muted)),
      ),
    );
  }
}
