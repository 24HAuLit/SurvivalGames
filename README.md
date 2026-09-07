# HungerGames

Single-arena Hunger Games plugin targeting the **Spigot 1.8.8 API**, for a Spigot 1.8.8 server and Minecraft **1.8.9 clients**. Build with JDK 8, 17, or 21 and Maven; Java 8 is the intended runtime for this legacy server. The plugin descriptor intentionally has **no `api-version` field** (that field is for newer servers).

## Build and install

```sh
mvn clean verify
```

On NixOS, you can build without a global Java/Maven installation:

```sh
nix-shell -p jdk8 maven --run 'mvn -Dmaven.repo.local=.m2 clean verify'
```
 
The output is `target/hungergames-1.0.0.jar`. Maven needs access to Maven Central and the Spigot snapshot repository on the first build. Stop the server, copy the JAR into its `plugins/` directory, and start it to generate plugin data. Configure and test on a backup/test server before opening it to players. Use `/hg reload` for plugin settings, not Bukkit's global `/reload`; replace plugin JARs only with the server stopped.

## Build and configure a map

There is **no world generation or full map reset**. Build or import your map manually. A **dedicated arena world** is recommended to avoid interfering with ordinary survival play; provide and load that world yourself. Back up the world and plugin data before setup and upgrades.

With no participants and the game in `WAITING`:

1. Build a safe arena, loot chests, and at least **two distinct individual spawn pads**. Provide one pad for each intended player slot. All spawns must be in the center's world and within `arena.radius` horizontally. Ensure solid footing, headroom, and safe separation yourself; location validation is not a terrain-safety check.
2. Stand at a **safe, accessible arena center** and run `/hg setcenter`. Keep the central area suitable for the final, shrinking deathmatch circle.
3. Stand at a safe lobby location and run `/hg setlobby`. Prefer a lobby outside the combat area; it may be in another loaded world.
4. Stand on each individual pad, face the intended direction, and run `/hg addspawn` once per pad. To rebuild the spawn list, use `/hg clearspawns` and record every pad again. Clearing spawns changes saved setup, not world blocks.
5. Check `/hg status`. Use two or more players to join and test. Match capacity is constrained by configured maximum players and available spawn pads; supply enough pads for your intended capacity and configured minimum.

Locations are saved in `plugins/HungerGames/arenas.yml`; settings and loot are in `plugins/HungerGames/config.yml`. Setup commands capture your current location and take no coordinates or other arguments. Setup changes and settings reload require `GameManager.isIdle()`: **`WAITING` with no participants**, not merely a game that has not started combat. Use `/hg stop` and resolve pending player recovery before editing an occupied arena. If saving setup fails, inspect the server log and correct the storage problem; do not assume an unsuccessful command persisted its changes.

## Commands and permissions

`/hg` (no arguments) shows help. `/hungergames` and `/sg` are aliases. Subcommands are case-insensitive, take no additional arguments, and have permission-aware help and tab completion.

| Command | Access | Purpose |
| --- | --- | --- |
| `/hg help` | Anyone, including console | Show available commands |
| `/hg join` | Player with `hungergames.play` | Join the waiting game |
| `/hg leave` | Any player, even without play permission | Leave or retry stalled inventory/state recovery |
| `/hg status` | Anyone, including console | Show game status |
| `/hg setlobby` | Admin player; idle only | Save current location as lobby |
| `/hg setcenter` | Admin player; idle only | Save current location as arena center |
| `/hg addspawn` | Admin player; idle only | Add current location as a spawn |
| `/hg clearspawns` | Admin; idle only | Clear saved spawn locations |
| `/hg start` | Admin | Request immediate start; does not bypass player/arena validation |
| `/hg stop` | Admin | Stop the game and restore players |
| `/hg reload` | Admin; idle only | Reload plugin settings |

- `hungergames.play` defaults to **true**; it gates joining, never leaving/recovery.
- `hungergames.admin` defaults to **op**.
- Console can use help/status and, with admin permission, start/stop/reload/clearspawns. Join, leave, and commands that capture a location require a player.
- `/hg start` delegates to the game manager and still requires **at least two players and a valid arena**. It is not a way to run a one-player game or bypass setup errors.
- Failed setup/reload operations report an error to the sender; exception details are logged on the server. A failed reload is not reported as successful.

## Match behavior and recovery

The lifecycle is `WAITING → COUNTDOWN → GRACE → RUNNING → DEATHMATCH → ENDING`, then back to waiting. Grace precedes normal combat; deathmatch can begin by timer or survivor threshold and shrinks the horizontal arena circle.

