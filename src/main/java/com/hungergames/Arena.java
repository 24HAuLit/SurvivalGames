package com.hungergames;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/** Persistent arena setup. Call from the server thread. */
public final class Arena {
    private final JavaPlugin plugin;
    private final File file;
    private Point lobby;
    private Point center;
    private final List<Point> spawns = new ArrayList<Point>();

    public Arena(JavaPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.file = new File(plugin.getDataFolder(), "arenas.yml");
        reload();
    }

    /** Reloads arenas.yml; the parent plugin owns reloading its main config. */
    public void reload() {
        YamlConfiguration yaml = new YamlConfiguration();
        if (file.exists()) {
            try {
                yaml.load(file);
            } catch (IOException | InvalidConfigurationException ex) {
                throw new IllegalStateException("Could not load arena setup from " + file, ex);
            }
        }
        Point newLobby = Point.read(yaml.get("lobby"));
        Point newCenter = Point.read(yaml.get("center"));
        List<Point> newSpawns = new ArrayList<Point>();
        Object rawSpawns = yaml.get("spawns");
        if (rawSpawns instanceof List<?>) {
            for (Object raw : (List<?>) rawSpawns) {
                newSpawns.add(Point.read(raw));
            }
        } else if (rawSpawns != null) {
            // Preserve an invalid entry so validation does not silently accept bad setup.
            newSpawns.add(null);
        }
        lobby = newLobby;
        center = newCenter;
        spawns.clear();
        spawns.addAll(newSpawns);
    }

    /** Returns a fresh location, or null if unset, invalid, or its world is unloaded. */
    public Location getLobby() {
        return resolve(lobby);
    }

    public void setLobby(Location location) {
        lobby = Point.fromLocation(location);
        save();
    }

    /** Returns a fresh location, or null if unset, invalid, or its world is unloaded. */
    public Location getCenter() {
        return resolve(center);
    }

    public void setCenter(Location location) {
        center = Point.fromLocation(location);
        save();
    }

    /** Returns an independent list of fresh locations; unresolved entries are omitted. */
    public List<Location> getSpawns() {
        List<Location> result = new ArrayList<Location>();
        for (Point spawn : spawns) {
            Location location = resolve(spawn);
            if (location != null) {
                result.add(location);
            }
        }
        return result;
    }

    public void addSpawn(Location location) {
        spawns.add(Point.fromLocation(location));
        save();
    }

    public void clearSpawns() {
        spawns.clear();
        save();
    }

    public boolean isReady(int minimumPlayers) {
        return getValidationError(minimumPlayers) == null;
    }

    public String getValidationError(int minimumPlayers) {
        if (getLobby() == null) {
            return "Lobby setup is missing or invalid, or its world is not loaded.";
        }
        Location arenaCenter = getCenter();
        if (arenaCenter == null) {
            return "Arena center setup is missing or invalid, or its world is not loaded.";
        }
        double radius = getRadius();
        if (!Double.isFinite(radius) || radius <= 0.0) {
            return "arena.radius must be a finite, positive number.";
        }
        int required = Math.max(0, minimumPlayers);
        if (spawns.size() < required) {
            return "Arena needs at least " + required + " spawns; only " + spawns.size() + " are configured.";
        }
        for (int i = 0; i < spawns.size(); i++) {
            Location spawn = resolve(spawns.get(i));
            if (spawn == null) {
                return "Spawn " + (i + 1) + " is invalid or its world is not loaded.";
            }
            if (!arenaCenter.getWorld().equals(spawn.getWorld())) {
                return "Spawn " + (i + 1) + " must be in the arena center's world.";
            }
            if (!inside(spawn, arenaCenter, radius)) {
                return "Spawn " + (i + 1) + " is outside the arena radius.";
            }
        }
        return null;
    }

    /** Reads the current in-memory plugin config; malformed values remain invalid. */
    public double getRadius() {
        Object value = plugin.getConfig().get("arena.radius");
        return value == null ? 150.0
                : value instanceof Number ? ((Number) value).doubleValue() : Double.NaN;
    }

