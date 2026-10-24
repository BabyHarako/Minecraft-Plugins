# NexoraConnect

A small Minecraft plugin (Paper, Spigot, Purpur, 1.18 and newer) that connects your server to the Nexora Discord bot.

Unlike DiscordSRV, **no Discord bot token is stored on the Minecraft server**. The bot runs on your computer and connects to the plugin.
The plugin opens one port; the bot connects to it. Your computer needs no open port.

## What it does
- Game -> Discord: chat, joins, leaves, deaths and advancements, with the exact player name (Java and Bedrock/Floodgate), and an optional rank/tag from PlaceholderAPI.
- Discord -> game: shows Discord chat in the game chat.
- Account linking, two ways: (1) in the game type `/mc link`, the bot answers with a private code, then type `/mc link` with that code in Discord. (2) `/mc link` in Discord gives a code, and the player types `!link CODE` in the game chat: the plugin hides that line from everyone and tells the bot, so the code never shows up in chat. Either way the Minecraft name is proven to belong to that player.
- Server start and stop messages.

It can **not** run commands, read files or change anything else. The web interface only has four routes (hello, events, chat, tell).

## Install
1. Build `NexoraConnect.jar` (below) or get it from the dashboard (Minecraft page, Connection, Nexora plugin).
2. Upload it to the server's `plugins` folder and start the server once.
3. In your host's panel add one extra port under **Network** (the same way you did for other plugins).
4. On the Nexora dashboard: Minecraft page, Connection, choose **Nexora plugin**, enter `server-ip:port`, press **Generate key**, **Save settings**.
5. Open `plugins/NexoraConnect/config.yml`: set `port:` to your port and `key:` to the generated key. Restart the server.
6. Press **Test connection** on the dashboard.

Players link with `/mc link` (permission `nexoraconnect.link`, everyone by default). Check on the server at any time with `/nexoraconnect` (it says when the bot was last seen).

## Build
You need Java 17 and Gradle:

    gradle build

The jar is `build/libs/NexoraConnect.jar`. Copy it next to this README as `NexoraConnect.jar` and the dashboard offers it as a download.

No Java on your PC? Push this folder to a GitHub repository (it has `.github/workflows/build.yml`), open the **Actions** tab and download the finished `NexoraConnect` file.

## Security
- Every request is signed with HMAC-SHA256 using the link key: `time \n nonce \n METHOD \n path?query \n sha256(body)`. The key itself is never sent.
- A request is valid for 60 seconds (`clock-skew-seconds`) and every nonce works only once, so a recorded request can not be replayed.
- Every answer is signed too, so the bot knows the answer comes from your plugin and not from something in between.
- Without a valid signature a request gets `signature` and nothing else. The reason (wrong clock, replay) is only told to someone who has the key.
- Traffic is **not encrypted** (the same as RCON). It contains only game chat, but do not put the key anywhere public. If it leaks, press Generate key again and paste the new key into `config.yml`.
- Everything the plugin receives is cleaned (colour codes, control characters, lengths, player names) before it reaches the game.

## Protocol (version 1)
Headers on every request: `X-Nexora-Time`, `X-Nexora-Nonce` (8-64 letters/digits), `X-Nexora-Sig`. Answers carry `X-Nexora-Sig` = HMAC(key, `resp \n time \n nonce \n sha256(answer)`).

| Route | |
|---|---|
| `GET /v1/hello` | plugin version, server, who is online, `epoch`, `last` (the position to start polling from) |
| `GET /v1/events?epoch=E&after=ID&wait=S` | events after `ID` (waits up to `S` seconds, at most 30). `after=-1` only returns the current position. A different `epoch` means the plugin restarted: `reset` is true and everything since the start is returned. |
| `POST /v1/chat` | `{prefix, name, color, text}` show a message in the game chat |
| `POST /v1/tell` | `{player, text, color}` a private message |

Events: `chat`, `join`, `leave`, `death`, `advancement`, `linkcode`, `team` (only when `team-placeholder` is set) in every player event, `linkrequest` (a player typed `/mc link`; only the player's name is sent, the bot answers through `/v1/tell`), `status` (starting, running, offline). The plugin keeps the last 500.

## Source layout
- `Api.java`, `Auth.java`, `Events.java`, `Text.java`, `Game.java`, `LinkRequests.java`: the network part, no Minecraft code. The bot's tests (`tools/test_plugin.py`) run exactly this code.
- `NexoraConnect.java`: the Minecraft part (listeners and sending chat).

## Updating from an older version (your repository on GitHub)

1. Unzip the new plugin source on your PC.
2. **Website way (no Git needed):** open your repository on GitHub -> **Add file -> Upload files** -> drag in everything from inside the unzipped folder (the `src` folder, `build.gradle`, `settings.gradle`, `README.md` and the `.github` folder) -> **Commit changes**. Files with the same name are replaced, the new `LinkRequests.java` is added.
   **Git way:** copy the unzipped files over your local repository folder (keep its `.git` folder), then `git add -A`, `git commit -m "NexoraConnect 1.2.0"`, `git push`.
3. Open the **Actions** tab. A run called "Build plugin" starts by itself (or press **Run workflow**). When it is green, open it and download **NexoraConnect** at the bottom (a zip with `NexoraConnect.jar` inside).
4. On the Minecraft server: stop it, delete the old `plugins/NexoraConnect.jar`, put the new `NexoraConnect.jar` in `plugins/`, start it. Keep `plugins/NexoraConnect/config.yml` (it holds your key). Version 1.2.0 works with the old config; to use teams add the new line `team-placeholder: "%your_team_placeholder%"` (see config.yml in this folder).
5. Check: the Minecraft page of the dashboard shows the plugin connected, and typing `/mc link` in the game answers "Asking the Nexora bot for your link code...".
6. Optional: copy the new jar next to this README in the bot's `minecraft-plugin/` folder so the dashboard offers it for download.

