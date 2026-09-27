import 'dart:async';

import 'package:drift/drift.dart';
import 'package:flutter/foundation.dart' show debugPrint, visibleForTesting;
import 'package:flutter/widgets.dart' show AppLifecycleListener;
import 'package:shared_preferences/shared_preferences.dart';
import 'package:supabase_flutter/supabase_flutter.dart';

import '../data/db/database.dart';
import '../data/db/tables.dart' show ExpenseSource;

/// Background two-way sync between Drift (source of truth) and Supabase.
/// Push dirty rows, pull rows changed since a per-table `synced_at` cursor.
/// Conflicts: newest `updatedAt` wins (server trigger + [applyCategories]).
///
/// ponytail: only own expenses are pulled; other members' family rows wait
/// for the Oila tab (step 9), which also needs owner names stored locally.
class SyncService {
  SyncService(this.db, this.client, this.prefs);

  final AppDatabase db;
  final SupabaseClient client;
  final SharedPreferences prefs;

  static const _uidKey = 'sync.uid';
  static const _familyKey = 'sync.family'; // '' = not in a family
  static const _retry = Duration(seconds: 30);
  static const _page = 1000;

  final _subs = <StreamSubscription<Object?>>[];
  AppLifecycleListener? _lifecycle;
  Timer? _timer;
  Future<void>? _inFlight;

