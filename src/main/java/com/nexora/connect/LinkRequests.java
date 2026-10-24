package com.nexora.connect;

import com.google.gson.JsonObject;

import java.util.HashMap;
import java.util.Map;

/**
 * The in-game "/mc link": the player asks the bot for a link code. The bot answers with a private message in the game, and the player then
 * types /mc link and the code in Discord. Only the player's own name is sent (never a code), so nothing secret passes through here.
 * No Minecraft code in this file, so tools/test_plugin.py runs exactly this.
 */
final class LinkRequests {
    static final long COOLDOWN_MILLIS = 5000;
    private final Events events;
    private final Map<String, Long> last = new HashMap<>();

    LinkRequests(Events events) {
        this.events = events;
    }

    /** Adds the request for the bot and returns the text to show the player right away (the code itself arrives as a separate private message). */
    synchronized String request(String player, long now) {
        Long before = last.get(player);
        if (before != null && now - before < COOLDOWN_MILLIS) return "Please wait a few seconds before asking again.";
        last.put(player, now);
        if (last.size() > 500) last.values().removeIf(t -> now - t > 60_000);
        JsonObject o = new JsonObject();
        o.addProperty("type", "linkrequest");
        o.addProperty("player", player);
        events.add(o);
        return "Asking the Nexora bot for your link code...";
    }
}
