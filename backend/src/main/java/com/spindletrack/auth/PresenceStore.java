package com.spindletrack.auth;

import java.util.Map;

public interface PresenceStore {
    record Entry(boolean online, Long lastSeen, String shopId) {}
    Map<String, Entry> readGroup(String group);
}
