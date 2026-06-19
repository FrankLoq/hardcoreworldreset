package com.frankloq;

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
import net.minecraft.world.GameMode;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static com.mojang.brigadier.arguments.IntegerArgumentType.integer;
import static com.mojang.brigadier.arguments.IntegerArgumentType.getInteger;
import static net.minecraft.server.command.CommandManager.argument;
import static com.mojang.brigadier.arguments.BoolArgumentType.bool;
import static com.mojang.brigadier.arguments.BoolArgumentType.getBool;

public class HardcoreWorldReset implements ModInitializer {

	public static final String MOD_ID = "hardcoreworldreset";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static boolean resetInProgress = false;
	private static int limboCountdownTicks = -1;
	private static boolean modEnabled = true;
	public static boolean reuseSeed = false; // Reuse the same seed for each reset.
	private static boolean scheduledResetActive = false; // Flag to indicate if a reset is currently scheduled
	private static int scheduledResetTicks = -1; // scheduled reset
	private static int initialScheduledMinutes = -1;// Store the initial minutes for accurate time remaining display
	private static boolean alwaysShowActionBar = false;
	private static int actionBarDisplayTicks = 0; // Tracks the 5-second popup
	private static final java.util.Map<net.minecraft.server.network.ServerPlayerEntity, Integer> rescueQueue = new java.util.HashMap<>();

	public static boolean isModEnabled() { return modEnabled; }

