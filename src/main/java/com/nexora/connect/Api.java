package com.nexora.connect;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The plugin's web interface (the bot is the client). Every request is signed (see Auth). It can only do four things:
 *   GET  /v1/hello   who is online, versions
 *   GET  /v1/events  game events after a position (long polling)
 *   POST /v1/chat    show a Discord message in the game chat
 *   POST /v1/tell    a private message to one player
 * It can NOT run commands.
 */
final class Api {
    static final int PROTOCOL = 1;
    private static final Gson GSON = new Gson();
    private final Game game;
    private final Events events;
    private final Auth auth;
    private final String bind;
    private final int port;
    private final String version;
    private final Logger log;
    private HttpServer server;
    private ExecutorService pool;
    volatile long lastSeen = 0;    // when the bot last made a valid request

    @SuppressWarnings("serial")
    private static final class BadRequest extends Exception {
        BadRequest(String message) { super(message); }
    }

    Api(Game game, Events events, Auth auth, String bind, int port, String version, Logger log) {
        this.game = game; this.events = events; this.auth = auth; this.bind = bind; this.port = port; this.version = version; this.log = log;
    }

    int port() {
        return server == null ? port : server.getAddress().getPort();
    }

    void start() throws IOException {
        InetSocketAddress address = bind == null || bind.isBlank() ? new InetSocketAddress(port) : new InetSocketAddress(bind, port);
        server = HttpServer.create(address, 50);
        pool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "NexoraConnect-http");
            t.setDaemon(true);
            return t;
        });
        server.setExecutor(pool);
        server.createContext("/", this::handle);
        server.start();
    }

    void stop() {
        if (server != null) server.stop(0);
        if (pool != null) pool.shutdownNow();
    }

    private void handle(HttpExchange ex) {
        String time = null, nonce = null;
        try {
            String method = ex.getRequestMethod();
            URI uri = ex.getRequestURI();
            String target = uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
            byte[] body = readBody(ex.getRequestBody(), 16384);
            if (body == null) {
                send(ex, 413, error("too large"), null, null);
                return;
            }
            Headers h = ex.getRequestHeaders();
            time = h.getFirst("X-Nexora-Time");
            nonce = h.getFirst("X-Nexora-Nonce");
            String problem = auth.check(time, nonce, method, target, h.getFirst("X-Nexora-Sig"), body);
            if (problem != null) {
                JsonObject o = error(problem);
                o.addProperty("server_time", System.currentTimeMillis() / 1000);
                send(ex, 401, o, null, null);
                return;
            }
            lastSeen = System.currentTimeMillis();
            String path = uri.getPath();
            JsonObject out;
            if (method.equals("GET") && path.equals("/v1/hello")) out = hello();
            else if (method.equals("GET") && path.equals("/v1/events")) out = poll(query(uri));
            else if (method.equals("POST") && path.equals("/v1/chat")) out = chat(parse(body));
            else if (method.equals("POST") && path.equals("/v1/tell")) out = tell(parse(body));
            else {
                send(ex, 404, error("not found"), time, nonce);
                return;
            }
            send(ex, 200, out, time, nonce);
        } catch (BadRequest e) {
            send(ex, 400, error(e.getMessage()), time, nonce);
        } catch (Exception e) {
            log.log(Level.WARNING, "Request failed", e);
            send(ex, 500, error("internal"), time, nonce);
        } finally {
            ex.close();
        }
    }

    private JsonObject hello() {
        long position = events.last();             // taken first, so nothing that happens while the player list is read is missed
        Game.Status s = game.status();
        JsonObject o = new JsonObject();
        o.addProperty("plugin", "NexoraConnect");
        o.addProperty("version", version);
        o.addProperty("protocol", PROTOCOL);
        o.addProperty("server", s.server());
        o.addProperty("minecraft", s.minecraft());
        o.addProperty("epoch", String.valueOf(events.epoch));
        o.addProperty("last", position);
        o.addProperty("now", System.currentTimeMillis());
        o.addProperty("max", s.max());
        JsonArray online = new JsonArray();
        s.online().forEach(online::add);
        o.add("online", online);
        return o;
    }

    private JsonObject poll(Map<String, String> q) throws InterruptedException {
        long after = number(q.get("after"), -1);
        long wait = Math.max(0, Math.min(30, number(q.get("wait"), 0))) * 1000;
        boolean reset = after >= 0 && !String.valueOf(events.epoch).equals(q.get("epoch"));     // the plugin restarted since the bot's last answer
        List<JsonObject> list = events.since(reset ? 0 : after, wait);
        JsonArray array = new JsonArray();
        list.forEach(array::add);
        JsonObject o = new JsonObject();
        o.addProperty("epoch", String.valueOf(events.epoch));
        o.addProperty("last", events.last());
        o.addProperty("now", System.currentTimeMillis());
        o.addProperty("reset", reset);
        o.add("events", array);
        return o;
    }

    private JsonObject chat(JsonObject in) throws BadRequest {
        String name = Text.line(str(in, "name"), 40), text = Text.line(str(in, "text"), 240), prefix = Text.line(str(in, "prefix"), 16);
        if (name.isEmpty() || text.isEmpty()) throw new BadRequest("name and text are required");
        game.broadcast(prefix.isEmpty() ? "Discord" : prefix, name, Text.hex(str(in, "color")), text);
        JsonObject o = new JsonObject();
        o.addProperty("ok", true);
        return o;
    }

    private JsonObject tell(JsonObject in) throws BadRequest {
        String player = str(in, "player"), text = Text.line(str(in, "text"), 240), color = str(in, "color");
        if (!Text.PLAYER.matcher(player).matches() || text.isEmpty()) throw new BadRequest("player and text are required");
        boolean delivered = game.tell(player, text, Text.TELL_COLORS.contains(color) ? color : "green");
        JsonObject o = new JsonObject();
        o.addProperty("ok", true);
        o.addProperty("delivered", delivered);
        return o;
    }

    // ---------- helpers ----------
    private static String str(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : "";
    }

    private static JsonObject parse(byte[] body) throws BadRequest {
        try {
            return JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (JsonParseException | IllegalStateException e) {
            throw new BadRequest("invalid JSON");
        }
    }

    private static long number(String s, long fallback) {
        try {
            return s == null ? fallback : Long.parseLong(s);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static Map<String, String> query(URI uri) {
        Map<String, String> q = new HashMap<>();
        String raw = uri.getRawQuery();
        if (raw == null) return q;
        for (String pair : raw.split("&")) {
            int i = pair.indexOf('=');
            if (i > 0) q.put(URLDecoder.decode(pair.substring(0, i), StandardCharsets.UTF_8), URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8));
        }
        return q;
    }

    private static byte[] readBody(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[2048];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            if (out.size() > limit) return null;
        }
        return out.toByteArray();
    }

    private static JsonObject error(String message) {
        JsonObject o = new JsonObject();
        o.addProperty("error", message);
        return o;
    }

    private void send(HttpExchange ex, int code, JsonObject body, String time, String nonce) {
        try {
            byte[] bytes = GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            if (time != null && nonce != null) ex.getResponseHeaders().set("X-Nexora-Sig", auth.signResponse(time, nonce, bytes));
            ex.sendResponseHeaders(code, bytes.length);
            ex.getResponseBody().write(bytes);
        } catch (IOException ignored) {
            // the bot went away
        }
    }
}
