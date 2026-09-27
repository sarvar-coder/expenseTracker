import 'package:firebase_auth/firebase_auth.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../app/theme.dart';
import '../../providers/providers.dart';

enum _Mode { signIn, signUp, verify, forgot }

/// Uzbek text for an auth failure (FirebaseAuthException codes).
String authErrorText(Object e) {
  if (e is! FirebaseAuthException) return 'Xatolik yuz berdi. Qayta urining';
  return switch (e.code) {
    'invalid-credential' ||
    'wrong-password' ||
    'user-not-found' =>
      'Email yoki parol noto\'g\'ri',
    'email-already-in-use' => 'Bu email allaqachon ro\'yxatdan o\'tgan',
    'invalid-email' => 'To\'g\'ri email kiriting',
    'weak-password' => 'Parol juda oddiy (kamida 6 belgi)',
    'too-many-requests' => 'Juda ko\'p urinish. Birozdan so\'ng qayta urining',
    'network-request-failed' => 'Internet aloqasini tekshiring',
    'user-disabled' => 'Bu hisob o\'chirilgan',
    _ => e.message ?? 'Xatolik yuz berdi. Qayta urining',
  };
}

/// Sign in / sign up / verify email / password reset. Shown by [AuthGate]
/// until there's a verified user. Confirm and reset go through Firebase's
/// emailed links; a signed-in, unverified user always lands on "verify".
class AuthScreen extends ConsumerStatefulWidget {
  const AuthScreen({super.key});

  @override
  ConsumerState<AuthScreen> createState() => _AuthScreenState();
}

class _AuthScreenState extends ConsumerState<AuthScreen> {
  final _form = GlobalKey<FormState>();
  final _email = TextEditingController();
  final _password = TextEditingController();
  var _mode = _Mode.signIn;
  var _busy = false;
  String? _error;

  FirebaseAuth get _auth => ref.read(authProvider);
  String get _mail => _email.text.trim();

  @override
  void dispose() {
    _email.dispose();
    _password.dispose();
    super.dispose();
  }

  void _go(_Mode m) => setState(() {
        _mode = m;
        _error = null;
        _password.clear();
      });

  void _toast(String text) {
    if (mounted) ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(text)));
  }

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

  // Unverified sign-in / sign-up keeps the user signed in; the verify view
  // (from unverifiedEmailProvider) takes over until the link is opened.
  Future<void> _submit(_Mode mode) => _run(() async {
        switch (mode) {
          case _Mode.signIn:
            await _auth.signInWithEmailAndPassword(email: _mail, password: _password.text);
          case _Mode.signUp:
            final cred = await _auth.createUserWithEmailAndPassword(
                email: _mail, password: _password.text);
            await cred.user!.sendEmailVerification();
          case _Mode.verify:
            await _auth.currentUser?.reload(); // userChanges fires; gate flips
            if (!(_auth.currentUser?.emailVerified ?? false)) {
              if (mounted) setState(() => _error = 'Email hali tasdiqlanmagan');
            }
          case _Mode.forgot:
            await _auth.sendPasswordResetEmail(email: _mail);
            _go(_Mode.signIn);
            _toast('Parolni tiklash havolasi emailingizga yuborildi');
        }
      });

  Future<void> _resend() async {
    try {
      await _auth.currentUser?.sendEmailVerification();
      _toast('Havola qayta yuborildi');
    } catch (e) {
      if (mounted) setState(() => _error = authErrorText(e));
    }
  }

  Future<void> _back() async {
    await _auth.signOut();
    _go(_Mode.signIn);
  }

  @override
  Widget build(BuildContext context) {
    final c = context.colors;
    final t = Theme.of(context).textTheme;
    final pending = ref.watch(unverifiedEmailProvider).value;
    final mode = pending != null ? _Mode.verify : _mode;
    final (title, hint, action) = switch (mode) {
      _Mode.signIn => ('Kirish', 'Hisobingizga kiring', 'Kirish'),
      _Mode.signUp => (
          'Ro\'yxatdan o\'tish',
          'Yangi hisob yarating',
          'Ro\'yxatdan o\'tish'
        ),
      _Mode.verify => (
          'Emailni tasdiqlang',
          '$pending manziliga havola yuborildi. Uni oching, so\'ng shu yerga qayting',
          'Tasdiqladim'
        ),
      _Mode.forgot => (
          'Parolni tiklash',
          'Emailingizga parolni tiklash havolasini yuboramiz',
          'Havola yuborish'
        ),
    };
    final needsEmail = mode != _Mode.verify;
    final needsPassword = {_Mode.signIn, _Mode.signUp}.contains(mode);

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
              if (needsPassword) ...[
                TextFormField(
                  controller: _password,
                  obscureText: true,
                  autofillHints: [
                    mode == _Mode.signIn
                        ? AutofillHints.password
                        : AutofillHints.newPassword
                  ],
                  decoration: const InputDecoration(labelText: 'Parol'),
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
                onPressed: _busy ? null : () => _submit(mode),
                child: _busy
                    ? const SizedBox.square(
                        dimension: 20,
                        child: CircularProgressIndicator(strokeWidth: 2))
                    : Text(action),
              ),
              const SizedBox(height: 8),
              ...switch (mode) {
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
                      child: const Text('Havolani qayta yuborish'),
                    ),
                    TextButton(
                      onPressed: _back,
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
