import 'dart:async';
import 'dart:math' show max;

import 'package:cloud_firestore/cloud_firestore.dart' hide Constant;

import 'package:drift/drift.dart' hide Query;
import 'package:flutter/foundation.dart' show debugPrint, visibleForTesting;
import 'package:flutter/widgets.dart' show AppLifecycleListener;
import 'package:shared_preferences/shared_preferences.dart';
import 'package:firebase_auth/firebase_auth.dart';

import '../data/db/database.dart';
import '../data/db/tables.dart' show ExpenseSource;
import '../data/settings_store.dart';

/// Background two-way sync between Drift (source of truth) and Firestore.
/// Push dirty rows, pull docs changed since a per-query `syncedAt` cursor.
/// Conflicts: newest `updatedAt` wins (firestore.rules + [applyCategories]).
/// Firestore's own offline cache is off (main.dart): Drift is the cache.
///
/// Only own expenses are pulled; the Oila tab reads other members' rows live.
class SyncService {
  SyncService(this.db, this.fs, this.auth, this.prefs);

  final AppDatabase db;
  final FirebaseFirestore fs;
  final FirebaseAuth auth;
  final SharedPreferences prefs;

  static const _uidKey = 'sync.uid';
  static const _familyKey = 'sync.family'; // '' = not in a family
  static const _adminKey = 'sync.admin';
  static const _retry = Duration(seconds: 30);
  static const _page = 1000;
  static const _timeout = Duration(seconds: 30);
  static const _server = GetOptions(source: Source.server);

  final _subs = <StreamSubscription<Object?>>[];
  AppLifecycleListener? _lifecycle;
  Timer? _timer;
  Future<void>? _inFlight;

  /// Triggers: sign-in / app start / email verified, local writes, resume.
  /// ponytail: "reconnect" = retry every 30s after a failed run; add
  /// connectivity_plus if that lag ever matters.
  void start() {
    _subs.add(auth.userChanges().listen((u) {
      if (u?.emailVerified ?? false) schedule();
    }));
    // distinct: a row the server keeps refusing mustn't re-trigger forever.
    _subs.add(_dirtyCount().distinct().where((n) => n > 0).listen(
          (_) => schedule(const Duration(seconds: 2)),
        ));
    _lifecycle = AppLifecycleListener(onResume: schedule);
  }

  void dispose() {
    for (final s in _subs) {
      s.cancel();
    }
    _lifecycle?.dispose();
    _timer?.cancel();
  }

  void schedule([Duration delay = Duration.zero]) {
    _timer?.cancel();
    _timer = Timer(delay, run);
  }

  /// One sync pass; concurrent callers share it. Never throws.
  Future<void> run() => _inFlight ??= _guarded().whenComplete(() => _inFlight = null);

  Future<void> _guarded() async {
    final user = auth.currentUser;
    if (user == null || !user.emailVerified) return; // app is locked until then
    try {
      await _sync(user.uid, user.email!.toLowerCase());
    } catch (e) {
      debugPrint('sync failed: $e');
      schedule(_retry);
    }
  }

  Future<void> _sync(String uid, String email) async {
    final prev = prefs.getString(_uidKey);
    if (prev != null && prev != uid) {
      // Another account on this device: its rows are not ours to show or push.
      await db.resetLocal();
      await SettingsStore(prefs).applyProfile(budget: 0, defaultPrivate: false);
      for (final k in prefs.getKeys().where((k) => k.startsWith('sync.'))) {
        await prefs.remove(k);
      }
    }
    await prefs.setString(_uidKey, uid);

    final member = (await fs.doc('members/$uid').get(_server).timeout(_timeout)).data();
    final familyId = member?['familyId'] as String?;

    // Joined, left, or was removed (maybe on another device): re-pull from
    // scratch, the new family's categories are older than our cursor.
    final prevFamily = prefs.getString(_familyKey);
    if (prevFamily != null && prevFamily != (familyId ?? '')) {
      for (final k in prefs.getKeys().where((k) => k.startsWith('sync.pull.'))) {
        await prefs.remove(k);
      }
    }
    await prefs.setString(_familyKey, familyId ?? '');
    await prefs.setBool(_adminKey, member?['role'] == 'admin');
    await _syncProfile(uid, email);

    // Categories first: merge local ones into same-name server ones before
    // pushing (a fresh device seeds defaults that already exist remotely).
    // Rules only allow queries they can prove: own and family ones separately.
    final cats = fs.collection('categories');
    Future<void> applyCats(List<Map<String, dynamic>> rows) => db.transaction(() async {
          await applyCategories(db, rows);
          await hideForeignCategories(db, familyId);
        });
    await _pull('sync.pull.catOwn', cats.where('ownerId', isEqualTo: uid), applyCats);
    if (familyId != null) {
      await _pull('sync.pull.catFamily', cats.where('familyId', isEqualTo: familyId), applyCats);
    }
    await hideForeignCategories(db, familyId); // no rows pulled after leaving
    await adoptLocal(db, uid, familyId, canCreate: canCreateCategories(prefs));
    await detachForeignExpenses(db, familyId);
    await _push('categories',
        await (db.select(db.categories)..where((c) => c.dirty)).get(), categoryDoc);
    await _push('expenses',
        await (db.select(db.expenses)..where((e) => e.dirty)).get(), expenseDoc);
    await _pull('sync.pull.expenses',
        fs.collection('expenses').where('ownerId', isEqualTo: uid), (rows) => applyExpenses(db, rows));
  }

