package com.hungergames;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFactory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.util.Vector;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

public class PlayerSnapshotTest {
    private static final UUID PLAYER_ID = UUID.fromString("12345678-1234-1234-1234-123456789abc");
    private static final UUID WORLD_ID = UUID.fromString("abcdef12-1234-1234-1234-123456789abc");

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private MockedStatic<Bukkit> bukkit;
    private JavaPlugin plugin;
    private World world;
    private Player player;
    private PlayerInventory inventory;
    private ItemStack[] contents;
    private ItemStack[] armor;
    private ItemStack cursor;
    private Location originalLocation;
    private Vector originalVelocity;
    private Path recoveryFile;

    @Before
    public void setUp() throws Exception {
        bukkit = mockStatic(Bukkit.class);
        ItemFactory itemFactory = mock(ItemFactory.class);
        // Plain stacks have no meta. Bukkit 1.8's hasItemMeta() delegates to
        // ItemFactory.equals(meta, null), including while serializing to YAML.
        when(itemFactory.equals((ItemMeta) null, (ItemMeta) null)).thenReturn(true);
        when(itemFactory.getItemMeta(any(Material.class))).thenReturn(null);
        bukkit.when(Bukkit::getItemFactory).thenReturn(itemFactory);
        Logger logger = Logger.getLogger(PlayerSnapshotTest.class.getName());
        bukkit.when(Bukkit::getLogger).thenReturn(logger);

        plugin = mock(JavaPlugin.class);
        File dataFolder = temporaryFolder.newFolder("plugin");
        when(plugin.getDataFolder()).thenReturn(dataFolder);
        when(plugin.getLogger()).thenReturn(logger);
        recoveryFile = dataFolder.toPath().resolve("player-data").resolve(PLAYER_ID + ".yml");

        world = mock(World.class);
        when(world.getUID()).thenReturn(WORLD_ID);
        when(world.getName()).thenReturn("original-world");
        bukkit.when(() -> Bukkit.getWorld(WORLD_ID)).thenReturn(world);

        inventory = mock(PlayerInventory.class);
        player = livingPlayer(inventory, 24.0D);
        contents = new ItemStack[36];
        contents[0] = new ItemStack(Material.STONE, 7, (short) 1);
        contents[35] = new ItemStack(Material.BREAD, 3);
        armor = new ItemStack[4];
        armor[0] = new ItemStack(Material.IRON_BOOTS, 1, (short) 12);
        armor[3] = new ItemStack(Material.IRON_HELMET, 1, (short) 8);
        cursor = new ItemStack(Material.APPLE, 2);
        when(inventory.getContents()).thenReturn(contents);
        when(inventory.getArmorContents()).thenReturn(armor);
        when(inventory.getHeldItemSlot()).thenReturn(6);
        when(player.getItemOnCursor()).thenReturn(cursor);

        originalLocation = new Location(world, -12.25D, 81.5D, 6.75D, 135.5F, -25.25F);
        originalVelocity = new Vector(0.125D, -0.25D, 0.375D);
        when(player.getLocation()).thenReturn(originalLocation);
        when(player.getVelocity()).thenReturn(originalVelocity);
        when(player.getGameMode()).thenReturn(GameMode.CREATIVE);
        when(player.getHealth()).thenReturn(17.5D);
        when(player.getFoodLevel()).thenReturn(16);
        when(player.getSaturation()).thenReturn(3.25F);
        when(player.getExhaustion()).thenReturn(1.5F);
        when(player.getTotalExperience()).thenReturn(137);
        when(player.getLevel()).thenReturn(8);
        when(player.getExp()).thenReturn(0.375F);
        when(player.getAllowFlight()).thenReturn(true);
        when(player.isFlying()).thenReturn(true);
        when(player.getFireTicks()).thenReturn(47);
        when(player.getMaximumAir()).thenReturn(400);
        when(player.getRemainingAir()).thenReturn(123);
        when(player.getFallDistance()).thenReturn(2.75F);
    }

