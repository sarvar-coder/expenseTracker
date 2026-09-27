import 'package:supabase_flutter/supabase_flutter.dart';

import 'category_matcher.dart' show uniqueColor;
import 'sync_service.dart';

/// A pending invite addressed to the signed-in user.
typedef FamilyInvite = ({String id, String familyName, String invitedBy});

/// A member's request for a new family category (admin sees all pending).
typedef CategoryRequest = ({String id, String name});

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