  /// Budget + private default: push a local edit, otherwise take the server's
  /// (another device may have changed them).
  /// ponytail: a pulled value shows in Settings/Home after the next launch;
  /// SettingsController doesn't watch prefs.
  /// First sync creates the profile doc (name = email's local part).
  Future<void> _syncProfile(String uid, String email) async {
    final store = SettingsStore(prefs);
    final ref = fs.doc('profiles/$uid');
    final server = (await ref.get(_server).timeout(_timeout)).data();
    final push = server == null ||
        shouldPushProfile(
          dirty: store.profileDirty,
          serverBudget: (server['budget'] as num?)?.toInt(),
          localBudget: store.load().monthlyBudget,
        );
    if (push) {
      final s = store.load();
      await ref.set({
        'email': email,
        'displayName': server?['displayName'] ?? email.split('@').first,
        'budget': s.monthlyBudget,
        'defaultPrivate': s.defaultPrivate,
        'updatedAt': Timestamp.now(),
      }).timeout(_timeout);
      await store.markProfileClean();
    } else {
      await store.applyProfile(
        budget: (server['budget'] as num).toInt(),
        defaultPrivate: server['defaultPrivate'] as bool,
      );
    }
  }

  /// Writes [rows] in batches of 500. A batch the rules refuse is retried
  /// doc by doc so one refused row (stale, frozen) doesn't block the rest; it
  /// stays dirty. Writes wait while offline, hence the timeout (the write may
  /// still land later; re-pushing it is harmless).
  Future<void> _push<D extends DataClass>(
    String collection,
    List<D> rows,
    Map<String, dynamic> Function(D) toDoc,
  ) async {
    final col = fs.collection(collection);
    final pushed = <Map<String, dynamic>>[];
    for (var i = 0; i < rows.length; i += 500) {
      final chunk = rows.skip(i).take(500).map(toDoc).toList();
      try {
        final batch = fs.batch();
        for (final d in chunk) {
          batch.set(col.doc(d['id'] as String), _stamped(d));
        }
        await batch.commit().timeout(_timeout);
        pushed.addAll(chunk);
      } on FirebaseException catch (e) {
        if (e.code != 'permission-denied') rethrow;
        for (final d in chunk) {
          try {
            await col.doc(d['id'] as String).set(_stamped(d)).timeout(_timeout);
            pushed.add(d);
          } on FirebaseException catch (e) {
            if (e.code != 'permission-denied') rethrow;
            debugPrint('sync: $collection ${d['id']} refused');
          }
        }
      }
    }
    // Clean only if not edited again while the request was in flight.
    for (final d in pushed) {
      await db.customUpdate(
        'UPDATE $collection SET dirty = 0 WHERE id = ? AND updated_at = ?',
        variables: [
          Variable.withString(d['id'] as String),
          Variable.withDateTime((d['updatedAt'] as Timestamp).toDate()),
        ],
      );
    }
  }

  static Map<String, dynamic> _stamped(Map<String, dynamic> d) =>
      {...d, 'syncedAt': FieldValue.serverTimestamp()}..remove('id');