    @After
    public void tearDown() {
        if (bukkit != null) {
            bukkit.close();
        }
    }

    @Test
    public void captureAndRepeatedRestoreDefensivelyCloneInventoryArmorCursorAndLocation() {
        PlayerSnapshot snapshot = PlayerSnapshot.capture(player);
        contents[0].setAmount(60);
        contents[35] = null;
        armor[0].setDurability((short) 99);
        armor[3] = null;
        cursor.setAmount(50);
        originalLocation.setX(999);
        originalVelocity.setX(999);
        snapshot.getReturnLocation().setZ(999);
        clearInvocations(player, inventory);

        snapshot.restore(player);
        ArgumentCaptor<ItemStack[]> inventoryCaptor = ArgumentCaptor.forClass(ItemStack[].class);
        ArgumentCaptor<ItemStack[]> armorCaptor = ArgumentCaptor.forClass(ItemStack[].class);
        ArgumentCaptor<ItemStack> cursorCaptor = ArgumentCaptor.forClass(ItemStack.class);
        ArgumentCaptor<Vector> velocityCaptor = ArgumentCaptor.forClass(Vector.class);
        verify(inventory).setContents(inventoryCaptor.capture());
        verify(inventory).setArmorContents(armorCaptor.capture());
        verify(player).setItemOnCursor(cursorCaptor.capture());
        verify(player).setVelocity(velocityCaptor.capture());
        ItemStack[] firstContents = inventoryCaptor.getValue();
        ItemStack[] firstArmor = armorCaptor.getValue();
        ItemStack firstCursor = cursorCaptor.getValue();
        assertOriginalItems(firstContents, firstArmor, firstCursor);
        assertNotSame(contents, firstContents);
        assertNotSame(contents[0], firstContents[0]);
        assertNotSame(armor, firstArmor);
        assertNotSame(armor[0], firstArmor[0]);
        assertNotSame(cursor, firstCursor);
        assertEquals(new Vector(0.125D, -0.25D, 0.375D), velocityCaptor.getValue());

        firstContents[0].setAmount(63);
        firstContents[35] = null;
        firstArmor[0].setDurability((short) 100);
        firstArmor[3] = null;
        firstCursor.setAmount(63);
        velocityCaptor.getValue().setX(999);
        clearInvocations(player, inventory);
        snapshot.restore(player);
        verify(inventory).setContents(inventoryCaptor.capture());
        verify(inventory).setArmorContents(armorCaptor.capture());
        verify(player).setItemOnCursor(cursorCaptor.capture());
        verify(player).setVelocity(velocityCaptor.capture());
        assertOriginalItems(inventoryCaptor.getValue(), armorCaptor.getValue(), cursorCaptor.getValue());
        assertNotSame(firstContents, inventoryCaptor.getValue());
        assertNotSame(firstContents[0], inventoryCaptor.getValue()[0]);
        assertNotSame(firstArmor, armorCaptor.getValue());
        assertNotSame(firstArmor[0], armorCaptor.getValue()[0]);
        assertNotSame(firstCursor, cursorCaptor.getValue());
        assertEquals(new Vector(0.125D, -0.25D, 0.375D), velocityCaptor.getValue());
        assertOriginalLocation(snapshot.getReturnLocation());
        verify(inventory, never()).addItem(any(ItemStack[].class));
    }

