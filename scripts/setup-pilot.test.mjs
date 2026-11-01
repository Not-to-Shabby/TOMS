// Tests for scripts/setup-pilot.mjs. Run with: node --test scripts/
// No network: every request goes to a fake fetch that records what was asked.
import { test, describe } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { validatePilotData, planChanges, toApiBodies, applyPilot, readExisting, checkServerAddress, readHidden, adminCredentials } from './setup-pilot.mjs';

const example = JSON.parse(await readFile(new URL('./pilot-data.example.json', import.meta.url), 'utf8'));

// A fake server: answers the admin API from an in-memory state and records every request.
function fakeServer(initial = {}) {
  const state = {
    vehicles: initial.vehicles ?? [],
    conductors: initial.conductors ?? [],
    routes: initial.routes ?? [],
    fares: initial.fares ?? {},
    devices: [],
    nextId: 100,
  };
  const calls = [];
  const json = (status, body) => new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } });
  const fetchImpl = async (url, opts = {}) => {
    const path = new URL(url).pathname;
    const method = opts.method ?? 'GET';
    const bodyText = typeof opts.body === 'string' ? opts.body : '';
    calls.push({ method, path, body: bodyText, auth: opts.headers?.Authorization ?? null });
    if (path === '/api/admin/login') return json(200, { token: 'T0KEN-test-only' });
    if (path === '/api/vehicles' && method === 'GET') return json(200, state.vehicles);
    if (path === '/api/vehicles' && method === 'POST') {
      // The real GET /api/vehicles returns assigned_route_id for each vehicle (SELECT v.*).
      const b = JSON.parse(bodyText);
      state.vehicles.push({ id: b.id, plate_number: b.plate_number, assigned_route_id: null, assigned_conductor_id: null });
      return json(201, {});
    }
    if (path.startsWith('/api/vehicles/') && method === 'PUT') {
      // Matches the real server: the update writes assigned_route_id and assigned_conductor_id from the body,
      // so a field left out becomes null. A route-only update therefore unlinks the conductor unless it is sent.
      const id = decodeURIComponent(path.slice('/api/vehicles/'.length));
      const vehicle = state.vehicles.find((v) => v.id === id);
      const b = JSON.parse(bodyText);
      if (vehicle) {
        vehicle.assigned_route_id = b.assigned_route_id ?? null;
        vehicle.assigned_conductor_id = b.assigned_conductor_id ?? null;
        for (const c of state.conductors) if (c.id === b.assigned_conductor_id) c.assigned_vehicle = id;
      }
      return json(200, { status: 'success' });
    }
    if (path === '/api/conductors' && method === 'GET') return json(200, state.conductors);
    if (path === '/api/conductors' && method === 'POST') {
      // The real create returns the new conductor, including its id and assigned_vehicle (checked on the real API).
      const b = JSON.parse(bodyText);
      const created = { id: state.nextId++, username: b.username, assigned_vehicle: b.assigned_vehicle ?? null };
      state.conductors.push(created);
      return json(201, created);
    }
    if (path === '/api/routes' && method === 'GET') return json(200, state.routes);
    if (path === '/api/routes' && method === 'POST') {
      const created = { id: state.nextId++, name: JSON.parse(bodyText).name, paths: [] };
      state.routes.push(created);
      return json(201, created);
    }
    const pathMatch = path.match(/^\/api\/routes\/(\d+)\/paths$/);
    if (pathMatch && method === 'GET') return json(200, state.routes.find((r) => r.id === Number(pathMatch[1]))?.paths ?? []);
    if (pathMatch && method === 'POST') {
      const route = state.routes.find((r) => r.id === Number(pathMatch[1]));
      const created = { id: state.nextId++, name: JSON.parse(bodyText).name };
      route.paths.push(created);
      return json(201, created);
    }
    if (/^\/api\/routes\/\d+\/paths\/\d+\/stops$/.test(path) && method === 'POST') return json(200, { status: 'success' });
    const fareMatch = path.match(/^\/api\/fares\/active\/(\d+)$/);
    if (fareMatch && method === 'GET') {
      const m = state.fares[fareMatch[1]];
      return m ? json(200, m) : json(404, { error: 'No active fare matrix.' });
    }
    if (path === '/api/fares' && method === 'POST') {
      // The fare arrives as FormData (the script sends it that way), so read route_id from the form, not a string.
      const form = opts.body instanceof FormData ? opts.body : null;
      const routeId = form?.get('route_id') ?? String(state.routes[state.routes.length - 1]?.id);
      const matrix = { id: state.nextId++, route_id: Number(routeId), is_active: true };
      state.fares[routeId] = matrix;
      return json(201, matrix);
    }
    if (path === '/api/devices' && method === 'POST') return json(201, { device_id: JSON.parse(bodyText).device_id, token: 'phone-1.SECRET-ONE-TIME' });
    return json(404, { error: 'not in the fake' });
  };
  return { fetchImpl, state, calls };
}

