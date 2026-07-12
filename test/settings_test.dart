import 'package:drift/native.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:expense_tracker/data/db/database.dart';

void main() {
  late AppDatabase db;
  setUp(() => db = AppDatabase.forTesting(NativeDatabase.memory()));
  tearDown(() => db.close());

  test('rename category persists new name', () async {
    final food = (await db.getCategories()).firstWhere((c) => c.name == 'Food & dining');
    await db.updateCategory(food.copyWith(name: 'Eating out'));
    final again = (await db.getCategories()).firstWhere((c) => c.id == food.id);
    expect(again.name, 'Eating out');
  });

  test('archive hides from getCategories but stays in getAllCategories; unarchive restores', () async {
    final all = await db.getCategories();
    final transport = all.firstWhere((c) => c.name == 'Transport');

    await db.updateCategory(transport.copyWith(isArchived: true));
    expect((await db.getCategories()).any((c) => c.id == transport.id), isFalse);
    expect((await db.getAllCategories()).any((c) => c.id == transport.id), isTrue);

    final archived = (await db.getAllCategories()).firstWhere((c) => c.id == transport.id);
    await db.updateCategory(archived.copyWith(isArchived: false));
    expect((await db.getCategories()).any((c) => c.id == transport.id), isTrue);
  });
}
