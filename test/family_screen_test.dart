import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:expense_tracker/data/db/database.dart';
import 'package:expense_tracker/data/db/tables.dart';
import 'package:expense_tracker/features/family/family_screen.dart';
import 'package:expense_tracker/providers/providers.dart';
import 'package:expense_tracker/services/family_service.dart';

void main() {
  final now = DateTime.now();
  final cat = Category(
    id: 'c1',
    name: 'Groceries',
    iconKey: 'x',
    colorHex: '6FA86A',
    isArchived: false,
    updatedAt: now,
    dirty: false,
  );
  Expense mine(String id, String desc, {String? family = 'f1', bool private = false}) => Expense(
    id: id,
    description: desc,
    amount: 10000,
    categoryId: 'c1',
    date: now,
    source: ExpenseSource.manual,
    familyId: family,
    isPrivate: private,
    frozen: false,
    createdAt: now,
    updatedAt: now,
    dirty: false,
  );

  Widget app({FamilyOverview? family, List<FamilyInvite> invites = const []}) => ProviderScope(
    overrides: [
      familyOverviewProvider.overrideWith((ref) async => family),
      myInvitesProvider.overrideWith((ref) async => invites),
      allCategoriesProvider.overrideWith((ref) => Stream.value([cat])),
      expensesProvider.overrideWith((ref) => Stream.value([
        mine('e1', 'Non'),
        mine('e2', 'Sovg\'a', private: true),
        mine('e3', 'Shaxsiy', family: null),
      ])),
    ],
    child: const MaterialApp(home: Scaffold(body: FamilyScreen())),
  );

  test('family budget is the sum of contributions, spent the sum of shared', () {
    final f = FamilyOverview(id: 'f1', name: 'Uy', myId: 'u1', isAdmin: false, others: [], members: [
      (userId: 'u1', name: 'Ali', isAdmin: true, shared: 300000, contribution: 1000000),
      (userId: 'u2', name: 'Vali', isAdmin: false, shared: 200000, contribution: -50000),
    ]);
    expect(f.budget, 950000);
    expect(f.spent, 500000);
  });

  testWidgets('not in a family: invites and create', (tester) async {
    await tester.pumpWidget(app(invites: [(id: 'i1', familyName: 'Uy', invitedBy: 'Ali')]));
    await tester.pump();
    await tester.pump();
    expect(find.text('Siz hali oilada emassiz'), findsOneWidget);
    expect(find.text('“Uy” oilasiga taklif'), findsOneWidget);
    expect(find.text('Qabul qilish'), findsOneWidget);
    expect(find.text('Oila yaratish'), findsOneWidget);
  });

  testWidgets('in a family: budget, members, shared list, admin requests', (tester) async {
    tester.view.physicalSize = const Size(800, 2400);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(app(
      family: FamilyOverview(
        id: 'f1',
        name: 'Uy',
        myId: 'u1',
        isAdmin: true,
        members: [
          (userId: 'u1', name: 'Ali', isAdmin: true, shared: 10000, contribution: 1000000),
          (userId: 'u2', name: 'Vali', isAdmin: false, shared: 20000, contribution: 500000),
        ],
        others: [
          (id: 'o1', ownerName: 'Vali', categoryId: 'c1', description: 'Taksi', amount: 20000, date: now),
        ],
        requests: [(id: 'r1', name: 'Dorilar')],
      ),
    ));
    await tester.pump();
    await tester.pump();

    expect(find.text('Uy'), findsOneWidget); // title = family name
    expect(find.text('Byudjet: 1 500 000'), findsOneWidget);
    expect(find.text('Ali (siz) · admin'), findsOneWidget);
    expect(find.text('Non'), findsOneWidget, reason: 'own shared row from Drift');
    expect(find.text('Taksi'), findsOneWidget, reason: 'another member\'s row');
    expect(find.text('Sovg\'a'), findsNothing, reason: 'private stays hidden');
    expect(find.text('Shaxsiy'), findsNothing, reason: 'personal, not in the family');
    expect(find.text('Dorilar'), findsOneWidget);
    expect(find.text('Oilani o\'chirish'), findsOneWidget);
  });
}