  /// Triggers: sign-in / app start (initial session), local writes, resume.
  /// ponytail: "reconnect" = retry every 30s after a failed run; add
  /// connectivity_plus if that lag ever matters.
  void start() {
    _subs.add(client.auth.onAuthStateChange.listen((s) {
      if (s.event == AuthChangeEvent.initialSession ||
          s.event == AuthChangeEvent.signedIn) {
        schedule();
      }
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
    final uid = client.auth.currentUser?.id;
    if (uid == null) return;
    try {
      await _sync(uid);
    } catch (e) {
      debugPrint('sync failed: $e');
      schedule(_retry);
    }
  }

  Future<void> _sync(String uid) async {
    final prev = prefs.getString(_uidKey);
    if (prev != null && prev != uid) {
      // Another account on this device: its rows are not ours to show or push.
      await db.resetLocal();
      for (final k in prefs.getKeys().where((k) => k.startsWith('sync.'))) {
        await prefs.remove(k);
      }
    }
    await prefs.setString(_uidKey, uid);

    final member = await client
        .from('family_members')
        .select('family_id')
        .eq('user_id', uid)
        .maybeSingle();
    final familyId = member?['family_id'] as String?;

    // Joined, left, or was removed (maybe on another device): re-pull from
    // scratch, the new family's categories are older than our cursor.
    final prevFamily = prefs.getString(_familyKey);
    if (prevFamily != null && prevFamily != (familyId ?? '')) {
      await prefs.remove('sync.categories');
      await prefs.remove('sync.expenses');
    }
    await prefs.setString(_familyKey, familyId ?? '');

    // Categories first: merge local ones into same-name server ones before
    // pushing (a fresh device seeds defaults that already exist remotely).
    await _pull('categories', (rows) => db.transaction(() async {
          await applyCategories(db, rows);
          await hideForeignCategories(db, familyId);
        }));
    await hideForeignCategories(db, familyId); // no rows pulled after leaving
    await adoptLocal(db, uid, familyId);
    await _push('categories',
        await (db.select(db.categories)..where((c) => c.dirty)).get(), _categoryJson);
    await _push('expenses',
        await (db.select(db.expenses)..where((e) => e.dirty)).get(), _expenseJson);
    await _pull('expenses', (rows) => applyExpenses(db, rows));
  }

  Future<void> _push<D extends DataClass>(
    String table,
    List<D> rows,
    Map<String, dynamic> Function(D) toJson,
  ) async {
    final pushed = <Map<String, dynamic>>[];
    for (var i = 0; i < rows.length; i += 500) {
      final chunk = rows.skip(i).take(500).map(toJson).toList();
      try {
        await client.from(table).upsert(chunk);
        pushed.addAll(chunk);
      } on PostgrestException {
        // One refused row (RLS, frozen) mustn't block the rest; it stays dirty.
        for (final r in chunk) {
          try {
            await client.from(table).upsert(r);
            pushed.add(r);
          } on PostgrestException catch (e) {
            debugPrint('sync: $table ${r['id']} refused: ${e.message}');
          }
        }
      }
    }
    // Clean only if not edited again while the request was in flight.
    for (final r in pushed) {
      await db.customUpdate(
        'UPDATE $table SET dirty = 0 WHERE id = ? AND updated_at = ?',
        variables: [
          Variable.withString(r['id'] as String),
          Variable.withDateTime(DateTime.parse(r['updated_at'] as String)),
        ],
      );
    }
  }

  Future<void> _pull(
    String table,
    Future<void> Function(List<Map<String, dynamic>>) apply,
  ) async {
    final key = 'sync.$table';
    // Overlap a minute: rows stamped inside a still-open server transaction
    // commit after later stamps. Re-applying a row is harmless.
    var since = DateTime.parse(prefs.getString(key) ?? '1970-01-01T00:00:00Z')
        .subtract(const Duration(minutes: 1))
        .toIso8601String();
    while (true) {
      final rows = await client
          .from(table)
          .select()
          .gt('synced_at', since)
          .order('synced_at')
          .limit(_page);
      if (rows.isEmpty) return;
      await apply(rows);
      since = rows.last['synced_at'] as String;
      await prefs.setString(key, since);
      if (rows.length < _page) return;
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

/// Gives unowned local rows (made before sign-in or since the last sync) to
/// [uid]. Unowned categories named like an existing one are merged into it.
@visibleForTesting
Future<void> adoptLocal(AppDatabase db, String uid, String? familyId) =>
    db.transaction(() async {
      final c = db.categories;
      final orphan = c.ownerId.isNull() & c.familyId.isNull();
      final owned = {
        for (final k in await (db.select(c)
              ..where((k) => k.deletedAt.isNull() & orphan.not()))
            .get())
          _norm(k.name): k.id,
      };
      for (final k in await (db.select(c)..where((_) => orphan)).get()) {
        final twin = owned[_norm(k.name)];
        if (twin == null) continue;
        await (db.update(db.expenses)..where((e) => e.categoryId.equals(k.id)))
            .write(ExpensesCompanion(
          categoryId: Value(twin),
          updatedAt: Value(DateTime.now()),
          dirty: const Value(true),
        ));
        // Never pushed, so a hard delete is safe.
        await (db.delete(c)..where((x) => x.id.equals(k.id))).go();
      }
      // In a family, new categories are the family's (server lets only the
      // admin insert those; a member's stays dirty until step 7's requests).
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

String _norm(String s) => s.trim().toLowerCase();

@visibleForTesting
Future<void> applyCategories(AppDatabase db, List<Map<String, dynamic>> rows) =>
    _apply(db, db.categories, rows, (r) => CategoriesCompanion.insert(
          id: Value(r['id'] as String),
          ownerId: Value(r['owner_id'] as String?),
          familyId: Value(r['family_id'] as String?),
          name: r['name'] as String,
          iconKey: Value(r['icon_key'] as String),
          colorHex: r['color_hex'] as String,
          isArchived: Value(r['is_archived'] as bool),
          updatedAt: Value(_ts(r['updated_at'])!),
          deletedAt: Value(_ts(r['deleted_at'])),
          dirty: const Value(false),
        ));

@visibleForTesting
Future<void> applyExpenses(AppDatabase db, List<Map<String, dynamic>> rows) =>
    _apply(db, db.expenses, rows, (r) => ExpensesCompanion.insert(
          id: Value(r['id'] as String),
          ownerId: Value(r['owner_id'] as String?),
          familyId: Value(r['family_id'] as String?),
          description: r['description'] as String,
          amount: (r['amount'] as num).toInt(),
          categoryId: r['category_id'] as String,
          date: _ts(r['date'])!,
          source: ExpenseSource.values.byName(r['source'] as String),
          rawInput: Value(r['raw_input'] as String?),
          isPrivate: Value(r['is_private'] as bool),
          pendingCategory: Value(r['pending_category'] as String?),
          frozen: Value(r['frozen'] as bool),
          createdAt: Value(_ts(r['created_at'])!),
          updatedAt: Value(_ts(r['updated_at'])!),
          deletedAt: Value(_ts(r['deleted_at'])),
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
    return mine == null || _ts(r['updated_at'])!.isAfter(mine);
  }).map(fromRow);
  await db.batch((b) => b.insertAllOnConflictUpdate(table, fresh.toList()));
}

DateTime? _ts(Object? v) => v == null ? null : DateTime.parse(v as String);
String? _iso(DateTime? d) => d?.toUtc().toIso8601String();

Map<String, dynamic> _categoryJson(Category c) => {
      'id': c.id,
      'owner_id': c.ownerId,
      'family_id': c.familyId,
      'name': c.name,
      'icon_key': c.iconKey,
      'color_hex': c.colorHex,
      'is_archived': c.isArchived,
      'updated_at': _iso(c.updatedAt),
      'deleted_at': _iso(c.deletedAt),
    };

Map<String, dynamic> _expenseJson(Expense e) => {
      'id': e.id,
      'owner_id': e.ownerId,
      'family_id': e.familyId,
      'category_id': e.categoryId,
      'description': e.description,
      'amount': e.amount,
      'date': _iso(e.date),
      'source': e.source.name,
      'raw_input': e.rawInput,
      'is_private': e.isPrivate,
      'pending_category': e.pendingCategory,
      'created_at': _iso(e.createdAt),
      'updated_at': _iso(e.updatedAt),
      'deleted_at': _iso(e.deletedAt),
    };
