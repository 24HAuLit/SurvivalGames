package com.hungergames;

import java.io.IOException;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

/**
 * Recovery state for one player on Spigot 1.8.8 / Java 8.
 *
 * <p>Use on the server thread. Close inventory interfaces before capture so
 * crafting/container inputs have been returned to the player's inventory.
 * Persist successfully BEFORE preparing a match. After a successful restore,
 * the caller should delete the recovery file; neither prepare nor restore
 * deletes it. On quit/restart, wait until the player is online and alive.
 *
 * <p>Inventory restoration replaces slots rather than adding items. It cannot
 * undo transfers/drops into the world or other inventories, so the caller must
 * prevent access to restored items until its recovery workflow is complete.
 * Bukkit state changes and file deletion are not a single transaction.
 */
public final class PlayerSnapshot {
    private static final int FORMAT_VERSION = 1;
    private static final int INVENTORY_SIZE = 36;
    private static final int ARMOR_SIZE = 4;

    // Assigned only during construction; mutable Bukkit values never escape.
    private UUID playerId;
    private UUID worldId;
    private String worldName;
    private double x;
    private double y;
    private double z;
    private float yaw;
    private float pitch;
    private GameMode gameMode;
    private ItemStack[] inventory;
    private ItemStack[] armor;
    private ItemStack cursor;
    private int heldSlot;
    private double maxHealth;
    private double health;
    private int food;
    private float saturation;
    private float exhaustion;
    private int totalExperience;
    private int level;
    private float experience;
    private boolean allowFlight;
    private boolean flying;
    private int fireTicks;
    private int maximumAir;
    private int remainingAir;
    private float fallDistance;
    private Vector velocity;
    private List<PotionEffect> effects;

    private PlayerSnapshot() {
    }

    public static PlayerSnapshot capture(Player player) {
        requireAlive(player);
        Location location = player.getLocation();
        if (location.getWorld() == null) {
            throw new IllegalStateException("Cannot capture a player without a world");
        }
        PlayerSnapshot snapshot = new PlayerSnapshot();
        snapshot.playerId = player.getUniqueId();
        snapshot.worldId = location.getWorld().getUID();
        snapshot.worldName = location.getWorld().getName();
        snapshot.x = location.getX();
        snapshot.y = location.getY();
        snapshot.z = location.getZ();
        snapshot.yaw = location.getYaw();
        snapshot.pitch = location.getPitch();
        snapshot.gameMode = player.getGameMode();
        snapshot.inventory = cloneItems(player.getInventory().getContents());
        snapshot.armor = cloneItems(player.getInventory().getArmorContents());
        snapshot.cursor = cloneItem(player.getItemOnCursor());
        snapshot.heldSlot = player.getInventory().getHeldItemSlot();
        snapshot.maxHealth = player.getMaxHealth();
        snapshot.health = player.getHealth();
        snapshot.food = player.getFoodLevel();
        snapshot.saturation = player.getSaturation();
        snapshot.exhaustion = player.getExhaustion();
        snapshot.totalExperience = player.getTotalExperience();
        snapshot.level = player.getLevel();
        snapshot.experience = player.getExp();
        snapshot.allowFlight = player.getAllowFlight();
        snapshot.flying = player.isFlying();
        snapshot.fireTicks = player.getFireTicks();
        snapshot.maximumAir = player.getMaximumAir();
        snapshot.remainingAir = player.getRemainingAir();
        snapshot.fallDistance = player.getFallDistance();
        snapshot.velocity = player.getVelocity().clone();
        snapshot.effects = new ArrayList<PotionEffect>();
        for (PotionEffect effect : player.getActivePotionEffects()) {
            snapshot.effects.add(copyEffect(effect));
        }
        snapshot.validate();
        return snapshot;
    }

    /** Resets match state without resetting the player's configured max health. */
    public void prepareForMatch(Player player) {
        requireOwner(player);
        requireAlive(player);
        player.getInventory().setContents(new ItemStack[INVENTORY_SIZE]);
        player.getInventory().setArmorContents(new ItemStack[ARMOR_SIZE]);
        player.setItemOnCursor(null);
        player.getInventory().setHeldItemSlot(0);
        clearEffects(player);
        player.setFlying(false);
        player.setGameMode(GameMode.SURVIVAL);
        player.setAllowFlight(false);
        // Removing HEALTH_BOOST may itself alter effective maximum health.
        player.setHealth(player.getMaxHealth());
        player.setFoodLevel(20);
        player.setSaturation(5.0F);
        player.setExhaustion(0.0F);
        player.setLevel(0);
        player.setExp(0.0F);
        player.setTotalExperience(0);
        player.setFireTicks(0);
        player.setRemainingAir(player.getMaximumAir());
        player.setFallDistance(0.0F);
        player.setVelocity(new Vector(0, 0, 0));
        player.updateInventory();
    }

