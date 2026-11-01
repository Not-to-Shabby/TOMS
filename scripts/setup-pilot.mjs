#!/usr/bin/env node
// Sets up a pilot on a TOMS server through the existing admin API: one vehicle, one conductor assigned to it,
// one route with a path and stops, and a published fare matrix. Also enrols a phone and prints its token once.
//
//   node scripts/setup-pilot.mjs --server https://toms.perchbuilds.me --dry-run
//   node scripts/setup-pilot.mjs --server https://toms.perchbuilds.me
//   node scripts/setup-pilot.mjs --server https://toms.perchbuilds.me enrol-device <device-id> <vehicle-id>
//
// The admin password is asked for at a hidden prompt, or read from TOMS_ADMIN_PASSWORD. It is never printed,
// saved, or written to a file. The token from the login is kept in memory only.
//
// --server is required, so the script cannot reach the wrong server by accident.
// Fares are in pesos for the API's base and per-km fields, and in centavos only for the rounding step.
import { readFile } from 'node:fs/promises';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const DEFAULT_DATA = resolve(dirname(fileURLToPath(import.meta.url)), 'pilot-data.json');
const ROUNDING_STEPS = new Set([25, 50, 100]);

// --- Validation -------------------------------------------------------------------------------

/**
 * Checks the pilot data before anything is sent. Returns a list of problems; an empty list means it is valid.
 * Every problem is listed, so one run shows them all.
 */
export function validatePilotData(data) {
  const problems = [];
  const need = (ok, message) => { if (!ok) problems.push(message); };
  const isText = (v) => typeof v === 'string' && v.trim().length > 0;
  const isNumber = (v) => typeof v === 'number' && Number.isFinite(v);

  need(data && typeof data === 'object', 'the file must hold a JSON object');
  if (!data || typeof data !== 'object') return problems;

  const v = data.vehicle;
  need(v && isText(v.id), 'vehicle.id is required');
  need(v && isText(v.plate_number), 'vehicle.plate_number is required');
  if (v && v.max_capacity !== undefined) {
    need(Number.isInteger(v.max_capacity) && v.max_capacity > 0, 'vehicle.max_capacity must be a positive whole number');
  }

  const c = data.conductor;
  need(c && isText(c.username) && c.username.length >= 3, 'conductor.username is required (at least 3 characters)');
  need(c && isText(c.name), 'conductor.name is required');

  const r = data.route;
  need(r && isText(r.name), 'route.name is required');
  const stops = Array.isArray(r?.stops) ? r.stops : [];
  need(stops.length >= 2, 'route.stops needs at least two stops');
  stops.forEach((s, i) => {
    const at = `route.stops[${i}]`;
    need(isText(s?.name), `${at}.name is required`);
    need(isNumber(s?.lat) && s.lat >= -90 && s.lat <= 90, `${at}.lat must be a number from -90 to 90`);
    need(isNumber(s?.lon) && s.lon >= -180 && s.lon <= 180, `${at}.lon must be a number from -180 to 180`);
    if (s?.radius_m !== undefined) {
      need(Number.isInteger(s.radius_m) && s.radius_m > 0, `${at}.radius_m must be a positive whole number`);
    }
  });

  const f = data.fare;
  need(f && isNumber(f.base_fare_pesos) && f.base_fare_pesos >= 0, 'fare.base_fare_pesos must be zero or more');
  need(f && isNumber(f.base_distance_km) && f.base_distance_km > 0, 'fare.base_distance_km must be more than zero');
  need(f && isNumber(f.per_km_pesos) && f.per_km_pesos >= 0, 'fare.per_km_pesos must be zero or more');
  need(f && ROUNDING_STEPS.has(f.rounding_step_cents), 'fare.rounding_step_cents must be 25, 50 or 100');
  if (f?.discounts !== undefined) {
    const d = f.discounts;
    need(d && typeof d === 'object', 'fare.discounts must be an object');
    for (const [key, percent] of Object.entries(d ?? {})) {
      need(['student', 'pwd', 'senior'].includes(key), `fare.discounts has an unknown category "${key}"`);
      need(Number.isInteger(percent) && percent >= 0 && percent <= 100, `fare.discounts.${key} must be a whole percent from 0 to 100`);
    }
  }
  if (f?.document_path !== undefined && f.document_path !== null) {
    need(isText(f.document_path), 'fare.document_path must be a file path, or null');
  }
  return problems;
}