    @Test
    public void realYamlRoundTripPreparesMatchAndRestoresAllSavedStateToNewPlayer() throws Exception {
        PlayerSnapshot snapshot = PlayerSnapshot.capture(player);
        assertTrue(snapshot.save(plugin, PLAYER_ID));
        assertTrue(Files.isRegularFile(recoveryFile));
        // Exercise replacement of an existing recovery file, not just creation.
        assertTrue(snapshot.save(plugin, PLAYER_ID));
        byte[] saved = Files.readAllBytes(recoveryFile);
        assertArrayEquals(new String[] {PLAYER_ID + ".yml"}, recoveryFile.getParent().toFile().list());

        snapshot.prepareForMatch(player);
        ArgumentCaptor<ItemStack[]> clearedContents = ArgumentCaptor.forClass(ItemStack[].class);
        ArgumentCaptor<ItemStack[]> clearedArmor = ArgumentCaptor.forClass(ItemStack[].class);
        verify(inventory).setContents(clearedContents.capture());
        verify(inventory).setArmorContents(clearedArmor.capture());
        assertArrayEquals(new ItemStack[36], clearedContents.getValue());
        assertArrayEquals(new ItemStack[4], clearedArmor.getValue());
        verify(inventory).setHeldItemSlot(0);
        verify(player).setItemOnCursor(null);
        verify(player).setGameMode(GameMode.SURVIVAL);
        verify(player).setHealth(24.0D);
        verify(player, never()).setMaxHealth(anyDouble());
        verify(player).setFoodLevel(20);
        verify(player).setSaturation(5.0F);
        verify(player).setExhaustion(0.0F);
        verify(player).setLevel(0);
        verify(player).setExp(0.0F);
        verify(player).setTotalExperience(0);
        verify(player).setFlying(false);
        verify(player).setAllowFlight(false);
        verify(player).setFireTicks(0);
        verify(player).setRemainingAir(400);
        verify(player).setFallDistance(0.0F);
        verify(player).setVelocity(new Vector(0, 0, 0));
        verify(player).updateInventory();

        PlayerSnapshot loaded = PlayerSnapshot.load(plugin, PLAYER_ID);
        assertNotNull(loaded);
        assertNotSame(snapshot, loaded);
        assertOriginalLocation(loaded.getReturnLocation());
        PlayerInventory restoredInventory = mock(PlayerInventory.class);
        Player restoredPlayer = livingPlayer(restoredInventory, 20.0D);
        loaded.restore(restoredPlayer);

        ArgumentCaptor<Location> teleport = ArgumentCaptor.forClass(Location.class);
        ArgumentCaptor<ItemStack[]> restoredContents = ArgumentCaptor.forClass(ItemStack[].class);
        ArgumentCaptor<ItemStack[]> restoredArmor = ArgumentCaptor.forClass(ItemStack[].class);
        ArgumentCaptor<ItemStack> restoredCursor = ArgumentCaptor.forClass(ItemStack.class);
        verify(restoredPlayer).teleport(teleport.capture());
        assertOriginalLocation(teleport.getValue());
        verify(restoredInventory).setContents(restoredContents.capture());
        verify(restoredInventory).setArmorContents(restoredArmor.capture());
        verify(restoredPlayer).setItemOnCursor(restoredCursor.capture());
        assertOriginalItems(restoredContents.getValue(), restoredArmor.getValue(), restoredCursor.getValue());
        verify(restoredInventory).setHeldItemSlot(6);
        verify(restoredInventory, never()).addItem(any(ItemStack[].class));
        verify(restoredPlayer).setGameMode(GameMode.CREATIVE);
        verify(restoredPlayer).setMaxHealth(24.0D);
        verify(restoredPlayer).setHealth(17.5D);
        verify(restoredPlayer).setFoodLevel(16);
        verify(restoredPlayer).setSaturation(3.25F);
        verify(restoredPlayer).setExhaustion(1.5F);
        verify(restoredPlayer).setLevel(8);
        verify(restoredPlayer).setExp(0.375F);
        verify(restoredPlayer).setTotalExperience(137);
        verify(restoredPlayer).setAllowFlight(true);
        verify(restoredPlayer).setFlying(true);
        verify(restoredPlayer).setFireTicks(47);
        verify(restoredPlayer).setMaximumAir(400);
        verify(restoredPlayer).setRemainingAir(123);
        verify(restoredPlayer).setFallDistance(2.75F);
        verify(restoredPlayer).setVelocity(new Vector(0.125D, -0.25D, 0.375D));
        verify(restoredPlayer).updateInventory();
        assertArrayEquals("Recovery must survive prepare and restore until explicitly deleted",
                saved, Files.readAllBytes(recoveryFile));
    }

