package com.hungergames;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;

import org.bukkit.block.Chest;
import org.bukkit.block.DoubleChest;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

/** All match mutations run on Bukkit's main thread. */
public final class GameManager {
    private static final String PREFIX = ChatColor.GOLD + "[HG] " + ChatColor.YELLOW;
    private final HungerGamesPlugin plugin;
    private final Arena arena;
    private final LootManager loot;
    private final Map<UUID, Participant> players = new LinkedHashMap<UUID, Participant>();
    private final Map<UUID, PlayerSnapshot> recovery = new HashMap<UUID, PlayerSnapshot>();
    private final Set<UUID> recoveryBlocked = new HashSet<UUID>();
    private final Set<UUID> internalTeleports = new HashSet<UUID>();
    private final Map<String, ChestBackup> chests = new HashMap<String, ChestBackup>();
    private final Set<Entity> temporaryEntities = new HashSet<Entity>();
    private GamePhase phase = GamePhase.WAITING;
    private int remaining;
    private int runningSeconds;
    private boolean refilled;
    private boolean shuttingDown;
    private final BukkitTask ticker;

    public GameManager(HungerGamesPlugin plugin, Arena arena, LootManager loot) {
        this.plugin = plugin;
        this.arena = arena;
        this.loot = loot;
        ticker = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            @Override
            public void run() {
                try {
                    tick();
                } catch (RuntimeException ex) {
                    plugin.getLogger().log(Level.SEVERE, "Match failed; restoring players", ex);
                    stop();
                }
            }
        }, 20L, 20L);
    }

    public GamePhase getPhase() { return phase; }
    public int getPlayerCount() { return players.size(); }
    public int getSecondsRemaining() { return remaining; }
    public int getMinimumPlayers() { return Math.max(2, setting("min-players", 2)); }
    public int getCapacity() {
        return Math.min(Math.max(getMinimumPlayers(), setting("max-players", 24)), arena.getSpawns().size());
    }
    public boolean isIdle() { return phase == GamePhase.WAITING && players.isEmpty(); }
    public boolean isParticipant(Player player) { return players.containsKey(player.getUniqueId()); }
    public Object getParticipationToken(Player player) { return players.get(player.getUniqueId()); }
    public boolean isRecovering(Player player) {
        UUID id = player.getUniqueId();
        return recovery.containsKey(id) || recoveryBlocked.contains(id);
    }
    public boolean isManaged(Player player) { return isParticipant(player) || isRecovering(player); }
    public boolean isInternalTeleport(Player player) { return internalTeleports.contains(player.getUniqueId()); }
    public boolean isAlive(Player player) {
        Participant entry = players.get(player.getUniqueId());
        return entry != null && !entry.eliminated;
    }
    public boolean canPlay(Player player) { return isAlive(player) && phase.isPlaying(); }
    public boolean isArenaProtected(Location location) {
        return (!players.isEmpty() || phase != GamePhase.WAITING) && arena.contains(location);
    }
    public int getAliveCount() {
        int count = 0;
        for (Participant entry : players.values()) {
            if (!entry.eliminated) count++;
        }
        return count;
    }

    public boolean join(Player player) {
        if (isParticipant(player)) {
            tell(player, "You are already in the game.");
            return false;
        }
        recover(player);
        if (isRecovering(player)) {
            tell(player, "Your previous inventory must be recovered first. Ask an admin, then retry /hg leave.");
            return false;
        }
        if (!phase.allowsJoining()) {
            tell(player, "A match is already in progress.");
            return false;
        }
        String error = arena.getValidationError(getMinimumPlayers());
        if (error != null) {
            tell(player, "Arena not ready: " + error);
            return false;
        }
        if (players.size() >= getCapacity()) {
            tell(player, "The arena is full.");
            return false;
        }
        if (player.isDead() || player.isInsideVehicle()) {
            tell(player, "Respawn and leave your vehicle before joining.");
            return false;
        }
        player.closeInventory();
        PlayerSnapshot snapshot = PlayerSnapshot.capture(player);
        if (!snapshot.save(plugin, player.getUniqueId())) {
            tell(player, "Could not save your inventory safely. Joining was cancelled; contact an admin.");
            return false;
        }
        Participant entry = new Participant(snapshot, player.getName());
        players.put(player.getUniqueId(), entry);
        try {
            snapshot.prepareForMatch(player);
            if (!teleport(player, arena.getLobby())) {
                throw new IllegalStateException("Lobby teleport was cancelled");
            }
        } catch (RuntimeException ex) {
            plugin.getLogger().log(Level.WARNING, "Could not join " + player.getName(), ex);
            leave(player);
            return false;
        }
        broadcast(player.getName() + " joined (" + players.size() + "/" + getCapacity() + ").");
        if (phase == GamePhase.WAITING && players.size() >= getMinimumPlayers()) {
            phase = GamePhase.COUNTDOWN;
            remaining = seconds("countdown-seconds", 30);
            broadcast("The match starts in " + remaining + " seconds!");
        }
        return true;
    }

    public void leave(Player player) {
        Participant entry = players.remove(player.getUniqueId());
        if (entry == null) {
            recover(player);
            if (!isRecovering(player)) tell(player, "You are not in a match. Any saved state has been restored.");
            return;
        }
        broadcast(entry.name + " left the game.");
        recovery.put(player.getUniqueId(), entry.snapshot);
        restore(player);
        checkPopulation();
    }

    /** Do not restore into a disconnecting player: recover on their next login. */
    public void disconnect(Player player) {
        Participant entry = players.remove(player.getUniqueId());
        recovery.remove(player.getUniqueId());
        recoveryBlocked.remove(player.getUniqueId());
        if (entry != null) {
            broadcast(entry.name + " disconnected and was eliminated.");
            checkPopulation();
        }
    }

    public void recover(Player player) {
        if (isParticipant(player)) return;
        UUID id = player.getUniqueId();
        if (!recovery.containsKey(id)) {
            try {
                PlayerSnapshot snapshot = PlayerSnapshot.load(plugin, id);
                recoveryBlocked.remove(id);
                if (snapshot == null) return;
                recovery.put(id, snapshot);
            } catch (RuntimeException ex) {
                recoveryBlocked.add(id);
                plugin.getLogger().log(Level.SEVERE, "Cannot read recovery for " + player.getName(), ex);
                tell(player, "Your HG recovery file needs admin attention. Your interactions are locked to protect your items.");
                return;
            }
        }
        restore(player);
    }

    private void restore(Player player) {
        UUID id = player.getUniqueId();
        PlayerSnapshot snapshot = recovery.get(id);
        if (snapshot == null || !player.isOnline() || player.isDead()) return;
        internalTeleports.add(id);
        try {
            player.closeInventory();
            snapshot.restore(player);
            // Persist restored vanilla state before removing the independent recovery record.
            player.saveData();
            if (!PlayerSnapshot.delete(plugin, id)) {
                tell(player, "State restored, but recovery cleanup failed. Contact an admin; /hg leave retries.");
                return;
            }
            recovery.remove(id);
            recoveryBlocked.remove(id);
            tell(player, "Your original inventory and location have been restored.");
        } catch (RuntimeException ex) {
            plugin.getLogger().log(Level.SEVERE, "Cannot restore " + player.getName() + "; recovery file retained", ex);
            tell(player, "Could not restore your saved state. Ask an admin to check the log, then retry /hg leave.");
        } finally {
            internalTeleports.remove(id);
        }
    }

    public boolean forceStart(CommandSender sender) {
        if (!phase.allowsJoining()) {
            tell(sender, "A match is already in progress.");
            return false;
        }
        String error = arena.getValidationError(getMinimumPlayers());
        if (error != null || getAliveCount() < getMinimumPlayers()) {
            tell(sender, error == null ? "At least " + getMinimumPlayers() + " players must join." : error);
            return false;
        }
        try {
            startMatch();
            return phase.isPlaying();
        } catch (RuntimeException ex) {
            stop();
            throw ex;
        }
    }

    private void startMatch() {
        String error = arena.getValidationError(getMinimumPlayers());
        if (error != null || getAliveCount() < getMinimumPlayers()) {
            broadcast("Start cancelled: " + (error == null ? "not enough players." : error));
            phase = GamePhase.WAITING;
            remaining = 0;
            return;
        }
        List<Location> spawns = arena.getSpawns();
        Collections.shuffle(spawns);
        phase = GamePhase.GRACE;
        remaining = Math.max(0, setting("grace-seconds", 30));
        runningSeconds = 0;
        refilled = false;
        int index = 0;
        for (Map.Entry<UUID, Participant> item : players.entrySet()) {
            Player player = Bukkit.getPlayer(item.getKey());
            if (player == null || !player.isOnline() || player.isDead()) {
                stop();
                return;
            }
            player.closeInventory();
            item.getValue().snapshot.prepareForMatch(player);
            if (index >= spawns.size() || !teleport(player, spawns.get(index++))) {
                broadcast("A spawn teleport failed. The match has been cancelled.");
                stop();
                return;
            }
        }
        broadcast("The Hunger Games have begun! Loot chests and survive.");
        if (remaining == 0) beginRunning();
        else broadcast("Grace period: " + remaining + " seconds. All damage is disabled.");
    }

    private void beginRunning() {
        phase = GamePhase.RUNNING;
        remaining = seconds("deathmatch-after-seconds", 600);
        broadcast("Grace is over. PvP is now enabled!");
    }

    private void beginDeathmatch() {
        phase = GamePhase.DEATHMATCH;
        remaining = seconds("deathmatch-seconds", 180);
        broadcast("DEATHMATCH! The arena's safe circle is shrinking. Move toward the center!");
        Location center = arena.getCenter();
        if (center != null) broadcast("Center: X " + center.getBlockX() + ", Z " + center.getBlockZ() + ".");
    }

    private void tick() {
        if (shuttingDown) return;
        if (phase == GamePhase.WAITING) return;
        if (phase == GamePhase.ENDING) {
            if (--remaining <= 0) reset();
            return;
        }
        if (phase == GamePhase.COUNTDOWN) {
            if (getAliveCount() < getMinimumPlayers()) {
                checkPopulation();
                return;
            }
            if (--remaining <= 0) startMatch();
            else if (remaining <= 5 || remaining % 10 == 0) broadcast("Starting in " + remaining + "...");
            return;
        }
        if (phase == GamePhase.GRACE) {
            if (--remaining <= 0) beginRunning();
            else if (remaining <= 5 || remaining % 10 == 0) broadcast("Grace ends in " + remaining + "...");
            return;
        }
        runningSeconds++;
        int refillAt = setting("chest-refill-seconds", 300);
        if (!refilled && refillAt > 0 && runningSeconds >= refillAt) {
            for (ChestBackup chest : chests.values()) loot.fill(chest.getInventory());
            refilled = true;
            broadcast("All opened chests have been refilled!");
        }
        if (phase == GamePhase.RUNNING) {
            int threshold = Math.max(0, setting("deathmatch-players", 3));
            if (--remaining <= 0 || (threshold > 0 && getAliveCount() <= threshold)) beginDeathmatch();
            else if (remaining % 60 == 0) broadcast(remaining + " seconds until deathmatch. " + getAliveCount() + " alive.");
        } else if (phase == GamePhase.DEATHMATCH) {
            remaining--;
            double radius = getSafeRadius();
            Location center = arena.getCenter();
            if (center == null) {
                stop();
                return;
            }
            for (UUID id : new ArrayList<UUID>(players.keySet())) {
                Player player = Bukkit.getPlayer(id);
                if (player == null || !isAlive(player)) continue;
                Location at = player.getLocation();
                if (!at.getWorld().equals(center.getWorld())
                        || Math.hypot(at.getX() - center.getX(), at.getZ() - center.getZ()) > radius) {
                    player.damage(borderDamage());
                    tell(player, "Outside the safe circle! Radius: " + (int) radius + " blocks.");
                }
            }
            // Damage can synchronously eliminate the last player and change phase.
            if (phase == GamePhase.DEATHMATCH && remaining <= 0) finish(null);
            else if (phase == GamePhase.DEATHMATCH && (remaining % 30 == 0 || remaining <= 5)) {
                broadcast("Deathmatch: " + remaining + " seconds left, safe radius " + (int) radius + ".");
            }
        }
    }

    public double getSafeRadius() {
        if (phase != GamePhase.DEATHMATCH) return arena.getRadius();
        double end = plugin.getConfig().getDouble("game.deathmatch-radius", 10.0);
        if (!Double.isFinite(end) || end < 1) end = 10.0;
        end = Math.min(arena.getRadius(), end);
        double fraction = Math.max(0, remaining) / (double) seconds("deathmatch-seconds", 180);
        return end + (arena.getRadius() - end) * Math.min(1.0, fraction);
    }

    private double borderDamage() {
        double damage = plugin.getConfig().getDouble("game.border-damage", 4.0);
        return Double.isFinite(damage) && damage > 0 ? Math.min(damage, 100.0) : 4.0;
    }

    public void eliminate(Player player, Player killer) {
        Participant entry = players.get(player.getUniqueId());
        if (entry == null || entry.eliminated) return;
        entry.eliminated = true;
        if (killer != null && !killer.equals(player) && isAlive(killer)) {
            players.get(killer.getUniqueId()).kills++;
            broadcast(entry.name + " was eliminated by " + killer.getName() + ". " + getAliveCount() + " remain.");
        } else broadcast(entry.name + " was eliminated. " + getAliveCount() + " remain.");
        tell(player, "You are out! Click Respawn to spectate, or use /hg leave after respawning.");
        checkPopulation();
    }

    public Location respawnLocation(Player player) {
        if (isParticipant(player)) return arena.getLobby();
        PlayerSnapshot snapshot = recovery.get(player.getUniqueId());
        return snapshot == null ? null : snapshot.getReturnLocation();
    }

    public void afterRespawn(Player player, Object token) {
        if (!player.isOnline()) return;
        if (isParticipant(player)) {
            // A delayed callback must never touch a later queue/match entry.
            if (getParticipationToken(player) != token || isAlive(player)) return;
            player.getInventory().clear();
            player.getInventory().setArmorContents(new ItemStack[4]);
            player.setGameMode(GameMode.SPECTATOR);
            player.setFireTicks(0);
            if (arena.getCenter() != null) teleport(player, arena.getCenter());
            tell(player, "You are spectating. Use /hg leave to return.");
        } else recover(player);
    }

    private void checkPopulation() {
        if (phase == GamePhase.COUNTDOWN && getAliveCount() < getMinimumPlayers()) {
            phase = GamePhase.WAITING;
            remaining = 0;
            broadcast("Countdown cancelled: not enough players.");
        }
        if (phase.isPlaying() && getAliveCount() <= 1) {
            Participant winner = null;
            for (Participant entry : players.values()) if (!entry.eliminated) winner = entry;
            finish(winner);
        }
        if (players.isEmpty() && phase != GamePhase.WAITING) reset();
    }

    private void finish(Participant winner) {
        phase = GamePhase.ENDING;
        remaining = seconds("ending-seconds", 10);
        broadcast(winner == null ? "The match ended in a draw!" : winner.name + " wins the Hunger Games with " + winner.kills + " kill(s)!");
        broadcast("Returning players in " + remaining + " seconds.");
        for (UUID id : players.keySet()) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) {
                player.closeInventory();
                player.setFireTicks(0);
            }
        }
    }

    public void stop() {
        if (!isIdle()) broadcast("The match was stopped.");
        reset();
    }

    private void reset() {
        phase = GamePhase.ENDING;
        Map<UUID, Participant> previous = new LinkedHashMap<UUID, Participant>(players);
        players.clear();
        for (Map.Entry<UUID, Participant> item : previous.entrySet()) {
            UUID id = item.getKey();
            Player player = Bukkit.getPlayer(id);
            if (player != null && player.isOnline()) {
                recovery.put(id, item.getValue().snapshot);
                restore(player);
            }
        }
        for (ChestBackup chest : chests.values()) {
            try {
                chest.getInventory().setContents(cloneItems(chest.original));
            } catch (RuntimeException ex) {
                plugin.getLogger().log(Level.WARNING, "Could not restore an arena chest", ex);
            }
        }
        chests.clear();
        for (Entity entity : temporaryEntities) if (entity.isValid()) entity.remove();
        temporaryEntities.clear();
        remaining = 0;
        runningSeconds = 0;
        refilled = false;
        phase = GamePhase.WAITING;
    }

    public void shutdown() {
        shuttingDown = true;
        ticker.cancel();
        reset();
    }

    public boolean teleport(Player player, Location location) {
        if (location == null) return false;
        internalTeleports.add(player.getUniqueId());
        try {
            return player.teleport(location);
        } finally {
            internalTeleports.remove(player.getUniqueId());
        }
    }

    /** Only ordinary chests entirely inside the arena may be used for loot. */
    public boolean openChest(Player player, Inventory inventory) {
        if (!canPlay(player)) return false;
        List<Chest> halves = chestHalves(inventory.getHolder());
        if (halves.isEmpty()) return false;
        List<String> keys = new ArrayList<String>();
        for (Chest chest : halves) {
            if (!arena.contains(chest.getLocation())) return false;
            keys.add(blockKey(chest.getLocation()));
        }
        Collections.sort(keys);
        String key = keys.toString();
        if (!chests.containsKey(key)) {
            chests.put(key, new ChestBackup(halves.get(0).getLocation(), cloneItems(inventory.getContents())));
            loot.fill(inventory);
        }
        return true;
    }

    public boolean isArenaInventory(Inventory inventory) {
        for (Chest chest : chestHalves(inventory.getHolder())) {
            if (isArenaProtected(chest.getLocation())) return true;
        }
        InventoryHolder holder = inventory.getHolder();
        return holder instanceof org.bukkit.block.BlockState
                && isArenaProtected(((org.bukkit.block.BlockState) holder).getLocation());
    }

    private List<Chest> chestHalves(InventoryHolder holder) {
        List<Chest> result = new ArrayList<Chest>();
        if (holder instanceof Chest) result.add((Chest) holder);
        else if (holder instanceof DoubleChest) {
            DoubleChest doubleChest = (DoubleChest) holder;
            if (doubleChest.getLeftSide() instanceof Chest && doubleChest.getRightSide() instanceof Chest) {
                result.add((Chest) doubleChest.getLeftSide());
                result.add((Chest) doubleChest.getRightSide());
            }
        }
        return result;
    }

    private String blockKey(Location location) {
        return location.getWorld().getUID() + ":" + location.getBlockX() + ":" + location.getBlockY() + ":" + location.getBlockZ();
    }

    private ItemStack[] cloneItems(ItemStack[] items) {
        ItemStack[] result = new ItemStack[items.length];
        for (int i = 0; i < items.length; i++) result[i] = items[i] == null ? null : items[i].clone();
        return result;
    }

    public void trackEntity(Entity entity) { temporaryEntities.add(entity); }
    public boolean isMatchEntity(Entity entity) { return temporaryEntities.contains(entity); }

    public void sendStatus(CommandSender sender) {
        tell(sender, "Phase: " + phase + " | Players: " + getPlayerCount() + "/" + getCapacity()
                + " | Alive: " + getAliveCount() + " | Timer: " + remaining + "s");
        String error = arena.getValidationError(getMinimumPlayers());
        if (error != null) tell(sender, "Setup needed: " + error);
        if (phase == GamePhase.DEATHMATCH) tell(sender, "Safe radius: " + (int) getSafeRadius() + " blocks.");
    }

    public void tell(CommandSender sender, String message) { sender.sendMessage(PREFIX + message); }

    private void broadcast(String message) {
        for (UUID id : players.keySet()) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) tell(player, message);
        }
        plugin.getLogger().info(message);
    }

    private int setting(String key, int fallback) { return plugin.getConfig().getInt("game." + key, fallback); }
    private int seconds(String key, int fallback) { return Math.max(1, setting(key, fallback)); }

    private static final class Participant {
        private final PlayerSnapshot snapshot;
        private final String name;
        private boolean eliminated;
        private int kills;
        private Participant(PlayerSnapshot snapshot, String name) {
            this.snapshot = snapshot;
            this.name = name;
        }
    }

    private static final class ChestBackup {
        private final Location location;
        private final ItemStack[] original;
        private ChestBackup(Location location, ItemStack[] original) {
            this.location = location.clone();
            this.original = original;
        }
        private Inventory getInventory() {
            // Resolve the live tile entity again if the chunk unloaded since opening.
            org.bukkit.block.BlockState state = location.getBlock().getState();
            if (!(state instanceof Chest)) throw new IllegalStateException("Loot chest was removed at " + location);
            Inventory inventory = ((Chest) state).getInventory();
            if (inventory.getSize() != original.length) throw new IllegalStateException("Loot chest size changed at " + location);
            return inventory;
        }
    }
}
