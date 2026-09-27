// Pure family rules (ported from supabase/migrations), kept free of Firestore
// so `npm test` can check them without an emulator.

export type Cat = { id: string; name: string; iconKey?: string; colorHex?: string; isArchived?: boolean };
export type Exp = {
  id: string;
  ownerId?: string;
  familyId?: string | null;
  categoryId?: string;
  amount?: number;
  isPrivate?: boolean;
  frozen?: boolean;
  deletedAt?: unknown;
};
/** Expense id -> fields to change. */
export type Edits = Map<string, Record<string, unknown>>;

/** Category names match trimmed and case-insensitive. */
export const key = (name: string) => name.trim().toLowerCase();

function editor() {
  const edits: Edits = new Map();
  const edit = (id: string, f: Record<string, unknown>) => edits.set(id, { ...edits.get(id), ...f });
  return { edits, edit };
}

/**
 * Joining a family: expenses in a same-name family category move there; the
 * rest go to Boshqa with a request (only if the category has live expenses).
 * [myCats] are the joiner's live personal categories, all retired by the caller.
 */
export function planJoin(
  myCats: Cat[],
  famIds: Map<string, string>,
  boshqa: string,
  expenses: Exp[],
  fid: string,
  includeHistory: boolean,
) {
  const { edits, edit } = editor();
  const requests: string[] = [];
  const open = expenses.filter((e) => !e.frozen);
  for (const c of myCats) {
    const target = famIds.get(key(c.name));
    const inCat = open.filter((e) => e.categoryId === c.id);
    if (target) {
      for (const e of inCat) edit(e.id, { categoryId: target });
    } else if (inCat.some((e) => e.deletedAt == null)) {
      for (const e of inCat) edit(e.id, { categoryId: boshqa, pendingCategory: c.name });
      requests.push(c.name);
    }
  }
  if (includeHistory) for (const e of open) if (e.familyId == null) edit(e.id, { familyId: fid });
  return { edits, requests };
}

/**
 * Leaving (or being removed): shared rows freeze as family history; the member
 * gets personal copies of the family's categories (reusing same-name ones) and
 * their personal expenses move onto them.
 */
export function planFreeze(expenses: Exp[], famCats: Cat[], myCats: Cat[], fid: string, newId: () => string) {
  const { edits, edit } = editor();
  const created: Cat[] = [];
  const stays = (e: Exp) => e.familyId !== fid && !e.frozen; // personal, not history
  for (const e of expenses) if (e.familyId === fid && !e.frozen) edit(e.id, { frozen: true });
  const mine = new Map(myCats.map((c) => [key(c.name), c.id]));
  for (const c of famCats) {
    let id = mine.get(key(c.name));
    if (!id) {
      id = newId();
      mine.set(key(c.name), id);
      created.push({ ...c, id });
    }
    for (const e of expenses) {
      if (e.categoryId === c.id && stays(e)) edit(e.id, { categoryId: id, pendingCategory: null });
    }
  }
  return { edits, created };
}

/**
 * Per member: shared spending and budget contribution (budget minus own
 * private spending; 0 when no budget). Raw budgets never leave this function.
 */
export function summarize(
  members: { userId: string; displayName: string; role: string; budget: number }[],
  expenses: Exp[],
) {
  return members.map((m) => {
    let shared = 0;
    let priv = 0;
    for (const e of expenses) {
      if (e.ownerId !== m.userId || e.deletedAt != null) continue;
      if (e.isPrivate) priv += e.amount ?? 0;
      else shared += e.amount ?? 0;
    }
    return {
      userId: m.userId,
      displayName: m.displayName,
      role: m.role,
      shared,
      contribution: m.budget ? m.budget - priv : 0,
    };
  });
}
