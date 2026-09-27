import 'package:supabase_flutter/supabase_flutter.dart';

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

/// Family lifecycle over the Supabase RPCs (rules live server-side). Each
/// change is bracketed by syncs: push local edits first so the server moves
/// them too, then pull what the change did (sync re-pulls on family change).
class FamilyService {
  FamilyService(this.client, this.sync);

  final SupabaseClient client;
  final SyncService sync;

  /// Null when not in a family. Syncs first so the server has our latest
  /// budget and expenses. Online only: the summary is computed server-side.
  Future<FamilyOverview?> overview(DateTime from, DateTime to) async {
    await sync.run();
    final uid = client.auth.currentUser!.id;
    final me = await _call(() => client
        .from('family_members')
        .select('family_id, role, families(name)')
        .eq('user_id', uid)
        .maybeSingle()) as Map<String, dynamic>?;
    if (me == null) return null;
    final fid = me['family_id'] as String;
    final isAdmin = me['role'] == 'admin';
    final (summary, rows, invites, requests) = await (
      _call(() => client.rpc('family_summary', params: {
            'p_from': from.toUtc().toIso8601String(),
            'p_to': to.toUtc().toIso8601String(),
          })),
      // ponytail: fetches the family's whole history and filters here; add a
      // dated RPC if families ever get big enough for that to be slow.
      _call(() => client.rpc('family_expenses_since',
          params: {'p_since': '1970-01-01T00:00:00Z'})),
      isAdmin
          ? _call(() => client.from('invites').select('id, email').eq('family_id', fid))
          : Future<Object?>.value(const []),
      isAdmin ? categoryRequests() : Future.value(const <CategoryRequest>[]),
    ).wait;
    return FamilyOverview(
      id: fid,
      name: (me['families'] as Map<String, dynamic>)['name'] as String,
      myId: uid,
      isAdmin: isAdmin,
      members: [
        for (final m in (summary as List).cast<Map<String, dynamic>>())
          (
            userId: m['user_id'] as String,
            name: m['display_name'] as String,
            isAdmin: m['role'] == 'admin',
            shared: (m['shared_total'] as num).toInt(),
            contribution: (m['contribution'] as num).toInt(),
          ),
      ],
      others: [
        for (final e in (rows as List).cast<Map<String, dynamic>>())
          if (e['is_private'] != true && e['deleted_at'] == null)
            if (DateTime.parse(e['date'] as String).toLocal() case final d
                when !d.isBefore(from) && d.isBefore(to))
              (
                id: e['id'] as String,
                ownerName: e['owner_name'] as String,
                categoryId: e['category_id'] as String,
                description: e['description'] as String,
                amount: (e['amount'] as num).toInt(),
                date: d,
              ),
      ],
      invites: [
        for (final i in (invites as List).cast<Map<String, dynamic>>())
          (id: i['id'] as String, email: i['email'] as String),
      ],
      requests: requests,
    );
  }

  Future<String> create(String name, {required bool includeHistory}) async =>
      await _change(() => client.rpc('create_family', params: {
            'p_name': name.trim(),
            'p_include_history': includeHistory,
          })) as String;

  /// Admin only (RLS). The invitee sees it after signing in; no email is sent.
  Future<void> invite(String familyId, String email) => _call(() => client
      .from('invites')
      .insert({
        'family_id': familyId,
        'email': email.trim().toLowerCase(),
        'invited_by': client.auth.currentUser!.id,
      }));

  /// Admin cancels an invite, or the invitee declines it.
  Future<void> deleteInvite(String id) =>
      _call(() => client.from('invites').delete().eq('id', id));

  Future<List<FamilyInvite>> myInvites() async {
    final rows = await _call(() => client.rpc('my_invites')) as List;
    return [
      for (final r in rows.cast<Map<String, dynamic>>())
        (
          id: r['id'] as String,
          familyName: r['family_name'] as String,
          invitedBy: r['invited_by_name'] as String,
        ),
    ];
  }

  Future<void> accept(String inviteId, {required bool includeHistory}) =>
      _change(() => client.rpc('accept_invite', params: {
            'p_invite_id': inviteId,
            'p_include_history': includeHistory,
          }));

  Future<void> leave() => _change(() => client.rpc('leave_family'));

  Future<void> removeMember(String userId) =>
      _change(() => client.rpc('remove_member', params: {'p_user': userId}));

  Future<void> transferAdmin(String userId) =>
      _call(() => client.rpc('transfer_admin', params: {'p_user': userId}));

  Future<void> deleteFamily() => _change(() => client.rpc('delete_family'));

  /// Pending category requests: all of them for the admin, own for a member (RLS).
  Future<List<CategoryRequest>> categoryRequests() async {
    final rows = await _call(() => client
        .from('category_requests')
        .select('id, name')
        .eq('status', 'pending')
        .order('created_at')) as List;
    return [
      for (final r in rows.cast<Map<String, dynamic>>())
        (id: r['id'] as String, name: r['name'] as String),
    ];
  }

  /// Creates the category (unused color) and moves waiting expenses into it.
  Future<void> approveRequest(String id) async {
    final used = {
      for (final c in await sync.db.select(sync.db.categories).get()) c.colorHex.toUpperCase(),
    };
    await _change(() => client.rpc('approve_category_request', params: {
          'p_id': id,
          'p_color_hex': uniqueColor(used),
          'p_icon_key': null,
        }));
  }

  /// Waiting expenses stay in Boshqa.
  Future<void> rejectRequest(String id) =>
      _change(() => client.rpc('reject_category_request', params: {'p_id': id}));

  Future<Object?> _change(Future<Object?> Function() rpc) async {
    await sync.run();
    final r = await _call(rpc);
    await sync.run();
    return r;
  }

  Future<Object?> _call(Future<Object?> Function() f) async {
    try {
      return await f();
    } on PostgrestException catch (e) {
      throw FamilyException(familyErrorText(e.message, e.code));
    } on Exception {
      throw FamilyException('Internet aloqasini tekshiring'); // offline, timeout
    }
  }
}

/// Server error codes (raised by the RPCs) to Uzbek text.
String familyErrorText(String message, [String? code]) => switch (message) {
      'already_in_family' => 'Siz allaqachon oiladasiz',
      'invite_not_found' => 'Taklif topilmadi',
      'not_in_family' => 'Siz oilada emassiz',
      'not_admin' => 'Faqat admin bajara oladi',
      'transfer_admin_first' => 'Avval adminlikni boshqa a\'zoga bering',
      'cannot_remove_self' => 'O\'zingizni chiqarib bo\'lmaydi',
      'not_a_member' => 'Bu foydalanuvchi oila a\'zosi emas',
      'request_not_found' => 'So\'rov topilmadi',
      _ when code == '23505' => 'Bu email allaqachon taklif qilingan',
      _ => 'Xatolik yuz berdi. Qayta urinib ko\'ring',
    };