describe('validatePilotData', () => {
  test('accepts the example file', () => {
    assert.deepEqual(validatePilotData(example), []);
  });

  test('reports every problem in one run, not just the first', () => {
    const bad = structuredClone(example);
    bad.route.stops = [{ name: 'Only one', lat: 8, lon: 124, radius_m: 100 }];
    bad.route.stops[0].lat = 95;
    bad.fare.base_fare_pesos = -1;
    bad.fare.rounding_step_cents = 10;
    bad.fare.discounts = { student: 150, cashier: 5 };
    const problems = validatePilotData(bad);
    assert.ok(problems.some((p) => /at least two stops/.test(p)), 'one stop');
    assert.ok(problems.some((p) => /lat must be/.test(p)), 'latitude');
    assert.ok(problems.some((p) => /base_fare_pesos/.test(p)), 'negative fare');
    assert.ok(problems.some((p) => /rounding_step_cents/.test(p)), 'rounding');
    assert.ok(problems.some((p) => /discounts\.student/.test(p)), 'discount over 100');
    assert.ok(problems.some((p) => /unknown category "cashier"/.test(p)), 'unknown discount');
    assert.ok(problems.length >= 6);
  });

  test('refuses longitudes and latitudes out of range, and text that is blank', () => {
    const bad = structuredClone(example);
    bad.route.stops[1].lon = 181;
    bad.vehicle.id = '   ';
    const problems = validatePilotData(bad);
    assert.ok(problems.some((p) => /route\.stops\[1\]\.lon/.test(p)));
    assert.ok(problems.some((p) => /vehicle\.id is required/.test(p)));
  });

  test('accepts zero fares and the full discount range, and refuses a non-number', () => {
    const edge = structuredClone(example);
    edge.fare.base_fare_pesos = 0;
    edge.fare.discounts = { student: 0, pwd: 100, senior: 50 };
    assert.deepEqual(validatePilotData(edge), []);
    const text = structuredClone(example);
    text.fare.per_km_pesos = '2.5';
    assert.ok(validatePilotData(text).some((p) => /per_km_pesos/.test(p)));
  });

  test('refuses a data file that is not an object', () => {
    assert.ok(validatePilotData(null).length > 0);
    assert.ok(validatePilotData('text').length > 0);
  });
});

describe('checkServerAddress', () => {
  test('refuses a missing address, so the script cannot hit the wrong server', () => {
    assert.match(checkServerAddress(null), /pass --server/);
    assert.match(checkServerAddress(undefined), /pass --server/);
  });
  test('refuses plain http, and accepts https', () => {
    assert.match(checkServerAddress('http://toms.perchbuilds.me'), /https/);
    assert.equal(checkServerAddress('https://toms.perchbuilds.me'), null);
  });
});