// --- The plan: what would be created, and what already exists ----------------------------------

// The dashboard shows "N/A" where a conductor has no vehicle. The API sends that text, not null, so the script
// must read it as empty. Any other value is a real vehicle id.
const PLACEHOLDERS = new Set(['N/A', '', 'null', 'undefined']);

/** The vehicle a conductor drives, or null. Reads the server's placeholder text as "none". */
export function assignedVehicle(conductor) {
  const value = conductor?.assigned_vehicle;
  if (value === null || value === undefined) return null;
  const text = String(value).trim();
  return PLACEHOLDERS.has(text) ? null : text;
}

/**
 * Compares the pilot data with what the server already has, so a re-run creates only what is missing.
 * `existing` holds lists from the server: vehicles, conductors, routes (with their paths), and the fare matrix per route.
 */
export function planChanges(data, existing) {
  const steps = [];

  const vehicle = existing.vehicles.find((x) => x.id === data.vehicle.id);
  steps.push(vehicle
    ? { action: 'skip', what: `vehicle ${data.vehicle.id}`, reason: 'already exists' }
    : { action: 'create', what: `vehicle ${data.vehicle.id}` });

  const conductor = existing.conductors.find((x) => x.username === data.conductor.username);
  const current = assignedVehicle(conductor);
  if (!conductor) {
    steps.push({ action: 'create', what: `conductor ${data.conductor.username}, assigned to ${data.vehicle.id}` });
  } else if (current === data.vehicle.id) {
    steps.push({ action: 'skip', what: `conductor ${data.conductor.username}`, reason: 'already assigned to this vehicle' });
  } else if (current) {
    // The server would move this person from their current bus. A pilot setup must not do that silently.
    steps.push({ action: 'refuse', what: `conductor ${data.conductor.username}`,
      reason: `already assigned to vehicle ${current}; change that on the dashboard first` });
  } else {
    steps.push({ action: 'assign', what: `conductor ${data.conductor.username} to ${data.vehicle.id}` });
  }

  const route = existing.routes.find((x) => x.name === data.route.name);
  if (!route) {
    steps.push({ action: 'create', what: `route "${data.route.name}" with its path and ${data.route.stops.length} stops` });
  } else {
    const pathOk = (route.paths ?? []).some((p) => p.name === data.route.path.name);
    steps.push(pathOk
      ? { action: 'skip', what: `route "${data.route.name}"`, reason: 'already exists with this path' }
      : { action: 'create', what: `path "${data.route.path.name}" on route "${data.route.name}"` });
  }

  const routeId = route?.id ?? null;
  const matrix = routeId != null ? existing.fareByRoute?.[routeId] : null;
  steps.push(matrix
    ? { action: 'skip', what: `fare matrix for "${data.route.name}"`, reason: 'a matrix is already published for this route' }
    : { action: 'create', what: `fare matrix for "${data.route.name}"` });

  return steps;
}

// --- Converting the file's values into the API's request bodies ---------------------------------

