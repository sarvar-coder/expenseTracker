import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:cloud_firestore/cloud_firestore.dart' show FirebaseFirestore;
import 'package:cloud_functions/cloud_functions.dart';
import 'package:firebase_auth/firebase_auth.dart';

import '../data/db/database.dart';
import '../data/settings_store.dart';
import '../services/ai_parser.dart';
import '../services/family_service.dart';
import '../services/speech_service.dart';
import '../services/sync_service.dart';

/// Overridden in main() with the resolved instance so downstream reads are sync.
final sharedPrefsProvider = Provider<SharedPreferences>(
  (_) => throw UnimplementedError('sharedPrefsProvider must be overridden in main()'),
);

final authProvider = Provider<FirebaseAuth>((_) => FirebaseAuth.instance);

/// Signed-in, email-verified user's email; null otherwise. Drives [AuthGate].
/// userChanges (not authStateChanges) so a verification reload flips it.
final sessionEmailProvider = StreamProvider<String?>((ref) => ref
    .watch(authProvider)
    .userChanges()
    .map((u) => u != null && u.emailVerified ? u.email : null));

/// Signed in but not verified yet: [AuthScreen] shows "check your email".
final unverifiedEmailProvider = StreamProvider<String?>((ref) => ref
    .watch(authProvider)
    .userChanges()
    .map((u) => u != null && !u.emailVerified ? u.email : null));

final databaseProvider = Provider<AppDatabase>((ref) {
  final db = AppDatabase();
  ref.onDispose(db.close);
  return db;
});

/// Background Firestore sync; started in main(), read by sign-out to flush.
final syncProvider = Provider<SyncService>((ref) {
  final sync = SyncService(
    ref.watch(databaseProvider),
    FirebaseFirestore.instance,
    ref.watch(authProvider),
    ref.watch(sharedPrefsProvider),
  )..start();
  ref.onDispose(sync.dispose);
  return sync;
});

final familyServiceProvider = Provider<FamilyService>(
  (ref) => FamilyService(
    FirebaseFunctions.instanceFor(region: 'europe-west3'), // same as the Functions
    ref.watch(syncProvider),
  ),
);

/// Oila tab: the user's family for this month, null when not in one.
/// Invalidate to refetch (tab opened, pull to refresh, after an action).
final familyOverviewProvider = FutureProvider<FamilyOverview?>((ref) {
  final now = DateTime.now();
  return ref.watch(familyServiceProvider).overview(
        DateTime(now.year, now.month),
        DateTime(now.year, now.month + 1),
      );
});

/// Invites addressed to the signed-in user (shown when not in a family).
final myInvitesProvider = FutureProvider<List<FamilyInvite>>(
  (ref) => ref.watch(familyServiceProvider).myInvites(),
);

final settingsStoreProvider = Provider<SettingsStore>((ref) => SettingsStore(ref.watch(sharedPrefsProvider)));

/// Reactive settings (budget / locale). Home watches this so budget
/// changes update the dashboard immediately.
final settingsProvider =
    NotifierProvider<SettingsController, Settings>(SettingsController.new);

class SettingsController extends Notifier<Settings> {
  SettingsStore get _store => ref.read(settingsStoreProvider);

  @override
  Settings build() => _store.load();

  Future<void> setBudget(int v) async {
    await _store.setBudget(v);
    state = state.copyWith(monthlyBudget: v);
  }

  Future<void> setLocale(String v) async {
    await _store.setLocale(v);
    state = state.copyWith(sttLocale: v);
  }

  Future<void> setDefaultPrivate(bool v) async {
    await _store.setDefaultPrivate(v);
    state = state.copyWith(defaultPrivate: v);
  }
}

/// Selected bottom-nav tab, so screens (e.g. Home "See all") can switch tabs.
final tabIndexProvider =
    NotifierProvider<TabIndexController, int>(TabIndexController.new);

class TabIndexController extends Notifier<int> {
  @override
  int build() => 0;
  void set(int i) => state = i;
}

final categoriesProvider = StreamProvider<List<Category>>(
  (ref) => ref.watch(databaseProvider).watchCategories(),
);

/// Includes archived — Settings > Categories.
final allCategoriesProvider = StreamProvider<List<Category>>(
  (ref) => ref.watch(databaseProvider).watchAllCategories(),
);

final expensesProvider = StreamProvider<List<Expense>>(
  (ref) => ref.watch(databaseProvider).watchExpenses(),
);

/// AI parsing through Firebase AI Logic.
final aiParserProvider = Provider<AiParser>((_) => AiParser());

/// On-device STT for Speak mode. Overridden in tests with a fake that drives
/// transcripts without a microphone.
final speechServiceProvider = Provider<SpeechService>((ref) => SpeechService());
