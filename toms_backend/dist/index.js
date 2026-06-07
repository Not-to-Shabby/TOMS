"use strict";
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
const express_1 = __importDefault(require("express"));
const http_1 = __importDefault(require("http"));
const socket_io_1 = require("socket.io");
const cors_1 = __importDefault(require("cors"));
const dotenv_1 = __importDefault(require("dotenv"));
const sqlite3_1 = __importDefault(require("sqlite3"));
const path_1 = __importDefault(require("path"));
const crypto_1 = __importDefault(require("crypto"));
dotenv_1.default.config();
const hashPassword = (password) => crypto_1.default.createHash('sha256').update(password).digest('hex');
const app = (0, express_1.default)();
const server = http_1.default.createServer(app);
const io = new socket_io_1.Server(server, {
    cors: {
        origin: '*', // For development
    }
});
app.use((0, cors_1.default)());
app.use(express_1.default.json());
// Initialize SQLite database
const dbPath = path_1.default.join(__dirname, '..', 'toms_backend.db');
const db = new sqlite3_1.default.Database(dbPath, (err) => {
    if (err) {
        console.error('Error opening database', err);
    }
    else {
        console.log('Connected to the SQLite database.');
        initDb();
    }
});
function initDb() {
    db.serialize(() => {
        db.run(`CREATE TABLE IF NOT EXISTS routes (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      name TEXT NOT NULL,
      company_id TEXT,
      base_fare REAL DEFAULT 15.0,
      per_km_fare REAL DEFAULT 2.5
    )`);
        db.run(`CREATE TABLE IF NOT EXISTS route_stops (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      route_id INTEGER,
      path_id INTEGER,
      name TEXT NOT NULL,
      lat REAL NOT NULL,
      lon REAL NOT NULL,
      stop_order INTEGER NOT NULL,
      radius_m INTEGER DEFAULT 100,
      FOREIGN KEY (route_id) REFERENCES routes (id) ON DELETE CASCADE
    )`);
        db.run(`CREATE TABLE IF NOT EXISTS route_paths (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      route_id INTEGER NOT NULL,
      name TEXT NOT NULL DEFAULT 'Primary',
      color TEXT NOT NULL DEFAULT '#00d2ff',
      FOREIGN KEY (route_id) REFERENCES routes (id) ON DELETE CASCADE
    )`);
        db.run(`CREATE TABLE IF NOT EXISTS route_schedules (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      route_id INTEGER NOT NULL,
      path_id INTEGER NOT NULL,
      active_days TEXT NOT NULL,
      start_time TEXT NOT NULL,
      end_time TEXT NOT NULL,
      FOREIGN KEY (route_id) REFERENCES routes (id) ON DELETE CASCADE,
      FOREIGN KEY (path_id) REFERENCES route_paths (id) ON DELETE CASCADE
    )`);
        // In case tables already exist, try to add the new columns (safe upgrades)
        db.run('ALTER TABLE routes ADD COLUMN base_fare REAL DEFAULT 15.0', () => { });
        db.run('ALTER TABLE routes ADD COLUMN per_km_fare REAL DEFAULT 2.5', () => { });
        db.run('ALTER TABLE route_stops ADD COLUMN radius_m INTEGER DEFAULT 100', () => { });
        db.run('ALTER TABLE route_stops ADD COLUMN path_id INTEGER', () => {
            // Migrate existing stops to a default path after column is created
            db.all('SELECT id FROM routes', [], (err, routes) => {
                if (!err && routes) {
                    routes.forEach(r => {
                        db.get('SELECT id FROM route_paths WHERE route_id = ?', [r.id], (err, row) => {
                            if (!row) {
                                db.run('INSERT INTO route_paths (route_id, name, color) VALUES (?, ?, ?)', [r.id, 'Primary', '#00d2ff'], function () {
                                    const newPathId = this.lastID;
                                    db.run('UPDATE route_stops SET path_id = ? WHERE route_id = ? AND path_id IS NULL', [newPathId, r.id]);
                                });
                            }
                            else {
                                db.run('UPDATE route_stops SET path_id = ? WHERE route_id = ? AND path_id IS NULL', [row.id, r.id]);
                            }
                        });
                    });
                }
            });
        });
        db.run(`CREATE TABLE IF NOT EXISTS conductors (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      username TEXT UNIQUE NOT NULL,
      password_hash TEXT NOT NULL,
      name TEXT NOT NULL,
      contact_number TEXT,
      address TEXT,
      company_id TEXT DEFAULT 'company_1',
      assigned_vehicle TEXT
    )`, () => {
            // Migration: Add new columns if they don't exist
            db.all("PRAGMA table_info(conductors)", (err, rows) => {
                if (!err && rows) {
                    const columns = rows.map((r) => r.name);
                    if (!columns.includes('contact_number'))
                        db.run("ALTER TABLE conductors ADD COLUMN contact_number TEXT");
                    if (!columns.includes('address'))
                        db.run("ALTER TABLE conductors ADD COLUMN address TEXT");
                }
            });
            // Insert a default admin/test conductor if none exists
            db.get('SELECT COUNT(*) as count FROM conductors', (err, row) => {
                if (row && row.count === 0) {
                    db.run('INSERT INTO conductors (username, password_hash, name, contact_number, address, assigned_vehicle) VALUES (?, ?, ?, ?, ?, ?)', ['test1', hashPassword('password123'), 'John Doe', '09123456789', 'Main St, Iligan City', 'BUS-101']);
                }
            });
        });
        db.run(`CREATE TABLE IF NOT EXISTS vehicles (
      id TEXT PRIMARY KEY,
      plate_number TEXT NOT NULL,
      max_capacity INTEGER NOT NULL DEFAULT 20,
      status TEXT DEFAULT 'Active',
      assigned_route_id INTEGER,
      assigned_conductor_id INTEGER,
      FOREIGN KEY (assigned_route_id) REFERENCES routes (id) ON DELETE SET NULL,
      FOREIGN KEY (assigned_conductor_id) REFERENCES conductors (id) ON DELETE SET NULL
    )`, () => {
            // Migration: Add columns if missing
            db.all("PRAGMA table_info(vehicles)", (err, rows) => {
                if (!err && rows) {
                    const columns = rows.map((r) => r.name);
                    if (!columns.includes('assigned_route_id'))
                        db.run("ALTER TABLE vehicles ADD COLUMN assigned_route_id INTEGER");
                    if (!columns.includes('assigned_conductor_id'))
                        db.run("ALTER TABLE vehicles ADD COLUMN assigned_conductor_id INTEGER");
                }
            });
            // Seed default vehicles if none exist
            db.get('SELECT COUNT(*) as count FROM vehicles', (err, row) => {
                if (row && row.count === 0) {
                    const stmt = db.prepare('INSERT INTO vehicles (id, plate_number, max_capacity, status) VALUES (?, ?, ?, ?)');
                    stmt.run(['BUS-101', 'ABC-1234', 20, 'Active']);
                    stmt.run(['BUS-102', 'XYZ-9876', 20, 'Active']);
                    stmt.run(['BUS-203', 'LMN-4567', 20, 'Active']);
                    stmt.finalize();
                }
            });
        });
        db.run(`CREATE TABLE IF NOT EXISTS events (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      event_type TEXT NOT NULL,
      timestamp TEXT NOT NULL,
      vehicle_id TEXT NOT NULL,
      route TEXT,
      slot_number INTEGER,
      slave_uid TEXT,
      passenger_type TEXT,
      fare_centavos INTEGER,
      discount_centavos INTEGER,
      boarding_stop TEXT,
      destination_stop TEXT,
      occupancy_now INTEGER,
      max_capacity INTEGER,
      seat_map TEXT,
      conductor_id TEXT,
      conductor_name TEXT,
      current_lat REAL,
      current_lon REAL
    )`, () => {
            // Migration: Add conductor tracking and GPS columns if they don't exist
            db.all("PRAGMA table_info(events)", (err, rows) => {
                if (!err && rows) {
                    const columns = rows.map((r) => r.name);
                    if (!columns.includes('conductor_id'))
                        db.run("ALTER TABLE events ADD COLUMN conductor_id TEXT");
                    if (!columns.includes('conductor_name'))
                        db.run("ALTER TABLE events ADD COLUMN conductor_name TEXT");
                    if (!columns.includes('current_lat'))
                        db.run("ALTER TABLE events ADD COLUMN current_lat REAL");
                    if (!columns.includes('current_lon'))
                        db.run("ALTER TABLE events ADD COLUMN current_lon REAL");
                }
            });
        });
        // Insert a default route if none exists
        db.get('SELECT COUNT(*) as count FROM routes', (err, row) => {
            if (row.count === 0) {
                db.run('INSERT INTO routes (name, company_id) VALUES (?, ?)', ['Default Route', 'company_1'], function (err) {
                    if (!err) {
                        console.log('Created default route with ID:', this.lastID);
                    }
                });
            }
        });
    });
}
// Basic health check route
app.get('/health', (req, res) => {
    res.json({ status: 'ok', timestamp: new Date() });
});
// GET all routes
app.get('/api/routes', (req, res) => {
    db.all('SELECT id, name, company_id, base_fare, per_km_fare FROM routes ORDER BY id ASC', [], (err, rows) => {
        if (err) {
            res.status(500).json({ error: err.message });
            return;
        }
        res.json(rows);
    });
});
// POST a new route
app.post('/api/routes', (req, res) => {
    const { name, company_id } = req.body;
    db.run('INSERT INTO routes (name, company_id) VALUES (?, ?)', [name, company_id || 'company_1'], function (err) {
        if (err) {
            res.status(500).json({ error: err.message });
            return;
        }
        res.json({
            id: this.lastID,
            name: name,
            company_id: company_id || 'company_1',
            base_fare: 15.0,
            per_km_fare: 2.5
        });
    });
});
// DELETE a route
app.delete('/api/routes/:id', (req, res) => {
    db.run('DELETE FROM routes WHERE id = ?', [req.params.id], function (err) {
        if (err)
            return res.status(500).json({ error: err.message });
        res.json({ status: 'success' });
    });
});
// POST update route fare matrix
app.post('/api/routes/:id/fare', (req, res) => {
    const { base_fare, per_km_fare } = req.body;
    db.run('UPDATE routes SET base_fare = ?, per_km_fare = ? WHERE id = ?', [base_fare, per_km_fare, req.params.id], function (err) {
        if (err)
            return res.status(500).json({ error: err.message });
        res.json({ status: 'success' });
    });
});
// --- NEW MULTI-PATH APIS ---
// GET route paths and their stops
app.get('/api/routes/:id/paths', (req, res) => {
    const routeId = req.params.id;
    db.all('SELECT id, name, color FROM route_paths WHERE route_id = ? ORDER BY id ASC', [routeId], (err, paths) => {
        if (err)
            return res.status(500).json({ error: err.message });
        if (paths.length === 0) {
            // If no paths exist yet (new route), return empty array
            return res.json([]);
        }
        db.all('SELECT * FROM route_schedules WHERE route_id = ?', [routeId], (err, schedules) => {
            const scheduleMap = {};
            (schedules || []).forEach(s => scheduleMap[s.path_id] = s);
            let pathsProcessed = 0;
            paths.forEach(p => {
                p.schedule = scheduleMap[p.id] || null;
                db.all('SELECT id, name, lat, lon, stop_order, radius_m FROM route_stops WHERE path_id = ? ORDER BY stop_order ASC', [p.id], (err, stops) => {
                    p.stops = (stops || []).map((r) => ({
                        id: r.id,
                        name: r.name,
                        lat: r.lat,
                        lon: r.lon,
                        radius_m: r.radius_m || 100
                    }));
                    pathsProcessed++;
                    if (pathsProcessed === paths.length) {
                        res.json(paths);
                    }
                });
            });
        });
    });
});
// POST a new path
app.post('/api/routes/:id/paths', (req, res) => {
    const routeId = req.params.id;
    const { name, color } = req.body;
    db.run('INSERT INTO route_paths (route_id, name, color) VALUES (?, ?, ?)', [routeId, name || 'Alternative', color || '#2ed573'], function (err) {
        if (err)
            return res.status(500).json({ error: err.message });
        res.json({ id: this.lastID, route_id: routeId, name: name || 'Alternative', color: color || '#2ed573', stops: [] });
    });
});
// POST update stops for a specific path
app.post('/api/routes/:id/paths/:pathId/stops', (req, res) => {
    const routeId = req.params.id;
    const pathId = req.params.pathId;
    const stops = req.body;
    db.serialize(() => {
        db.run('BEGIN TRANSACTION');
        db.run('DELETE FROM route_stops WHERE path_id = ?', [pathId]);
        if (stops && stops.length > 0) {
            const stmt = db.prepare('INSERT INTO route_stops (route_id, path_id, name, lat, lon, stop_order, radius_m) VALUES (?, ?, ?, ?, ?, ?, ?)');
            stops.forEach((stop, index) => {
                stmt.run([routeId, pathId, stop.name, stop.lat, stop.lon, index, stop.radius_m || 100]);
            });
            stmt.finalize();
        }
        db.run('COMMIT', (err) => {
            if (err)
                return res.status(500).json({ error: 'Transaction failed' });
            res.json({ status: 'success' });
        });
    });
});
// DELETE a path
app.delete('/api/routes/:id/paths/:pathId', (req, res) => {
    db.run('DELETE FROM route_paths WHERE id = ?', [req.params.pathId], (err) => {
        if (err)
            return res.status(500).json({ error: err.message });
        res.json({ status: 'success' });
    });
});
// --- SCHEDULE APIs (Phase 4h) ---
// GET schedules for a route
app.get('/api/routes/:id/schedules', (req, res) => {
    db.all('SELECT * FROM route_schedules WHERE route_id = ?', [req.params.id], (err, rows) => {
        if (err)
            return res.status(500).json({ error: err.message });
        res.json(rows || []);
    });
});
// POST save schedules for a route
app.post('/api/routes/:id/schedules', (req, res) => {
    const routeId = req.params.id;
    const schedules = req.body;
    if (!Array.isArray(schedules))
        return res.status(400).json({ error: 'Expected array of schedules' });
    db.serialize(() => {
        db.run('BEGIN TRANSACTION');
        db.run('DELETE FROM route_schedules WHERE route_id = ?', [routeId]);
        if (schedules.length > 0) {
            const stmt = db.prepare('INSERT INTO route_schedules (route_id, path_id, active_days, start_time, end_time) VALUES (?, ?, ?, ?, ?)');
            schedules.forEach(s => {
                stmt.run([routeId, s.path_id, s.active_days, s.start_time, s.end_time]);
            });
            stmt.finalize();
        }
        db.run('COMMIT', (err) => {
            if (err)
                return res.status(500).json({ error: 'Transaction failed' });
            res.json({ status: 'success' });
        });
    });
});
// --- NEW CONDUCTOR AUTH APIS (Phase 4e) ---
app.post('/api/conductors/login', (req, res) => {
    const { username, password } = req.body;
    const hashed = hashPassword(password);
    db.get('SELECT id, username, name, company_id, assigned_vehicle FROM conductors WHERE username = ? AND password_hash = ?', [username, hashed], (err, row) => {
        if (err)
            return res.status(500).json({ error: err.message });
        if (!row)
            return res.status(401).json({ error: 'Invalid username or password' });
        // Simple mock JWT format for prototype
        const token = `jwt_${row.id}_${Date.now()}`;
        res.json({ token, user: row });
    });
});
// POST a new conductor
app.post('/api/conductors', (req, res) => {
    const { username, password, name, contact_number, address, assigned_vehicle } = req.body;
    if (!username || !password || !name) {
        return res.status(400).json({ error: 'Username, password, and name are required' });
    }
    const hash = hashPassword(password);
    db.run('INSERT INTO conductors (username, password_hash, name, contact_number, address, assigned_vehicle) VALUES (?, ?, ?, ?, ?, ?)', [username, hash, name, contact_number || null, address || null, assigned_vehicle || null], function (err) {
        if (err) {
            if (err.message.includes('UNIQUE constraint failed')) {
                return res.status(400).json({ error: 'Username already exists' });
            }
            return res.status(500).json({ error: err.message });
        }
        res.json({ id: this.lastID, username, name, contact_number, address, assigned_vehicle });
    });
});
app.delete('/api/conductors/:id', (req, res) => {
    db.run('DELETE FROM conductors WHERE id = ?', [req.params.id], (err) => {
        if (err)
            return res.status(500).json({ error: err.message });
        res.json({ status: 'success' });
    });
});
// --- FLEET MANAGEMENT APIS ---
// GET all vehicles
app.get('/api/vehicles', (req, res) => {
    db.all(`
    SELECT v.*, r.name as assigned_route_name, c.name as assigned_conductor_name
    FROM vehicles v
    LEFT JOIN routes r ON v.assigned_route_id = r.id
    LEFT JOIN conductors c ON v.assigned_conductor_id = c.id
  `, [], (err, rows) => {
        if (err)
            return res.status(500).json({ error: err.message });
        res.json(rows);
    });
});
// POST a new vehicle
app.post('/api/vehicles', (req, res) => {
    const { id, plate_number, max_capacity, status } = req.body;
    if (!id || !plate_number)
        return res.status(400).json({ error: 'id and plate_number are required' });
    db.run('INSERT INTO vehicles (id, plate_number, max_capacity, status) VALUES (?, ?, ?, ?)', [id, plate_number, max_capacity || 20, status || 'Active'], function (err) {
        if (err)
            return res.status(500).json({ error: err.message });
        res.json({ id, plate_number, max_capacity: max_capacity || 20, status: status || 'Active' });
    });
});
// PUT update a vehicle (including dispatch assignments)
app.put('/api/vehicles/:id', (req, res) => {
    const { plate_number, max_capacity, status, assigned_route_id, assigned_conductor_id } = req.body;
    const id = req.params.id;
    // First, if assigning a conductor, clear their assignment from any other vehicle
    db.serialize(() => {
        db.run('BEGIN TRANSACTION');
        if (assigned_conductor_id) {
            db.run('UPDATE vehicles SET assigned_conductor_id = NULL WHERE assigned_conductor_id = ? AND id != ?', [assigned_conductor_id, id]);
        }
        db.run('UPDATE vehicles SET plate_number = COALESCE(?, plate_number), max_capacity = COALESCE(?, max_capacity), status = COALESCE(?, status), assigned_route_id = ?, assigned_conductor_id = ? WHERE id = ?', [plate_number, max_capacity, status, assigned_route_id || null, assigned_conductor_id || null, id], function (err) {
            if (err) {
                db.run('ROLLBACK');
                return res.status(500).json({ error: err.message });
            }
            db.run('COMMIT');
            // Tell WebSocket clients that dispatch updated so they can refresh
            io.emit('dispatch_updated', { vehicle_id: id });
            res.json({ status: 'success' });
        });
    });
});
// DELETE a vehicle
app.delete('/api/vehicles/:id', (req, res) => {
    db.run('DELETE FROM vehicles WHERE id = ?', [req.params.id], (err) => {
        if (err)
            return res.status(500).json({ error: err.message });
        res.json({ status: 'success' });
    });
});
// --- LEGACY STOPS API (Kept for backward compatibility if needed) ---
// GET route stops
app.get('/api/routes/:id/stops', (req, res) => {
    const routeId = req.params.id;
    db.all('SELECT id, name, lat, lon, stop_order, radius_m FROM route_stops WHERE route_id = ? ORDER BY stop_order ASC', [routeId], (err, rows) => {
        if (err) {
            res.status(500).json({ error: err.message });
            return;
        }
        // Map to expected format
        const stops = rows.map((r) => ({
            id: r.id,
            name: r.name,
            lat: r.lat,
            lon: r.lon,
            radius_m: r.radius_m || 100
        }));
        res.json(stops);
    });
});
// POST update route stops (Replaces all stops for the route)
app.post('/api/routes/:id/stops', (req, res) => {
    const routeId = req.params.id;
    const stops = req.body; // Array of stops
    if (!Array.isArray(stops)) {
        res.status(400).json({ error: 'Expected an array of stops' });
        return;
    }
    db.serialize(() => {
        db.run('BEGIN TRANSACTION');
        db.run('DELETE FROM route_stops WHERE route_id = ?', [routeId]);
        const stmt = db.prepare('INSERT INTO route_stops (route_id, name, lat, lon, stop_order, radius_m) VALUES (?, ?, ?, ?, ?, ?)');
        stops.forEach((stop, index) => {
            stmt.run([routeId, stop.name, stop.lat, stop.lon, index, stop.radius_m || 100]);
        });
        stmt.finalize();
        db.run('COMMIT', (err) => {
            if (err) {
                res.status(500).json({ error: 'Transaction failed', details: err.message });
            }
            else {
                res.json({ status: 'success', message: 'Route stops updated successfully' });
            }
        });
    });
});
// POST sync events from mobile app
app.post('/api/events', (req, res) => {
    const events = Array.isArray(req.body) ? req.body : [req.body];
    if (events.length === 0) {
        res.json({ status: 'success', synced: 0 });
        return;
    }
    db.serialize(() => {
        db.run('BEGIN TRANSACTION');
        const stmt = db.prepare(`
      INSERT INTO events (
        event_type, timestamp, vehicle_id, route, slot_number, slave_uid, 
        passenger_type, fare_centavos, discount_centavos, boarding_stop, 
        destination_stop, occupancy_now, max_capacity, seat_map,
        conductor_id, conductor_name, current_lat, current_lon
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    `);
        let latestFleetStatus = {};
        events.forEach(event => {
            stmt.run([
                event.event_type,
                event.timestamp,
                event.vehicle_id,
                event.route || null,
                event.slot_number || null,
                event.slave_uid || null,
                event.passenger_type || null,
                event.fare_centavos,
                event.discount_centavos,
                event.boarding_stop,
                event.destination_stop,
                event.occupancy_now,
                event.max_capacity,
                event.seat_map || null,
                event.conductor_id || null,
                event.conductor_name || null,
                event.current_lat || null,
                event.current_lon || null
            ]);
            // Track latest status per vehicle to emit via socket
            if (!latestFleetStatus[event.vehicle_id]) {
                latestFleetStatus[event.vehicle_id] = {
                    vehicle_id: event.vehicle_id,
                    route: event.route,
                    occupancy_now: event.occupancy_now,
                    max_capacity: event.max_capacity,
                    seat_map: event.seat_map,
                    last_updated: event.timestamp,
                    current_lat: event.current_lat,
                    current_lon: event.current_lon,
                    daily_revenue: 0 // Will populate next
                };
            }
            else {
                latestFleetStatus[event.vehicle_id] = {
                    ...latestFleetStatus[event.vehicle_id],
                    occupancy_now: event.occupancy_now,
                    seat_map: event.seat_map,
                    last_updated: event.timestamp,
                    current_lat: event.current_lat || latestFleetStatus[event.vehicle_id].current_lat,
                    current_lon: event.current_lon || latestFleetStatus[event.vehicle_id].current_lon
                };
            }
        });
        stmt.finalize();
        db.run('COMMIT', (err) => {
            if (err) {
                res.status(500).json({ error: 'Transaction failed', details: err.message });
            }
            else {
                // Calculate today's revenue for the updated vehicles
                const todayStr = new Date().toISOString().split('T')[0];
                db.all(`SELECT vehicle_id, SUM(fare_centavos) as daily_revenue 
           FROM events 
           WHERE timestamp LIKE ? AND event_type = 'payment' 
           GROUP BY vehicle_id`, [`${todayStr}%`], (err, rows) => {
                    if (!err && rows) {
                        rows.forEach((r) => {
                            if (latestFleetStatus[r.vehicle_id]) {
                                latestFleetStatus[r.vehicle_id].daily_revenue = r.daily_revenue || 0;
                            }
                        });
                    }
                    // Emit live update to all connected dashboard clients
                    Object.values(latestFleetStatus).forEach(status => {
                        io.emit('fleet_update', status);
                    });
                    res.json({ status: 'success', synced: events.length });
                });
            }
        });
    });
});
// DEV ONLY: Seed endpoint to populate rich historical data for testing charts & audit tables
app.post('/api/dev/seed', (req, res) => {
    const vehicles = ['BUS-101', 'BUS-102', 'BUS-203'];
    const routes = ['Buru-un to City Proper', 'Tominobo to City Proper', 'Buru-un Loop'];
    const passengerTypes = ['Regular', 'Student', 'Senior'];
    const stops = ['Buru-un', 'Tominobo', 'San Miguel', 'Del Carmen', 'City Proper'];
    const uids = ['04:AB:CD:EF', '04:12:34:56', '04:78:90:AB', '04:FE:DC:BA', '04:55:66:77', '04:88:99:AA'];
    const mockConductors = [
        { id: 'C100', name: 'John Doe' },
        { id: 'C101', name: 'Maria Santos' },
        { id: 'C102', name: 'Pedro Penduko' }
    ];
    db.serialize(() => {
        db.run('BEGIN TRANSACTION');
        // Clear existing events for fresh seeding if requested
        if (req.query.clear === 'true') {
            db.run('DELETE FROM events');
        }
        const stmt = db.prepare(`
      INSERT INTO events (
        event_type, timestamp, vehicle_id, route, slot_number, slave_uid, 
        passenger_type, fare_centavos, discount_centavos, boarding_stop, 
        destination_stop, occupancy_now, max_capacity, seat_map, conductor_id, conductor_name
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    `);
        // Let's generate data for the last 14 days
        const totalDays = 14;
        let totalGenerated = 0;
        for (let dayOffset = totalDays; dayOffset >= 0; dayOffset--) {
            const date = new Date();
            date.setDate(date.getDate() - dayOffset);
            const dateString = date.toISOString().split('T')[0];
            vehicles.forEach((vehicle, vehicleIdx) => {
                const route = routes[Math.floor(Math.random() * routes.length)];
                const conductor = mockConductors[Math.floor(Math.random() * mockConductors.length)];
                let currentOccupancy = 0;
                const maxCap = 20;
                // Generate 15-40 events per bus per day
                const eventsPerBus = Math.floor(Math.random() * 25) + 15;
                for (let i = 0; i < eventsPerBus; i++) {
                    const hour = 7 + Math.floor(Math.random() * 12); // 7 AM to 7 PM
                    const min = Math.floor(Math.random() * 60);
                    const sec = Math.floor(Math.random() * 60);
                    const eventTimestamp = `${dateString}T${hour.toString().padStart(2, '0')}:${min.toString().padStart(2, '0')}:${sec.toString().padStart(2, '0')}.000Z`;
                    const uid = uids[Math.floor(Math.random() * uids.length)];
                    const slot = Math.floor(Math.random() * maxCap) + 1;
                    const bStop = stops[Math.floor(Math.random() * stops.length)];
                    const dStopIndex = (stops.indexOf(bStop) + Math.floor(Math.random() * (stops.length - 1)) + 1) % stops.length;
                    const dStop = stops[dStopIndex];
                    // 1. Boarding
                    currentOccupancy = Math.min(maxCap, currentOccupancy + 1);
                    stmt.run([
                        'boarding',
                        eventTimestamp,
                        vehicle,
                        route,
                        slot,
                        uid,
                        null,
                        0,
                        0,
                        bStop,
                        dStop,
                        currentOccupancy,
                        maxCap,
                        null,
                        conductor.id,
                        conductor.name
                    ]);
                    totalGenerated++;
                    // 2. Payment (1-5 minutes after boarding)
                    const pType = passengerTypes[Math.floor(Math.random() * passengerTypes.length)];
                    let fare = Math.floor(Math.random() * 1500) + 1000; // 10.00 to 25.00
                    let disc = 0;
                    if (pType !== 'Regular') {
                        disc = Math.floor(fare * 0.2); // 20% discount
                        fare -= disc;
                    }
                    const payMin = (min + Math.floor(Math.random() * 5) + 1) % 60;
                    const payTimestamp = `${dateString}T${hour.toString().padStart(2, '0')}:${payMin.toString().padStart(2, '0')}:${sec.toString().padStart(2, '0')}.000Z`;
                    stmt.run([
                        'payment',
                        payTimestamp,
                        vehicle,
                        route,
                        slot,
                        uid,
                        pType,
                        fare,
                        disc,
                        bStop,
                        dStop,
                        currentOccupancy,
                        maxCap,
                        null,
                        conductor.id,
                        conductor.name
                    ]);
                    totalGenerated++;
                    // 3. Optional Release/Alighting (70% chance of release event on same day)
                    if (Math.random() > 0.3) {
                        const relMin = (payMin + Math.floor(Math.random() * 20)) % 60;
                        const relTimestamp = `${dateString}T${hour.toString().padStart(2, '0')}:${relMin.toString().padStart(2, '0')}:${sec.toString().padStart(2, '0')}.000Z`;
                        currentOccupancy = Math.max(0, currentOccupancy - 1);
                        stmt.run([
                            'release',
                            relTimestamp,
                            vehicle,
                            route,
                            slot,
                            uid,
                            null,
                            0,
                            0,
                            null,
                            dStop,
                            currentOccupancy,
                            maxCap,
                            null,
                            conductor.id,
                            conductor.name
                        ]);
                        totalGenerated++;
                    }
                }
            });
        }
        stmt.finalize();
        db.run('COMMIT', (err) => {
            if (err) {
                res.status(500).json({ error: 'Failed to seed database', details: err.message });
            }
            else {
                res.json({ status: 'success', message: `Database seeded successfully with ${totalGenerated} historical events.` });
            }
        });
    });
});
// GET fleet live status (aggregated latest event per vehicle + daily revenue)
app.get('/api/fleet/status', (req, res) => {
    const todayStr = new Date().toISOString().split('T')[0];
    const query = `
    SELECT 
      v.id as vehicle_id,
      v.plate_number,
      v.status,
      v.max_capacity,
      rt.name as assigned_route_name,
      c.name as assigned_conductor_name,
      e1.route as current_route,
      COALESCE(e1.occupancy_now, 0) as occupancy_now,
      e1.seat_map,
      e1.timestamp as last_updated,
      e1.conductor_id as current_conductor_id,
      e1.conductor_name as current_conductor_name,
      e1.current_lat,
      e1.current_lon,
      COALESCE(r.daily_revenue, 0) as daily_revenue
    FROM vehicles v
    LEFT JOIN routes rt ON v.assigned_route_id = rt.id
    LEFT JOIN conductors c ON v.assigned_conductor_id = c.id
    LEFT JOIN (
      SELECT vehicle_id, MAX(timestamp) as max_time
      FROM events
      GROUP BY vehicle_id
    ) e2 ON v.id = e2.vehicle_id
    LEFT JOIN events e1 ON e1.vehicle_id = e2.vehicle_id AND e1.timestamp = e2.max_time
    LEFT JOIN (
      SELECT vehicle_id, SUM(fare_centavos) as daily_revenue
      FROM events
      WHERE timestamp LIKE ? AND event_type = 'payment'
      GROUP BY vehicle_id
    ) r ON v.id = r.vehicle_id
  `;
    db.all(query, [`${todayStr}%`], (err, rows) => {
        if (err) {
            res.status(500).json({ error: err.message });
            return;
        }
        res.json(rows);
    });
});
// GET /api/passenger-manifest - Fleet-wide passenger boarding records
app.get('/api/passenger-manifest', (req, res) => {
    const page = parseInt(req.query.page) || 1;
    const limit = parseInt(req.query.limit) || 15;
    const offset = (page - 1) * limit;
    const search = req.query.search || '';
    const vehicleId = req.query.vehicleId || '';
    const conductorId = req.query.conductorId || '';
    const route = req.query.route || '';
    const passengerType = req.query.passengerType || '';
    const date = req.query.date || '';
    let whereClauses = ["b.event_type = 'boarding'"];
    let params = [];
    if (search) {
        whereClauses.push('(b.slave_uid LIKE ? OR b.boarding_stop LIKE ? OR b.destination_stop LIKE ? OR b.conductor_name LIKE ? OR b.vehicle_id LIKE ?)');
        const searchParam = `%${search}%`;
        params.push(searchParam, searchParam, searchParam, searchParam, searchParam);
    }
    if (vehicleId) {
        whereClauses.push('b.vehicle_id = ?');
        params.push(vehicleId);
    }
    if (conductorId) {
        whereClauses.push('b.conductor_id = ?');
        params.push(conductorId);
    }
    if (route) {
        whereClauses.push('b.route = ?');
        params.push(route);
    }
    if (passengerType) {
        whereClauses.push('p.passenger_type = ?');
        params.push(passengerType);
    }
    if (date) {
        whereClauses.push('b.timestamp LIKE ?');
        params.push(`${date}%`);
    }
    const whereSql = whereClauses.join(' AND ');
    const countQuery = `
    SELECT COUNT(DISTINCT b.id) as total 
    FROM events b
    LEFT JOIN events p ON p.event_type = 'payment' 
      AND p.vehicle_id = b.vehicle_id 
      AND p.slot_number = b.slot_number 
      AND p.slave_uid = b.slave_uid
      AND p.timestamp >= b.timestamp
      AND p.timestamp <= datetime(b.timestamp, '+2 hours')
    WHERE ${whereSql}
  `;
    const dataQuery = `
    SELECT 
      b.id as id,
      b.timestamp as boarding_time,
      b.vehicle_id,
      b.route,
      b.slot_number,
      b.slave_uid,
      b.boarding_stop,
      b.destination_stop,
      b.conductor_id,
      b.conductor_name,
      p.passenger_type,
      p.fare_centavos,
      p.discount_centavos,
      p.timestamp as payment_time,
      r.timestamp as release_time,
      CASE 
        WHEN r.timestamp IS NOT NULL THEN 'Alighted'
        WHEN p.timestamp IS NOT NULL THEN 'Paid'
        ELSE 'Active'
      END as status
    FROM events b
    LEFT JOIN events p ON p.event_type = 'payment' 
      AND p.vehicle_id = b.vehicle_id 
      AND p.slot_number = b.slot_number 
      AND p.slave_uid = b.slave_uid
      AND p.timestamp >= b.timestamp
      AND p.timestamp <= datetime(b.timestamp, '+2 hours')
    LEFT JOIN events r ON r.event_type = 'release'
      AND r.vehicle_id = b.vehicle_id
      AND r.slot_number = b.slot_number
      AND r.slave_uid = b.slave_uid
      AND r.timestamp >= b.timestamp
      AND r.timestamp <= datetime(b.timestamp, '+12 hours')
    WHERE ${whereSql}
    GROUP BY b.id
    ORDER BY b.timestamp DESC
    LIMIT ? OFFSET ?
  `;
    db.get(countQuery, params, (errCount, countRow) => {
        if (errCount) {
            res.status(500).json({ error: errCount.message });
            return;
        }
        const total = countRow ? countRow.total : 0;
        const dataParams = [...params, limit, offset];
        db.all(dataQuery, dataParams, (errData, rows) => {
            if (errData) {
                res.status(500).json({ error: errData.message });
                return;
            }
            res.json({
                metadata: {
                    total,
                    page,
                    limit,
                    totalPages: Math.ceil(total / limit)
                },
                manifest: rows
            });
        });
    });
});
// GET /api/audit/logs - Paginated and searchable transaction logs
app.get('/api/audit/logs', (req, res) => {
    const page = parseInt(req.query.page) || 1;
    const limit = parseInt(req.query.limit) || 50;
    const offset = (page - 1) * limit;
    const search = req.query.search || '';
    const startDate = req.query.startDate || '';
    const endDate = req.query.endDate || '';
    let whereClauses = ['1=1'];
    let params = [];
    if (search) {
        whereClauses.push('(vehicle_id LIKE ? OR conductor_name LIKE ? OR slave_uid LIKE ? OR boarding_stop LIKE ? OR destination_stop LIKE ? OR passenger_type LIKE ?)');
        const searchParam = `%${search}%`;
        params.push(searchParam, searchParam, searchParam, searchParam, searchParam, searchParam);
    }
    if (startDate) {
        whereClauses.push('timestamp >= ?');
        params.push(startDate);
    }
    if (endDate) {
        whereClauses.push('timestamp <= ?');
        params.push(endDate);
    }
    const whereSql = whereClauses.join(' AND ');
    const countQuery = `SELECT COUNT(*) as total FROM events WHERE ${whereSql}`;
    const dataQuery = `
    SELECT * FROM events 
    WHERE ${whereSql} 
    ORDER BY timestamp DESC 
    LIMIT ? OFFSET ?
  `;
    db.get(countQuery, params, (errCount, countRow) => {
        if (errCount) {
            res.status(500).json({ error: errCount.message });
            return;
        }
        const total = countRow ? countRow.total : 0;
        const dataParams = [...params, limit, offset];
        db.all(dataQuery, dataParams, (errData, rows) => {
            if (errData) {
                res.status(500).json({ error: errData.message });
                return;
            }
            res.json({
                metadata: {
                    total,
                    page,
                    limit,
                    totalPages: Math.ceil(total / limit)
                },
                logs: rows
            });
        });
    });
});
// GET /api/analytics/revenue - Time-series aggregates of fares/ridership
app.get('/api/analytics/revenue', (req, res) => {
    const days = parseInt(req.query.days) || 7;
    const startDate = new Date();
    startDate.setDate(startDate.getDate() - days);
    const startDateStr = startDate.toISOString().split('T')[0] + 'T00:00:00.000Z';
    const query = `
    SELECT 
      SUBSTR(timestamp, 1, 10) as date,
      SUM(CASE WHEN passenger_type = 'Regular' THEN fare_centavos ELSE 0 END) as regular_revenue,
      SUM(CASE WHEN passenger_type = 'Student' THEN fare_centavos ELSE 0 END) as student_revenue,
      SUM(CASE WHEN passenger_type = 'Senior' THEN fare_centavos ELSE 0 END) as senior_revenue,
      SUM(fare_centavos) as total_revenue,
      COUNT(CASE WHEN event_type = 'payment' THEN 1 END) as total_payments,
      COUNT(CASE WHEN event_type = 'boarding' THEN 1 END) as total_boardings
    FROM events
    WHERE timestamp >= ?
    GROUP BY date
    ORDER BY date ASC
  `;
    db.all(query, [startDateStr], (err, rows) => {
        if (err) {
            res.status(500).json({ error: err.message });
            return;
        }
        // Convert centavos to standard currency amount
        const formatted = rows.map((r) => ({
            date: r.date,
            regular_revenue: r.regular_revenue / 100,
            student_revenue: r.student_revenue / 100,
            senior_revenue: r.senior_revenue / 100,
            total_revenue: r.total_revenue / 100,
            total_payments: r.total_payments,
            total_boardings: r.total_boardings
        }));
        res.json(formatted);
    });
});
// GET /api/export/audit - Download full audit logs as CSV
app.get('/api/export/audit', (req, res) => {
    const query = `
    SELECT 
      timestamp, event_type, vehicle_id, conductor_name, route, slot_number, 
      slave_uid, passenger_type, fare_centavos, discount_centavos, 
      boarding_stop, destination_stop, occupancy_now
    FROM events
    ORDER BY timestamp DESC
  `;
    db.all(query, [], (err, rows) => {
        if (err) {
            res.status(500).json({ error: err.message });
            return;
        }
        // Generate CSV string
        const headers = 'Timestamp,Event Type,Vehicle ID,Conductor,Route,Slot,Slave UID,Passenger Type,Fare (PHP),Discount (PHP),Boarding Stop,Destination,Occupancy';
        const csvRows = rows.map(r => {
            const fare = r.fare_centavos ? (r.fare_centavos / 100).toFixed(2) : '0.00';
            const disc = r.discount_centavos ? (r.discount_centavos / 100).toFixed(2) : '0.00';
            return `${r.timestamp},${r.event_type},${r.vehicle_id},"${r.conductor_name || ''}","${r.route || ''}",${r.slot_number || ''},${r.slave_uid || ''},${r.passenger_type || ''},${fare},${disc},"${r.boarding_stop || ''}","${r.destination_stop || ''}",${r.occupancy_now || ''}`;
        });
        const csvContent = [headers, ...csvRows].join('\n');
        res.header('Content-Type', 'text/csv');
        res.attachment('toms_audit_logs.csv');
        res.send(csvContent);
    });
});
// GET /api/conductors - Staff list with live shift and dynamic sales updates
app.get('/api/conductors', (req, res) => {
    const todayStr = new Date().toISOString().split('T')[0] + '%';
    const query = `
    SELECT vehicle_id, SUM(fare_centavos) as today_sales, MAX(timestamp) as last_sync
    FROM events
    WHERE timestamp LIKE ? AND event_type = 'payment'
    GROUP BY vehicle_id
  `;
    db.all(query, [todayStr], (err, eventRows) => {
        if (err)
            return res.status(500).json({ error: err.message });
        const salesMap = {};
        const syncMap = {};
        eventRows.forEach(r => {
            salesMap[r.vehicle_id] = r.today_sales / 100;
            syncMap[r.vehicle_id] = r.last_sync;
        });
        db.all(`
      SELECT c.id, c.username, c.name, c.contact_number, c.address, c.company_id, v.id as assigned_vehicle_from_db
      FROM conductors c
      LEFT JOIN vehicles v ON v.assigned_conductor_id = c.id
    `, [], (err, staffRows) => {
            if (err)
                return res.status(500).json({ error: err.message });
            const staffWithMetrics = staffRows.map(s => {
                const vehicle = s.assigned_vehicle_from_db || 'N/A';
                const sales = salesMap[vehicle] || 0;
                const lastSync = syncMap[vehicle] || 'N/A';
                return {
                    id: s.id,
                    username: s.username,
                    name: s.name,
                    contact_number: s.contact_number,
                    address: s.address,
                    company_id: s.company_id,
                    assigned_vehicle: vehicle,
                    total_sales_today: sales,
                    last_sync: lastSync,
                    status: vehicle !== 'N/A' ? 'Active' : 'Offline'
                };
            });
            res.json(staffWithMetrics);
        });
    });
});
// Real-time socket connections
io.on('connection', (socket) => {
    console.log('A client connected:', socket.id);
    socket.on('disconnect', () => {
        console.log('Client disconnected:', socket.id);
    });
});
const PORT = process.env.PORT || 3000;
server.listen(PORT, () => {
    console.log(`TOMS Backend API running on port ${PORT}`);
});