    /**
     * Restores the original state, or throws so the caller can retain recovery.
     * A dead player or unavailable world is rejected before any player mutation.
     * Other plugins may cancel teleportation; this is also a visible failure.
     */
    public void restore(Player player) {
        requireOwner(player);
        requireAlive(player);
        Location destination = getReturnLocation();
        if (destination == null) {
            throw new IllegalStateException("Cannot restore " + playerId
                    + ": saved world '" + worldName + "' (" + worldId
                    + ") is not loaded; retain the recovery file and retry later");
        }
        if (!player.teleport(destination)) {
            throw new IllegalStateException("Cannot restore " + playerId
                    + ": teleport to the saved location was refused; retain the recovery file");
        }
        // A teleport listener may have killed the player.
        requireAlive(player);
        player.setGameMode(gameMode);
        clearEffects(player);
        for (PotionEffect effect : effects) {
            player.addPotionEffect(copyEffect(effect), true);
        }
        // In 1.8 getMaxHealth reports the effective value, but setMaxHealth
        // writes the base attribute. Do not count HEALTH_BOOST twice.
        if (Double.compare(player.getMaxHealth(), maxHealth) != 0) {
            double baseHealth = maxHealth;
            for (PotionEffect effect : effects) {
                if (PotionEffectType.HEALTH_BOOST.equals(effect.getType())) {
                    baseHealth -= 4.0D * (effect.getAmplifier() + 1.0D);
                }
            }
            if (baseHealth <= 0) {
                throw new IllegalStateException("Cannot restore maximum health for " + playerId
                        + ": incompatible health modifiers; retain the recovery file");
            }
            player.setMaxHealth(baseHealth);
        }
        if (Double.compare(player.getMaxHealth(), maxHealth) != 0) {
            throw new IllegalStateException("Cannot restore maximum health for " + playerId
                    + ": custom attribute modifiers are not supported; retain the recovery file");
        }
        player.setHealth(health);
        player.getInventory().setContents(cloneItems(inventory));
        player.getInventory().setArmorContents(cloneItems(armor));
        player.setItemOnCursor(cloneItem(cursor));
        player.getInventory().setHeldItemSlot(heldSlot);
        player.setFoodLevel(food);
        player.setSaturation(saturation);
        player.setExhaustion(exhaustion);
        player.setLevel(level);
        player.setExp(experience);
        // These are independent Bukkit fields: do not use giveExp().
        player.setTotalExperience(totalExperience);
        player.setAllowFlight(allowFlight);
        player.setFlying(flying);
        player.setFireTicks(fireTicks);
        player.setMaximumAir(maximumAir);
        player.setRemainingAir(remainingAir);
        player.setFallDistance(fallDistance);
        player.setVelocity(velocity.clone());
        player.updateInventory();
    }

    /** A fresh location, or null if the exact saved world UUID is not loaded. */
    public Location getReturnLocation() {
        World world = Bukkit.getWorld(worldId);
        return world == null ? null : new Location(world, x, y, z, yaw, pitch);
    }

