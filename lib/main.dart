import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:supabase_flutter/supabase_flutter.dart';

import 'app/shell.dart';
import 'app/theme.dart';
import 'features/auth/auth_screen.dart';
import 'providers/providers.dart';

void main() async {
  WidgetsFlutterBinding.ensureInitialized();
  final prefs = await SharedPreferences.getInstance();
  // Publishable key is meant for clients; RLS guards the data.
  await Supabase.initialize(
    url: 'https://xgxygopxnchdobuooyzw.supabase.co',
    publishableKey: 'sb_publishable_0-lm1ze8Nz93QSuvegf3kA_jkH1EHn0',
  );
  runApp(
    ProviderScope(
      overrides: [sharedPrefsProvider.overrideWithValue(prefs)],
      child: const ExpenseTrackerApp(),
    ),
  );
}

class ExpenseTrackerApp extends StatelessWidget {
  const ExpenseTrackerApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Xarajatlar',
      debugShowCheckedModeBanner: false,
      theme: buildTheme(Brightness.light),
      darkTheme: buildTheme(Brightness.dark),
      themeMode: ThemeMode.system,
      locale: const Locale('uz'),
      supportedLocales: const [Locale('uz'), Locale('en')],
      localizationsDelegates: const [
        GlobalMaterialLocalizations.delegate,
        GlobalWidgetsLocalizations.delegate,
        GlobalCupertinoLocalizations.delegate,
      ],
      home: const AuthGate(),
    );
  }
}

/// The whole app sits behind a Supabase session.
class AuthGate extends ConsumerWidget {
  const AuthGate({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) =>
      switch (ref.watch(sessionEmailProvider)) {
        AsyncData(value: != null) => const AppShell(),
        AsyncData() || AsyncError() => const AuthScreen(),
        _ => const Scaffold(),
      };
}