describe('planChanges and idempotency', () => {
  test('a fresh server gets everything created', () => {
    const plan = planChanges(example, { vehicles: [], conductors: [], routes: [], fareByRoute: {} });
    assert.deepEqual(plan.map((s) => s.action), ['create', 'create', 'create', 'create']);
  });

  test('a second run on a configured server skips everything', () => {
    const existing = {
      vehicles: [{ id: example.vehicle.id }],
      conductors: [{ id: 1, username: example.conductor.username, assigned_vehicle: example.vehicle.id }],
      routes: [{ id: 7, name: example.route.name, paths: [{ id: 3, name: example.route.path.name }] }],
      fareByRoute: { 7: { id: 9 } },
    };
    const plan = planChanges(example, existing);
    assert.deepEqual(plan.map((s) => s.action), ['skip', 'skip', 'skip', 'skip']);
  });

  test('a conductor on another bus is refused, never silently moved', () => {
    const existing = {
      vehicles: [{ id: example.vehicle.id }],
      conductors: [{ id: 1, username: example.conductor.username, assigned_vehicle: 'OTHER-BUS' }],
      routes: [],
      fareByRoute: {},
    };
    const conductorStep = planChanges(example, existing).find((s) => s.what.startsWith('conductor'));
    assert.equal(conductorStep.action, 'refuse');
    assert.match(conductorStep.reason, /OTHER-BUS/);
  });

  test('the dashboard placeholder "N/A" means no vehicle, so a free conductor is assigned, not refused', () => {
    // The real GET /api/conductors sends "N/A" for a conductor with no vehicle (not null). Found on the real API.
    const existing = {
      vehicles: [{ id: example.vehicle.id }],
      conductors: [{ id: 1, username: example.conductor.username, assigned_vehicle: 'N/A' }],
      routes: [],
      fareByRoute: {},
    };
    const conductorStep = planChanges(example, existing).find((s) => s.what.startsWith('conductor'));
    assert.equal(conductorStep.action, 'assign');
  });

  test('assignedVehicle reads placeholders as none, and real ids as the vehicle', async () => {
    const { assignedVehicle } = await import('./setup-pilot.mjs');
    assert.equal(assignedVehicle({ assigned_vehicle: 'N/A' }), null);
    assert.equal(assignedVehicle({ assigned_vehicle: null }), null);
    assert.equal(assignedVehicle({ assigned_vehicle: '' }), null);
    assert.equal(assignedVehicle({}), null);
    assert.equal(assignedVehicle({ assigned_vehicle: 'BUS-7' }), 'BUS-7');
  });

  test('a conductor with no bus is assigned, not recreated', () => {
    const existing = {
      vehicles: [{ id: example.vehicle.id }],
      conductors: [{ id: 1, username: example.conductor.username, assigned_vehicle: null }],
      routes: [],
      fareByRoute: {},
    };
    const conductorStep = planChanges(example, existing).find((s) => s.what.startsWith('conductor'));
    assert.equal(conductorStep.action, 'assign');
  });

  test('an existing route without the path gets only the path', () => {
    const existing = { vehicles: [], conductors: [], routes: [{ id: 4, name: example.route.name, paths: [] }], fareByRoute: {} };
    const plan = planChanges(example, existing);
    // The planner has no route step when the route exists: it only adds the missing path. So the route must
    // never be created again, and the path must be created.
    assert.equal(plan.some((s) => s.action === 'create' && s.what.startsWith(`route "`)), false, 'the route is not created again');
    const pathStep = plan.find((s) => s.what.startsWith('path '));
    assert.ok(pathStep, 'the missing path is in the plan');
    assert.equal(pathStep.action, 'create', 'only the missing path is created');
  });
});

describe('toApiBodies units', () => {
  test('fares go to the API in pesos, and the rounding step stays in centavos', () => {
    const bodies = toApiBodies(example);
    assert.equal(bodies.fare.base_fare, 15);
    assert.equal(bodies.fare.per_km_fare, 2.5);
    assert.equal(bodies.fare.rounding_step_cents, 100);
    assert.deepEqual(bodies.fare.discounts, { student: 20, pwd: 20, senior: 20 });
  });
});

