import 'package:drift/drift.dart';

/// How an expense was entered.
enum ExpenseSource { typed, voice, manual }

class Categories extends Table {
  IntColumn get id => integer().autoIncrement()();
  TextColumn get name => text().withLength(min: 1, max: 60)();
  TextColumn get iconKey => text().withDefault(const Constant('category'))();
  TextColumn get colorHex => text().withLength(min: 6, max: 6)(); // e.g. E08A5B
  BoolColumn get isArchived => boolean().withDefault(const Constant(false))();
}

class Expenses extends Table {
  IntColumn get id => integer().autoIncrement()();
  TextColumn get description => text()();
  IntColumn get amount => integer()(); // UZS, whole units
  IntColumn get categoryId =>
      integer().references(Categories, #id, onDelete: KeyAction.restrict)();
  DateTimeColumn get date => dateTime()();
  TextColumn get source => textEnum<ExpenseSource>()();
  TextColumn get rawInput => text().nullable()();
  DateTimeColumn get createdAt =>
      dateTime().withDefault(currentDateAndTime)();
}
