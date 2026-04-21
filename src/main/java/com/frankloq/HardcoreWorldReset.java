package com.frankloq;

import com.frankloq.LimboDimension;
import com.frankloq.reset.PlayerRespawner;
import com.frankloq.reset.WorldResetManager;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import static net.minecraft.server.command.CommandManager.literal;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.network.packet.s2c.play.GameStateChangeS2CPacket;
import net.minecraft.network.packet.s2c.play.HealthUpdateS2CPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameMode;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HardcoreWorldReset implements ModInitializer {

	public static final String MOD_ID = "hardcoreworldreset";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static boolean resetInProgress = false;
	private static int limboCountdownTicks = -1;
	private static boolean modEnabled = true;
	private static final java.util.Map<net.minecraft.server.network.ServerPlayerEntity, Integer> rescueQueue = new java.util.HashMap<>();

	public static boolean isModEnabled() { return modEnabled; }

	public static boolean cancelCountdown(MinecraftServer server) {
		if (resetInProgress && limboCountdownTicks > 0) {
			resetInProgress = false;
			limboCountdownTicks = -1;
			WorldResetManager.unlockCountdown();

			return true;
		}
		return false;
	}

	@Override
	public void onInitialize() {
		LOGGER.info("HardcoreWorldReset initialized.");
		ServerTickEvents.END_SERVER_TICK.register(this::onServerTick);

		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			WorldResetManager.guaranteeLevelDatOnShutdown(server);
		});

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			dispatcher.register(literal("hwr")
					.requires(source -> source.hasPermissionLevel(2)) // Makes command require op status

					// stopCountdown (Aborts any active reset without turning off the mod)
					.then(literal("stopCountdown")
							.executes(context -> {
								if (cancelCountdown(context.getSource().getServer())) {
									context.getSource().getServer().getPlayerManager().broadcast(
											Text.literal("§aCountdown aborted! The world is safe."), false
									);
								} else {
									context.getSource().sendError(Text.literal("§cThere is no active countdown to stop!"));
								}
								return 1;
							}))

					// 2. off (Disables the mod and aborts any active reset)
					.then(literal("off")
							.executes(context -> {
								modEnabled = false;
								boolean stopped = cancelCountdown(context.getSource().getServer());

								if (stopped) {
									context.getSource().getServer().getPlayerManager().broadcast(
											Text.literal("§cEmergency Stop! Mod disabled and active reset aborted."), false
									);
								} else {
									context.getSource().getServer().getPlayerManager().broadcast(
											Text.literal("§cMod disabled. Deaths will no longer reset the world."), false
									);
								}
								return 1;
							}))

					// 3. on (Enables the mod)
					.then(literal("on")
							.executes(context -> {
								modEnabled = true;
								context.getSource().getServer().getPlayerManager().broadcast(
										Text.literal("§aMod enabled. Hardcore resets are active!"), false
								);
								return 1;
							}))
			);
		});

		// Fix for player getting stuck in the Limbo if they leave after the DELETING phase
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			net.minecraft.server.network.ServerPlayerEntity player = handler.player;

			// Check if the player logging in is trapped in Limbo
			if (player.getServerWorld().getRegistryKey() == com.frankloq.LimboDimension.LIMBO_KEY) {
				// Give blindness so they don't see the void
				player.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(
						net.minecraft.entity.effect.StatusEffects.BLINDNESS, 40, 1, false, false, false
				));

				// Add them to the rescue queue with a 20 tick delay
				rescueQueue.put(player, 20);
				HardcoreWorldReset.LOGGER.info("Player " + player.getName().getString() + " detected in Limbo. Rescue arriving in 1 second...");
			}
		});
	}

	private void onServerTick(MinecraftServer server) {
		// Process rescue queue
		if (!rescueQueue.isEmpty()) {
			java.util.Iterator<java.util.Map.Entry<net.minecraft.server.network.ServerPlayerEntity, Integer>> iterator = rescueQueue.entrySet().iterator();

			while (iterator.hasNext()) {
				java.util.Map.Entry<net.minecraft.server.network.ServerPlayerEntity, Integer> entry = iterator.next();
				net.minecraft.server.network.ServerPlayerEntity p = entry.getKey();

				// Decrease timer
				entry.setValue(entry.getValue() - 1);

				if (entry.getValue() <= 0) {
					iterator.remove(); // Remove them from the queue

					if (!p.isDisconnected()) {
						ServerWorld overworld = server.getWorld(World.OVERWORLD);
						if (overworld != null) {
							net.minecraft.util.math.BlockPos spawnPos = overworld.getSpawnPos();

							// Wipe the player cache
							com.frankloq.reset.WorldInjectionUtils.wipePlayerState(p);

							// Teleport the player
							p.teleport(
									overworld,
									spawnPos.getX() + 0.5,
									spawnPos.getY(),
									spawnPos.getZ() + 0.5,
									0.0f,
									0.0f
							);

							HardcoreWorldReset.LOGGER.info("Successfully rescued & wiped offline player: " + p.getName().getString());
						}
					}
				}
			}
		}

		// Advance the world reset pipeline if it is running
		WorldResetManager.tick(server);

		if (!resetInProgress) {
			return;
		}

		if (limboCountdownTicks > 0) {
			limboCountdownTicks--;

			if (limboCountdownTicks % 20 == 0) {
				int secondsLeft = limboCountdownTicks / 20;
				if (secondsLeft > 0) {
					server.getPlayerManager().broadcast(
							Text.literal("§7Erasing the world in §c"
									+ secondsLeft
									+ "§7 second"
									+ (secondsLeft == 1 ? "" : "s")
									+ "..."),
							false
					);
				}
			}

			if (limboCountdownTicks == 0) {
				executeLimboTeleport(server);
			}
		}
	}

	public static void handlePlayerDeath(
			ServerPlayerEntity player,
			DamageSource damageSource) {

		LOGGER.info("Intercepting death for player: {}",
				player.getName().getString());

		// This happens for everyone who dies
		// Restore health server-side
		player.setHealth(20.0f);
		player.getHungerManager().setFoodLevel(20);
		player.getHungerManager().setSaturationLevel(5.0f);

		// Sync health to client
		player.networkHandler.sendPacket(
				new HealthUpdateS2CPacket(
						20.0f,
						player.getHungerManager().getFoodLevel(),
						player.getHungerManager().getSaturationLevel()
				)
		);

		// Switch to spectator
		player.changeGameMode(GameMode.SPECTATOR);
		player.networkHandler.sendPacket(
				new GameStateChangeS2CPacket(
						GameStateChangeS2CPacket.GAME_MODE_CHANGED,
						3.0f
				)
		);

		MinecraftServer server = player.getServer();
		if (server == null) return;

		if (WorldResetManager.tryLockCountdown()) {

			// Broadcast vanilla death message only for the first player
			Text deathMessage = damageSource.getDeathMessage(player);
			server.getPlayerManager().broadcast(deathMessage, false);

			resetInProgress = true;
			limboCountdownTicks = 5 * 20;

			server.getPlayerManager().broadcast(
					Text.literal("§7Erasing the world in §c5 §7seconds..."),
					false
			);

			LOGGER.info("World reset sequence started. Countdown: 5 seconds.");

		} else {
			// This runs for anyone else who dies while the countdown is already happening
			player.sendMessage(Text.literal("§eA world reset is already in progress. Joining The Limbo..."), false);
		}
	}

	private static void executeLimboTeleport(MinecraftServer server) {
		LOGGER.info("Teleporting all players to Limbo...");

		int successCount = 0;
		int failCount = 0;

		for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
			player.changeGameMode(GameMode.SPECTATOR);
			boolean success = LimboDimension.teleportToLimbo(player);

			if (success) {
				player.sendMessage(
						Text.literal("§7You have entered The Limbo. Please wait..."),
						false
				);
				successCount++;
			} else {
				failCount++;
			}
		}

		LOGGER.info("Limbo teleport complete. Success: {}, Failed: {}",
				successCount, failCount);

		if (failCount > 0) {
			server.getPlayerManager().broadcast(
					Text.literal("§cFailed to teleport all players to Limbo. Check logs."),
					false
			);
			resetInProgress = false;
			limboCountdownTicks = -1;
			return;
		}

		// All players are in Limbo, now begin the actual world reset
		limboCountdownTicks = -1;
		WorldResetManager.beginReset(server);
	}

	// Called by WorldResetManager when all phases are complete
	public static void onResetComplete(MinecraftServer server) {
		LOGGER.info("Reset complete. Respawning all players.");

		// Reset the flag so future deaths trigger a new reset
		resetInProgress = false;

		// Respawn all players into the fresh world
		PlayerRespawner.respawnAllPlayers(server);

		// Broadcasts the try counter
		server.getPlayerManager().broadcast(
				Text.literal("§7Try §c#" + WorldResetManager.getCurrentTry(server)),
				false
		);
	}
}