export function toApiBodies(data) {
  return {
    vehicle: { id: data.vehicle.id, plate_number: data.vehicle.plate_number, max_capacity: data.vehicle.max_capacity ?? 20 },
    conductor: {
      username: data.conductor.username,
      name: data.conductor.name,
      contact_number: data.conductor.contact_number ?? null,
      address: data.conductor.address ?? null,
    },
    route: { name: data.route.name },
    path: { name: data.route.path?.name ?? 'Main', color: data.route.path?.color ?? null },
    stops: data.route.stops.map((s) => ({ name: s.name, lat: s.lat, lon: s.lon, radius_m: s.radius_m ?? 100 })),
    fare: {
      base_fare: data.fare.base_fare_pesos,
      base_distance_km: data.fare.base_distance_km,
      per_km_fare: data.fare.per_km_pesos,
      rounding_step_cents: data.fare.rounding_step_cents,
      discounts: data.fare.discounts ?? { student: 20, pwd: 20, senior: 20 },
      order_reference: data.fare.order_reference ?? null,
      effective_date: data.fare.effective_date ?? null,
      document_path: data.fare.document_path ?? null,
    },
  };
}

// --- HTTP ---------------------------------------------------------------------------------------

/** Makes one request to the server. Throws an Error that names the path and status, never a password or token. */
export async function api(fetchImpl, base, token, method, path, body, { form } = {}) {
  const headers = {};
  if (token) headers.Authorization = `Bearer ${token}`;
  let payload;
  if (form) {
    payload = form;
  } else if (body !== undefined) {
    headers['Content-Type'] = 'application/json';
    payload = JSON.stringify(body);
  }
  const res = await fetchImpl(`${base}${path}`, { method, headers, body: payload });
  let json = null;
  try { json = await res.json(); } catch { json = null; }
  if (!res.ok) {
    const detail = json?.error ? `: ${json.error}` : '';
    const err = new Error(`${method} ${path} failed with ${res.status}${detail}`);
    err.status = res.status;
    throw err;
  }
  return json;
}

/** Creates everything in the plan, in order. Stops at the first failure. Returns the log of what happened. */
export async function applyPilot(fetchImpl, base, token, data, existing, conductorPassword) {
  const bodies = toApiBodies(data);
  const plan = planChanges(data, existing);
  const log = [];

  if (plan.find((s) => s.action === 'create' && s.what.startsWith('vehicle'))) {
    await api(fetchImpl, base, token, 'POST', '/api/vehicles', bodies.vehicle);
    log.push(`created vehicle ${bodies.vehicle.id}`);
  } else {
    log.push(`vehicle ${bodies.vehicle.id}: already exists, skipped`);
  }

  const conductorStep = plan.find((s) => s.what.startsWith(`conductor ${bodies.conductor.username}`));
  if (conductorStep?.action === 'refuse') {
    throw new Error(`stopped: ${conductorStep.what} ${conductorStep.reason}`);
  }
  // The id of the conductor on this vehicle, kept so the route change below does not unlink them.
  let conductorOnVehicle = existing.conductors.find((c) => assignedVehicle(c) === bodies.vehicle.id)?.id ?? null;
  if (conductorStep?.action === 'create') {
    // The server requires a password for a new conductor (at least 8 characters). It comes from the prompt or the
    // environment, never from the data file, and it is not written to the log.
    if (typeof conductorPassword !== 'string' || conductorPassword.length < 8) {
      throw new Error('a new conductor needs a password of at least 8 characters (asked for at the prompt, or TOMS_CONDUCTOR_PASSWORD)');
    }
    const created = await api(fetchImpl, base, token, 'POST', '/api/conductors', {
      ...bodies.conductor,
      password: conductorPassword,
      assigned_vehicle: bodies.vehicle.id,
    });
    conductorOnVehicle = created?.id ?? null;
    log.push(`created conductor ${bodies.conductor.username} on ${bodies.vehicle.id}`);
  } else if (conductorStep?.action === 'assign') {
    const conductor = existing.conductors.find((x) => x.username === bodies.conductor.username);
    // The planner marked this conductor as free. Confirm that here too, before sending anything.
    if (assignedVehicle(conductor)) throw new Error(`stopped: conductor ${bodies.conductor.username} is already assigned to a vehicle`);
    await api(fetchImpl, base, token, 'PUT', `/api/vehicles/${encodeURIComponent(bodies.vehicle.id)}`, { assigned_conductor_id: conductor.id });
    conductorOnVehicle = conductor.id;
    log.push(`assigned conductor ${bodies.conductor.username} to ${bodies.vehicle.id}`);
  } else {
    log.push(`conductor ${bodies.conductor.username}: ${conductorStep?.reason ?? 'already in place'}, skipped`);
  }

  let route = existing.routes.find((x) => x.name === bodies.route.name);
  if (!route) {
    route = await api(fetchImpl, base, token, 'POST', '/api/routes', bodies.route);
    log.push(`created route "${bodies.route.name}"`);
  }
  const routeId = route.id;

  let pathId = (route.paths ?? []).find((p) => p.name === bodies.path.name)?.id;
  if (pathId == null) {
    const createdPath = await api(fetchImpl, base, token, 'POST', `/api/routes/${routeId}/paths`, bodies.path);
    pathId = createdPath.id;
    log.push(`created path "${bodies.path.name}"`);
    await api(fetchImpl, base, token, 'POST', `/api/routes/${routeId}/paths/${pathId}/stops`, bodies.stops);
    log.push(`saved ${bodies.stops.length} stops on path "${bodies.path.name}"`);
  } else {
    log.push(`path "${bodies.path.name}" already exists, its stops were left as they are`);
  }

  // The phone's configuration comes from the vehicle's assigned route, so the vehicle must point at this route.
  // Done after the route exists, and only when it does not already. The server's vehicle update replaces the
  // conductor link unless it is sent too, so the current conductor is sent back with the route, or the route
  // change would unlink the conductor.
  const vehicle = existing.vehicles.find((x) => x.id === bodies.vehicle.id);
  if (vehicle?.assigned_route_id !== routeId) {
    await api(fetchImpl, base, token, 'PUT', `/api/vehicles/${encodeURIComponent(bodies.vehicle.id)}`, {
      assigned_route_id: routeId,
      assigned_conductor_id: conductorOnVehicle,
    });
    log.push(`assigned route "${bodies.route.name}" to vehicle ${bodies.vehicle.id}`);
  } else {
    log.push(`vehicle ${bodies.vehicle.id} already on route "${bodies.route.name}", skipped`);
  }

  if (existing.fareByRoute?.[routeId]) {
    log.push(`fare matrix for "${bodies.route.name}" already published, skipped`);
  } else {
    await publishFare(fetchImpl, base, token, routeId, bodies.fare, data.fare.document_path, log);
  }
  return log;
}

