package com.hungergames;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

public class LootManagerTest {
    private JavaPlugin plugin;
    private Logger logger;
    private YamlConfiguration config;

    @Before
    public void setUp() {
        plugin = mock(JavaPlugin.class);
        logger = mock(Logger.class);
        config = new YamlConfiguration();
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getLogger()).thenReturn(logger);
    }

    @Test
    public void absentConfigurationUsesBasicTableAndDefaultCountBounds() {
        LootManager manager = new LootManager(plugin);
        assertEquals(4, manager.getEntryCount());
        Set<Material> materials = new HashSet<Material>(Arrays.asList(Material.BREAD,
                Material.APPLE, Material.WOOD_SWORD, Material.LEATHER_CHESTPLATE));
        for (int i = 0; i < 30; i++) {
            for (ItemStack stack : fillAndCapture(manager, 27, 3, 6)) {
                assertTrue(materials.contains(stack.getType()));
                int maximum = stack.getType() == Material.BREAD ? 3
                        : stack.getType() == Material.APPLE ? 2 : 1;
                assertTrue(stack.getAmount() >= 1 && stack.getAmount() <= maximum);
            }
        }
        verify(logger).warning(contains("built-in basic loot table"));
    }

    @Test
    public void emptyMalformedAndEntirelyInvalidTablesFallBack() {
        for (Object table : Arrays.<Object>asList(new ArrayList<Object>(), "not a list",
                Arrays.asList("not a map", entry("AIR"), entry("NOT_A_MATERIAL")))) {
            config.set("loot.entries", table);
            LootManager manager = new LootManager(plugin);
            assertEquals(4, manager.getEntryCount());
            fillAndCapture(manager, 9, 3, 6);
        }
    }

    @Test
    public void invalidEntriesAreSkippedWhileValidEntryRemainsUsable() {
        List<Object> invalid = new ArrayList<Object>();
        invalid.add(null);
        invalid.add("not a map");
        for (Object[] change : new Object[][] {
                {"material", null}, {"material", 1}, {"material", "AIR"},
                {"material", "NOT_A_MATERIAL"}, {"min", 0}, {"min", -1},
                {"min", 1.5}, {"min", "1"}, {"min", Double.NaN},
                {"min", 2147483648L}, {"max", 0}, {"max", 1.5},
                {"data", -1}, {"data", 32768}, {"data", 0.5}, {"data", "0"},
                {"weight", 0}, {"weight", -1}, {"weight", "1"},
                {"weight", Double.NaN}, {"weight", Double.POSITIVE_INFINITY},
                {"weight", Double.NEGATIVE_INFINITY}
        }) {
            Map<String, Object> value = entry("BREAD");
            value.put((String) change[0], change[1]);
            invalid.add(value);
        }
        fixedCount(2);
        for (Object value : invalid) {
            config.set("loot.entries", Arrays.asList(value, entry("APPLE")));
            LootManager manager = new LootManager(plugin);
            assertEquals("Invalid entry: " + value, 1, manager.getEntryCount());
            for (ItemStack stack : fillAndCapture(manager, 9, 2, 2)) {
                assertEquals(Material.APPLE, stack.getType());
            }
        }
        verify(logger, times(invalid.size())).warning(contains("Skipping loot.entries entry 1"));
    }

    @Test
    public void omittedEntryFieldsDefaultToOneItemAndZeroData() {
        fixedCount(1);
        config.set("loot.entries", Arrays.asList(entry("  bread  ")));
        LootManager manager = new LootManager(plugin);
        assertEquals(1, manager.getEntryCount());
        ItemStack stack = fillAndCapture(manager, 9, 1, 1).get(0);
        assertEquals(Material.BREAD, stack.getType());
        assertEquals(1, stack.getAmount());
        assertEquals(0, stack.getDurability());
    }

    @Test
    public void omittedMaximumDefaultsToConfiguredMinimum() {
        fixedCount(1);
        Map<String, Object> value = entry("BREAD");
        value.put("min", 7.0);
        config.set("loot.entries", Arrays.asList(value));
        LootManager manager = new LootManager(plugin);
        assertEquals(1, manager.getEntryCount());
        assertEquals(7, fillAndCapture(manager, 9, 1, 1).get(0).getAmount());
    }

    @Test
    public void maximumCountBelowMinimumIsRaisedToMinimum() {
        config.set("loot.min-items", 5);
        config.set("loot.max-items", 2);
        config.set("loot.entries", Arrays.asList(entry("BREAD")));
        LootManager manager = new LootManager(plugin);
        fillAndCapture(manager, 9, 5, 5);
        verify(logger).warning(contains("using 5 for both"));
    }

    @Test
    public void invalidMinimumCountsUseDefaultThree() {
        config.set("loot.max-items", 3);
        config.set("loot.entries", Arrays.asList(entry("BREAD")));
        for (Object invalid : invalidCounts()) {
            config.set("loot.min-items", invalid);
            fillAndCapture(new LootManager(plugin), 9, 3, 3);
        }
        verify(logger, times(invalidCounts().size())).warning(contains("using 3."));
    }

    @Test
    public void invalidMaximumCountsUseDefaultSix() {
        config.set("loot.min-items", 6);
        config.set("loot.entries", Arrays.asList(entry("BREAD")));
        for (Object invalid : invalidCounts()) {
            config.set("loot.max-items", invalid);
            fillAndCapture(new LootManager(plugin), 9, 6, 6);
        }
        verify(logger, times(invalidCounts().size())).warning(contains("using 6."));
    }

    @Test
    public void zeroCountClearsExistingContentsWithoutWritingItems() {
        fixedCount(0);
        LootManager manager = new LootManager(plugin);
        Inventory inventory = mockInventory(9);
        manager.fill(inventory);
        verify(inventory).clear();
        verify(inventory, never()).setItem(anyInt(), any(ItemStack.class));
    }

    @Test
    public void nonpositiveInventorySizesAreClearedWithoutWritingItems() {
        LootManager manager = new LootManager(plugin);
        for (int size : new int[] {0, -1}) {
            Inventory inventory = mockInventory(size);
            manager.fill(inventory);
            verify(inventory).clear();
            verify(inventory, never()).setItem(anyInt(), any(ItemStack.class));
        }
    }

    @Test
    public void hugeConfiguredCountsAreCappedToInventorySizeAndUseDistinctSlots() {
        fixedCount(Integer.MAX_VALUE);
        config.set("loot.entries", Arrays.asList(entry("BREAD")));
        LootManager manager = new LootManager(plugin);
        for (int size : new int[] {1, 5, 27}) {
            fillAndCapture(manager, size, size, size);
        }
    }

    @Test
    public void rangedCountsAndAmountsStayWithinInclusiveBounds() {
        config.set("loot.min-items", 2);
        config.set("loot.max-items", 7);
        config.set("loot.entries", Arrays.asList(entry("BREAD", 3, 8)));
        LootManager manager = new LootManager(plugin);
        // Assert invariants, not a random distribution or that both endpoints appear.
        for (int i = 0; i < 40; i++) {
            for (ItemStack stack : fillAndCapture(manager, 9, 2, 7)) {
                assertEquals(Material.BREAD, stack.getType());
                assertTrue(stack.getAmount() >= 3 && stack.getAmount() <= 8);
            }
        }
    }

    @Test
    public void bothAmountBoundsAreClampedToMaterialStackLimits() {
        fixedCount(4);
        for (Material material : Arrays.asList(Material.BREAD, Material.WOOD_SWORD,
                Material.LEATHER_CHESTPLATE)) {
            config.set("loot.entries", Arrays.asList(entry(material.name(), 100, Integer.MAX_VALUE)));
            LootManager manager = new LootManager(plugin);
            assertEquals(1, manager.getEntryCount());
            for (ItemStack stack : fillAndCapture(manager, 9, 4, 4)) {
                assertEquals(material, stack.getType());
                assertEquals(material.getMaxStackSize(), stack.getAmount());
            }
        }
        verify(logger, times(3)).warning(contains("Clamping"));
    }

    @Test
    public void maximumAmountIsClampedWithoutRaisingValidMinimum() {
        fixedCount(9);
        config.set("loot.entries", Arrays.asList(entry("BREAD", 60, 100)));
        LootManager manager = new LootManager(plugin);
        for (ItemStack stack : fillAndCapture(manager, 9, 9, 9)) {
            assertEquals(Material.BREAD, stack.getType());
            assertTrue(stack.getAmount() >= 60 && stack.getAmount() <= 64);
        }
    }

    @Test
    public void legacyDataIncludesZeroAndShortMaximum() {
        fixedCount(1);
        for (int data : new int[] {0, 14, Short.MAX_VALUE}) {
            Map<String, Object> value = entry("WOOL");
            value.put("data", data);
            config.set("loot.entries", Arrays.asList(value));
            LootManager manager = new LootManager(plugin);
            assertEquals(1, manager.getEntryCount());
            ItemStack stack = fillAndCapture(manager, 9, 1, 1).get(0);
            assertEquals(Material.WOOL, stack.getType());
            assertEquals(data, stack.getDurability());
        }
    }

    @Test
    public void reloadReplacesEntriesCountsAndWeightTotalsUsingInMemoryConfig() {
        fixedCount(1);
        Map<String, Object> original = entry("BREAD");
        original.put("weight", Double.MAX_VALUE);
        config.set("loot.entries", Arrays.asList(original));
        LootManager manager = new LootManager(plugin);
        assertEquals(Material.BREAD, fillAndCapture(manager, 9, 1, 1).get(0).getType());
        fixedCount(3);
        Map<String, Object> replacement = entry("APPLE", 2, 2);
        replacement.put("weight", Double.MIN_VALUE);
        config.set("loot.entries", Arrays.asList(replacement));
        manager.reload();
        assertEquals(1, manager.getEntryCount());
        for (ItemStack stack : fillAndCapture(manager, 9, 3, 3)) {
            assertEquals(Material.APPLE, stack.getType());
            assertEquals(2, stack.getAmount());
        }
        config.set("loot.entries", new ArrayList<Object>());
        manager.reload();
        assertEquals(4, manager.getEntryCount());
        verify(plugin, never()).reloadConfig();
    }

    @Test
    public void extremeFiniteWeightsRemainValidAndGenerateConfiguredLoot() {
        fixedCount(9);
        for (double weight : new double[] {Double.MAX_VALUE, Double.MIN_VALUE}) {
            Map<String, Object> first = entry("BREAD", 2, 2);
            Map<String, Object> second = entry("APPLE", 3, 3);
            first.put("weight", weight);
            second.put("weight", weight);
            config.set("loot.entries", Arrays.asList(first, second));
            LootManager manager = new LootManager(plugin);
            assertEquals(2, manager.getEntryCount());
            for (ItemStack stack : fillAndCapture(manager, 9, 9, 9)) {
                assertTrue(stack.getType() == Material.BREAD || stack.getType() == Material.APPLE);
                assertEquals(stack.getType() == Material.BREAD ? 2 : 3, stack.getAmount());
            }
        }
        verify(logger, never()).warning(anyString());
    }

    @Test
    public void nullDependenciesAreRejected() {
        assertThrows(NullPointerException.class, () -> new LootManager(null));
        LootManager manager = new LootManager(plugin);
        assertThrows(NullPointerException.class, () -> manager.fill(null));
    }

    private void fixedCount(int count) {
        config.set("loot.min-items", count);
        config.set("loot.max-items", count);
    }

    private static List<Object> invalidCounts() {
        return Arrays.<Object>asList(-1, 1.5, "3", Double.NaN,
                Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 2147483648L);
    }

    private static Map<String, Object> entry(String material) {
        Map<String, Object> entry = new LinkedHashMap<String, Object>();
        entry.put("material", material);
        return entry;
    }

    private static Map<String, Object> entry(String material, int min, int max) {
        Map<String, Object> entry = entry(material);
        entry.put("min", min);
        entry.put("max", max);
        return entry;
    }

    private static Inventory mockInventory(int size) {
        Inventory inventory = mock(Inventory.class);
        when(inventory.getSize()).thenReturn(size);
        return inventory;
    }

    private static List<ItemStack> fillAndCapture(LootManager manager, int size,
            int minimum, int maximum) {
        Inventory inventory = mockInventory(size);
        manager.fill(inventory);
        ArgumentCaptor<Integer> slots = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<ItemStack> stacks = ArgumentCaptor.forClass(ItemStack.class);
        InOrder order = inOrder(inventory);
        order.verify(inventory).clear();
        order.verify(inventory).getSize();
        order.verify(inventory, atLeastOnce()).setItem(slots.capture(), stacks.capture());
        order.verifyNoMoreInteractions();
        int count = slots.getAllValues().size();
        assertTrue("count " + count + " below " + minimum, count >= minimum);
        assertTrue("count " + count + " above " + maximum, count <= maximum);
        assertEquals("Slots must be distinct", count, new HashSet<Integer>(slots.getAllValues()).size());
        for (int slot : slots.getAllValues()) {
            assertTrue("Invalid slot " + slot, slot >= 0 && slot < size);
        }
        for (ItemStack stack : stacks.getAllValues()) {
            assertNotNull(stack);
            assertTrue(stack.getAmount() >= 1);
            assertTrue(stack.getAmount() <= stack.getType().getMaxStackSize());
        }
        return stacks.getAllValues();
    }
}
