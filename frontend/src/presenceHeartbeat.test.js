import assert from 'node:assert/strict';
import test from 'node:test';
import { startPresenceHeartbeat } from './presenceHeartbeat.js';

function createFirebaseMocks({ onlineError } = {}) {
  const events = [];
  const errors = [];
  const database = { mocked: true };
  const logger = { error: (...args) => errors.push(args) };
  const user = {
    uid: 'firebase-user-1',
    getIdToken: async (forceRefresh) => events.push(['refresh-token', forceRefresh]),
  };
  const ref = (actualDatabase, path) => {
    assert.equal(actualDatabase, database);
    events.push(['ref', path]);
    return { path };
  };
  const onDisconnect = (presenceRef) => {
    events.push(['onDisconnect', presenceRef.path]);
    return {
      set: async (value) => events.push(['onDisconnect.set', presenceRef.path, value]),
      cancel: async () => events.push(['onDisconnect.cancel', presenceRef.path]),
    };
  };
  const set = async (presenceRef, value) => {
    events.push(['set', presenceRef.path, value]);
    if (value.online && onlineError) throw onlineError;
  };
  const serverTimestamp = () => 'SERVER_TIMESTAMP';
  return { database, errors, events, logger, onDisconnect, ref, serverTimestamp, set, user };
}

test('admin and customer paths use the backend role after refreshing claims and register offline before online', async () => {
  for (const [role, group] of [['PLATFORM_ADMIN', 'admin'], ['CUSTOMER', 'customers']]) {
    const mocks = createFirebaseMocks();
    const heartbeat = startPresenceHeartbeat({
      ...mocks,
      session: { role },
    });

    await heartbeat.ready;
    const path = `presence/${group}/${mocks.user.uid}`;
    assert.deepEqual(mocks.events.map((event) => event[0]), [
      'refresh-token', 'ref', 'onDisconnect', 'onDisconnect.set', 'set',
    ]);
    assert.equal(mocks.events[0][1], true);
    assert.equal(mocks.events[1][1], path);
    assert.equal(mocks.events[3][2].online, false);
    assert.equal(mocks.events[4][2].online, true);

    await heartbeat.stop();
    assert.equal(mocks.events.at(-2)[0], 'onDisconnect.cancel');
    assert.equal(mocks.events.at(-1)[0], 'set');
    assert.equal(mocks.events.at(-1)[2].online, false);
    assert.deepEqual(mocks.errors, []);
  }
});

test('missing RTDB URL is reported without attempting a Firebase write', async () => {
  const mocks = createFirebaseMocks();
  const heartbeat = startPresenceHeartbeat({
    ...mocks,
    database: null,
    session: { role: 'CUSTOMER' },
  });

  await heartbeat.ready;
  assert.equal(mocks.errors.length, 1);
  assert.match(mocks.errors[0][0], /VITE_FIREBASE_DATABASE_URL/);
  assert.deepEqual(mocks.events, []);
});

test('Realtime Database permission errors are logged without logging a token', async () => {
  const mocks = createFirebaseMocks({
    onlineError: { code: 'PERMISSION_DENIED', message: 'Permission denied' },
  });
  const heartbeat = startPresenceHeartbeat({
    ...mocks,
    session: { role: 'CUSTOMER' },
  });

  await heartbeat.ready;
  assert.equal(mocks.errors.length, 1);
  assert.match(mocks.errors[0][1].code, /PERMISSION_DENIED/);
  assert.match(mocks.errors[0][1].message, /Permission denied/);
  assert.doesNotMatch(JSON.stringify(mocks.errors), /idToken|Bearer\s/);
});