- Players wait within 16 horizontal blocks and 8 vertical blocks of the lobby. During matches, only `/hg` commands are allowed; use `/hg leave` before using other plugin commands.
- During an occupied match, block changes, fire, explosions, and container access are restricted in the arena circle, except for match loot chests and crafting tables. This protection is not a general-purpose world rollback or a substitute for backups.
- Arena loot chests receive **random loot on first opening**, with **one timed refill** of opened chests. Unopened chests receive fresh loot on first use. Original chest inventories are restored at normal match end; **there is no chest crash-recovery guarantee**. Do not put valuable storage in arena loot chests.
- Dead players become spectators. **Click Respawn after death** so spectator handling and eventual restoration can complete. Disconnecting eliminates a participant; reconnecting does not re-enter them as an alive contestant.
- Original pre-join player state is restored after leaving or match end. Persistent **player-data files** support pending restoration on reconnect or server restart. **Do not delete these files**: they are recovery records, not disposable match cache. Keep backups of plugin data and investigate logged recovery failures.
- If inventory/state recovery stalls, respawn first if dead and retry **`/hg leave`**. This command remains available even if play permission has been removed. Do not repeatedly rejoin or delete recovery data to work around a failed restore.
- At deathmatch timeout, surviving players draw; last-survivor wins occur before that deadline. The safe circle is a plugin-enforced horizontal radius, not a visible vanilla world border. `/hg status` reports its current size.
- Ordinary match item drops are cleaned up at the end. There is no full map reset, and player recovery does not imply chest or world crash recovery.

## Configuration reference

Defaults are in [`src/main/resources/config.yml`](src/main/resources/config.yml). Edit the generated server config, then run `/hg reload` while idle. Durations are in seconds; radii are in blocks.

| Setting | Default | Meaning |
| --- | --- | --- |
| `arena.radius` | `150.0` | Horizontal X/Z arena circle around the saved center |
| `game.min-players` | `2` | Minimum players for automatic countdown; matches need at least two |
| `game.max-players` | `24` | Player limit, also constrained by available spawn pads |
| `game.countdown-seconds` | `30` | Pre-match countdown |
| `game.grace-seconds` | `30` | Grace duration |
| `game.deathmatch-after-seconds` | `600` | Running time before deathmatch, excluding grace |
| `game.deathmatch-players` | `3` | Early deathmatch survivor threshold; `0` disables early entry |
| `game.deathmatch-seconds` | `180` | Deathmatch duration |
| `game.deathmatch-radius` | `10.0` | Final radius of the shrinking deathmatch circle |
| `game.border-damage` | `4.0` | Half-hearts of damage per second outside the shrinking circle |
| `game.ending-seconds` | `10` | Ending phase duration |
| `game.chest-refill-seconds` | `300` | One refill after this much running time; `0` disables it |
| `loot.min-items` / `loot.max-items` | `3` / `6` | Range of loot stacks placed into chest slots |

`loot.entries` is a weighted list using **Bukkit 1.8 material names**. Each entry has `material`, stack-size `min`/`max`, and relative `weight`; optional `data` supplies legacy durability/data. For example:

```yaml
- {material: ARROW, min: 3, max: 8, weight: 10}
```

Higher weight makes an entry more likely; it is not a percentage. Defaults include weapons, armor, food, arrows, and fishing rods. Use positive, sensible limits/radii and safe spawn locations; read server warnings after changing configuration.

## Manual smoke checklist

Automated tests cover arena persistence/validation, loot bounds, match phases, recovery ordering, player snapshots, and event isolation. Mocked Bukkit tests do not exercise a running server. A Maven build does **not** establish live-server correctness. Run these checks on a disposable Spigot 1.8.8 server with Minecraft 1.8.9 clients; they are a checklist, not a claim of completed runtime testing.

- [ ] Build with `mvn clean verify`, install the JAR, and check startup logs for errors.
- [ ] Check help, aliases, mixed-case subcommands, unknown commands, extra-argument rejection, and tab completion as a normal player, admin, and console. Deny play permission: joining must fail, but leave/recovery must remain available. Non-admin setup/start/stop/reload must fail.
- [ ] Check missing lobby/center, insufficient pads, wrong-world pads, and pads outside the radius. Starting with zero or one player must fail even via `/hg start`.
- [ ] Configure two safe pads, join with two players, and verify countdown, forced start, grace, combat, status, deathmatch, and normal ending. With default survivor threshold `3`, a two-player test may enter deathmatch early; set the threshold to `0` while idle to test the full running/refill timers.
- [ ] Try every setup command and reload during occupied `WAITING`, countdown, and combat: all must be refused. Verify they work again once fully idle. In a controlled test, cause a setup save/reload error and verify sender feedback plus logged details, with no success message.
- [ ] Test first-open randomized loot, a second opening without reroll, the single timed refill, and original chest inventory restoration at normal end. Temporarily shorten timers while idle if needed; retain a backup of test chest contents.
- [ ] Test block placement/breaking, fire, explosions, and non-loot containers inside the occupied arena circle. Verify ordinary match drops are removed at end.
- [ ] Record pre-join inventory, armor, location, and player state. Test leaving, winning, dying then clicking Respawn, and admin stop; confirm state restoration and no duplicated/lost items.
- [ ] Test elimination on disconnect, restoration on reconnect and restart, and `/hg leave` retry after a stalled restore. Keep player-data files intact. Use disposable inventories for recovery tests; do not infer chest crash recovery from successful player restoration.
