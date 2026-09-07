package com.hungergames;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.io.File;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class ArenaTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private static final Server SERVER = mock(Server.class);
    private JavaPlugin plugin;
    private YamlConfiguration config;
    private File arenaFile;
    private World world;
    private World otherWorld;
    private Arena arena;

    @BeforeClass
    public static void installServer() {
        when(SERVER.getLogger()).thenReturn(Logger.getLogger(ArenaTest.class.getName()));
        when(SERVER.getName()).thenReturn("ArenaTest");
        when(SERVER.getVersion()).thenReturn("test");
        when(SERVER.getBukkitVersion()).thenReturn("1.8.8");
        // Bukkit permits installation only once; all tests reuse this server.
        Bukkit.setServer(SERVER);
    }

    @Before
    public void setUp() throws Exception {
        plugin = mock(JavaPlugin.class);
        config = new YamlConfiguration();
        when(plugin.getConfig()).thenReturn(config);
        File dataFolder = temporaryFolder.newFolder("plugin");
        // Mockito-inline supports Spigot 1.8.8's final getDataFolder() method.
        when(plugin.getDataFolder()).thenReturn(dataFolder);
        arenaFile = new File(dataFolder, "arenas.yml");
        world = mock(World.class);
        otherWorld = mock(World.class);
        when(world.getName()).thenReturn("arena");
        when(otherWorld.getName()).thenReturn("other");
        when(SERVER.getWorld("arena")).thenReturn(world);
        when(SERVER.getWorld("other")).thenReturn(otherWorld);
        arena = new Arena(plugin);
    }

    @Test
    public void missingSetupReportsLobbyThenCenterThenSpawns() {
        assertNull(arena.getLobby());
        assertNull(arena.getCenter());
        assertTrue(arena.getSpawns().isEmpty());
        assertError("Lobby", 2);
        arena.setLobby(location(0, 64, 0));
        assertError("Arena center", 2);
        arena.setCenter(location(0, 64, 0));
        assertError("at least 2 spawns; only 0", 2);
        assertFalse(arenaFile.length() == 0);
    }

    @Test
    public void completeSetupRoundTripsWorldCoordinatesAndRotation() {
        Location lobby = new Location(otherWorld, -12.25, 90.5, 8.75, 135.5f, -25.25f);
        Location center = new Location(world, 10.5, 65.25, -20.75, -45.5f, 12.5f);
        Location spawn = new Location(world, 13.5, 100.25, -16.75, 180.0f, -60.0f);
        config.set("arena.radius", 5);
        arena.setLobby(lobby);
        arena.setCenter(center);
        arena.addSpawn(spawn);

        Arena loaded = new Arena(plugin);
        assertLocation(lobby, loaded.getLobby());
        assertLocation(center, loaded.getCenter());
        assertEquals(1, loaded.getSpawns().size());
        assertLocation(spawn, loaded.getSpawns().get(0));
        assertTrue(loaded.isReady(1));
        assertNull(loaded.getValidationError(1));
        verify(plugin, never()).reloadConfig();
    }

    @Test
    public void settersAndGettersDoNotExposeMutableLocationsOrSpawnList() {
        Location lobby = location(1, 65, 2);
        Location center = location(0, 64, 0);
        Location spawn = location(3, 66, 4);
        arena.setLobby(lobby);
        arena.setCenter(center);
        arena.addSpawn(spawn);
        lobby.setX(999);
        center.setX(999);
        spawn.setX(999);
        assertEquals(1.0, arena.getLobby().getX(), 0.0);
        assertEquals(0.0, arena.getCenter().getX(), 0.0);
        assertEquals(3.0, arena.getSpawns().get(0).getX(), 0.0);
        assertNotSame(arena.getLobby(), arena.getLobby());
        assertNotSame(arena.getCenter(), arena.getCenter());
        assertNotSame(arena.getSpawns().get(0), arena.getSpawns().get(0));
        arena.getLobby().setX(999);
        arena.getCenter().setX(999);
        List<Location> returned = arena.getSpawns();
        returned.get(0).setX(999);
        returned.clear();
        assertEquals(1.0, arena.getLobby().getX(), 0.0);
        assertEquals(0.0, arena.getCenter().getX(), 0.0);
        assertEquals(1, arena.getSpawns().size());
        assertEquals(3.0, arena.getSpawns().get(0).getX(), 0.0);
        assertTrue(arena.isReady(1));
    }

    @Test
    public void clearingSpawnsPersistsWithoutRemovingLobbyOrCenter() {
        readyArena();
        arena.clearSpawns();
        Arena loaded = new Arena(plugin);
        assertTrue(loaded.getSpawns().isEmpty());
        assertNotNull(loaded.getLobby());
        assertNotNull(loaded.getCenter());
        assertTrue(loaded.isReady(0));
        assertFalse(loaded.isReady(1));
    }

    @Test
    public void containsUsesInclusiveHorizontalCircleAndRejectsWrongWorld() {
        config.set("arena.radius", 5);
        arena.setCenter(location(10, 64, -10));
        assertTrue(arena.contains(location(13, 10000, -6)));
        assertTrue(arena.contains(location(10, -10000, -10)));
        assertFalse(arena.contains(location(13.0001, 64, -6)));
        assertFalse(arena.contains(new Location(otherWorld, 10, 64, -10)));
        assertFalse(arena.contains(null));
        assertFalse(arena.contains(new Location(null, 10, 64, -10)));
        assertFalse(arena.contains(location(Double.NaN, 64, -10)));
        assertFalse(arena.contains(location(10, Double.POSITIVE_INFINITY, -10)));
    }

    @Test
    public void invalidSetterInputsAreRejectedWithoutChangingSavedSetup() {
        readyArena();
        List<Location> invalid = Arrays.asList(null, new Location(null, 0, 64, 0),
                location(Double.NaN, 64, 0), location(0, Double.POSITIVE_INFINITY, 0),
                location(0, 64, Double.NEGATIVE_INFINITY),
                new Location(world, 0, 64, 0, Float.NaN, 0),
                new Location(world, 0, 64, 0, 0, Float.POSITIVE_INFINITY));
        for (Location value : invalid) {
            assertThrows(IllegalArgumentException.class, () -> arena.setLobby(value));
            assertThrows(IllegalArgumentException.class, () -> arena.setCenter(value));
            assertThrows(IllegalArgumentException.class, () -> arena.addSpawn(value));
        }
        Arena loaded = new Arena(plugin);
        assertLocation(location(0, 64, 0), loaded.getLobby());
        assertLocation(location(0, 64, 0), loaded.getCenter());
        assertEquals(1, loaded.getSpawns().size());
        assertTrue(loaded.isReady(1));
    }

    @Test
    public void invalidRadiiPreventReadinessAndContainment() {
        readyArena();
        for (Object radius : Arrays.<Object>asList(0, -1, "5", Double.NaN,
                Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            config.set("arena.radius", radius);
            assertError("finite, positive", 1);
            assertFalse(arena.contains(location(0, 64, 0)));
        }
    }

    @Test
    public void radiusDefaultsAndReadsCurrentConfigWithoutReload() {
        readyArena();
        assertEquals(150.0, arena.getRadius(), 0.0);
        assertTrue(arena.contains(location(150, 64, 0)));
        config.set("arena.radius", 2.5);
        assertEquals(2.5, arena.getRadius(), 0.0);
        assertTrue(arena.contains(location(2.5, 64, 0)));
        assertFalse(arena.contains(location(2.5001, 64, 0)));
        config.set("arena.radius", null);
        assertEquals(150.0, arena.getRadius(), 0.0);
    }

    @Test
    public void minimumPlayersIsInclusiveAndNegativeMeansZero() {
        readyArena();
        assertTrue(arena.isReady(1));
        assertError("at least 2 spawns; only 1", 2);
        arena.clearSpawns();
        assertTrue(arena.isReady(0));
        assertTrue(arena.isReady(-1));
    }

    @Test
    public void wrongWorldSpawnIsInvalidEvenWhenNoPlayersAreRequired() {
        readyArena();
        arena.addSpawn(new Location(otherWorld, 0, 64, 0));
        assertError("Spawn 2 must be in the arena center's world", 0);
    }

    @Test
    public void spawnOnRadiusIsValidButOutsideIsNot() {
        readyArena();
        config.set("arena.radius", 5);
        arena.addSpawn(location(3, 999, 4));
        assertTrue(arena.isReady(2));
        arena.addSpawn(location(3, 64, 4.001));
        assertError("Spawn 3 is outside", 3);
    }

    @Test
    public void unloadedWorldsResolveAgainWithoutLosingPersistedPoints() {
        readyArena();
        when(SERVER.getWorld("arena")).thenReturn(null);
        Arena loaded = new Arena(plugin);
        assertNull(loaded.getLobby());
        assertNull(loaded.getCenter());
        assertTrue(loaded.getSpawns().isEmpty());
        assertFalse(loaded.contains(location(0, 64, 0)));
        assertFalse(loaded.isReady(1));
        when(SERVER.getWorld("arena")).thenReturn(world);
        assertNotNull(loaded.getLobby());
        assertNotNull(loaded.getCenter());
        assertEquals(1, loaded.getSpawns().size());
        assertTrue(loaded.isReady(1));
    }

    @Test
    public void unloadedSpawnIsOmittedFromGetterButStillFailsValidation() {
        readyArena();
        arena.addSpawn(new Location(otherWorld, 0, 64, 0));
        when(SERVER.getWorld("other")).thenReturn(null);
        assertEquals(1, arena.getSpawns().size());
        assertError("Spawn 2 is invalid or its world is not loaded", 2);
    }

    @Test
    public void malformedSerializedLobbyAndCenterAreNotAccepted() throws Exception {
        List<Object> invalid = new ArrayList<Object>();
        invalid.add("not a point");
        for (Object[] change : new Object[][] {
                {"world", " "}, {"world", 123}, {"x", null}, {"y", "64"},
                {"z", Double.NaN}, {"yaw", Double.POSITIVE_INFINITY}, {"pitch", Double.MAX_VALUE}
        }) {
            Map<String, Object> point = point();
            point.put((String) change[0], change[1]);
            invalid.add(point);
        }
        for (Object value : invalid) {
            YamlConfiguration yaml = validYaml();
            yaml.set("lobby", value);
            yaml.save(arenaFile);
            arena.reload();
            assertNull(arena.getLobby());
            assertError("Lobby", 1);
            yaml = validYaml();
            yaml.set("center", value);
            yaml.save(arenaFile);
            arena.reload();
            assertNull(arena.getCenter());
            assertError("Arena center", 1);
        }
    }

    @Test
    public void malformedSpawnContainerOrEntryCannotSilentlyBecomeValid() throws Exception {
        for (Object spawns : Arrays.<Object>asList("not a list",
                Arrays.asList(point(), "not a point"), Arrays.asList(point(), null))) {
            YamlConfiguration yaml = validYaml();
            yaml.set("spawns", spawns);
            yaml.save(arenaFile);
            arena.reload();
            assertFalse(arena.isReady(0));
            assertTrue(arena.getValidationError(0).contains("invalid"));
        }
    }

    @Test
    public void absentSerializedRotationDefaultsToZero() throws Exception {
        YamlConfiguration yaml = validYaml();
        yaml.save(arenaFile);
        arena.reload();
        assertLocation(location(0, 64, 0), arena.getLobby());
        assertLocation(location(0, 64, 0), arena.getCenter());
        assertLocation(location(0, 64, 0), arena.getSpawns().get(0));
        assertTrue(arena.isReady(1));
    }

    @Test
    public void malformedYamlThrowsAndPreservesPreviouslyLoadedSetup() throws Exception {
        readyArena();
        Files.write(arenaFile.toPath(), "lobby: [unterminated\n".getBytes(StandardCharsets.UTF_8));
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> arena.reload());
        assertTrue(error.getMessage().contains("Could not load arena setup"));
        assertNotNull(error.getCause());
        assertTrue(arena.isReady(1));
        assertEquals(1, arena.getSpawns().size());
    }

    private void readyArena() {
        arena.setLobby(location(0, 64, 0));
        arena.setCenter(location(0, 64, 0));
        arena.addSpawn(location(0, 64, 0));
    }

    private Location location(double x, double y, double z) {
        return new Location(world, x, y, z);
    }

    private void assertError(String text, int minimumPlayers) {
        assertFalse(arena.isReady(minimumPlayers));
        String error = arena.getValidationError(minimumPlayers);
        assertNotNull(error);
        assertTrue(error, error.contains(text));
    }

    private static void assertLocation(Location expected, Location actual) {
        assertNotNull(actual);
        assertSame(expected.getWorld(), actual.getWorld());
        assertEquals(expected.getX(), actual.getX(), 0.0);
        assertEquals(expected.getY(), actual.getY(), 0.0);
        assertEquals(expected.getZ(), actual.getZ(), 0.0);
        assertEquals(expected.getYaw(), actual.getYaw(), 0.0f);
        assertEquals(expected.getPitch(), actual.getPitch(), 0.0f);
    }

    private static Map<String, Object> point() {
        Map<String, Object> point = new LinkedHashMap<String, Object>();
        point.put("world", "arena");
        point.put("x", 0.0);
        point.put("y", 64.0);
        point.put("z", 0.0);
        return point;
    }

    private static YamlConfiguration validYaml() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("lobby", point());
        yaml.set("center", point());
        yaml.set("spawns", Arrays.asList(point()));
        return yaml;
    }
}