	public static boolean cancelCountdown(MinecraftServer server) {
		boolean stopped = false;

		// Stop death countdown
		if (resetInProgress && limboCountdownTicks > 0) {
			resetInProgress = false;
			limboCountdownTicks = -1;
			WorldResetManager.unlockCountdown();
			stopped = true;
		}

		// Stop scheduled countdown
		if (scheduledResetActive || initialScheduledMinutes > 0) {
			scheduledResetActive = false;
			scheduledResetTicks = -1;
			initialScheduledMinutes = -1;
			stopped = true;
		}

		return stopped;
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

					// stopCountdown (Aborts any active reset without turning off the mod)
					.then(literal("stopCountdown")
							.requires(source -> source.hasPermissionLevel(2)) // Makes command require op status
							.executes(context -> {
								if (cancelCountdown(context.getSource().getServer())) {
									context.getSource().getServer().getPlayerManager().broadcast(
											Text.literal("§a[Reset] §7Countdown aborted! The world is safe."), false
									);
								} else {
									context.getSource().sendError(Text.literal("§c[Reset] §7There is no active countdown to stop!"));
								}
								return 1;
							}))

					// 2. off (Disables the mod and aborts any active reset)
					.then(literal("off")
							.requires(source -> source.hasPermissionLevel(2))
							.executes(context -> {
								modEnabled = false;
								boolean stopped = cancelCountdown(context.getSource().getServer());

								if (stopped) {
									context.getSource().getServer().getPlayerManager().broadcast(
											Text.literal("§c[Reset] §7Mod disabled and active reset aborted."), false
									);
								} else {
									context.getSource().getServer().getPlayerManager().broadcast(
											Text.literal("§c[Reset] §7Mod disabled. Deaths will no longer reset the world."), false
									);
								}
								return 1;
							}))

					// 3. on (Enables the mod)
					.then(literal("on")
							.requires(source -> source.hasPermissionLevel(2))
							.executes(context -> {
								modEnabled = true;
								context.getSource().getServer().getPlayerManager().broadcast(
										Text.literal("§a[Reset] §7Mod enabled. Hardcore resets are active!"), false
								);
								return 1;
							}))
					// 4. startTimer (Sets a timer in minutes)
					.then(literal("startTimer")
							.requires(source -> source.hasPermissionLevel(2))
							.then(argument("minutes", integer(1))
									.executes(context -> {
										int mins = getInteger(context, "minutes");

										// Save the initial time into memory
										initialScheduledMinutes = mins;

										// 20 ticks = 1 second. 60 seconds = 1 minute.
										scheduledResetTicks = mins * 60 * 20;
										scheduledResetActive = true;
										actionBarDisplayTicks = 100;

										context.getSource().getServer().getPlayerManager().broadcast(
												Text.literal("§e[Reset] §7World reset scheduled for§c " + mins + " §7minutes."), false
										);
										return 1;
									})))
					// 5. Show time remaining on scheduled reset
					.then(literal("timeRemaining")
							.executes(context -> {
								if (scheduledResetActive) {
									int secondsLeft = scheduledResetTicks / 20;
									int mins = secondsLeft / 60;
									secondsLeft = secondsLeft % 60;
									context.getSource().getServer().getPlayerManager().broadcast(
											Text.literal("§7Time until scheduled reset: §c" + mins + " §7minute(s) and §c" + secondsLeft + " §7second(s)."), false
									);
								} else {
									context.getSource().getServer().getPlayerManager().broadcast(
											Text.literal("§cThere isn't any currently active timer."), false
									);
								}
								return 1;
							}))

					// 6. reuseSameSeed (Toggles on/off the seed reuse feature in-game)
					.then(literal("reuseSameSeed")
							.requires(source -> source.hasPermissionLevel(2))
							.then(argument("value", bool())
									.executes(context -> {
										boolean value = getBool(context, "value");

										// 1. Update the live RAM
										reuseSeed = value;

										// 2. Save it permanently to the physical file
										saveConfig();

										context.getSource().getServer().getPlayerManager().broadcast(
												Text.literal("§a[Reset] §7reuse-same-seed set to: §e" + value), false
										);
										return 1;
									})))

					// 7. alwaysShowActionBar (Toggles the permanent action bar)
					.then(literal("alwaysShowActionBar")
							.requires(source -> source.hasPermissionLevel(2))
							.then(argument("value", bool())
									.executes(context -> {
										boolean value = getBool(context, "value");

										alwaysShowActionBar = value;
										saveConfig();

										context.getSource().getServer().getPlayerManager().broadcast(
												Text.literal("§a[Reset] §7always-show-action-bar has been set to: §e" + value), false
										);
										return 1;
									})))
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
		// Scheduled reset logic
		if (scheduledResetActive && scheduledResetTicks > 0) {
			scheduledResetTicks--;

			// Handle action bar tick countdown (runs every tick)
			if (!alwaysShowActionBar && actionBarDisplayTicks > 0) {
				actionBarDisplayTicks--;
			}

			// Only run checks once per second (every 20 ticks) to save performance
			if ((scheduledResetTicks + 1) % 20 == 0) {
				int secondsLeft = scheduledResetTicks / 20;

				// Warnings
				if (secondsLeft == 600) {
					server.getPlayerManager().broadcast(Text.literal("§7The world will be erased in exactly §c10 §7minutes!"), false);
					actionBarDisplayTicks = 120;
				} else if (secondsLeft == 300) {
					server.getPlayerManager().broadcast(Text.literal("§7The world will be erased in exactly §c5 §7minutes!"), false);
					actionBarDisplayTicks = 120;
				} else if (secondsLeft == 60) {
					server.getPlayerManager().broadcast(Text.literal("§7The world will be erased in exactly §c1 §7minute!"), false);
					actionBarDisplayTicks = 120;
				} else if (secondsLeft <= 5 && secondsLeft > 0) {
					server.getPlayerManager().broadcast(Text.literal("§7Erasing in §c" + secondsLeft + "§7..."), false);
				}

				// Action bar timer logic
				if (secondsLeft > 5) {
					if (alwaysShowActionBar || actionBarDisplayTicks > 0) {
						// Calculate minutes and remaining seconds for a clean 00:00 format
						int displayMins = secondsLeft / 60;
						int displaySecs = secondsLeft % 60;
						String timerText = String.format("§eReset in: %02d:%02d", displayMins, displaySecs);

						// Send to every player's Action Bar (the 'true' makes it go above the hotbar)
						for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
							p.sendMessage(Text.literal(timerText), true);
						}
					}
				}
			}

			// Trigger Reset
			if (scheduledResetTicks == 0) {
				scheduledResetActive = false;

				// Try to lock the reset (prevents double-resets if someone dies at the exact same millisecond)
				if (WorldResetManager.tryLockCountdown()) {
					executeLimboTeleport(server);
				}
			}
		}

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
									spawnPos.getY() + 1.0,
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