    /** Horizontal (X/Z) circle, including the boundary; height is unrestricted. */
    public boolean contains(Location location) {
        Location arenaCenter = getCenter();
        double radius = getRadius();
        return validLocation(location) && arenaCenter != null
                && Double.isFinite(radius) && radius > 0.0
                && arenaCenter.getWorld().equals(location.getWorld())
                && inside(location, arenaCenter, radius);
    }

    private static boolean inside(Location location, Location center, double radius) {
        return Math.hypot(location.getX() - center.getX(), location.getZ() - center.getZ()) <= radius;
    }

    private static Location resolve(Point point) {
        return point == null ? null : point.toLocation();
    }

    private static boolean validLocation(Location location) {
        return location != null && location.getWorld() != null
                && Double.isFinite(location.getX()) && Double.isFinite(location.getY())
                && Double.isFinite(location.getZ()) && Float.isFinite(location.getYaw())
                && Float.isFinite(location.getPitch());
    }

    private void save() {
        File folder = plugin.getDataFolder();
        if (!folder.isDirectory() && !folder.mkdirs() && !folder.isDirectory()) {
            throw new IllegalStateException("Could not create arena data directory " + folder);
        }
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("lobby", lobby == null ? null : lobby.toMap());
        yaml.set("center", center == null ? null : center.toMap());
        List<Map<String, Object>> savedSpawns = new ArrayList<Map<String, Object>>();
        for (Point spawn : spawns) {
            savedSpawns.add(spawn == null ? null : spawn.toMap());
        }
        yaml.set("spawns", savedSpawns);
        try {
            yaml.save(file);
        } catch (IOException ex) {
            throw new IllegalStateException("Could not save arena setup to " + file, ex);
        }
    }

    // Keep world names rather than World references, including while worlds are unloaded.
    private static final class Point {
        private final String world;
        private final double x;
        private final double y;
        private final double z;
        private final float yaw;
        private final float pitch;

        private Point(String world, double x, double y, double z, float yaw, float pitch) {
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.pitch = pitch;
        }

        private static Point fromLocation(Location location) {
            if (!validLocation(location)) {
                throw new IllegalArgumentException("Arena locations need a loaded world and finite coordinates/rotation.");
            }
            return new Point(location.getWorld().getName(), location.getX(), location.getY(),
                    location.getZ(), location.getYaw(), location.getPitch());
        }

        private static Point read(Object raw) {
            if (raw instanceof ConfigurationSection) {
                raw = ((ConfigurationSection) raw).getValues(false);
            }
            if (!(raw instanceof Map<?, ?>)) {
                return null;
            }
            Map<?, ?> map = (Map<?, ?>) raw;
            Object world = map.get("world");
            if (!(world instanceof String) || ((String) world).trim().isEmpty()) {
                return null;
            }
            double x = number(map.get("x"), Double.NaN);
            double y = number(map.get("y"), Double.NaN);
            double z = number(map.get("z"), Double.NaN);
            float yaw = (float) number(map.get("yaw"), 0.0);
            float pitch = (float) number(map.get("pitch"), 0.0);
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                    || !Float.isFinite(yaw) || !Float.isFinite(pitch)) {
                return null;
            }
            return new Point((String) world, x, y, z, yaw, pitch);
        }

        private static double number(Object raw, double defaultValue) {
            return raw == null ? defaultValue
                    : raw instanceof Number ? ((Number) raw).doubleValue() : Double.NaN;
        }

        private Location toLocation() {
            World loadedWorld = Bukkit.getWorld(world);
            return loadedWorld == null ? null : new Location(loadedWorld, x, y, z, yaw, pitch);
        }

        private Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<String, Object>();
            map.put("world", world);
            map.put("x", x);
            map.put("y", y);
            map.put("z", z);
            map.put("yaw", yaw);
            map.put("pitch", pitch);
            return map;
        }
    }
}