async function publishFare(fetchImpl, base, token, routeId, fare, documentPath, log) {
  const form = new FormData();
  form.append('route_id', String(routeId));
  form.append('base_fare', String(fare.base_fare));
  form.append('base_distance_km', String(fare.base_distance_km));
  form.append('per_km_fare', String(fare.per_km_fare));
  form.append('rounding_step_cents', String(fare.rounding_step_cents));
  form.append('discounts', JSON.stringify(fare.discounts));
  if (fare.order_reference) form.append('order_reference', fare.order_reference);
  if (fare.effective_date) form.append('effective_date', fare.effective_date);
  if (documentPath) {
    const bytes = await readFile(documentPath);
    form.append('document', new Blob([bytes]), documentPath.split(/[\\/]/).pop());
  }
  await api(fetchImpl, base, token, 'POST', '/api/fares', undefined, { form });
  log.push(`published fare matrix for route ${routeId}${documentPath ? ' with its document' : ''}`);
}

/** Reads what the server already has, so the plan can skip what exists. */
export async function readExisting(fetchImpl, base, token) {
  const vehicles = await api(fetchImpl, base, token, 'GET', '/api/vehicles');
  const conductors = (await api(fetchImpl, base, token, 'GET', '/api/conductors')) ?? [];
  const routes = (await api(fetchImpl, base, token, 'GET', '/api/routes')) ?? [];
  const fareByRoute = {};
  for (const route of routes) {
    const paths = (await api(fetchImpl, base, token, 'GET', `/api/routes/${route.id}/paths`)) ?? [];
    route.paths = paths;
    try {
      const matrix = await api(fetchImpl, base, token, 'GET', `/api/fares/active/${route.id}`);
      if (matrix && matrix.id != null) fareByRoute[route.id] = matrix;
    } catch (err) {
      if (err.status !== 404) throw err;
    }
  }
  return { vehicles: vehicles ?? [], conductors, routes, fareByRoute };
}

