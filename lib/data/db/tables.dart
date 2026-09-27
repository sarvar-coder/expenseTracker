import 'package:drift/drift.dart';
import 'package:uuid/uuid.dart';

/// How an expense was entered.
enum ExpenseSource { typed, voice, manual }

const _uuid = Uuid();

/// Client-generated UUID, so rows made offline keep their id on the server.
String newId() => _uuid.v4();

/// Sync bookkeeping shared by synced tables. Mirrors the Firestore fields;
/// [dirty] is local-only (row changed since last push).
mixin Synced on Table {
  TextColumn get id => text().clientDefault(newId)();
  TextColumn get ownerId => text().nullable()(); // null until first sign-in
  TextColumn get familyId => text().nullable()();
  DateTimeColumn get updatedAt => dateTime().clientDefault(DateTime.now)();
  DateTimeColumn get deletedAt => dateTime().nullable()(); // soft delete
  BoolColumn get dirty => boolean().withDefault(const Constant(true))();

  @override
  Set<Column> get primaryKey => {id};
}

class Categories extends Table with Synced {
  TextColumn get name => text().withLength(min: 1, max: 60)();
  TextColumn get iconKey => text().withDefault(const Constant('category'))();
  TextColumn get colorHex => text().withLength(min: 6, max: 6)(); // e.g. E08A5B
  BoolColumn get isArchived => boolean().withDefault(const Constant(false))();
}

class Expenses extends Table with Synced {
  TextColumn get description => text()();
  IntColumn get amount => integer()(); // UZS, whole units
  TextColumn get categoryId =>
      text().references(Categories, #id, onDelete: KeyAction.restrict)();
  DateTimeColumn get date => dateTime()();
  TextColumn get source => textEnum<ExpenseSource>()();
  TextColumn get rawInput => text().nullable()();
  BoolColumn get isPrivate => boolean().withDefault(const Constant(false))();
  TextColumn get pendingCategory => text().nullable()(); // awaiting admin approval
  BoolColumn get frozen => boolean().withDefault(const Constant(false))(); // ex-member history
  DateTimeColumn get createdAt =>
      dateTime().withDefault(currentDateAndTime)();
}
