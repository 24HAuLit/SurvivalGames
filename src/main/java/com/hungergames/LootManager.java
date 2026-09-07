package com.hungergames;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;

import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/** Configurable legacy loot table. Call from the server thread. */
public final class LootManager {
    private final JavaPlugin plugin;
    private final Random random = new Random();
    private final List<Entry> entries = new ArrayList<Entry>();
    private int minItems;
    private int maxItems;
    private double weightScale;
    private double totalWeight;

    public LootManager(JavaPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        reload();
    }

    /** Reads the current plugin config; does not call plugin.reloadConfig(). */
    public void reload() {
        FileConfiguration config = plugin.getConfig();
        minItems = readCount(config, "loot.min-items", 3);
        maxItems = readCount(config, "loot.max-items", 6);
        if (maxItems < minItems) {
            warn("loot.max-items is below loot.min-items; using " + minItems + " for both.");
            maxItems = minItems;
        }

        entries.clear();
        Object rawEntries = config.get("loot.entries");
        if (rawEntries instanceof List<?>) {
            int index = 0;
            for (Object raw : (List<?>) rawEntries) {
                index++;
                try {
                    if (!(raw instanceof Map<?, ?>)) {
                        throw new IllegalArgumentException("expected a map");
                    }
                    entries.add(readEntry((Map<?, ?>) raw));
                } catch (IllegalArgumentException ex) {
                    warn("Skipping loot.entries entry " + index + ": " + ex.getMessage());
                }
            }
        } else if (rawEntries != null) {
            warn("loot.entries must be a list of maps.");
        }

        if (entries.isEmpty()) {
            warn("No valid loot entries; using the built-in basic loot table.");
            entries.add(new Entry(Material.BREAD, 1, 3, 5.0, (short) 0));
            entries.add(new Entry(Material.APPLE, 1, 2, 4.0, (short) 0));
            entries.add(new Entry(Material.WOOD_SWORD, 1, 1, 2.0, (short) 0));
            entries.add(new Entry(Material.LEATHER_CHESTPLATE, 1, 1, 1.0, (short) 0));
        }

        // Scaling by the largest weight prevents overflow even for very large finite weights.
        weightScale = 0.0;
        for (Entry entry : entries) {
            weightScale = Math.max(weightScale, entry.weight);
        }
        totalWeight = 0.0;
        for (Entry entry : entries) {
            totalWeight += entry.weight / weightScale;
        }
    }

    /** Clears the inventory, then fills distinct randomly selected slots with loot stacks. */
    public void fill(Inventory inventory) {
        Objects.requireNonNull(inventory, "inventory");
        inventory.clear();
        int size = inventory.getSize();
        if (size <= 0) {
            return;
        }
        int minimum = Math.min(minItems, size);
        int maximum = Math.min(maxItems, size);
        int count = randomBetween(minimum, maximum);
        if (count == 0) {
            return;
        }

        int[] slots = new int[size];
        for (int i = 0; i < size; i++) {
            slots[i] = i;
        }
        for (int i = 0; i < count; i++) {
            int selected = i + random.nextInt(size - i);
            int slot = slots[selected];
            slots[selected] = slots[i];
            slots[i] = slot;
            Entry entry = chooseEntry();
            int amount = randomBetween(entry.min, entry.max);
            inventory.setItem(slot, new ItemStack(entry.material, amount, entry.data));
        }
    }

    /** Includes fallback entries when the configured table has no valid entries. */
    public int getEntryCount() {
        return entries.size();
    }

    private Entry readEntry(Map<?, ?> map) {
        Object rawMaterial = map.get("material");
        if (!(rawMaterial instanceof String)) {
            throw new IllegalArgumentException("material must be a legacy material name");
        }
        Material material = Material.matchMaterial(((String) rawMaterial).trim());
        if (material == null || material == Material.AIR || material.getMaxStackSize() <= 0) {
            throw new IllegalArgumentException("unknown or unusable material: " + rawMaterial);
        }
        int min = integer(map.get("min"), 1, "min");
        int max = integer(map.get("max"), min, "max");
        if (min < 1 || max < min) {
            throw new IllegalArgumentException("amounts must satisfy 1 <= min <= max");
        }
        int data = integer(map.get("data"), 0, "data");
        // Legacy ItemStack uses a short for both block/item metadata and durability.
        if (data < 0 || data > Short.MAX_VALUE) {
            throw new IllegalArgumentException("data must be between 0 and " + Short.MAX_VALUE);
        }
        Object rawWeight = map.get("weight");
        double weight;
        if (rawWeight == null) {
            weight = 1.0;
        } else if (rawWeight instanceof Number) {
            weight = ((Number) rawWeight).doubleValue();
        } else {
            throw new IllegalArgumentException("weight must be a number");
        }
        if (!Double.isFinite(weight) || weight <= 0.0) {
            throw new IllegalArgumentException("weight must be finite and positive");
        }
        int stackLimit = material.getMaxStackSize();
        if (max > stackLimit) {
            warn("Clamping " + material.name() + " loot amounts to its stack limit of " + stackLimit + ".");
        }
        return new Entry(material, Math.min(min, stackLimit), Math.min(max, stackLimit),
                weight, (short) data);
    }

    private Entry chooseEntry() {
        double target = random.nextDouble() * totalWeight;
        Entry lastPositive = entries.get(0);
        for (Entry entry : entries) {
            double weight = entry.weight / weightScale;
            if (weight > 0.0) {
                lastPositive = entry;
                if (target < weight) {
                    return entry;
                }
                target -= weight;
            }
        }
        // Floating-point rounding can leave a tiny remainder at the upper boundary.
        return lastPositive;
    }

    private int readCount(FileConfiguration config, String path, int defaultValue) {
        try {
            int value = integer(config.get(path), defaultValue, path);
            if (value < 0) {
                throw new IllegalArgumentException(path + " must be nonnegative");
            }
            return value;
        } catch (IllegalArgumentException ex) {
            warn(ex.getMessage() + "; using " + defaultValue + ".");
            return defaultValue;
        }
    }

    private static int integer(Object raw, int defaultValue, String name) {
        if (raw == null) {
            return defaultValue;
        }
        if (raw instanceof Number) {
            double value = ((Number) raw).doubleValue();
            if (Double.isFinite(value) && value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE
                    && value == Math.rint(value)) {
                return (int) value;
            }
        }
        throw new IllegalArgumentException(name + " must be a 32-bit integer");
    }

    private int randomBetween(int minimum, int maximum) {
        long range = (long) maximum - minimum + 1L;
        if (range <= Integer.MAX_VALUE) {
            return minimum + random.nextInt((int) range);
        }
        // Only the inclusive 0..Integer.MAX_VALUE count range needs this branch.
        return random.nextInt() & Integer.MAX_VALUE;
    }

    private void warn(String message) {
        plugin.getLogger().warning("[LootManager] " + message);
    }

    private static final class Entry {
        private final Material material;
        private final int min;
        private final int max;
        private final double weight;
        private final short data;

        private Entry(Material material, int min, int max, double weight, short data) {
            this.material = material;
            this.min = min;
            this.max = max;
            this.weight = weight;
            this.data = data;
        }
    }
}
