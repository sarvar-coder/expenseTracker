import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:supabase_flutter/supabase_flutter.dart';

import 'package:expense_tracker/features/auth/auth_screen.dart';
import 'package:expense_tracker/main.dart';
import 'package:expense_tracker/providers/providers.dart';

void main() {
  test('authErrorText maps Supabase codes and network errors to Uzbek', () {
    expect(authErrorText(const AuthApiException('x', code: 'invalid_credentials')),
        'Email yoki parol noto\'g\'ri');
    expect(authErrorText(const AuthApiException('x', code: 'otp_expired')),
        'Kod noto\'g\'ri yoki eskirgan');
    expect(
        authErrorText(const AuthApiException(
            'For security purposes, you can only request this after 42 seconds.',
            code: 'over_email_send_rate_limit')),
        'Juda tez. 42 soniyadan so\'ng qayta urining');
    expect(
        authErrorText(const AuthApiException('email rate limit exceeded',
            code: 'over_email_send_rate_limit')),
        'Soatlik email limiti tugadi. 1 soatgacha kuting, so\'ng qayta urining');
    expect(authErrorText(AuthRetryableFetchException()),
        'Internet aloqasini tekshiring');
    expect(authErrorText(const AuthApiException('Server said no')), 'Server said no');
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
      ],
      child: const ExpenseTrackerApp(),
    ));
    await tester.pumpAndSettle();

    expect(find.text('Hisobingizga kiring'), findsOneWidget);
    expect(find.text('Asosiy'), findsNothing, reason: 'app locked');

    // Invalid input never reaches Supabase (authProvider is not set up here).
    await tester.tap(find.widgetWithText(FilledButton, 'Kirish'));
    await tester.pump();
    expect(find.text('To\'g\'ri email kiriting'), findsOneWidget);
    expect(find.text('Kamida 6 belgi'), findsOneWidget);

    await tester.tap(find.text('Parolni unutdingizmi?'));
    await tester.pump();
    expect(find.text('Emailingizga 6 xonali kod yuboramiz'), findsOneWidget);
    expect(find.text('Parol'), findsNothing);

    await tester.tap(find.text('Kirish sahifasiga qaytish'));
    await tester.pump();
    await tester.tap(find.text('Hisobingiz yo\'qmi? Ro\'yxatdan o\'ting'));
    await tester.pump();
    expect(find.text('Yangi hisob yarating'), findsOneWidget);
  });
}