		// Clear entities safely
		LOGGER.info("Clearing overworld entities to prevent chunk-holder crash...");
		ServerWorld overworld = server.getWorld(World.OVERWORLD);
		if (overworld != null) {
			// Create a safe temporary list
			java.util.List<net.minecraft.entity.Entity> entitiesToRemove = new java.util.ArrayList<>();

			// Gather everything that isn't a player
			for (net.minecraft.entity.Entity entity : overworld.iterateEntities()) {
				if (entity != null && !(entity instanceof ServerPlayerEntity)) {
					entitiesToRemove.add(entity);
				}
			}

			// Delete them safely outside the main iteration loop
			for (net.minecraft.entity.Entity entity : entitiesToRemove) {
				entity.discard();
			}
		}

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

		// Restarting the timer
		if (initialScheduledMinutes > 0) {
			// Reset the clock to the maximum time
			scheduledResetTicks = initialScheduledMinutes * 60 * 20;
			scheduledResetActive = true;

			// Show the action bar for 5 seconds when the new loop starts
			actionBarDisplayTicks = 100;

			// Let the players know the clock has started
			server.getPlayerManager().broadcast(
					Text.literal("§e[Reset] §7The clock is ticking! Next reset in §c" + initialScheduledMinutes + "§7 minutes."),
					false
			);
		}
	}

	public static void loadConfig() {
		try {
			// This gets the standard .minecraft/config folder
			java.nio.file.Path configDir = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir();
			java.nio.file.Path configFile = configDir.resolve("hardcoreworldreset.properties");
			java.util.Properties props = new java.util.Properties();

			if (java.nio.file.Files.exists(configFile)) {
				// If config exists, read it
				try (java.io.InputStream in = java.nio.file.Files.newInputStream(configFile)) {
					props.load(in);
					String reuse = props.getProperty("reuse-same-seed", "true");
					reuseSeed = Boolean.parseBoolean(reuse);
					String showBar = props.getProperty("always-show-action-bar", "false");
					alwaysShowActionBar = Boolean.parseBoolean(showBar);

					LOGGER.info("Loaded config: reuse-same-seed = " + reuseSeed + ", always-show-action-bar = " + alwaysShowActionBar);
				}
			} else {
				// If it doesn't exist, create it with the default set to false
				props.setProperty("reuse-same-seed", "false");
				try (java.io.OutputStream out = java.nio.file.Files.newOutputStream(configFile)) {
					props.store(out, "Hardcore World Reset Configuration");
					LOGGER.info("Generated default config file.");
				}
			}
		} catch (Exception e) {
			LOGGER.error("Failed to load or generate config file", e);
		}
	}

	public static void saveConfig() {
		try {
			java.nio.file.Path configDir = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir();
			java.nio.file.Path configFile = configDir.resolve("hardcoreworldreset.properties");
			java.util.Properties props = new java.util.Properties();

			props.setProperty("reuse-same-seed", String.valueOf(reuseSeed));
			props.setProperty("always-show-action-bar", String.valueOf(alwaysShowActionBar));

			try (java.io.OutputStream out = java.nio.file.Files.newOutputStream(configFile)) {
				props.store(out, "Hardcore World Reset Configuration");
				LOGGER.info("Saved config: reuse-same-seed = " + reuseSeed);
			}
		} catch (Exception e) {
			LOGGER.error("Failed to save config file!", e);
		}
	}
}