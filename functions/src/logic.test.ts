import assert from 'node:assert/strict';
import { test } from 'node:test';
import { planFreeze, planJoin, summarize } from './logic.ts';

test('join: same-name merges, unmatched goes to Boshqa + request', () => {
  const { edits, requests } = planJoin(
    [
      { id: 'food', name: ' FOOD ' },
      { id: 'pets', name: 'Pets' },
      { id: 'empty', name: 'Unused' },
    ],
    new Map([['food', 'famFood'], ['boshqa', 'b']]),
    'b',
    [
      { id: 'e1', categoryId: 'food', familyId: null },
      { id: 'e2', categoryId: 'pets', familyId: null },
      { id: 'e3', categoryId: 'pets', frozen: true, familyId: 'old' },
    ],
    'fam',
    true,
  );
  assert.deepEqual(edits.get('e1'), { categoryId: 'famFood', familyId: 'fam' });
  assert.deepEqual(edits.get('e2'), { categoryId: 'b', pendingCategory: 'Pets', familyId: 'fam' });
  assert.equal(edits.has('e3'), false); // frozen history untouched
  assert.deepEqual(requests, ['Pets']);
});

test('freeze: shared rows freeze, personal ones move to personal copies', () => {
  const { edits, created } = planFreeze(
    [
      { id: 'shared', familyId: 'fam', categoryId: 'famFood' },
      { id: 'personal', familyId: null, categoryId: 'famFood' },
      { id: 'mine', familyId: null, categoryId: 'famBills' },
    ],
    [
      { id: 'famFood', name: 'Food', colorHex: 'E08A5B' },
      { id: 'famBills', name: 'Bills' },
    ],
    [{ id: 'myBills', name: 'bills' }],
    'fam',
    () => 'new',
  );
  assert.deepEqual(edits.get('shared'), { frozen: true });
  assert.deepEqual(edits.get('personal'), { categoryId: 'new', pendingCategory: null });
  assert.deepEqual(edits.get('mine'), { categoryId: 'myBills', pendingCategory: null });
  assert.deepEqual(created, [{ id: 'new', name: 'Food', colorHex: 'E08A5B' }]);
});

test('summary: contribution = budget - private; no budget = 0', () => {
  const rows = summarize(
    [
      { userId: 'a', displayName: 'A', role: 'admin', budget: 1000 },
      { userId: 'b', displayName: 'B', role: 'member', budget: 0 },
    ],
    [
      { id: '1', ownerId: 'a', amount: 300 },
      { id: '2', ownerId: 'a', amount: 1500, isPrivate: true },
      { id: '3', ownerId: 'a', amount: 99, deletedAt: 'x' },
      { id: '4', ownerId: 'b', amount: 50, isPrivate: true },
    ],
  );
  assert.deepEqual(rows.map((r) => [r.shared, r.contribution]), [[300, -500], [0, 0]]);
});