// --- Hidden prompt -------------------------------------------------------------------------------

/**
 * Reads one line from the terminal without echoing it. Uses raw mode, which Node documents, rather than a readline
 * internal. Ctrl-C cancels. Without a terminal it fails, unless the caller set the value in the environment.
 */
export function readHidden(question, stdin = process.stdin, stdout = process.stdout) {
  if (!stdin.isTTY) return Promise.reject(new Error('no terminal to type into; run it in a terminal, or use the environment variables'));
  return new Promise((resolvePromise, rejectPromise) => {
    stdout.write(question);
    stdin.setEncoding('utf8');
    let value = '';
    const finish = (fn) => {
      stdin.setRawMode(false);
      stdin.pause();
      stdin.removeListener('data', onData);
      stdout.write('\n');
      fn();
    };
    const onData = (chunk) => {
      // Accept a string or a Buffer. Iterating a Buffer yields numbers, which would never match a character.
      for (const ch of String(chunk)) {
        if (ch === '\r' || ch === '\n') return finish(() => resolvePromise(value));
        if (ch === '\u0003') return finish(() => rejectPromise(new Error('cancelled')));
        if (ch === '\u007f' || ch === '\b') value = value.slice(0, -1);
        else value += ch;
      }
    };
    // The listener is attached before raw mode and resume, so no keystroke can arrive before it is read.
    // Without setRawMode and resume the terminal sends nothing until Enter, and the prompt never reads a key.
    stdin.on('data', onData);
    stdin.setRawMode(true);
    stdin.resume();
  });
}

/** The new conductor's password: from TOMS_CONDUCTOR_PASSWORD, otherwise asked for at the hidden prompt. */
export async function conductorSecret(env = process.env, stdin = process.stdin, stdout = process.stdout) {
  if (env.TOMS_CONDUCTOR_PASSWORD) return env.TOMS_CONDUCTOR_PASSWORD;
  return readHidden('Password for the new conductor (at least 8 characters): ', stdin, stdout);
}

/**
 * Reads one line from the terminal and shows what is typed. A username is not a secret, so it is not hidden.
 * Without a terminal it fails, unless the caller set the value in the environment.
 */
export function readVisible(question, stdin = process.stdin, stdout = process.stdout) {
  if (!stdin.isTTY) return Promise.reject(new Error('no terminal to type into; run it in a terminal, or use the environment variables'));
  stdout.write(question);
  return new Promise((resolvePromise) => {
    let value = '';
    const onData = (chunk) => {
      for (const ch of String(chunk)) {
        if (ch === '\r' || ch === '\n') {
          stdin.removeListener('data', onData);
          stdin.setRawMode(false);
          stdin.pause();
          stdout.write('\n');
          return resolvePromise(value.trim());
        }
        if (ch === '\u007f' || ch === '\b') {
          value = value.slice(0, -1);
          stdout.write('\b \b');
        } else {
          value += ch;
          stdout.write(ch);
        }
      }
    };
    // The listener is attached before the terminal is resumed, so no keystroke can arrive before it is read.
    stdin.on('data', onData);
    stdin.setRawMode(true);
    stdin.resume();
  });
}

/** The admin credentials: from the environment when both are set, otherwise asked for in the terminal. */
export async function adminCredentials(env = process.env, stdin = process.stdin, stdout = process.stdout) {
  const username = env.TOMS_ADMIN_USERNAME || await readVisible('Admin username: ', stdin, stdout);
  const password = env.TOMS_ADMIN_PASSWORD || await readHidden('Admin password: ', stdin, stdout);
  if (!username || !password) throw new Error('an admin username and password are both needed');
  return { username, password };
}

