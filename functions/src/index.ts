// Family lifecycle, ported 1:1 from the Supabase RPCs. The Admin SDK bypasses
// firestore.rules, so every access check lives here. Errors are
// HttpsError('failed-precondition', <code>) with the same codes the app maps
// in familyErrorText. Multi-doc changes run in one transaction.
// ponytail: whole family in one transaction and `in` queries over member ids
// (max 30); fine for a family, revisit if one ever outgrows that.

import { randomUUID } from 'node:crypto';
import { initializeApp } from 'firebase-admin/app';
import {
  FieldValue,
  getFirestore,
  Timestamp,
  type DocumentData,
  type QuerySnapshot,
  type Transaction,
} from 'firebase-admin/firestore';
import { setGlobalOptions } from 'firebase-functions/v2';
import { onDocumentWritten } from 'firebase-functions/v2/firestore';
import { HttpsError, onCall, type CallableRequest } from 'firebase-functions/v2/https';
import { key, planFreeze, planJoin, summarize, type Cat, type Edits } from './logic.js';

initializeApp();
setGlobalOptions({ region: 'europe-west3' }); // same as Firestore
const db = getFirestore();
const members = db.collection('members');
const families = db.collection('families');
const invites = db.collection('invites');
const profiles = db.collection('profiles');
const cats = db.collection('categories');
const expenses = db.collection('expenses');
const requests = db.collection('categoryRequests');

type Row = DocumentData & { id: string };
const rows = (s: QuerySnapshot): Row[] => s.docs.map((d) => ({ ...d.data(), id: d.id }));
const live = (r: Row) => r.deletedAt == null;

const fail = (code: string): never => {
  throw new HttpsError('failed-precondition', code);
};

function who(req: CallableRequest) {
  if (!req.auth) throw new HttpsError('unauthenticated', 'not_signed_in');
  return { uid: req.auth.uid, email: String(req.auth.token.email ?? '').toLowerCase() };
}

function str(v: unknown, max: number): string {
  const s = typeof v === 'string' ? v.trim() : '';
  if (!s || s.length > max) throw new HttpsError('invalid-argument', 'bad_input');
  return s;
}

function millis(v: unknown): Timestamp {
  if (typeof v !== 'number' || !Number.isFinite(v)) throw new HttpsError('invalid-argument', 'bad_input');
  return Timestamp.fromMillis(v);
}

// Like the SQL sync_stamp trigger: a server edit stays newer than the
// client's updatedAt, and syncedAt feeds the clients' pull cursor.
function touch(old?: DocumentData) {
  const now = Timestamp.now();
  const prev = old?.updatedAt as Timestamp | undefined;
  return {
    updatedAt: prev && prev.toMillis() > now.toMillis() ? prev : now,
    syncedAt: FieldValue.serverTimestamp(),
  };
}

function applyEdits(tx: Transaction, docs: Row[], edits: Edits) {
  const byId = new Map(docs.map((d) => [d.id, d]));
  for (const [id, f] of edits) tx.update(expenses.doc(id), { ...f, ...touch(byId.get(id)) });
}

function createCategory(
  tx: Transaction,
  id: string,
  c: Omit<Cat, 'id'> & { ownerId: string | null; familyId: string | null },
) {
  tx.create(cats.doc(id), {
    ownerId: c.ownerId,
    familyId: c.familyId,
    name: c.name,
    nameLower: key(c.name),
    iconKey: c.iconKey ?? 'category',
    colorHex: c.colorHex ?? '9E9E9E',
    isArchived: c.isArchived ?? false,
    updatedAt: Timestamp.now(),
    deletedAt: null,
    syncedAt: FieldValue.serverTimestamp(),
  });
  return id;
}

/** Every family has Boshqa: the fallback for unapproved names. */
function ensureBoshqa(tx: Transaction, fid: string, famIds: Map<string, string>) {
  let id = famIds.get('boshqa');
  if (!id) {
    id = createCategory(tx, randomUUID(), { ownerId: null, familyId: fid, name: 'Boshqa' });
    famIds.set('boshqa', id);
  }
  return id;
}

/** Files admin requests, one pending per name. */
function fileRequests(tx: Transaction, fid: string, uid: string, names: string[], pending: Row[]) {
  const seen = new Set(pending.map((r) => r.nameLower as string));
  for (const raw of names) {
    const name = raw.trim().slice(0, 60);
    if (!name || seen.has(name.toLowerCase())) continue;
    seen.add(name.toLowerCase());
    tx.create(requests.doc(), {
      familyId: fid,
      requestedBy: uid,
      name,
      nameLower: name.toLowerCase(),
      status: 'pending',
      createdAt: FieldValue.serverTimestamp(),
    });
  }
}

