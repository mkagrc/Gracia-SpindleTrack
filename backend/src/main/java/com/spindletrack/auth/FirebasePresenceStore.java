package com.spindletrack.auth;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.ValueEventListener;
import org.springframework.stereotype.Repository;

@Repository
public class FirebasePresenceStore implements PresenceStore {
    private final FirebaseAdminTokenVerifier firebase;

    public FirebasePresenceStore(FirebaseAdminTokenVerifier firebase) { this.firebase = firebase; }

    @Override
    public Map<String, Entry> readGroup(String group) {
        if (!java.util.List.of("admin", "owners", "staff", "customers").contains(group)) {
            throw new IllegalArgumentException("Unknown presence group.");
        }
        DataSnapshot snapshot = read(firebase.realtimeDatabase().getReference("presence").child(group));
        Map<String, Entry> entries = new LinkedHashMap<>();
        for (DataSnapshot child : snapshot.getChildren()) {
            Object onlineValue = child.child("online").getValue();
            Object seenValue = child.child("lastSeen").getValue();
            Long lastSeen = seenValue instanceof Number number ? number.longValue() : null;
            entries.put(child.getKey(), new Entry(Boolean.TRUE.equals(onlineValue), lastSeen,
                    child.child("shopId").getValue(String.class)));
        }
        return Map.copyOf(entries);
    }

    private static DataSnapshot read(DatabaseReference reference) {
        CompletableFuture<DataSnapshot> result = new CompletableFuture<>();
        reference.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snapshot) { result.complete(snapshot); }
            @Override public void onCancelled(DatabaseError error) { result.completeExceptionally(new IllegalStateException("Realtime Database presence read failed.")); }
        });
        try {
            return result.get(15, TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new IllegalStateException("Realtime Database presence read failed or timed out.", exception);
        }
    }
}
