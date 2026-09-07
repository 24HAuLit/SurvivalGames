package com.hungergames;

import static org.junit.Assert.*;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.MockedStatic;

public class GameManagerTest {
    private MockedStatic<Bukkit> bukkit;
    private MockedStatic<PlayerSnapshot> snapshots;
    private HungerGamesPlugin plugin;
    private Logger logger;
    private YamlConfiguration config;
    private Arena arena;
    private GameManager manager;
    private Runnable ticker;
    private Location center;
    private final Map<UUID, PlayerSnapshot> captured = new HashMap<UUID, PlayerSnapshot>();
    // Represents durable records independently of GameManager's in-memory recovery state.
    private final Map<UUID, PlayerSnapshot> saved = new HashMap<UUID, PlayerSnapshot>();

    @Before
    public void setUp() {
        bukkit = mockStatic(Bukkit.class);
        snapshots = mockStatic(PlayerSnapshot.class);
        plugin = mock(HungerGamesPlugin.class);
        logger = mock(Logger.class);
        config = new YamlConfiguration();
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getLogger()).thenReturn(logger);
        config.set("game.min-players", 2);
        config.set("game.countdown-seconds", 2);
        config.set("game.grace-seconds", 2);
        config.set("game.deathmatch-after-seconds", 2);
        config.set("game.deathmatch-seconds", 2);
        config.set("game.ending-seconds", 2);
        config.set("game.deathmatch-players", 0);
        config.set("game.chest-refill-seconds", 0);

        World world = mock(World.class);
        center = new Location(world, 0, 64, 0);
        arena = mock(Arena.class);
        when(arena.getLobby()).thenReturn(new Location(world, 0, 70, 0));
        when(arena.getCenter()).thenReturn(center);
        when(arena.getRadius()).thenReturn(100.0);
        when(arena.getSpawns()).thenAnswer(invocation -> new ArrayList<Location>(Arrays.asList(
                new Location(world, 1, 64, 0), new Location(world, -1, 64, 0),
                new Location(world, 0, 64, 1))));
        when(arena.getValidationError(anyInt())).thenReturn(null);

