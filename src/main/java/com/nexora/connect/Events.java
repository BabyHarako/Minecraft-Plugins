package com.nexora.connect;

import com.google.gson.JsonObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** The last events of the game, in order. The bot asks for everything after the last one it saw (long polling). */
final class Events {
    static final int MAX = 500;
    final long epoch = System.currentTimeMillis();   // changes when the plugin restarts, so the bot knows its position is no longer valid
    private final ArrayDeque<JsonObject> buffer = new ArrayDeque<>();
    private long last = 0;

    synchronized void add(JsonObject event) {
        last++;
        event.addProperty("id", last);
        event.addProperty("t", System.currentTimeMillis());
        buffer.addLast(event);
        while (buffer.size() > MAX) buffer.removeFirst();
        notifyAll();
    }

    synchronized long last() {
        return last;
    }

    /** Events after the given id; waits up to waitMillis when there are none yet. after below 0 only asks where we are (no events). */
    synchronized List<JsonObject> since(long after, long waitMillis) throws InterruptedException {
        List<JsonObject> out = new ArrayList<>();
        if (after < 0) return out;
        long deadline = System.currentTimeMillis() + waitMillis;
        while (last <= after) {
            long left = deadline - System.currentTimeMillis();
            if (left <= 0) break;
            wait(left);
        }
        for (JsonObject e : buffer) if (e.get("id").getAsLong() > after) out.add(e);
        return out;
    }
}
