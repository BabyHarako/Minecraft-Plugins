package com.nexora.connect;

import com.google.gson.JsonObject;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Connects the server to the Nexora Discord bot. The bot connects to this plugin (not the other way round), so no bot token is ever on the server.
 * See Api.java for what the connection can do.
 */
public final class NexoraConnect extends JavaPlugin implements Listener, Game {
    private static final Pattern LINK = Pattern.compile("^!link\\s+([A-Za-z0-9]{4,10})$", Pattern.CASE_INSENSITIVE);
    private Events events;
    private Api api;
    private LinkRequests linkRequests;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        String key = getConfig().getString("key", "").trim();
        if (key.length() < 24) {
            getLogger().severe("No link key in plugins/NexoraConnect/config.yml. On the Nexora dashboard (Minecraft page) choose the plugin connection, press Generate key, "
                    + "paste the key into config.yml and restart the server. Nothing is shared until then.");
            return;
        }
        events = new Events();
        linkRequests = new LinkRequests(events);
        api = new Api(this, events, new Auth(key, Math.max(10, getConfig().getLong("clock-skew-seconds", 60))),
                getConfig().getString("bind", "0.0.0.0"), getConfig().getInt("port", 25590), getDescription().getVersion(), getLogger());
        try {
            api.start();
        } catch (Exception e) {
            getLogger().severe("Could not open port " + getConfig().getInt("port", 25590) + " (" + e.getMessage() + "). Is it added under Network in your host's panel and not used by something else?");
            api = null;
            return;
        }
        getLogger().info("Listening for the Nexora bot on port " + api.port() + ".");
        getServer().getPluginManager().registerEvents(this, this);
        status("starting");
        Bukkit.getScheduler().runTask(this, () -> status("running"));        // runs on the first tick, which is after the server finished loading
    }

    @Override
    public void onDisable() {
        if (api == null) return;
        status("offline");
        try {
            Thread.sleep(300);          // lets the bot's waiting request pick up the last event
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        api.stop();
    }

    // ---------- game -> Discord ----------
    private void status(String state) {
        JsonObject e = new JsonObject();
        e.addProperty("type", "status");
        e.addProperty("state", state);
        events.add(e);
    }

    private JsonObject event(String type, Player p) {
        JsonObject e = new JsonObject();
        e.addProperty("type", type);
        e.addProperty("player", p.getName());
        e.addProperty("prefix", placeholder(p, getConfig().getString("rank-placeholder", "")));
        e.addProperty("suffix", placeholder(p, getConfig().getString("tag-placeholder", "")));
        String teamTemplate = getConfig().getString("team-placeholder", "");
        if (teamTemplate != null && !teamTemplate.isBlank()) e.addProperty("team", placeholder(p, teamTemplate));      // sent only when set: an empty text then means "no team"
        return e;
    }

    /** Optional: a PlaceholderAPI text such as %luckperms_prefix% gives the rank shown before the name in Discord. */
    private String placeholder(Player p, String template) {
        if (template == null || template.isBlank() || Bukkit.getPluginManager().getPlugin("PlaceholderAPI") == null) return "";
        try {
            Object r = Class.forName("me.clip.placeholderapi.PlaceholderAPI").getMethod("setPlaceholders", OfflinePlayer.class, String.class).invoke(null, p, template);
            return Text.line(Text.stripColors(String.valueOf(r)), 32);
        } catch (Throwable t) {
            return "";
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onLinkCode(AsyncPlayerChatEvent e) {
        Matcher m = LINK.matcher(e.getMessage().trim());
        if (!m.matches()) return;
        e.setCancelled(true);                         // the code never shows in the game chat
        JsonObject o = new JsonObject();
        o.addProperty("type", "linkcode");
        o.addProperty("player", e.getPlayer().getName());
        o.addProperty("code", m.group(1).toUpperCase(Locale.ROOT));
        events.add(o);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent e) {
        JsonObject o = event("chat", e.getPlayer());
        o.addProperty("message", Text.line(Text.stripColors(e.getMessage()), 500));
        events.add(o);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        events.add(event("join", e.getPlayer()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        events.add(event("leave", e.getPlayer()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    @SuppressWarnings("deprecation")
    public void onDeath(PlayerDeathEvent e) {
        String message = e.getDeathMessage();
        if (message == null || message.isBlank()) return;
        JsonObject o = event("death", e.getEntity());
        o.addProperty("death_message", Text.line(Text.stripColors(message), 300));
        events.add(o);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAdvancement(PlayerAdvancementDoneEvent e) {
        String key = e.getAdvancement().getKey().getKey();          // for example story/mine_stone
        if (key.startsWith("recipes/") || key.endsWith("/root")) return;
        StringBuilder title = new StringBuilder();
        for (String word : key.substring(key.lastIndexOf('/') + 1).split("_")) {
            if (word.isEmpty()) continue;
            title.append(title.length() > 0 ? " " : "").append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        JsonObject o = event("advancement", e.getPlayer());
        o.addProperty("advancement", title.toString());
        events.add(o);
    }

    // ---------- Discord -> game ----------
    @Override
    public Status status() {
        try {
            return Bukkit.getScheduler().callSyncMethod(this, this::snapshot).get(3, TimeUnit.SECONDS);
        } catch (Exception e) {
            return snapshot();         // the main thread is busy: reading the list from here is good enough
        }
    }

    private Status snapshot() {
        List<String> names = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) names.add(p.getName());
        return new Status(Bukkit.getServer().getName(), Bukkit.getBukkitVersion(), names, Bukkit.getMaxPlayers());
    }

    @Override
    public void broadcast(String prefix, String name, String hexColor, String text) {
        Bukkit.getScheduler().runTask(this, () -> {
            BaseComponent[] message = {part("[" + prefix + "] ", ChatColor.of("#5865f2")), part(name, ChatColor.of(hexColor)), part(": ", ChatColor.GRAY), part(text, ChatColor.WHITE)};
            for (Player p : Bukkit.getOnlinePlayers()) p.spigot().sendMessage(message);
            Bukkit.getConsoleSender().sendMessage("[" + prefix + "] " + name + ": " + text);
        });
    }

    @Override
    public boolean tell(String player, String text, String color) {
        try {
            return Bukkit.getScheduler().callSyncMethod(this, () -> {
                Player p = Bukkit.getPlayerExact(player);
                if (p == null) return false;
                p.spigot().sendMessage(part(text, ChatColor.valueOf(color.toUpperCase(Locale.ROOT))));
                return true;
            }).get(3, TimeUnit.SECONDS);
        } catch (Exception e) {
            return false;
        }
    }

    private static TextComponent part(String text, ChatColor color) {
        TextComponent c = new TextComponent(text);
        c.setColor(color);
        return c;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("mc")) {              // /mc link: ask the bot for a code to enter in Discord
            if (!(sender instanceof Player)) {
                sender.sendMessage("Only players can link their account.");
            } else if (api == null || linkRequests == null) {
                sender.sendMessage("The Nexora connection is not running. Ask an admin.");
            } else if (args.length == 1 && args[0].equalsIgnoreCase("link")) {
                sender.sendMessage(linkRequests.request(((Player) sender).getName(), System.currentTimeMillis()));
            } else {
                sender.sendMessage("Usage: /mc link");
            }
            return true;
        }
        long ago = api == null ? -1 : api.lastSeen == 0 ? -1 : (System.currentTimeMillis() - api.lastSeen) / 1000;
        sender.sendMessage(api == null ? "NexoraConnect is not running. Check the key in config.yml and the console."
                : "NexoraConnect listens on port " + api.port() + ". " + (ago < 0 ? "The bot has not connected yet." : "The bot was last seen " + ago + " seconds ago."));
        return true;
    }
}