    @Test
    public void unloadedWorldFailsBeforeAnyRestoreMutationAndRecoveryCanBeRetried() throws Exception {
        assertTrue(PlayerSnapshot.capture(player).save(plugin, PLAYER_ID));
        byte[] saved = Files.readAllBytes(recoveryFile);
        bukkit.when(() -> Bukkit.getWorld(WORLD_ID)).thenReturn(null);
        PlayerSnapshot loaded = PlayerSnapshot.load(plugin, PLAYER_ID);
        assertNotNull(loaded);
        assertNull(loaded.getReturnLocation());
        clearInvocations(player, inventory);

        IllegalStateException failure = expectIllegalState(() -> loaded.restore(player));
        assertTrue(failure.getMessage().contains("not loaded"));
        assertTrue(failure.getMessage().contains(WORLD_ID.toString()));
        assertOnlyGuardReads();
        assertArrayEquals(saved, Files.readAllBytes(recoveryFile));

        bukkit.when(() -> Bukkit.getWorld(WORLD_ID)).thenReturn(world);
        loaded.restore(player);
        verify(player).teleport(any(Location.class));
        verify(inventory).setContents(any(ItemStack[].class));
        assertArrayEquals(saved, Files.readAllBytes(recoveryFile));
    }

    @Test
    public void deadFlagOrZeroHealthRejectsRestoreWithoutMutationOrDeletingRecovery() throws Exception {
        PlayerSnapshot snapshot = PlayerSnapshot.capture(player);
        assertTrue(snapshot.save(plugin, PLAYER_ID));
        byte[] saved = Files.readAllBytes(recoveryFile);
        for (boolean deadFlag : new boolean[] {true, false}) {
            when(player.isDead()).thenReturn(deadFlag);
            when(player.getHealth()).thenReturn(deadFlag ? 17.5D : 0.0D);
            clearInvocations(player, inventory);
            IllegalStateException failure = expectIllegalState(() -> snapshot.restore(player));
            assertTrue(failure.getMessage().contains("dead"));
            assertTrue(failure.getMessage().contains("respawn"));
            assertOnlyGuardReads();
            assertArrayEquals(saved, Files.readAllBytes(recoveryFile));
        }
    }

    @Test
    public void malformedYamlAndInvalidSnapshotFieldsBlockRecoveryWithoutChangingFile() throws Exception {
        assertTrue(PlayerSnapshot.capture(player).save(plugin, PLAYER_ID));
        String valid = new String(Files.readAllBytes(recoveryFile), StandardCharsets.UTF_8);
        assertMalformedRecovery("version: [unterminated\n");
        assertMalformedRecovery("version: 1\n");
        for (Object[] invalid : new Object[][] {
                {"version", 2},
                {"player", "00000000-0000-0000-0000-000000000000"},
                {"health", 0},
                {"location.x", Double.NaN},
                {"held-slot", 9},
                {"xp.progress", "not a number"},
                {"flight.allowed", false},
                {"inventory", Collections.emptyList()},
                {"armor", Arrays.asList("not an item", null, null, null)},
                {"cursor", Collections.singletonList("not an item")},
                {"potions", "not a list"}
        }) {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.loadFromString(valid);
            yaml.set((String) invalid[0], invalid[1]);
            assertMalformedRecovery(yaml.saveToString());
        }
    }

    @Test
    public void absentLoadAndDeletionAreIdempotentAndDeletionIsExplicit() throws Exception {
        assertFalse(Files.exists(recoveryFile.getParent()));
        assertNull(PlayerSnapshot.load(plugin, PLAYER_ID));
        assertTrue(PlayerSnapshot.delete(plugin, PLAYER_ID));
        assertFalse(Files.exists(recoveryFile.getParent()));

        assertTrue(PlayerSnapshot.capture(player).save(plugin, PLAYER_ID));
        assertNotNull(PlayerSnapshot.load(plugin, PLAYER_ID));
        assertTrue(Files.exists(recoveryFile));
        assertTrue(PlayerSnapshot.delete(plugin, PLAYER_ID));
        assertFalse(Files.exists(recoveryFile));
        assertNull(PlayerSnapshot.load(plugin, PLAYER_ID));
        assertTrue(PlayerSnapshot.delete(plugin, PLAYER_ID));
    }

