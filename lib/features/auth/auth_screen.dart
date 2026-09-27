import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:supabase_flutter/supabase_flutter.dart';

import '../../app/theme.dart';
import '../../providers/providers.dart';

enum _Mode { signIn, signUp, verify, forgot, reset }

/// Uzbek text for an auth failure. Supabase error codes first, then network.
String authErrorText(Object e) {
  if (e is AuthRetryableFetchException) return 'Internet aloqasini tekshiring';
  if (e is! AuthException) return 'Xatolik yuz berdi. Qayta urining';
  return switch (e.code) {
    'invalid_credentials' => 'Email yoki parol noto\'g\'ri',
    'user_already_exists' ||
    'email_exists' =>
      'Bu email allaqachon ro\'yxatdan o\'tgan',
    'otp_expired' => 'Kod noto\'g\'ri yoki eskirgan',
    'weak_password' => 'Parol juda oddiy (kamida 6 belgi)',
    'over_email_send_rate_limit' ||
    'over_request_rate_limit' =>
      'Juda ko\'p urinish. Birozdan so\'ng qayta urining',
    _ => e.message,
  };
}

/// Sign in / sign up / 6-digit code confirm / password reset. Shown by
/// [AuthGate] while there is no session; a successful sign-in flips the gate.
class AuthScreen extends ConsumerStatefulWidget {
  const AuthScreen({super.key});

  @override
  ConsumerState<AuthScreen> createState() => _AuthScreenState();
}

class _AuthScreenState extends ConsumerState<AuthScreen> {
  final _form = GlobalKey<FormState>();
  final _email = TextEditingController();
  final _password = TextEditingController();
  final _code = TextEditingController();
  var _mode = _Mode.signIn;
  var _busy = false;
  var _obscure = true;
  String? _error;

  GoTrueClient get _auth => ref.read(authProvider);
  String get _mail => _email.text.trim();

  @override
  void dispose() {
    _email.dispose();
    _password.dispose();
    _code.dispose();
    super.dispose();
  }

  void _go(_Mode m) => setState(() {
        _mode = m;
        _error = null;
        _obscure = true;
        _code.clear();
        if (m != _Mode.verify) _password.clear();
      });

