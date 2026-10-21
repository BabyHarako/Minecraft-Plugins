package com.nexora.connect;

import java.util.List;

/** What the network part needs from the game. The Minecraft side is NexoraConnect.java; tests use a fake. */
public interface Game {
    record Status(String server, String minecraft, List<String> online, int max) {}

    /** May be called from any thread. */
    Status status();

    /** Shows a message from Discord in the game chat. Implementations run it on the main thread. */
    void broadcast(String prefix, String name, String hexColor, String text);

    /** A message only one player sees. Returns false when that player is not online. */
    boolean tell(String player, String text, String color);
}
