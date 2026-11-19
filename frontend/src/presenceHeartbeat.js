const PRESENCE_GROUP = {
  PLATFORM_ADMIN: 'admin',
  OWNER: 'owners',
  STAFF: 'staff',
  CUSTOMER: 'customers',
};

function logError(logger, action, error) {
  logger.error(`[SpindleTrack presence] ${action}`, {
    code: typeof error?.code === 'string' ? error.code : 'unknown',
    message: typeof error?.message === 'string' ? error.message : 'No error message provided',
  });
}

export function startPresenceHeartbeat({
  database,
  user,
  session,
  ref,
  onDisconnect,
  serverTimestamp,
  set,
  logger = console,
}) {
  if (!database) {
    logger.error('[SpindleTrack presence] Heartbeat was not started: VITE_FIREBASE_DATABASE_URL is missing.');
    return { ready: Promise.resolve(), stop: async () => {} };
  }

  const group = PRESENCE_GROUP[session?.role];
  if (!group) {
    logger.error('[SpindleTrack presence] Heartbeat was not started: backend session returned an unsupported role.', {
      role: session?.role ?? null,
    });
    return { ready: Promise.resolve(), stop: async () => {} };
  }

  let stopped = false;
  let stopPromise;
  let presenceRef;
  let disconnectRegistration;
  const shopId = session.shopId || session.shop?.id || null;
  const extra = session.role === 'STAFF' && shopId ? { shopId } : {};
  const offlineValue = { online: false, lastSeen: serverTimestamp(), ...extra };
  const onlineValue = { online: true, lastSeen: serverTimestamp(), ...extra };

  const ready = (async () => {
    try {
      // Session creation updates custom claims; refresh before Realtime Database evaluates rules.
      await user.getIdToken(true);
      if (stopped) return;
      presenceRef = ref(database, `presence/${group}/${user.uid}`);
      disconnectRegistration = onDisconnect(presenceRef);
      await disconnectRegistration.set(offlineValue);
      if (!stopped) await set(presenceRef, onlineValue);
    } catch (error) {
      logError(logger, 'Could not register or write the presence heartbeat.', error);
    }
  })();

  function stop() {
    if (stopPromise) return stopPromise;
    stopped = true;
    stopPromise = (async () => {
      await ready;
      if (disconnectRegistration) {
        try {
          await disconnectRegistration.cancel();
        } catch (error) {
          logError(logger, 'Could not cancel the disconnect handler during cleanup.', error);
        }
      }
      if (presenceRef) {
        try {
          await set(presenceRef, offlineValue);
        } catch (error) {
          logError(logger, 'Could not mark the user offline during cleanup.', error);
        }
      }
    })();
    return stopPromise;
  }

  return { ready, stop };
}

export function createPresenceHeartbeatManager(start = startPresenceHeartbeat) {
  let current = null;
  let queue = Promise.resolve();
  let generation = 0;

  function switchTo(options) {
    const requestedGeneration = ++generation;
    const operation = queue.then(async () => {
      if (current) {
        const previous = current;
        current = null;
        await previous.stop();
      }
      if (requestedGeneration !== generation) return null;
      const heartbeat = start(options);
      current = heartbeat;
      await heartbeat.ready;
      return heartbeat;
    });
    queue = operation.catch(() => {});
    return operation;
  }

  function stop() {
    generation += 1;
    const operation = queue.then(async () => {
      if (!current) return;
      const previous = current;
      current = null;
      await previous.stop();
    });
    queue = operation.catch(() => {});
    return operation;
  }

  return { switchTo, stop };
}