  /// Pages [query] by `syncedAt` (then doc id, so docs stamped by one batch
  /// aren't split across pages) from the cursor saved under [key].
  Future<void> _pull(
    String key,
    Query<Map<String, dynamic>> query,
    Future<void> Function(List<Map<String, dynamic>>) apply,
  ) async {
    // Overlap a minute, as the SQL version did: cheap insurance against
    // stamps becoming visible out of order. Re-applying a doc is harmless.
    final since = max(0, (prefs.getInt(key) ?? 0) - 60000);
    var page = query
        .where('syncedAt', isGreaterThan: Timestamp.fromMillisecondsSinceEpoch(since))
        .orderBy('syncedAt')
        .limit(_page);
    while (true) {
      final docs = (await page.get(_server).timeout(_timeout)).docs;
      if (docs.isEmpty) return;
      await apply([for (final d in docs) {...d.data(), 'id': d.id}]);
      await prefs.setInt(key, (docs.last['syncedAt'] as Timestamp).millisecondsSinceEpoch);
      if (docs.length < _page) return;
      page = page.startAfterDocument(docs.last);
    }
  }

  Stream<int> _dirtyCount() => db
      .customSelect(
        'SELECT (SELECT count(*) FROM categories WHERE dirty)'
        ' + (SELECT count(*) FROM expenses WHERE dirty) AS n',
        readsFrom: {db.categories, db.expenses},
      )
      .watchSingle()
      .map((r) => r.read<int>('n'));
}

/// Push this device's budget/private default, or take the server's? Push
/// when edited here, or when the server has no budget but this device does
/// (set before sign-in or before profiles synced; 0 = unset) so it isn't wiped.
@visibleForTesting
bool shouldPushProfile({
  required bool dirty,
  required int? serverBudget,
  required int localBudget,
}) =>
    dirty || (serverBudget == 0 && localBudget > 0);

/// Outside a family, or as its admin, the user may create categories. As of
/// the last sync; true before the first one.
bool canCreateCategories(SharedPreferences prefs) =>
    (prefs.getString(SyncService._familyKey) ?? '').isEmpty ||
    (prefs.getBool(SyncService._adminKey) ?? false);

/// Gives unowned local rows (made before sign-in or since the last sync) to
/// [uid]. Unowned categories named like an existing one are merged into it;
/// if the user can't create categories, the rest become Boshqa + request.
@visibleForTesting
Future<void> adoptLocal(AppDatabase db, String uid, String? familyId, {bool canCreate = true}) =>
    db.transaction(() async {
      final c = db.categories;
      final orphan = c.ownerId.isNull() & c.familyId.isNull();
      final owned = {
        for (final k in await (db.select(c)
              ..where((k) => k.deletedAt.isNull() & orphan.not()))
            .get())
          _norm(k.name): k.id,
      };
      final boshqa = canCreate ? null : owned['boshqa'];
      for (final k in await (db.select(c)..where((_) => orphan)).get()) {
        final twin = owned[_norm(k.name)];
        if (twin == null && boshqa == null) continue;
        await (db.update(db.expenses)..where((e) => e.categoryId.equals(k.id)))
            .write(ExpensesCompanion(
          categoryId: Value(twin ?? boshqa!),
          pendingCategory: twin == null ? Value(k.name) : const Value.absent(),
          updatedAt: Value(DateTime.now()),
          dirty: const Value(true),
        ));
        // Never pushed, so a hard delete is safe.
        await (db.delete(c)..where((x) => x.id.equals(k.id))).go();
      }
      // In a family, new categories are the family's (admin only; a member's
      // were all turned into requests above).
      await (db.update(c)..where((_) => orphan)).write(familyId == null
          ? CategoriesCompanion(ownerId: Value(uid))
          : CategoriesCompanion(familyId: Value(familyId)));
      await (db.update(db.expenses)..where((e) => e.ownerId.isNull()))
          .write(ExpensesCompanion(ownerId: Value(uid), familyId: Value(familyId)));
    });

/// Categories of a family we're no longer in (left, removed, deleted) stay
/// visible to the server for our frozen history, but the picker must show the
/// personal copies the server made instead. Hidden locally, never pushed.
/// ponytail: frozen history then shows a generic badge; look up categories
/// including hidden ones if that bothers anyone.
@visibleForTesting
Future<void> hideForeignCategories(AppDatabase db, String? familyId) {
  final c = db.categories;
  return (db.update(c)
        ..where((k) =>
            k.familyId.isNotNull() &
            (familyId == null ? const Constant(true) : k.familyId.equals(familyId).not()) &
            k.deletedAt.isNull()))
      .write(CategoriesCompanion(deletedAt: Value(DateTime.now()), dirty: const Value(false)));
}

