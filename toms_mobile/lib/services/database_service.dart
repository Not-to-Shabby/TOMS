import 'package:sqflite/sqflite.dart';
import 'package:path/path.dart';
import '../models/models.dart';

/// Local SQLite database for offline-first transaction caching.
class DatabaseService {
  static Database? _db;

  Future<Database> get database async {
    if (_db != null) return _db!;
    _db = await _initDb();
    return _db!;
  }

  Future<Database> _initDb() async {
    final dbPath = await getDatabasesPath();
    final path = join(dbPath, 'toms.db');

    return openDatabase(
      path,
      version: 2,
      onCreate: (db, version) async {
        await db.execute('''
          CREATE TABLE passenger_logs (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            timestamp INTEGER NOT NULL,
            boarding_type INTEGER NOT NULL,
            fare_centavos INTEGER NOT NULL,
            seat_number INTEGER NOT NULL,
            route_id INTEGER NOT NULL,
            passenger_id TEXT NOT NULL,
            synced INTEGER DEFAULT 0,
            passenger_type INTEGER DEFAULT 0,
            discount_centavos INTEGER DEFAULT 0,
            boarding_stop TEXT DEFAULT '',
            destination_stop TEXT DEFAULT '',
            destination_lat REAL DEFAULT 0,
            destination_lon REAL DEFAULT 0
          )
        ''');
        await db.execute('''
          CREATE TABLE occupancy_events (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            event_type TEXT NOT NULL,
            timestamp INTEGER NOT NULL,
            payload TEXT NOT NULL,
            synced INTEGER DEFAULT 0
          )
        ''');
      },
      onUpgrade: (db, oldVersion, newVersion) async {
        if (oldVersion < 2) {
          await db.execute('ALTER TABLE passenger_logs ADD COLUMN passenger_type INTEGER DEFAULT 0');
          await db.execute('ALTER TABLE passenger_logs ADD COLUMN discount_centavos INTEGER DEFAULT 0');
          await db.execute('ALTER TABLE passenger_logs ADD COLUMN boarding_stop TEXT DEFAULT ""');
          await db.execute('ALTER TABLE passenger_logs ADD COLUMN destination_stop TEXT DEFAULT ""');
          await db.execute('ALTER TABLE passenger_logs ADD COLUMN destination_lat REAL DEFAULT 0');
          await db.execute('ALTER TABLE passenger_logs ADD COLUMN destination_lon REAL DEFAULT 0');
          await db.execute('''
            CREATE TABLE occupancy_events (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              event_type TEXT NOT NULL,
              timestamp INTEGER NOT NULL,
              payload TEXT NOT NULL,
              synced INTEGER DEFAULT 0
            )
          ''');
        }
      },
    );
  }

  /// Insert a new passenger boarding event.
  Future<int> insertLog(PassengerLog log) async {
    final db = await database;
    return db.insert('passenger_logs', log.toMap());
  }

  /// Insert a new occupancy event to sync queue.
  Future<int> insertEvent(String eventType, int timestamp, String payload) async {
    final db = await database;
    return db.insert('occupancy_events', {
      'event_type': eventType,
      'timestamp': timestamp,
      'payload': payload,
      'synced': 0,
    });
  }

  /// Get all logs, newest first.
  Future<List<PassengerLog>> getAllLogs({int limit = 50}) async {
    final db = await database;
    final maps = await db.query(
      'passenger_logs',
      orderBy: 'timestamp DESC',
      limit: limit,
    );
    return maps.map((m) => PassengerLog.fromMap(m)).toList();
  }

  /// Get unsynced occupancy events for cloud push.
  Future<List<Map<String, dynamic>>> getUnsyncedEvents() async {
    final db = await database;
    return db.query(
      'occupancy_events',
      where: 'synced = 0',
      orderBy: 'timestamp ASC',
    );
  }

  /// Mark occupancy events as synced.
  Future<void> markEventsSynced(List<int> ids) async {
    final db = await database;
    final batch = db.batch();
    for (final id in ids) {
      batch.update(
        'occupancy_events',
        {'synced': 1},
        where: 'id = ?',
        whereArgs: [id],
      );
    }
    await batch.commit(noResult: true);
  }

  /// Get unsynced logs for cloud push.
  Future<List<PassengerLog>> getUnsyncedLogs() async {
    final db = await database;
    final maps = await db.query(
      'passenger_logs',
      where: 'synced = 0',
      orderBy: 'timestamp ASC',
    );
    return maps.map((m) => PassengerLog.fromMap(m)).toList();
  }

  /// Mark logs as synced.
  Future<void> markSynced(List<int> ids) async {
    final db = await database;
    final batch = db.batch();
    for (final id in ids) {
      batch.update(
        'passenger_logs',
        {'synced': 1},
        where: 'id = ?',
        whereArgs: [id],
      );
    }
    await batch.commit(noResult: true);
  }

  /// Get total transaction count.
  Future<int> getLogCount() async {
    final db = await database;
    final result = await db.rawQuery('SELECT COUNT(*) as cnt FROM passenger_logs');
    return Sqflite.firstIntValue(result) ?? 0;
  }

  /// Get today's revenue in centavos.
  Future<int> getTodayRevenue() async {
    final db = await database;
    final now = DateTime.now();
    final startOfDay = DateTime(now.year, now.month, now.day).millisecondsSinceEpoch ~/ 1000;
    final result = await db.rawQuery(
      'SELECT COALESCE(SUM(fare_centavos), 0) as total FROM passenger_logs WHERE timestamp >= ?',
      [startOfDay],
    );
    return Sqflite.firstIntValue(result) ?? 0;
  }

  /// Get today's passenger count.
  Future<int> getTodayPassengerCount() async {
    final db = await database;
    final now = DateTime.now();
    final startOfDay = DateTime(now.year, now.month, now.day).millisecondsSinceEpoch ~/ 1000;
    final result = await db.rawQuery(
      'SELECT COUNT(*) as cnt FROM passenger_logs WHERE timestamp >= ?',
      [startOfDay],
    );
    return Sqflite.firstIntValue(result) ?? 0;
  }
}