// --- Command line --------------------------------------------------------------------------------

function parseArgs(argv) {
  const out = { server: null, dryRun: false, data: DEFAULT_DATA, positional: [] };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === '--server') out.server = argv[++i];
    else if (a === '--dry-run') out.dryRun = true;
    else if (a === '--data') out.data = resolve(argv[++i]);
    else out.positional.push(a);
  }
  return out;
}

export function checkServerAddress(server) {
  if (!server) return 'pass --server https://your-server (there is no default, so the script cannot hit the wrong server)';
  if (!/^https:\/\//.test(server)) return 'the server address must start with https://';
  return null;
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  const serverProblem = checkServerAddress(args.server);
  if (serverProblem) {
    console.error(`error: ${serverProblem}`);
    process.exit(2);
  }
  const base = args.server.replace(/\/+$/, '');
  const command = args.positional[0];

  if (command === 'enrol-device') {
    const [, deviceId, vehicleId] = args.positional;
    if (!deviceId || !vehicleId) {
      console.error('usage: setup-pilot.mjs --server <url> enrol-device <device-id> <vehicle-id>');
      process.exit(2);
    }
    const { username, password } = await adminCredentials();
    const token = await login(fetch, base, username, password);
    const created = await api(fetch, base, token, 'POST', '/api/devices', { device_id: deviceId, vehicle_id: vehicleId });
    console.log(`Enrolled device ${deviceId} on vehicle ${vehicleId}.`);
    console.log('');
    console.log('DEVICE TOKEN (shown once):');
    console.log(created.token);
    console.log('');
    console.log('Copy it into the app now (More > Server Sync > Set Token). It is not saved anywhere else.');
    return;
  }

  const raw = await readFile(args.data, 'utf8').catch((err) => {
    throw new Error(`cannot read ${args.data}: ${err.code === 'ENOENT' ? 'file not found (copy scripts/pilot-data.example.json)' : err.message}`);
  });
  const data = JSON.parse(raw);
  const problems = validatePilotData(data);
  if (problems.length > 0) {
    console.error('The pilot data is not valid:');
    for (const p of problems) console.error(`  - ${p}`);
    process.exit(1);
  }

  if (args.dryRun) {
    console.log(`Dry run for ${base}. Nothing is sent.`);
    console.log(`Vehicle ${data.vehicle.id}, conductor ${data.conductor.username}, route "${data.route.name}" with ${data.route.stops.length} stops, one fare matrix.`);
    console.log('Credentials are asked for only on a real run, and not in a dry run.');
    return;
  }

  const { username, password } = await adminCredentials();
  const token = await login(fetch, base, username, password);
  const existing = await readExisting(fetch, base, token);
  const plan = planChanges(data, existing);
  for (const step of plan) {
    console.log(`${step.action.padEnd(7)} ${step.what}${step.reason ? ` (${step.reason})` : ''}`);
  }
  // Only asked for when a new conductor will be created, so a re-run does not ask again.
  const needsConductorPassword = plan.some((s) => s.action === 'create' && s.what.startsWith(`conductor ${data.conductor.username}`));
  const conductorPassword = needsConductorPassword ? await conductorSecret() : undefined;
  const log = await applyPilot(fetch, base, token, data, existing, conductorPassword);
  console.log('');
  for (const line of log) console.log(`  ${line}`);
  console.log('\nDone. Next: enrol the phone with: node scripts/setup-pilot.mjs --server <url> enrol-device <device-id> ' + data.vehicle.id);
}

/** Logs in and returns the token. The token is only returned to the caller, never printed. */
export async function login(fetchImpl, base, username, password) {
  const res = await api(fetchImpl, base, null, 'POST', '/api/admin/login', { username, password });
  if (!res?.token) throw new Error('the login did not return a token');
  return res.token;
}

const isMain = process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url);
if (isMain) {
  main().catch((err) => {
    console.error(`error: ${err.message}`);
    process.exit(1);
  });
}