  Future<void> _run(Future<void> Function() action) async {
    if (!_form.currentState!.validate()) return;
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      await action();
    } catch (e) {
      if (mounted) setState(() => _error = authErrorText(e));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _submit() => _run(() async {
        switch (_mode) {
          case _Mode.signIn:
            try {
              await _auth.signInWithPassword(
                  email: _mail, password: _password.text);
            } on AuthException catch (e) {
              if (e.code != 'email_not_confirmed') rethrow;
              await _auth.resend(type: OtpType.signup, email: _mail);
              _go(_Mode.verify);
            }
          case _Mode.signUp:
            final res =
                await _auth.signUp(email: _mail, password: _password.text);
            // Already-confirmed email: Supabase sends nothing and returns a
            // fake user with no identities (anti-enumeration).
            if (res.user?.identities?.isEmpty ?? false) {
              throw const AuthException('', code: 'user_already_exists');
            }
            if (res.session == null) _go(_Mode.verify);
          case _Mode.verify:
            await _auth.verifyOTP(
                type: OtpType.signup, email: _mail, token: _code.text.trim());
          case _Mode.forgot:
            await _auth.resetPasswordForEmail(_mail);
            _go(_Mode.reset);
          case _Mode.reset:
            // verifyOTP signs in and the gate unmounts this screen, so read
            // the new password before awaiting.
            final password = _password.text;
            await _auth.verifyOTP(
                type: OtpType.recovery, email: _mail, token: _code.text.trim());
            await _auth.updateUser(UserAttributes(password: password));
        }
      });

  Future<void> _resend() async {
    try {
      await _auth.resend(type: OtpType.signup, email: _mail);
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(content: Text('Kod qayta yuborildi')));
      }
    } catch (e) {
      if (mounted) setState(() => _error = authErrorText(e));
    }
  }

  @override
  Widget build(BuildContext context) {
    final c = context.colors;
    final t = Theme.of(context).textTheme;
    final (title, hint, action) = switch (_mode) {
      _Mode.signIn => ('Kirish', 'Hisobingizga kiring', 'Kirish'),
      _Mode.signUp => (
          'Ro\'yxatdan o\'tish',
          'Yangi hisob yarating',
          'Ro\'yxatdan o\'tish'
        ),
      _Mode.verify => (
          'Emailni tasdiqlang',
          '$_mail manziliga 6 xonali kod yuborildi',
          'Tasdiqlash'
        ),
      _Mode.forgot => (
          'Parolni tiklash',
          'Emailingizga 6 xonali kod yuboramiz',
          'Kod yuborish'
        ),
      _Mode.reset => (
          'Yangi parol',
          '$_mail manziliga yuborilgan kodni va yangi parolni kiriting',
          'Saqlash'
        ),
    };
    final needsEmail = {_Mode.signIn, _Mode.signUp, _Mode.forgot}.contains(_mode);
    final needsPassword =
        {_Mode.signIn, _Mode.signUp, _Mode.reset}.contains(_mode);
    final needsCode = {_Mode.verify, _Mode.reset}.contains(_mode);

    return Scaffold(
      body: SafeArea(
        child: Form(
          key: _form,
          child: ListView(
            padding: const EdgeInsets.all(AppSpace.page),
            children: [
              const SizedBox(height: 48),
              Icon(Icons.account_balance_wallet_outlined,
                  size: 48, color: c.accent),
              const SizedBox(height: AppSpace.gap),
              Text(title, style: t.headlineMedium),
              const SizedBox(height: 6),
              Text(hint, style: t.bodyMedium?.copyWith(color: c.muted)),
              const SizedBox(height: AppSpace.section),
              if (needsEmail) ...[
                TextFormField(
                  controller: _email,
                  keyboardType: TextInputType.emailAddress,
                  autofillHints: const [AutofillHints.email],
                  decoration: const InputDecoration(labelText: 'Email'),
                  validator: (v) => (v ?? '').contains('@')
                      ? null
                      : 'To\'g\'ri email kiriting',
                ),
                const SizedBox(height: 12),
              ],
              if (needsCode) ...[
                TextFormField(
                  controller: _code,
                  keyboardType: TextInputType.number,
                  maxLength: 6,
                  autofillHints: const [AutofillHints.oneTimeCode],
                  decoration:
                      const InputDecoration(labelText: '6 xonali kod'),
                  validator: (v) => RegExp(r'^\d{6}$').hasMatch(v?.trim() ?? '')
                      ? null
                      : '6 ta raqam kiriting',
                ),
                const SizedBox(height: 12),
              ],
              if (needsPassword) ...[
                TextFormField(
                  controller: _password,
                  obscureText: _obscure,
                  autofillHints: [
                    _mode == _Mode.signIn
                        ? AutofillHints.password
                        : AutofillHints.newPassword
                  ],
                  decoration: InputDecoration(
                    labelText: _mode == _Mode.reset ? 'Yangi parol' : 'Parol',
                    suffixIcon: IconButton(
                      icon: Icon(_obscure
                          ? Icons.visibility_outlined
                          : Icons.visibility_off_outlined),
                      tooltip: _obscure
                          ? 'Parolni ko\'rsatish'
                          : 'Parolni yashirish',
                      onPressed: () => setState(() => _obscure = !_obscure),
                    ),
                  ),
                  validator: (v) => (v ?? '').length >= 6
                      ? null
                      : 'Kamida 6 belgi',
                ),
                const SizedBox(height: 12),
              ],
              if (_error != null)
                Padding(
                  padding: const EdgeInsets.only(bottom: 12),
                  child: Text(_error!,
                      style: t.bodyMedium?.copyWith(color: c.danger)),
                ),
              FilledButton(
                onPressed: _busy ? null : _submit,
                child: _busy
                    ? const SizedBox.square(
                        dimension: 20,
                        child: CircularProgressIndicator(strokeWidth: 2))
                    : Text(action),
              ),
              const SizedBox(height: 8),
              ...switch (_mode) {
                _Mode.signIn => [
                    TextButton(
                      onPressed: () => _go(_Mode.signUp),
                      child: const Text('Hisobingiz yo\'qmi? Ro\'yxatdan o\'ting'),
                    ),
                    TextButton(
                      onPressed: () => _go(_Mode.forgot),
                      child: const Text('Parolni unutdingizmi?'),
                    ),
                  ],
                _Mode.verify => [
                    TextButton(
                      onPressed: _busy ? null : _resend,
                      child: const Text('Kodni qayta yuborish'),
                    ),
                    TextButton(
                      onPressed: () => _go(_Mode.signIn),
                      child: const Text('Orqaga'),
                    ),
                  ],
                _ => [
                    TextButton(
                      onPressed: () => _go(_Mode.signIn),
                      child: const Text('Kirish sahifasiga qaytish'),
                    ),
                  ],
              },
            ],
          ),
        ),
      ),
    );
  }
}