const pendingRequests = (tx: Transaction, fid: string) =>
  tx.get(requests.where('familyId', '==', fid).where('status', '==', 'pending'));

async function famCategories(tx: Transaction, fid: string) {
  return rows(await tx.get(cats.where('familyId', '==', fid))).filter(live) as (Row & Cat)[];
}

async function requireAdmin(tx: Transaction, uid: string): Promise<string> {
  const me = await tx.get(members.doc(uid));
  if (me.get('role') !== 'admin') fail('not_admin');
  return me.get('familyId');
}

// Transactions need all reads before any write, so the multi-step helpers
// read first and return their writes.

async function prepFreeze(tx: Transaction, user: string, fid: string, famCats: Cat[]) {
  const [exp, mine] = await Promise.all([
    tx.get(expenses.where('ownerId', '==', user)),
    tx.get(cats.where('ownerId', '==', user)),
  ]);
  return () => {
    const e = rows(exp);
    const plan = planFreeze(e, famCats, rows(mine).filter(live) as (Row & Cat)[], fid, randomUUID);
    for (const c of plan.created) createCategory(tx, c.id, { ...c, ownerId: user, familyId: null });
    applyEdits(tx, e, plan.edits);
    tx.delete(members.doc(user));
  };
}

async function prepClose(tx: Transaction, fid: string) {
  const [inv, req] = await Promise.all([
    tx.get(invites.where('familyId', '==', fid)),
    pendingRequests(tx, fid),
  ]);
  return () => {
    tx.update(families.doc(fid), { deletedAt: FieldValue.serverTimestamp() });
    for (const d of inv.docs) tx.delete(d.ref);
    for (const d of req.docs) tx.update(d.ref, { status: 'rejected' });
  };
}

// ---------------------------------------------------------------- callables

/** Personal categories become the family's; history optional. */
export const createFamily = onCall(async (req) => {
  const { uid } = who(req);
  const name = str(req.data?.name, 60);
  const includeHistory = req.data?.includeHistory === true;
  return db.runTransaction(async (tx) => {
    const [me, myCats, myExp] = await Promise.all([
      tx.get(members.doc(uid)),
      tx.get(cats.where('ownerId', '==', uid)),
      tx.get(expenses.where('ownerId', '==', uid)),
    ]);
    if (me.exists) fail('already_in_family');
    const fam = families.doc();
    tx.create(fam, { name, createdAt: FieldValue.serverTimestamp(), deletedAt: null });
    tx.create(members.doc(uid), { familyId: fam.id, role: 'admin', joinedAt: FieldValue.serverTimestamp() });
    const famIds = new Map<string, string>();
    for (const c of rows(myCats).filter(live)) {
      famIds.set(key(c.name), c.id);
      tx.update(cats.doc(c.id), { ownerId: null, familyId: fam.id, ...touch(c) });
    }
    ensureBoshqa(tx, fam.id, famIds);
    if (includeHistory) {
      for (const e of rows(myExp)) {
        if (e.familyId == null && !e.frozen) tx.update(expenses.doc(e.id), { familyId: fam.id, ...touch(e) });
      }
    }
    return fam.id;
  });
});

/** Same-name categories merge; others go to Boshqa with an auto request. */
export const acceptInvite = onCall(async (req) => {
  const { uid, email } = who(req);
  const inviteId = str(req.data?.inviteId, 400);
  const includeHistory = req.data?.includeHistory === true;
  return db.runTransaction(async (tx) => {
    const inv = await tx.get(invites.doc(inviteId));
    if (!inv.exists || inv.get('email') !== email) fail('invite_not_found');
    const fid: string = inv.get('familyId');
    const [me, myInvites, myCats, famCats, myExp, pending] = await Promise.all([
      tx.get(members.doc(uid)),
      tx.get(invites.where('email', '==', email)),
      tx.get(cats.where('ownerId', '==', uid)),
      famCategories(tx, fid),
      tx.get(expenses.where('ownerId', '==', uid)),
      pendingRequests(tx, fid),
    ]);
    if (me.exists) fail('already_in_family');

    tx.create(members.doc(uid), { familyId: fid, role: 'member', joinedAt: FieldValue.serverTimestamp() });
    for (const d of myInvites.docs) tx.delete(d.ref);
    const famIds = new Map(famCats.map((c) => [key(c.name), c.id]));
    const boshqa = ensureBoshqa(tx, fid, famIds);
    const mine = rows(myCats).filter(live) as (Row & Cat)[];
    const exp = rows(myExp);
    const plan = planJoin(mine, famIds, boshqa, exp, fid, includeHistory);
    applyEdits(tx, exp, plan.edits);
    fileRequests(tx, fid, uid, plan.requests, rows(pending));
    for (const c of mine) tx.update(cats.doc(c.id), { deletedAt: Timestamp.now(), ...touch(c) });
    return fid;
  });
});