    private Player livingPlayer(PlayerInventory playerInventory, double initialMaxHealth) {
        Player result = mock(Player.class);
        when(result.getUniqueId()).thenReturn(PLAYER_ID);
        when(result.isOnline()).thenReturn(true);
        when(result.isDead()).thenReturn(false);
        when(result.getHealth()).thenReturn(10.0D);
        when(result.getInventory()).thenReturn(playerInventory);
        when(result.teleport(any(Location.class))).thenReturn(true);
        // Avoid touching the global PotionEffectType registry in serverless tests.
        when(result.getActivePotionEffects()).thenReturn(Collections.<PotionEffect>emptyList());
        // restore() reads the maximum again after setting it: model that field.
        final double[] maximumHealth = {initialMaxHealth};
        when(result.getMaxHealth()).thenAnswer(invocation -> maximumHealth[0]);
        doAnswer(invocation -> {
            maximumHealth[0] = invocation.getArgument(0);
            return null;
        }).when(result).setMaxHealth(anyDouble());
        return result;
    }

    private void assertOnlyGuardReads() {
        verify(player, atLeastOnce()).getUniqueId();
        verify(player).isOnline();
        verify(player).isDead();
        verify(player, atMostOnce()).getHealth();
        // Whitelisting reads catches every mutation, including teleport, not
        // merely a selected set of setters that could miss a partial restore.
        verifyNoMoreInteractions(player);
        verifyNoInteractions(inventory);
    }

    private void assertMalformedRecovery(String text) throws Exception {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        Files.write(recoveryFile, bytes);
        IllegalStateException failure = expectIllegalState(() -> PlayerSnapshot.load(plugin, PLAYER_ID));
        assertTrue(failure.getMessage().contains("block joining"));
        assertTrue(failure.getMessage().contains(recoveryFile.toString()));
        assertNotNull(failure.getCause());
        assertArrayEquals(bytes, Files.readAllBytes(recoveryFile));
    }

    private static IllegalStateException expectIllegalState(Runnable operation) {
        try {
            operation.run();
        } catch (IllegalStateException expected) {
            return expected;
        }
        throw new AssertionError("Expected IllegalStateException");
    }

    private void assertOriginalLocation(Location location) {
        assertNotNull(location);
        assertSame(world, location.getWorld());
        assertEquals(-12.25D, location.getX(), 0.0D);
        assertEquals(81.5D, location.getY(), 0.0D);
        assertEquals(6.75D, location.getZ(), 0.0D);
        assertEquals(135.5F, location.getYaw(), 0.0F);
        assertEquals(-25.25F, location.getPitch(), 0.0F);
    }

    private static void assertOriginalItems(ItemStack[] contents, ItemStack[] armor, ItemStack cursor) {
        assertEquals(36, contents.length);
        assertItem(contents[0], Material.STONE, 7, 1);
        assertItem(contents[35], Material.BREAD, 3, 0);
        for (int slot = 1; slot < 35; slot++) {
            assertNull("Inventory slot " + slot, contents[slot]);
        }
        assertEquals(4, armor.length);
        assertItem(armor[0], Material.IRON_BOOTS, 1, 12);
        assertNull(armor[1]);
        assertNull(armor[2]);
        assertItem(armor[3], Material.IRON_HELMET, 1, 8);
        assertItem(cursor, Material.APPLE, 2, 0);
    }

    private static void assertItem(ItemStack item, Material material, int amount, int durability) {
        assertNotNull(item);
        assertEquals(material, item.getType());
        assertEquals(amount, item.getAmount());
        assertEquals(durability, item.getDurability());
    }
}