describe('applyPilot on a fresh server', () => {
  test('creates vehicle, conductor, route, path, stops and fare in order', async () => {
    const server = fakeServer();
    const existing = await readExisting(server.fetchImpl, 'https://x.example', 'T0KEN-test-only');
    const log = await applyPilot(server.fetchImpl, 'https://x.example', 'T0KEN-test-only', example, existing, 'conductor-pass-1');
    const paths = server.calls.map((c) => `${c.method} ${c.path}`);
    const idx = (needle) => paths.findIndex((p) => p.includes(needle));
    assert.ok(idx('POST /api/vehicles') < idx('POST /api/conductors'), 'vehicle before conductor');
    assert.ok(idx('POST /api/conductors') < idx('POST /api/routes'), 'conductor before route');
    assert.ok(idx('POST /api/routes') < idx('/paths') , 'route before path');
    assert.ok(idx('/stops') > idx('/paths'), 'stops after path');
    assert.ok(idx('POST /api/fares') > idx('/stops'), 'fare after stops');
    assert.ok(log.some((l) => /published fare matrix/.test(l)));
  });

  test('sends the stops as an array with the radius, and the fare as multipart', async () => {
    const server = fakeServer();
    const existing = await readExisting(server.fetchImpl, 'https://x.example', 'T0KEN-test-only');
    await applyPilot(server.fetchImpl, 'https://x.example', 'T0KEN-test-only', example, existing, 'conductor-pass-1');
    const stopCall = server.calls.find((c) => /\/stops$/.test(c.path));
    const stops = JSON.parse(stopCall.body);
    assert.equal(stops.length, 3);
    assert.equal(stops[0].radius_m, 100);
    assert.equal(stops[1].lat, 8.21);
    // The fare is sent as multipart form data, so the body is a FormData and the server reads the fields by name.
    const fareCall = server.calls.find((c) => c.path === '/api/fares');
    assert.ok(fareCall, 'a fare was published');
    assert.equal(fareCall.method, 'POST');
    assert.equal(fareCall.body, '', 'fare body is FormData, not a JSON string');
  });

  test('the fare form carries the fields the server reads, in pesos', async () => {
    const seen = [];
    const server = fakeServer();
    const spy = async (url, opts = {}) => {
      if (String(url).endsWith('/api/fares') && opts.body instanceof FormData) {
        for (const [k, v] of opts.body.entries()) seen.push([k, typeof v === 'string' ? v : 'file']);
      }
      return server.fetchImpl(url, opts);
    };
    const existing = await readExisting(server.fetchImpl, 'https://x.example', 'T0KEN-test-only');
    await applyPilot(spy, 'https://x.example', 'T0KEN-test-only', example, existing, 'conductor-pass-1');
    const fields = Object.fromEntries(seen);
    assert.equal(fields.base_fare, '15');
    assert.equal(fields.per_km_fare, '2.5');
    assert.equal(fields.base_distance_km, '4');
    assert.equal(fields.rounding_step_cents, '100');
    assert.equal(fields.route_id !== undefined, true);
    assert.deepEqual(JSON.parse(fields.discounts), { student: 20, pwd: 20, senior: 20 });
  });

  test('a new conductor is created with a password, because the real API requires one', async () => {
    const server = fakeServer();
    const existing = await readExisting(server.fetchImpl, 'https://x.example', 'T0KEN-test-only');
    await applyPilot(server.fetchImpl, 'https://x.example', 'T0KEN-test-only', example, existing, 'conductor-pass-1');
    const conductorCall = server.calls.find((c) => c.path === '/api/conductors' && c.method === 'POST');
    const body = JSON.parse(conductorCall.body);
    assert.equal(body.password, 'conductor-pass-1');
    assert.equal(body.assigned_vehicle, example.vehicle.id);
  });

  test('refuses to create a conductor without a password of at least 8 characters, before any conductor request', async () => {
    const server = fakeServer();
    const existing = await readExisting(server.fetchImpl, 'https://x.example', 'T0KEN-test-only');
    await assert.rejects(
      applyPilot(server.fetchImpl, 'https://x.example', 'T0KEN-test-only', example, existing, 'short'),
      /at least 8 characters/,
    );
    assert.equal(server.calls.filter((c) => c.path === '/api/conductors' && c.method === 'POST').length, 0);
  });

  test('the conductor password is never in the log or in an error message', async () => {
    const server = fakeServer();
    const existing = await readExisting(server.fetchImpl, 'https://x.example', 'T0KEN-test-only');
    const log = await applyPilot(server.fetchImpl, 'https://x.example', 'T0KEN-test-only', example, existing, 'VERY-SECRET-PASS');
    assert.doesNotMatch(log.join('\n'), /VERY-SECRET-PASS/);
  });

  test('the route change keeps the conductor on the vehicle (the server unlinks anyone left out of the update)', async () => {
    const server = fakeServer();
    const existing = await readExisting(server.fetchImpl, 'https://x.example', 'T0KEN-test-only');
    await applyPilot(server.fetchImpl, 'https://x.example', 'T0KEN-test-only', example, existing, 'conductor-pass-1');
    const vehicle = server.state.vehicles.find((v) => v.id === example.vehicle.id);
    const conductor = server.state.conductors.find((c) => c.username === example.conductor.username);
    assert.ok(vehicle.assigned_route_id, 'the vehicle has its route');
    assert.equal(vehicle.assigned_conductor_id, conductor.id, 'the conductor is still on the vehicle after the route change');
  });

  test('a second run on the same server creates nothing new', async () => {
    const server = fakeServer();
    const first = await readExisting(server.fetchImpl, 'https://x.example', 'T0KEN-test-only');
    await applyPilot(server.fetchImpl, 'https://x.example', 'T0KEN-test-only', example, first, 'conductor-pass-1');
    const before = server.calls.filter((c) => c.method === 'POST').length;
    const second = await readExisting(server.fetchImpl, 'https://x.example', 'T0KEN-test-only');
    const log = await applyPilot(server.fetchImpl, 'https://x.example', 'T0KEN-test-only', example, second, undefined);
    const after = server.calls.filter((c) => c.method === 'POST').length;
    assert.equal(after, before, 'no new POST on the second run');
    assert.ok(log.every((l) => /skipped|already|left as they are/.test(l)), log.join('; '));
  });

  test('stops before anything is sent when a conductor is on another bus', async () => {
    const server = fakeServer({
      vehicles: [{ id: example.vehicle.id }],
      conductors: [{ id: 1, username: example.conductor.username, assigned_vehicle: 'OTHER-BUS' }],
    });
    const existing = await readExisting(server.fetchImpl, 'https://x.example', 'T0KEN-test-only');
    await assert.rejects(applyPilot(server.fetchImpl, 'https://x.example', 'T0KEN-test-only', example, existing), /OTHER-BUS/);
    assert.equal(server.calls.filter((c) => c.method === 'POST' && c.path === '/api/routes').length, 0);
  });
});