    /**
     * Atomically replaces data-folder/player-data/UUID.yml using a same-directory
     * temporary file. No non-atomic fallback: an unsupported atomic move fails
     * without deliberately deleting/truncating the existing recovery file.
     */
    public boolean save(JavaPlugin plugin, UUID id) {
        Path temporary = null;
        try {
            if (!playerId.equals(id)) {
                throw new IllegalArgumentException("Snapshot owner does not match " + id);
            }
            Path target = snapshotPath(plugin, id);
            Files.createDirectories(target.getParent());
            byte[] bytes = toYaml().saveToString().getBytes(StandardCharsets.UTF_8);
            temporary = Files.createTempFile(target.getParent(), id + "-", ".tmp");
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException | RuntimeException exception) {
            plugin.getLogger().log(Level.SEVERE, "Failed to save player snapshot for " + id,
                    exception);
            return false;
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException | RuntimeException exception) {
                    plugin.getLogger().log(Level.WARNING,
                            "Failed to remove snapshot temporary file " + temporary, exception);
                }
            }
        }
    }

    /** Null only for an absent file; malformed/unreadable recovery blocks joining. */
    public static PlayerSnapshot load(JavaPlugin plugin, UUID id) {
        Path path = snapshotPath(plugin, id);
        try {
            if (Files.notExists(path)) {
                return null;
            }
            YamlConfiguration yaml = new YamlConfiguration();
            // loadConfiguration(File) logs parse failures and returns an empty
            // configuration on older Bukkit; use the throwing API instead.
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                yaml.load(reader);
            }
            if (integer(yaml.get("version"), "version") != FORMAT_VERSION) {
                throw new IllegalArgumentException("Unsupported snapshot version");
            }
            PlayerSnapshot snapshot = new PlayerSnapshot();
            snapshot.playerId = UUID.fromString(text(yaml.get("player"), "player"));
            if (!snapshot.playerId.equals(id)) {
                throw new IllegalArgumentException("Snapshot player UUID does not match filename");
            }
            snapshot.worldId = UUID.fromString(text(yaml.get("location.world-uuid"), "location.world-uuid"));
            snapshot.worldName = text(yaml.get("location.world-name"), "location.world-name");
            snapshot.x = number(yaml.get("location.x"), "location.x");
            snapshot.y = number(yaml.get("location.y"), "location.y");
            snapshot.z = number(yaml.get("location.z"), "location.z");
            snapshot.yaw = decimal(yaml.get("location.yaw"), "location.yaw");
            snapshot.pitch = decimal(yaml.get("location.pitch"), "location.pitch");
            snapshot.gameMode = GameMode.valueOf(text(yaml.get("game-mode"), "game-mode"));
            snapshot.inventory = items(yaml.get("inventory"), INVENTORY_SIZE, "inventory");
            snapshot.armor = items(yaml.get("armor"), ARMOR_SIZE, "armor");
            snapshot.cursor = items(yaml.get("cursor"), 1, "cursor")[0];
            snapshot.heldSlot = integer(yaml.get("held-slot"), "held-slot");
            snapshot.maxHealth = number(yaml.get("max-health"), "max-health");
            snapshot.health = number(yaml.get("health"), "health");
            snapshot.food = integer(yaml.get("food"), "food");
            snapshot.saturation = decimal(yaml.get("saturation"), "saturation");
            snapshot.exhaustion = decimal(yaml.get("exhaustion"), "exhaustion");
            snapshot.totalExperience = integer(yaml.get("xp.total"), "xp.total");
            snapshot.level = integer(yaml.get("xp.level"), "xp.level");
            snapshot.experience = decimal(yaml.get("xp.progress"), "xp.progress");
            snapshot.allowFlight = bool(yaml.get("flight.allowed"), "flight.allowed");
            snapshot.flying = bool(yaml.get("flight.flying"), "flight.flying");
            snapshot.fireTicks = integer(yaml.get("fire-ticks"), "fire-ticks");
            snapshot.maximumAir = integer(yaml.get("air.maximum"), "air.maximum");
            snapshot.remainingAir = integer(yaml.get("air.remaining"), "air.remaining");
            snapshot.fallDistance = decimal(yaml.get("fall-distance"), "fall-distance");
            snapshot.velocity = new Vector(number(yaml.get("velocity.x"), "velocity.x"),
                    number(yaml.get("velocity.y"), "velocity.y"),
                    number(yaml.get("velocity.z"), "velocity.z"));
            snapshot.effects = readEffects(yaml.get("potions"));
            snapshot.validate();
            return snapshot;
        } catch (IOException | InvalidConfigurationException | RuntimeException exception) {
            throw new IllegalStateException("Cannot load player recovery snapshot " + path
                    + "; retain the file and block joining until it is repaired", exception);
        }
    }

    /** Idempotent: true if removed or already absent; logs and returns false on failure. */
    public static boolean delete(JavaPlugin plugin, UUID id) {
        try {
            Files.deleteIfExists(snapshotPath(plugin, id));
            return true;
        } catch (IOException | RuntimeException exception) {
            plugin.getLogger().log(Level.SEVERE, "Failed to delete player snapshot for " + id,
                    exception);
            return false;
        }
    }

    private YamlConfiguration toYaml() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("version", FORMAT_VERSION);
        yaml.set("player", playerId.toString());
        yaml.set("location.world-uuid", worldId.toString());
        yaml.set("location.world-name", worldName);
        yaml.set("location.x", x);
        yaml.set("location.y", y);
        yaml.set("location.z", z);
        yaml.set("location.yaw", yaw);
        yaml.set("location.pitch", pitch);
        yaml.set("game-mode", gameMode.name());
        yaml.set("inventory", Arrays.asList(cloneItems(inventory)));
        yaml.set("armor", Arrays.asList(cloneItems(armor)));
        yaml.set("cursor", Arrays.asList(new ItemStack[] {cloneItem(cursor)}));
        yaml.set("held-slot", heldSlot);
        yaml.set("max-health", maxHealth);
        yaml.set("health", health);
        yaml.set("food", food);
        yaml.set("saturation", saturation);
        yaml.set("exhaustion", exhaustion);
        yaml.set("xp.total", totalExperience);
        yaml.set("xp.level", level);
        yaml.set("xp.progress", experience);
        yaml.set("flight.allowed", allowFlight);
        yaml.set("flight.flying", flying);
        yaml.set("fire-ticks", fireTicks);
        yaml.set("air.maximum", maximumAir);
        yaml.set("air.remaining", remainingAir);
        yaml.set("fall-distance", fallDistance);
        yaml.set("velocity.x", velocity.getX());
        yaml.set("velocity.y", velocity.getY());
        yaml.set("velocity.z", velocity.getZ());
        List<Map<String, Object>> potions = new ArrayList<Map<String, Object>>();
        for (PotionEffect effect : effects) {
            Map<String, Object> potion = new LinkedHashMap<String, Object>();
            potion.put("type", effect.getType().getName());
            potion.put("duration", effect.getDuration());
            potion.put("amplifier", effect.getAmplifier());
            potion.put("ambient", effect.isAmbient());
            potion.put("particles", effect.hasParticles());
            potions.add(potion);
        }
        yaml.set("potions", potions);
        return yaml;
    }

    private void validate() {
        number(x, "location.x");
        number(y, "location.y");
        number(z, "location.z");
        number(yaw, "location.yaw");
        number(pitch, "location.pitch");
        number(maxHealth, "max-health");
        number(health, "health");
        number(saturation, "saturation");
        number(exhaustion, "exhaustion");
        number(experience, "xp.progress");
        number(fallDistance, "fall-distance");
        number(velocity.getX(), "velocity.x");
        number(velocity.getY(), "velocity.y");
        number(velocity.getZ(), "velocity.z");
        if (inventory.length != INVENTORY_SIZE || armor.length != ARMOR_SIZE
                || heldSlot < 0 || heldSlot > 8) {
            throw new IllegalArgumentException("Invalid inventory dimensions or held slot");
        }
        if (maxHealth <= 0 || health <= 0 || health > maxHealth) {
            throw new IllegalArgumentException("Invalid health/max-health for a living player");
        }
        if (food < 0 || food > 20 || saturation < 0 || exhaustion < 0) {
            throw new IllegalArgumentException("Invalid food, saturation or exhaustion");
        }
        if (totalExperience < 0 || level < 0 || experience < 0 || experience > 1) {
            throw new IllegalArgumentException("Invalid experience state");
        }
        if (flying && !allowFlight) {
            throw new IllegalArgumentException("Flying requires flight permission");
        }
        if (maximumAir < 0) {
            throw new IllegalArgumentException("Invalid maximum air");
        }
        for (PotionEffect effect : effects) {
            if (effect.getDuration() < 0 || effect.getAmplifier() < 0) {
                throw new IllegalArgumentException("Invalid potion duration or amplifier");
            }
        }
    }

    private void requireOwner(Player player) {
        if (player == null || !playerId.equals(player.getUniqueId())) {
            throw new IllegalArgumentException("Snapshot belongs to player " + playerId);
        }
    }

    private static void requireAlive(Player player) {
        if (player == null) {
            throw new IllegalArgumentException("Player must not be null");
        }
        if (!player.isOnline()) {
            throw new IllegalStateException("Player " + player.getUniqueId() + " is offline; retry after joining");
        }
        if (player.isDead() || player.getHealth() <= 0) {
            throw new IllegalStateException("Player " + player.getUniqueId()
                    + " is dead; retain recovery and retry after respawn");
        }
    }

    private static Path snapshotPath(JavaPlugin plugin, UUID id) {
        if (plugin == null || id == null) {
            throw new IllegalArgumentException("Plugin and player UUID must not be null");
        }
        return plugin.getDataFolder().toPath().resolve("player-data").resolve(id.toString() + ".yml");
    }

    private static ItemStack cloneItem(ItemStack item) {
        return item == null ? null : item.clone();
    }

    private static ItemStack[] cloneItems(ItemStack[] items) {
        ItemStack[] copy = new ItemStack[items.length];
        for (int i = 0; i < items.length; i++) {
            copy[i] = cloneItem(items[i]);
        }
        return copy;
    }

    private static ItemStack[] items(Object value, int size, String key) {
        if (!(value instanceof List) || ((List<?>) value).size() != size) {
            throw new IllegalArgumentException(key + " must contain exactly " + size + " slots");
        }
        List<?> list = (List<?>) value;
        ItemStack[] result = new ItemStack[size];
        for (int i = 0; i < size; i++) {
            Object item = list.get(i);
            if (item != null && !(item instanceof ItemStack)) {
                throw new IllegalArgumentException("Invalid ItemStack at " + key + "[" + i + "]");
            }
            result[i] = cloneItem((ItemStack) item);
        }
        return result;
    }

    private static PotionEffect copyEffect(PotionEffect effect) {
        return new PotionEffect(effect.getType(), effect.getDuration(), effect.getAmplifier(),
                effect.isAmbient(), effect.hasParticles());
    }

    private static void clearEffects(Player player) {
        for (PotionEffect effect : new ArrayList<PotionEffect>(player.getActivePotionEffects())) {
            player.removePotionEffect(effect.getType());
        }
    }

    private static List<PotionEffect> readEffects(Object value) {
        if (!(value instanceof List)) {
            throw new IllegalArgumentException("potions must be a list");
        }
        List<PotionEffect> result = new ArrayList<PotionEffect>();
        Set<PotionEffectType> seen = new HashSet<PotionEffectType>();
        for (Object entry : (List<?>) value) {
            if (!(entry instanceof Map)) {
                throw new IllegalArgumentException("Each potion must be a map");
            }
            Map<?, ?> map = (Map<?, ?>) entry;
            String name = text(map.get("type"), "potion.type");
            PotionEffectType type = PotionEffectType.getByName(name);
            if (type == null || !seen.add(type)) {
                throw new IllegalArgumentException("Unknown or duplicate potion type: " + name);
            }
            result.add(new PotionEffect(type, integer(map.get("duration"), "potion.duration"),
                    integer(map.get("amplifier"), "potion.amplifier"),
                    bool(map.get("ambient"), "potion.ambient"),
                    bool(map.get("particles"), "potion.particles")));
        }
        return result;
    }

    private static String text(Object value, String key) {
        if (!(value instanceof String) || ((String) value).trim().isEmpty()) {
            throw new IllegalArgumentException("Missing or invalid string: " + key);
        }
        return (String) value;
    }

    private static boolean bool(Object value, String key) {
        if (!(value instanceof Boolean)) {
            throw new IllegalArgumentException("Missing or invalid boolean: " + key);
        }
        return (Boolean) value;
    }

    private static double number(Object value, String key) {
        if (!(value instanceof Number)) {
            throw new IllegalArgumentException("Missing or invalid number: " + key);
        }
        double result = ((Number) value).doubleValue();
        if (Double.isNaN(result) || Double.isInfinite(result)) {
            throw new IllegalArgumentException("Non-finite number: " + key);
        }
        return result;
    }

    private static float decimal(Object value, String key) {
        double result = number(value, key);
        if (result < -Float.MAX_VALUE || result > Float.MAX_VALUE) {
            throw new IllegalArgumentException("Float out of range: " + key);
        }
        return (float) result;
    }

    private static int integer(Object value, String key) {
        double result = number(value, key);
        if (result < Integer.MIN_VALUE || result > Integer.MAX_VALUE || result != Math.rint(result)) {
            throw new IllegalArgumentException("Invalid integer: " + key);
        }
        return (int) result;
    }
}