/// An expense queued offline in a family we've since left can't be pushed
/// there (rules refuse a foreign familyId); it becomes personal instead.
/// ponytail: one already on the server was frozen there and stays refused
/// (dirty) until a newer server copy replaces it.
@visibleForTesting
Future<void> detachForeignExpenses(AppDatabase db, String? familyId) {
  final e = db.expenses;
  return (db.update(e)
        ..where((x) =>
            x.dirty &
            x.frozen.not() &
            x.familyId.isNotNull() &
            (familyId == null ? const Constant(true) : x.familyId.equals(familyId).not())))
      .write(const ExpensesCompanion(familyId: Value(null)));
}

String _norm(String s) => s.trim().toLowerCase();

@visibleForTesting
Future<void> applyCategories(AppDatabase db, List<Map<String, dynamic>> rows) =>
    _apply(db, db.categories, rows, (r) => CategoriesCompanion.insert(
          id: Value(r['id'] as String),
          ownerId: Value(r['ownerId'] as String?),
          familyId: Value(r['familyId'] as String?),
          name: r['name'] as String,
          iconKey: Value(r['iconKey'] as String),
          colorHex: r['colorHex'] as String,
          isArchived: Value(r['isArchived'] as bool),
          updatedAt: Value(_ts(r['updatedAt'])!),
          deletedAt: Value(_ts(r['deletedAt'])),
          dirty: const Value(false),
        ));

@visibleForTesting
Future<void> applyExpenses(AppDatabase db, List<Map<String, dynamic>> rows) =>
    _apply(db, db.expenses, rows, (r) => ExpensesCompanion.insert(
          id: Value(r['id'] as String),
          ownerId: Value(r['ownerId'] as String?),
          familyId: Value(r['familyId'] as String?),
          description: r['description'] as String,
          amount: (r['amount'] as num).toInt(),
          categoryId: r['categoryId'] as String,
          date: _ts(r['date'])!,
          source: ExpenseSource.values.byName(r['source'] as String),
          rawInput: Value(r['rawInput'] as String?),
          isPrivate: Value(r['isPrivate'] as bool),
          pendingCategory: Value(r['pendingCategory'] as String?),
          frozen: Value(r['frozen'] as bool? ?? false),
          createdAt: Value(_ts(r['createdAt'])!),
          updatedAt: Value(_ts(r['updatedAt'])!),
          deletedAt: Value(_ts(r['deletedAt'])),
          dirty: const Value(false),
        ));

/// Upserts server rows, except where a local unpushed edit is as new or newer.
Future<void> _apply<T extends Table, D>(
  AppDatabase db,
  TableInfo<T, D> table,
  List<Map<String, dynamic>> rows,
  Insertable<D> Function(Map<String, dynamic>) fromRow,
) async {
  final pending = {
    for (final r in await db
        .customSelect('SELECT id, updated_at FROM ${table.actualTableName} WHERE dirty')
        .get())
      r.read<String>('id'): r.read<DateTime>('updated_at'),
  };
  final fresh = rows.where((r) {
    final mine = pending[r['id']];
    return mine == null || _ts(r['updatedAt'])!.isAfter(mine);
  }).map(fromRow);
  await db.batch((b) => b.insertAllOnConflictUpdate(table, fresh.toList()));
}

DateTime? _ts(Object? v) => (v as Timestamp?)?.toDate();
Timestamp? _t(DateTime? d) => d == null ? null : Timestamp.fromDate(d);

/// Firestore doc for a category; `id` is the doc id, stripped before writing.
@visibleForTesting
Map<String, dynamic> categoryDoc(Category c) => {
      'id': c.id,
      'ownerId': c.ownerId,
      'familyId': c.familyId,
      'name': c.name,
      'nameLower': _norm(c.name),
      'iconKey': c.iconKey,
      'colorHex': c.colorHex,
      'isArchived': c.isArchived,
      'updatedAt': _t(c.updatedAt),
      'deletedAt': _t(c.deletedAt),
    };

@visibleForTesting
Map<String, dynamic> expenseDoc(Expense e) => {
      'id': e.id,
      'ownerId': e.ownerId,
      'familyId': e.familyId,
      'categoryId': e.categoryId,
      'description': e.description,
      'amount': e.amount,
      'date': _t(e.date),
      'source': e.source.name,
      'rawInput': e.rawInput,
      'isPrivate': e.isPrivate,
      'pendingCategory': e.pendingCategory,
      'frozen': e.frozen,
      'createdAt': _t(e.createdAt),
      'updatedAt': _t(e.updatedAt),
      'deletedAt': _t(e.deletedAt),
    };