describe('secrets never reach an error or a log', () => {
  test('a failed login reports the status, never the password', async () => {
    const failing = async () => new Response(JSON.stringify({ error: 'Invalid username or password' }), { status: 401 });
    const { login } = await import('./setup-pilot.mjs');
    await assert.rejects(login(failing, 'https://x.example', 'boss', 'PASSWORD-SHOULD-NOT-APPEAR'), (err) => {
      assert.match(err.message, /401/);
      assert.doesNotMatch(err.message, /PASSWORD-SHOULD-NOT-APPEAR/);
      return true;
    });
  });

  test('a device token is returned to the caller, not written into an error', async () => {
    const server = fakeServer();
    const { api } = await import('./setup-pilot.mjs');
    const created = await api(server.fetchImpl, 'https://x.example', 'T0KEN-test-only', 'POST', '/api/devices', { device_id: 'phone-1', vehicle_id: 'v' });
    assert.equal(created.token, 'phone-1.SECRET-ONE-TIME');
  });
});

describe('credentials from the environment', () => {
  test('uses the environment variables without asking', async () => {
    const creds = await adminCredentials({ TOMS_ADMIN_USERNAME: 'boss', TOMS_ADMIN_PASSWORD: 'long-enough-pass' }, { isTTY: false });
    assert.deepEqual(creds, { username: 'boss', password: 'long-enough-pass' });
  });

  test('without a terminal and without the variables, it refuses instead of waiting', async () => {
    await assert.rejects(readHidden('Admin password: ', { isTTY: false }, { write() {} }), /no terminal/);
  });
});