/** Admin must transfer first; the last member leaving closes the family. */
export const leaveFamily = onCall(async (req) => {
  const { uid } = who(req);
  await db.runTransaction(async (tx) => {
    const me = await tx.get(members.doc(uid));
    if (!me.exists) fail('not_in_family');
    const fid: string = me.get('familyId');
    const [all, famCats] = await Promise.all([
      tx.get(members.where('familyId', '==', fid)),
      famCategories(tx, fid),
    ]);
    if (me.get('role') === 'admin' && all.size > 1) fail('transfer_admin_first');
    const writes = [await prepFreeze(tx, uid, fid, famCats)];
    if (all.size === 1) writes.push(await prepClose(tx, fid));
    for (const w of writes) w();
  });
  return null;
});

export const removeMember = onCall(async (req) => {
  const { uid } = who(req);
  const user = str(req.data?.userId, 128);
  await db.runTransaction(async (tx) => {
    const fid = await requireAdmin(tx, uid);
    if (user === uid) fail('cannot_remove_self');
    const [m, famCats] = await Promise.all([tx.get(members.doc(user)), famCategories(tx, fid)]);
    if (m.get('familyId') !== fid) fail('not_a_member');
    (await prepFreeze(tx, user, fid, famCats))();
  });
  return null;
});

export const transferAdmin = onCall(async (req) => {
  const { uid } = who(req);
  const user = str(req.data?.userId, 128);
  await db.runTransaction(async (tx) => {
    const fid = await requireAdmin(tx, uid);
    const m = await tx.get(members.doc(user));
    if (user === uid || m.get('familyId') !== fid) fail('not_a_member');
    tx.update(members.doc(uid), { role: 'member' });
    tx.update(m.ref, { role: 'admin' });
  });
  return null;
});

export const deleteFamily = onCall(async (req) => {
  const { uid } = who(req);
  await db.runTransaction(async (tx) => {
    const fid = await requireAdmin(tx, uid);
    const [all, famCats] = await Promise.all([
      tx.get(members.where('familyId', '==', fid)),
      famCategories(tx, fid),
    ]);
    const writes = await Promise.all(all.docs.map((d) => prepFreeze(tx, d.id, fid, famCats)));
    writes.push(await prepClose(tx, fid));
    for (const w of writes) w();
  });
  return null;
});

/** Admin's pending request plus current members' expenses waiting on it. */
async function settle(tx: Transaction, uid: string, id: string) {
  const fid = await requireAdmin(tx, uid);
  const r = await tx.get(requests.doc(id));
  if (r.get('familyId') !== fid || r.get('status') !== 'pending') fail('request_not_found');
  const ids = (await tx.get(members.where('familyId', '==', fid))).docs.map((d) => d.id);
  const [famCats, waiting] = await Promise.all([
    famCategories(tx, fid),
    tx.get(expenses.where('ownerId', 'in', ids).where('pendingCategory', '!=', null)),
  ]);
  const name: string = r.get('name');
  const matching = rows(waiting).filter((e) => !e.frozen && key(e.pendingCategory) === key(name));
  return { fid, ref: r.ref, name, famCats, matching };
}

/** Creates the category (or reuses a same-name one) and moves waiting expenses in. */
export const approveCategoryRequest = onCall(async (req) => {
  const { uid } = who(req);
  const id = str(req.data?.id, 128);
  const colorHex = str(req.data?.colorHex, 6);
  if (!/^[0-9A-Fa-f]{6}$/.test(colorHex)) throw new HttpsError('invalid-argument', 'bad_input');
  const iconKey = req.data?.iconKey == null ? 'category' : str(req.data.iconKey, 40);
  return db.runTransaction(async (tx) => {
    const s = await settle(tx, uid, id);
    const cid =
      s.famCats.find((c) => key(c.name) === key(s.name))?.id ??
      createCategory(tx, randomUUID(), { ownerId: null, familyId: s.fid, name: s.name, colorHex, iconKey });
    for (const e of s.matching) tx.update(expenses.doc(e.id), { categoryId: cid, pendingCategory: null, ...touch(e) });
    tx.update(s.ref, { status: 'approved' });
    return cid;
  });
});

