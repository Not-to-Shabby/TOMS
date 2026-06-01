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
dotenv_1.default.config();
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
      seat_map TEXT
    )`);
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
        res.json({ id: this.lastID, name, company_id: company_id || 'company_1', base_fare: 15.0, per_km_fare: 2.5 });
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
        let pathsProcessed = 0;
        paths.forEach(p => {
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
        destination_stop, occupancy_now, max_capacity, seat_map
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    `);
        let latestFleetStatus = {};
        events.forEach(event => {
            stmt.run([
                event.event_type,
                event.timestamp,
                event.vehicle_id,
                event.route,
                event.slot_number,
                event.slave_uid,
                event.passenger_type,
                event.fare_centavos,
                event.discount_centavos,
                event.boarding_stop,
                event.destination_stop,
                event.occupancy_now,
                event.max_capacity,
                event.seat_map || null
            ]);
            // Track latest status per vehicle to emit via socket
            // (For real-time daily revenue, we'll need to calculate it properly. 
            // For now, we will compute it in a follow-up query before emitting, 
            // or simply aggregate it as we go.)
            if (!latestFleetStatus[event.vehicle_id]) {
                latestFleetStatus[event.vehicle_id] = {
                    vehicle_id: event.vehicle_id,
                    route: event.route,
                    occupancy_now: event.occupancy_now,
                    max_capacity: event.max_capacity,
                    seat_map: event.seat_map,
                    last_updated: event.timestamp,
                    daily_revenue: 0 // Will populate next
                };
            }
            else {
                latestFleetStatus[event.vehicle_id] = {
                    ...latestFleetStatus[event.vehicle_id],
                    occupancy_now: event.occupancy_now,
                    seat_map: event.seat_map,
                    last_updated: event.timestamp
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
    const eventTypes = ['boarding', 'payment', 'release'];
    const stops = ['Buru-un', 'Tominobo', 'San Miguel', 'Del Carmen', 'City Proper'];
    const uids = ['04:AB:CD:EF', '04:12:34:56', '04:78:90:AB', '04:FE:DC:BA', '04:55:66:77', '04:88:99:AA'];
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
        destination_stop, occupancy_now, max_capacity, seat_map
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    `);
        // Let's generate data for the last 14 days
        const totalDays = 14;
        let totalGenerated = 0;
        for (let dayOffset = totalDays; dayOffset >= 0; dayOffset--) {
            const date = new Date();
            date.setDate(date.getDate() - dayOffset);
            const dateString = date.toISOString().split('T')[0];
            vehicles.forEach((vehicle, vehicleIdx) => {
                const route = routes[vehicleIdx % routes.length];
                let currentOccupancy = 0;
                const maxCap = 30;
                // Generate random number of passenger sequences per day
                const dailyPassengers = Math.floor(Math.random() * 15) + 10; // 10-25 passengers per bus per day
                for (let p = 0; p < dailyPassengers; p++) {
                    const passType = passengerTypes[Math.floor(Math.random() * passengerTypes.length)];
                    const baseFare = 1500; // 15 PHP
                    const discount = passType !== 'Regular' ? 300 : 0; // Student/Senior discount
                    const fare = baseFare - discount;
                    const slot = Math.floor(Math.random() * maxCap) + 1;
                    const uid = uids[Math.floor(Math.random() * uids.length)];
                    const bStop = stops[Math.floor(Math.random() * (stops.length - 2))]; // Boarding early
                    const dStop = stops[Math.floor(Math.random() * 2) + 3]; // Alighting late
                    // Custom timestamps spread out during operating hours (e.g. 7 AM to 7 PM)
                    const hour = Math.floor(Math.random() * 12) + 7;
                    const min = Math.floor(Math.random() * 60);
                    const sec = Math.floor(Math.random() * 60);
                    const timestamp = `${dateString}T${hour.toString().padStart(2, '0')}:${min.toString().padStart(2, '0')}:${sec.toString().padStart(2, '0')}.000Z`;
                    // 1. Boarding
                    currentOccupancy = Math.min(maxCap, currentOccupancy + 1);
                    stmt.run([
                        'boarding',
                        timestamp,
                        vehicle,
                        route,
                        slot,
                        uid,
                        null,
                        0,
                        0,
                        bStop,
                        null,
                        currentOccupancy,
                        maxCap,
                        null
                    ]);
                    totalGenerated++;
                    // 2. Payment (usually shortly after)
                    const payMin = (min + Math.floor(Math.random() * 5)) % 60;
                    const payTimestamp = `${dateString}T${hour.toString().padStart(2, '0')}:${payMin.toString().padStart(2, '0')}:${sec.toString().padStart(2, '0')}.000Z`;
                    stmt.run([
                        'payment',
                        payTimestamp,
                        vehicle,
                        route,
                        slot,
                        uid,
                        passType,
                        fare,
                        discount,
                        bStop,
                        dStop,
                        currentOccupancy,
                        maxCap,
                        null
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
                            null
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
      e1.*,
      COALESCE(r.daily_revenue, 0) as daily_revenue
    FROM events e1
    JOIN (
      SELECT vehicle_id, MAX(timestamp) as max_time
      FROM events
      GROUP BY vehicle_id
    ) e2 ON e1.vehicle_id = e2.vehicle_id AND e1.timestamp = e2.max_time
    LEFT JOIN (
      SELECT vehicle_id, SUM(fare_centavos) as daily_revenue
      FROM events
      WHERE timestamp LIKE ? AND event_type = 'payment'
      GROUP BY vehicle_id
    ) r ON e1.vehicle_id = r.vehicle_id
  `;
    db.all(query, [`${todayStr}%`], (err, rows) => {
        if (err) {
            res.status(500).json({ error: err.message });
            return;
        }
        res.json(rows);
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
        whereClauses.push('(vehicle_id LIKE ? OR slave_uid LIKE ? OR boarding_stop LIKE ? OR destination_stop LIKE ? OR passenger_type LIKE ?)');
        const searchParam = `%${search}%`;
        params.push(searchParam, searchParam, searchParam, searchParam, searchParam);
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
// GET /api/conductors - Staff list with live shift and dynamic sales updates
app.get('/api/conductors', (req, res) => {
    const todayStr = new Date().toISOString().split('T')[0] + '%';
    const query = `
    SELECT vehicle_id, SUM(fare_centavos) as today_sales, MAX(timestamp) as last_sync
    FROM events
    WHERE timestamp LIKE ? AND event_type = 'payment'
    GROUP BY vehicle_id
  `;
    db.all(query, [todayStr], (err, rows) => {
        if (err) {
            res.status(500).json({ error: err.message });
            return;
        }
        const salesMap = {};
        const syncMap = {};
        rows.forEach(r => {
            salesMap[r.vehicle_id] = r.today_sales / 100;
            syncMap[r.vehicle_id] = r.last_sync;
        });
        const staticStaff = [
            { id: 'COND-001', name: 'Juan Dela Cruz', status: 'Active', assigned_vehicle: 'BUS-101', email: 'juan.delacruz@toms.com', phone: '+63 912 345 6789' },
            { id: 'COND-002', name: 'Maria Clara Santos', status: 'Active', assigned_vehicle: 'BUS-102', email: 'maria.santos@toms.com', phone: '+63 923 456 7890' },
            { id: 'COND-003', name: 'Pedro Penduko', status: 'Active', assigned_vehicle: 'BUS-203', email: 'pedro.penduko@toms.com', phone: '+63 934 567 8901' },
            { id: 'COND-004', name: 'Gabriela Silang', status: 'Offline', assigned_vehicle: 'N/A', email: 'gabriela.silang@toms.com', phone: '+63 945 678 9012' },
            { id: 'COND-005', name: 'Andres Bonifacio', status: 'Offline', assigned_vehicle: 'N/A', email: 'andres.bonifacio@toms.com', phone: '+63 956 789 0123' }
        ];
        const staffWithMetrics = staticStaff.map(s => {
            const vehicle = s.assigned_vehicle;
            const sales = salesMap[vehicle] || 0;
            const lastSync = syncMap[vehicle] || 'N/A';
            return {
                ...s,
                total_sales_today: sales,
                last_sync: lastSync,
                status: vehicle !== 'N/A' ? 'Active' : 'Offline'
            };
        });
        res.json(staffWithMetrics);
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