        snapshots.when(() -> PlayerSnapshot.load(eq(plugin), any(UUID.class)))
                .thenAnswer(invocation -> saved.get(invocation.getArgument(1)));
        snapshots.when(() -> PlayerSnapshot.delete(eq(plugin), any(UUID.class)))
                .thenAnswer(invocation -> {
                    saved.remove(invocation.getArgument(1));
                    return true;
                });

        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        BukkitTask task = mock(BukkitTask.class);
        bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
        when(scheduler.runTaskTimer(eq(plugin), any(Runnable.class), eq(20L), eq(20L)))
                .thenReturn(task);
        manager = new GameManager(plugin, arena, mock(LootManager.class));
        ArgumentCaptor<Runnable> runnable = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).runTaskTimer(eq(plugin), runnable.capture(), eq(20L), eq(20L));
        ticker = runnable.getValue();
    }

    @After
    public void tearDown() {
        try {
            if (snapshots != null) snapshots.close();
        } finally {
            if (bukkit != null) bukkit.close();
        }
    }

    @Test
    public void countdownStartsAtConfiguredMinimumAndLeavingCancelsIt() {
        config.set("game.min-players", 3);
        Player alice = player("Alice");
        Player bob = player("Bob");
        Player charlie = player("Charlie");
        assertTrue(manager.join(alice));
        assertTrue(manager.join(bob));
        tick(3);
        assertPhase(GamePhase.WAITING, 0);

        assertTrue(manager.join(charlie));
        assertPhase(GamePhase.COUNTDOWN, 2);
        tick(1);
        assertPhase(GamePhase.COUNTDOWN, 1);
        manager.leave(charlie);
        assertPhase(GamePhase.WAITING, 0);
        assertEquals(2, manager.getPlayerCount());
        assertFalse(manager.isManaged(charlie));
        verify(snapshot(charlie)).restore(charlie);
        tick(3);
        assertPhase(GamePhase.WAITING, 0);
        verify(snapshot(alice), times(1)).prepareForMatch(alice);
        verify(logger).info("Countdown cancelled: not enough players.");

        assertTrue(manager.join(charlie));
        assertPhase(GamePhase.COUNTDOWN, 2);
        tick(2);
        assertPhase(GamePhase.GRACE, 2);
    }

    @Test
    public void forceStartRejectsOnePlayerEvenWhenConfiguredMinimumIsOne() {
        config.set("game.min-players", 1);
        Player alice = player("Alice");
        CommandSender sender = mock(CommandSender.class);
        assertEquals(2, manager.getMinimumPlayers());
        assertTrue(manager.join(alice));
        assertFalse(manager.forceStart(sender));
        tick(3);
        assertPhase(GamePhase.WAITING, 0);
        assertEquals(1, manager.getAliveCount());
        verify(sender).sendMessage(contains("At least 2 players must join."));
        verify(snapshot(alice), times(1)).prepareForMatch(alice);
        verify(logger, never()).info(contains("wins the Hunger Games"));
    }

    @Test
    public void schedulerAdvancesEveryPhaseAndDeathmatchTimeoutIsADraw() {
        Player alice = player("Alice");
        Player bob = player("Bob");
        assertTrue(manager.join(alice));
        assertTrue(manager.join(bob));
        assertPhase(GamePhase.COUNTDOWN, 2);
        tick(1);
        assertPhase(GamePhase.COUNTDOWN, 1);
        tick(1);
        assertPhase(GamePhase.GRACE, 2);
        assertTrue(manager.canPlay(alice));
        tick(1);
        assertPhase(GamePhase.GRACE, 1);
        tick(1);
        assertPhase(GamePhase.RUNNING, 2);
        tick(1);
        assertPhase(GamePhase.RUNNING, 1);
        tick(1);
        assertPhase(GamePhase.DEATHMATCH, 2);
        tick(1);
        assertPhase(GamePhase.DEATHMATCH, 1);
        tick(1);
        assertPhase(GamePhase.ENDING, 2);
        assertFalse(manager.canPlay(alice));
        assertEquals(2, manager.getAliveCount());
        verify(logger).info("The match ended in a draw!");
        verify(logger, never()).info(contains("wins the Hunger Games"));
        verify(snapshot(alice), never()).restore(alice);
        tick(1);
        assertPhase(GamePhase.ENDING, 1);
        tick(1);
        assertPhase(GamePhase.WAITING, 0);
        assertTrue(manager.isIdle());
        assertEquals(0, manager.getAliveCount());
        assertFalse(manager.isManaged(alice));
        assertFalse(manager.isManaged(bob));
        verify(snapshot(alice)).restore(alice);
        verify(snapshot(bob)).restore(bob);
        verify(alice).saveData();
        verify(bob).saveData();
        assertTrue(saved.isEmpty());
        tick(3);
        assertPhase(GamePhase.WAITING, 0);
        verify(snapshot(alice), times(1)).restore(alice);
    }

    @Test
    public void emptyQueueCannotStartOrAnnounceAWinner() {
        Player alice = player("Alice");
        Player bob = player("Bob");
        assertTrue(manager.join(alice));
        assertTrue(manager.join(bob));
        manager.leave(alice);
        manager.leave(bob);
        assertTrue(manager.isIdle());
        assertEquals(0, manager.getAliveCount());
        assertFalse(manager.forceStart(mock(CommandSender.class)));
        tick(10);
        assertPhase(GamePhase.WAITING, 0);
        verify(logger, never()).info(contains("wins the Hunger Games"));
        verify(logger, never()).info("The match ended in a draw!");
        assertTrue(saved.isEmpty());
    }

    @Test
    public void quittingAwardsWinnerButRetainsQuittersRecoveryUntilNextLogin() {
        Player alice = player("Alice");
        Player bob = player("Bob");
        PlayerSnapshot original = snapshot(alice);
        UUID id = alice.getUniqueId();
        assertTrue(manager.join(alice));
        assertTrue(manager.join(bob));
        tick(2);
        assertPhase(GamePhase.GRACE, 2);

        // Quit events may still report online; disconnect must not restore even then.
        manager.disconnect(alice);
        when(alice.isOnline()).thenReturn(false);
        bukkit.when(() -> Bukkit.getPlayer(id)).thenReturn(null);
        assertPhase(GamePhase.ENDING, 2);
        assertEquals(1, manager.getAliveCount());
        assertFalse(manager.isParticipant(alice));
        verify(logger).info("Bob wins the Hunger Games with 0 kill(s)!");
        verify(original, never()).restore(any(Player.class));
        verify(alice, never()).saveData();
        snapshots.verify(() -> PlayerSnapshot.delete(plugin, id), never());
        assertSame(original, saved.get(id));

        tick(2);
        assertTrue(manager.isIdle());
        assertSame(original, saved.get(id));
        assertEquals(1, saved.size());
        snapshots.verify(() -> PlayerSnapshot.delete(plugin, id), never());
        Player reconnected = player("Alice");
        assertNotSame(alice, reconnected);
        assertEquals(id, reconnected.getUniqueId());
        manager.recover(reconnected);
        verify(original).restore(reconnected);
        verify(reconnected).saveData();
        snapshots.verify(() -> PlayerSnapshot.load(plugin, id), times(2));
        snapshots.verify(() -> PlayerSnapshot.delete(plugin, id));
        assertFalse(manager.isManaged(reconnected));
        assertTrue(saved.isEmpty());
    }

    @Test
    public void snapshotIsSavedBeforePlayerIsPreparedOrTeleported() {
        Player alice = player("Alice");
        PlayerSnapshot original = snapshot(alice);
        assertTrue(manager.join(alice));
        InOrder order = inOrder(original, alice);
        order.verify(original).save(plugin, alice.getUniqueId());
        order.verify(original).prepareForMatch(alice);
        order.verify(alice).teleport(arena.getLobby());
        snapshots.verify(() -> PlayerSnapshot.capture(alice));
        assertSame(original, saved.get(alice.getUniqueId()));
        assertTrue(manager.isParticipant(alice));
    }

    @Test
    public void failedSnapshotSaveDoesNotPrepareOrAdmitPlayer() {
        Player alice = player("Alice");
        PlayerSnapshot original = snapshot(alice);
        UUID id = alice.getUniqueId();
        doReturn(false).when(original).save(plugin, id);
        assertFalse(manager.join(alice));
        assertTrue(manager.isIdle());
        assertFalse(manager.isManaged(alice));
        assertTrue(saved.isEmpty());
        verify(snapshot(alice), never()).prepareForMatch(any(Player.class));
        verify(alice, never()).teleport(any(Location.class));
        verify(snapshot(alice), never()).restore(any(Player.class));
        verify(alice).sendMessage(contains("Could not save your inventory safely"));
    }

    @Test
    public void recoveryRestoresThenSavesVanillaDataThenDeletesRecord() {
        Player alice = player("Alice");
        PlayerSnapshot original = snapshot(alice);
        UUID id = alice.getUniqueId();
        InOrder order = inOrder(original, alice);
        snapshots.when(() -> PlayerSnapshot.delete(plugin, id)).thenAnswer(invocation -> {
            // Checking at the static call proves deletion cannot precede either operation.
            order.verify(original).restore(alice);
            order.verify(alice).saveData();
            assertTrue(manager.isRecovering(alice));
            assertTrue(manager.isInternalTeleport(alice));
            saved.remove(id);
            return true;
        });
        saved.put(id, original);

        manager.recover(alice);
        snapshots.verify(() -> PlayerSnapshot.load(plugin, id));
        snapshots.verify(() -> PlayerSnapshot.delete(plugin, id));
        assertFalse(manager.isRecovering(alice));
        assertFalse(manager.isInternalTeleport(alice));
        assertFalse(manager.isManaged(alice));
        assertTrue(saved.isEmpty());
        manager.recover(alice);
        verify(original, times(1)).restore(alice);
        verify(alice, times(1)).saveData();
    }

    @Test
    public void restoreFailureRetainsRecordAndLocksJoiningUntilRetrySucceeds() {
        Player alice = player("Alice");
        PlayerSnapshot original = snapshot(alice);
        UUID id = alice.getUniqueId();
        saved.put(id, original);
        doThrow(new IllegalStateException("restore failed")).when(original).restore(alice);
        manager.recover(alice);
        assertRecoveryLocked(alice);
        assertFalse(manager.join(alice));
        assertRecoveryLocked(alice);
        verify(alice, never()).saveData();
        snapshots.verify(() -> PlayerSnapshot.delete(plugin, id), never());
        verify(original, never()).prepareForMatch(alice);
        assertSame(original, saved.get(id));

        doNothing().when(original).restore(alice);
        manager.recover(alice);
        assertFalse(manager.isManaged(alice));
        assertTrue(saved.isEmpty());
        verify(alice).saveData();
    }

    @Test
    public void saveDataFailureRetainsRecordAndLocksJoiningUntilRetrySucceeds() {
        Player alice = player("Alice");
        PlayerSnapshot original = snapshot(alice);
        UUID id = alice.getUniqueId();
        saved.put(id, original);
        doThrow(new IllegalStateException("player data write failed")).when(alice).saveData();
        manager.recover(alice);
        assertRecoveryLocked(alice);
        assertFalse(manager.join(alice));
        assertRecoveryLocked(alice);
        verify(original, times(2)).restore(alice);
        snapshots.verify(() -> PlayerSnapshot.delete(plugin, id), never());
        assertSame(original, saved.get(id));

        doNothing().when(alice).saveData();
        manager.recover(alice);
        assertFalse(manager.isManaged(alice));
        assertTrue(saved.isEmpty());
        snapshots.verify(() -> PlayerSnapshot.delete(plugin, id));
    }

    @Test
    public void deleteFailureKeepsRecoveryLockedUntilCleanupRetrySucceeds() {
        Player alice = player("Alice");
        PlayerSnapshot original = snapshot(alice);
        UUID id = alice.getUniqueId();
        snapshots.when(() -> PlayerSnapshot.delete(plugin, id)).thenReturn(false, false)
                .thenAnswer(invocation -> {
                    saved.remove(id);
                    return true;
                });
        saved.put(id, original);
        manager.recover(alice);
        assertRecoveryLocked(alice);
        assertFalse(manager.join(alice));
        assertRecoveryLocked(alice);
        assertSame(original, saved.get(id));
        verify(original, times(2)).restore(alice);
        verify(alice, times(2)).saveData();
        verify(original, never()).prepareForMatch(alice);

        manager.recover(alice);
        assertFalse(manager.isManaged(alice));
        assertTrue(saved.isEmpty());
        snapshots.verify(() -> PlayerSnapshot.delete(plugin, id), times(3));
    }

    @Test
    public void unreadableRecoveryLocksPlayerWithoutCapturingNewState() {
        Player alice = player("Alice");
        PlayerSnapshot original = snapshot(alice);
        UUID id = alice.getUniqueId();
        saved.put(id, original);
        snapshots.when(() -> PlayerSnapshot.load(plugin, id))
                .thenThrow(new IllegalStateException("invalid recovery record"));
        manager.recover(alice);
        assertRecoveryLocked(alice);
        assertFalse(manager.join(alice));
        assertRecoveryLocked(alice);
        snapshots.verify(() -> PlayerSnapshot.capture(alice), never());
        snapshots.verify(() -> PlayerSnapshot.delete(plugin, id), never());
        verify(original, never()).restore(alice);
        verify(alice, never()).saveData();
        assertSame(original, saved.get(id));

        snapshots.when(() -> PlayerSnapshot.load(plugin, id)).thenReturn(original);
        manager.recover(alice);
        verify(original).restore(alice);
        assertFalse(manager.isManaged(alice));
        assertTrue(saved.isEmpty());
    }

    @Test
    public void staleRespawnTokenCannotAlterAnEliminatedPlayerInANewMatch() {
        Player alice = player("Alice");
        Player bob = player("Bob");
        Player charlie = player("Charlie");
        assertTrue(manager.join(alice));
        assertTrue(manager.join(bob));
        assertTrue(manager.join(charlie));
        tick(2);
        Object oldToken = manager.getParticipationToken(alice);
        assertNotNull(oldToken);
        manager.eliminate(alice, bob);
        manager.leave(alice);
        manager.stop();
        assertTrue(manager.isIdle());

        assertTrue(manager.join(alice));
        assertTrue(manager.join(bob));
        assertTrue(manager.join(charlie));
        tick(2);
        Object newToken = manager.getParticipationToken(alice);
        assertNotNull(newToken);
        assertNotSame(oldToken, newToken);
        manager.eliminate(alice, bob);
        assertFalse(manager.isAlive(alice));
        assertPhase(GamePhase.GRACE, 2);
        PlayerInventory inventory = alice.getInventory();
        clearInvocations(alice, inventory, snapshot(alice));

        manager.afterRespawn(alice, oldToken);
        assertSame(newToken, manager.getParticipationToken(alice));
        assertEquals(3, manager.getPlayerCount());
        assertEquals(2, manager.getAliveCount());
        verifyNoInteractions(inventory, snapshot(alice));
        verify(alice, never()).getInventory();
        verify(alice, never()).setGameMode(any(GameMode.class));
        verify(alice, never()).setFireTicks(anyInt());
        verify(alice, never()).teleport(any(Location.class));
        verify(alice, never()).sendMessage(anyString());

        manager.afterRespawn(alice, newToken);
        verify(inventory).clear();
        verify(inventory).setArmorContents(aryEq(new ItemStack[4]));
        verify(alice).setGameMode(GameMode.SPECTATOR);
        verify(alice).setFireTicks(0);
        verify(alice).teleport(center);
        assertSame(newToken, manager.getParticipationToken(alice));
    }

    private Player player(String name) {
        UUID id = UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(id);
        when(player.getName()).thenReturn(name);
        when(player.isOnline()).thenReturn(true);
        when(player.teleport(any(Location.class))).thenReturn(true);
        when(player.getLocation()).thenReturn(center.clone());
        when(player.getInventory()).thenReturn(mock(PlayerInventory.class));
        bukkit.when(() -> Bukkit.getPlayer(id)).thenReturn(player);
        PlayerSnapshot snapshot = mock(PlayerSnapshot.class);
        captured.put(id, snapshot);
        snapshots.when(() -> PlayerSnapshot.capture(player)).thenReturn(snapshot);
        when(snapshot.save(plugin, id)).thenAnswer(invocation -> {
            saved.put(id, snapshot);
            return true;
        });
        return player;
    }

    private PlayerSnapshot snapshot(Player player) {
        return captured.get(player.getUniqueId());
    }

    private void tick(int seconds) {
        for (int i = 0; i < seconds; i++) ticker.run();
    }

    private void assertPhase(GamePhase phase, int remaining) {
        assertEquals(phase, manager.getPhase());
        assertEquals(remaining, manager.getSecondsRemaining());
    }

    private void assertRecoveryLocked(Player player) {
        assertTrue(manager.isRecovering(player));
        assertTrue(manager.isManaged(player));
        assertFalse(manager.isParticipant(player));
        assertFalse(manager.isInternalTeleport(player));
    }
}