/** Waiting expenses stay in Boshqa. */
export const rejectCategoryRequest = onCall(async (req) => {
  const { uid } = who(req);
  const id = str(req.data?.id, 128);
  await db.runTransaction(async (tx) => {
    const s = await settle(tx, uid, id);
    for (const e of s.matching) tx.update(expenses.doc(e.id), { pendingCategory: null, ...touch(e) });
    tx.update(s.ref, { status: 'rejected' });
  });
  return null;
});

/**
 * The Oila tab for [from, to) (epoch ms): family, per-member shared spending
 * and contribution, and other members' shared expenses (ex-members' frozen
 * rows included). Null when not in a family. Raw budgets and private rows
 * never leave the server.
 */
export const familySummary = onCall(async (req) => {
  const { uid } = who(req);
  const from = millis(req.data?.from);
  const to = millis(req.data?.to);
  const me = await members.doc(uid).get();
  if (!me.exists) return null;
  const fid: string = me.get('familyId');
  const [all, fam, exp] = await Promise.all([
    members.where('familyId', '==', fid).get(),
    families.doc(fid).get(),
    expenses.where('familyId', '==', fid).where('date', '>=', from).where('date', '<', to).get(),
  ]);
  const e = rows(exp);
  const uids = [...new Set([...all.docs.map((d) => d.id), ...e.map((x) => x.ownerId as string)])];
  const profs = new Map((await db.getAll(...uids.map((u) => profiles.doc(u)))).map((p) => [p.id, p.data() ?? {}]));
  const nameOf = (u: string): string => profs.get(u)?.displayName ?? '';
  return {
    id: fid,
    name: fam.get('name'),
    isAdmin: me.get('role') === 'admin',
    members: summarize(
      all.docs.map((d) => ({
        userId: d.id,
        displayName: nameOf(d.id),
        role: d.get('role'),
        budget: profs.get(d.id)?.budget ?? 0,
      })),
      e,
    ),
    others: e
      .filter((x) => x.ownerId !== uid && !x.isPrivate && live(x))
      .map((x) => ({
        id: x.id,
        ownerName: nameOf(x.ownerId),
        categoryId: x.categoryId,
        description: x.description,
        amount: x.amount,
        date: (x.date as Timestamp).toMillis(),
      })),
  };
});

/** Invitee's pending invites, with names they can't read under the rules. */
export const myInvites = onCall(async (req) => {
  const { email } = who(req);
  const inv = rows(await invites.where('email', '==', email).get());
  if (!inv.length) return [];
  const [fams, profs] = await Promise.all([
    db.getAll(...inv.map((i) => families.doc(i.familyId))),
    db.getAll(...inv.map((i) => profiles.doc(i.invitedBy))),
  ]);
  return inv
    .map((i, n) => ({ i, fam: fams[n], by: profs[n] }))
    .filter(({ fam }) => fam.exists && fam.get('deletedAt') == null)
    .map(({ i, fam, by }) => ({
      id: i.id,
      familyName: fam.get('name') as string,
      invitedByName: (by.get('displayName') as string | undefined) ?? '',
      createdAt: (i.createdAt as Timestamp).toMillis(),
    }))
    .sort((a, b) => b.createdAt - a.createdAt);
});

// ---------------------------------------------------------------- trigger

/**
 * A member's expense arrives with pendingCategory (it sits in Boshqa): file
 * the admin request here, on sync, so requests made offline still land. If
 * the family already has that category, move the expense straight into it.
 */
export const onExpenseWritten = onDocumentWritten('expenses/{id}', async (event) => {
  const before = event.data?.before.data();
  const after = event.data?.after.data();
  const pending = after?.pendingCategory;
  if (typeof pending !== 'string' || !pending.trim()) return;
  if (after!.familyId == null || after!.frozen || before?.pendingCategory === pending) return;
  const fid: string = after!.familyId;
  const ref = event.data!.after.ref;
  await db.runTransaction(async (tx) => {
    const [cur, famCats, open] = await Promise.all([tx.get(ref), famCategories(tx, fid), pendingRequests(tx, fid)]);
    if (cur.get('pendingCategory') !== pending) return; // changed since
    const cat = famCats.find((c) => key(c.name) === key(pending));
    if (cat) tx.update(ref, { categoryId: cat.id, pendingCategory: null, ...touch(cur.data()) });
    else fileRequests(tx, fid, after!.ownerId, [pending], rows(open));
  });
});
