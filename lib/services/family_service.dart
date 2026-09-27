import 'package:cloud_firestore/cloud_firestore.dart';
import 'package:cloud_functions/cloud_functions.dart';

import 'category_matcher.dart' show uniqueColor;
import 'sync_service.dart';

/// A pending invite addressed to the signed-in user.
typedef FamilyInvite = ({String id, String familyName, String invitedBy});

/// A member's request for a new family category (admin sees all pending).
typedef CategoryRequest = ({String id, String name});

/// One current member's month: shared spending and budget contribution
/// (personal budget minus own private spending, computed server-side).
typedef FamilyMember = ({
  String userId,
  String name,
  bool isAdmin,
  int shared,
  int contribution,
});

/// A shared family expense, with who added it.
typedef FamilyExpense = ({
  String id,
  String ownerName,
  String categoryId,
  String description,
  int amount,
  DateTime date,
});

/// Everything the Oila tab shows for the family the user is in.
class FamilyOverview {
  FamilyOverview({
    required this.id,
    required this.name,
    required this.myId,
    required this.isAdmin,
    required this.members,
    required this.others,
    this.invites = const [],
    this.requests = const [],
  });

  final String id;
  final String name;
  final String myId;
  final bool isAdmin;
  final List<FamilyMember> members;

  /// Other members' shared expenses this month (own ones come from Drift).
  final List<FamilyExpense> others;

  /// Admin only: sent invites and pending category requests.
  final List<({String id, String email})> invites;
  final List<CategoryRequest> requests;

  /// Family budget = live sum of contributions; spent = shared spending.
  int get budget => members.fold(0, (a, m) => a + m.contribution);
  int get spent => members.fold(0, (a, m) => a + m.shared);
}

/// Thrown by [FamilyService] with a message ready to show (Uzbek).
class FamilyException implements Exception {
  FamilyException(this.message);
  final String message;
  @override
  String toString() => message;
}

/// Family lifecycle over the Cloud Functions (rules live server-side). Each
/// change is bracketed by syncs: push local edits first so the server moves
/// them too, then pull what the change did (sync re-pulls on family change).
class FamilyService {
  FamilyService(this.functions, this.sync);

  final FirebaseFunctions functions;
  final SyncService sync;

  FirebaseFirestore get _fs => sync.fs;
  String get _uid => sync.auth.currentUser!.uid;

  /// Null when not in a family. Syncs first so the server has our latest
  /// budget and expenses. Online only: the summary is computed server-side.
  Future<FamilyOverview?> overview(DateTime from, DateTime to) async {
    await sync.run();
    final s = await _call(() => _fn('familySummary', {
          'from': from.millisecondsSinceEpoch,
          'to': to.millisecondsSinceEpoch,
        })) as Map?;
    if (s == null) return null;
    final fid = s['id'] as String;
    final isAdmin = s['isAdmin'] == true;
    final (invites, requests) = await (
      isAdmin ? _invites(fid) : Future.value(const <({String id, String email})>[]),
      isAdmin ? _requests(fid) : Future.value(const <CategoryRequest>[]),
    ).wait;
    return FamilyOverview(
      id: fid,
      name: s['name'] as String,
      myId: _uid,
      isAdmin: isAdmin,
      members: [
        for (final m in (s['members'] as List).cast<Map>())
          (
            userId: m['userId'] as String,
            name: m['displayName'] as String,
            isAdmin: m['role'] == 'admin',
            shared: (m['shared'] as num).toInt(),
            contribution: (m['contribution'] as num).toInt(),
          ),
      ],
      others: [
        for (final e in (s['others'] as List).cast<Map>())
          (
            id: e['id'] as String,
            ownerName: e['ownerName'] as String,
            categoryId: e['categoryId'] as String,
            description: e['description'] as String,
            amount: (e['amount'] as num).toInt(),
            date: DateTime.fromMillisecondsSinceEpoch((e['date'] as num).toInt()),
          ),
      ],
      invites: invites,
      requests: requests,
    );
  }

  Future<String> create(String name, {required bool includeHistory}) async =>
      await _change(() => _fn('createFamily', {
            'name': name.trim(),
            'includeHistory': includeHistory,
          })) as String;

  /// Admin only (rules). The invitee sees it after signing in; no email is
  /// sent. Doc id `familyId_email` keeps it unique: a repeat is refused.
  Future<void> invite(String familyId, String email) async {
    final mail = email.trim().toLowerCase();
    final doc = _fs.doc('invites/${familyId}_$mail');
    try {
      await _call(() => doc.set({
            'familyId': familyId,
            'email': mail,
            'invitedBy': _uid,
            'createdAt': FieldValue.serverTimestamp(),
          }).timeout(_write));
    } on FamilyException {
      final dup = await doc.get().then((d) => d.exists, onError: (_) => false);
      if (dup) throw FamilyException(familyErrorText('already_invited'));
      rethrow;
    }
  }

