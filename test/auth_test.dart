import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:firebase_auth/firebase_auth.dart';

import 'package:expense_tracker/features/auth/auth_screen.dart';
import 'package:expense_tracker/main.dart';
import 'package:expense_tracker/providers/providers.dart';

void main() {
  test('authErrorText maps Firebase codes and network errors to Uzbek', () {
    expect(authErrorText(FirebaseAuthException(code: 'invalid-credential')),
        'Email yoki parol noto\'g\'ri');
    expect(authErrorText(FirebaseAuthException(code: 'email-already-in-use')),
        contains('allaqachon'));
    expect(authErrorText(FirebaseAuthException(code: 'network-request-failed')),
        'Internet aloqasini tekshiring');
    expect(authErrorText(FirebaseAuthException(code: 'x', message: 'Server said no')),
        'Server said no');
    expect(authErrorText(StateError('x')), 'Xatolik yuz berdi. Qayta urining');
  });

  testWidgets('signed out: gate shows sign-in; forms validate before any call',
      (tester) async {
    SharedPreferences.setMockInitialValues({});
    final prefs = await SharedPreferences.getInstance();
    await tester.pumpWidget(ProviderScope(
      overrides: [
        sharedPrefsProvider.overrideWithValue(prefs),
        sessionEmailProvider.overrideWith((ref) => Stream.value(null)),
        unverifiedEmailProvider.overrideWith((ref) => Stream.value(null)),
      ],
      child: const ExpenseTrackerApp(),
    ));
    await tester.pumpAndSettle();

    expect(find.text('Hisobingizga kiring'), findsOneWidget);
    expect(find.text('Asosiy'), findsNothing, reason: 'app locked');

    // Invalid input never reaches Firebase (authProvider is not set up here).
    await tester.tap(find.widgetWithText(FilledButton, 'Kirish'));
    await tester.pump();
    expect(find.text('To\'g\'ri email kiriting'), findsOneWidget);
    expect(find.text('Kamida 6 belgi'), findsOneWidget);

    await tester.tap(find.text('Parolni unutdingizmi?'));
    await tester.pump();
    expect(find.text('Emailingizga parolni tiklash havolasini yuboramiz'), findsOneWidget);
    expect(find.text('Parol'), findsNothing);

    await tester.tap(find.text('Kirish sahifasiga qaytish'));
    await tester.pump();
    await tester.tap(find.text('Hisobingiz yo\'qmi? Ro\'yxatdan o\'ting'));
    await tester.pump();
    expect(find.text('Yangi hisob yarating'), findsOneWidget);
  });

  testWidgets('signed in but unverified: verify view with resend and back', (tester) async {
    SharedPreferences.setMockInitialValues({});
    final prefs = await SharedPreferences.getInstance();
    await tester.pumpWidget(ProviderScope(
      overrides: [
        sharedPrefsProvider.overrideWithValue(prefs),
        sessionEmailProvider.overrideWith((ref) => Stream.value(null)),
        unverifiedEmailProvider.overrideWith((ref) => Stream.value('me@oila.uz')),
      ],
      child: const ExpenseTrackerApp(),
    ));
    await tester.pumpAndSettle();

    expect(find.text('Emailni tasdiqlang'), findsOneWidget);
    expect(find.textContaining('me@oila.uz'), findsOneWidget);
    expect(find.text('Havolani qayta yuborish'), findsOneWidget);
    expect(find.text('Email'), findsNothing);
  });
}