  /// Admin cancels an invite, or the invitee declines it.
  Future<void> deleteInvite(String id) => _call(() => _fs.doc('invites/$id').delete().timeout(_write));

  Future<List<FamilyInvite>> myInvites() async {
    final rows = await _call(() => _fn('myInvites')) as List;
    return [
      for (final r in rows.cast<Map>())
        (
          id: r['id'] as String,
          familyName: r['familyName'] as String,
          invitedBy: r['invitedByName'] as String,
        ),
    ];
  }

  Future<void> accept(String inviteId, {required bool includeHistory}) =>
      _change(() => _fn('acceptInvite', {
            'inviteId': inviteId,
            'includeHistory': includeHistory,
          }));

  Future<void> leave() => _change(() => _fn('leaveFamily'));

  Future<void> removeMember(String userId) =>
      _change(() => _fn('removeMember', {'userId': userId}));

  Future<void> transferAdmin(String userId) =>
      _call(() => _fn('transferAdmin', {'userId': userId}));

  Future<void> deleteFamily() => _change(() => _fn('deleteFamily'));

  Future<List<({String id, String email})>> _invites(String fid) async {
    final q = await _call(() => _fs.collection('invites').where('familyId', isEqualTo: fid).get())
        as QuerySnapshot<Map<String, dynamic>>;
    return [for (final d in q.docs) (id: d.id, email: d['email'] as String)];
  }

  /// Admin: the family's pending category requests, oldest first.
  Future<List<CategoryRequest>> _requests(String fid) async {
    final q = await _call(() => _fs
        .collection('categoryRequests')
        .where('familyId', isEqualTo: fid)
        .where('status', isEqualTo: 'pending')
        .orderBy('createdAt')
        .get()) as QuerySnapshot<Map<String, dynamic>>;
    return [for (final d in q.docs) (id: d.id, name: d['name'] as String)];
  }

  /// Creates the category (unused color) and moves waiting expenses into it.
  Future<void> approveRequest(String id) async {
    final used = {
      for (final c in await sync.db.select(sync.db.categories).get()) c.colorHex.toUpperCase(),
    };
    await _change(() => _fn('approveCategoryRequest', {
          'id': id,
          'colorHex': uniqueColor(used),
          'iconKey': null,
        }));
  }

  /// Waiting expenses stay in Boshqa.
  Future<void> rejectRequest(String id) =>
      _change(() => _fn('rejectCategoryRequest', {'id': id}));

  Future<Object?> _fn(String name, [Map<String, Object?>? data]) async =>
      (await functions.httpsCallable(name).call<Object?>(data)).data;

  Future<Object?> _change(Future<Object?> Function() call) async {
    await sync.run();
    final r = await _call(call);
    await sync.run();
    return r;
  }

  Future<Object?> _call(Future<Object?> Function() f) async {
    try {
      return await f();
    } on FirebaseFunctionsException catch (e) {
      if (e.code == 'unavailable' || e.code == 'deadline-exceeded') throw _offline;
      throw FamilyException(familyErrorText(e.message ?? ''));
    } on FirebaseException catch (e) {
      if (e.code == 'unavailable') throw _offline;
      throw FamilyException(familyErrorText(e.code));
    } on Exception {
      throw _offline; // timeout, socket
    }
  }

  /// Firestore writes wait silently while offline; give up instead.
  static const _write = Duration(seconds: 15);
  static final _offline = FamilyException('Internet aloqasini tekshiring');
}

/// Server error codes (thrown by the Functions) to Uzbek text.
String familyErrorText(String code) => switch (code) {
      'already_in_family' => 'Siz allaqachon oiladasiz',
      'invite_not_found' => 'Taklif topilmadi',
      'not_in_family' => 'Siz oilada emassiz',
      'not_admin' => 'Faqat admin bajara oladi',
      'transfer_admin_first' => 'Avval adminlikni boshqa a\'zoga bering',
      'cannot_remove_self' => 'O\'zingizni chiqarib bo\'lmaydi',
      'not_a_member' => 'Bu foydalanuvchi oila a\'zosi emas',
      'request_not_found' => 'So\'rov topilmadi',
      'already_invited' => 'Bu email allaqachon taklif qilingan',
      _ => 'Xatolik yuz berdi. Qayta urinib ko\'ring',
    };
