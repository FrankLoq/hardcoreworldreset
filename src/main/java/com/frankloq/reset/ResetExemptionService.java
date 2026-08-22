package com.frankloq.reset;

import com.frankloq.data.PlayerDataStore;
import com.frankloq.data.PlayerRecord;
import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import org.slf4j.Logger;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public final class ResetExemptionService {
	private final PlayerDataStore playerDataStore;
	private final Logger logger;
	private boolean enabled;

	public ResetExemptionService(PlayerDataStore playerDataStore, Logger logger) {
		this.playerDataStore = playerDataStore;
		this.logger = logger;
	}

	public void loadPlayerData() {
		playerDataStore.load();
	}

	public boolean isPlayerDataAvailable() {
		return playerDataStore.isAvailable();
	}

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public int addExemptions(Collection<GameProfile> profiles) {
		int changed = 0;
		for (GameProfile profile : profiles) {
			UUID uuid = profile.getId();
			if (uuid == null) {
				continue;
			}

			String name = profile.getName() == null ? "" : profile.getName();
			if (playerDataStore.addOrUpdateResetExemption(uuid, name)) {
				changed++;
			}
		}

		if (changed > 0) {
			playerDataStore.save();
		}
		return changed;
	}

	public int removeExemptions(Collection<GameProfile> profiles) {
		int changed = 0;
		for (GameProfile profile : profiles) {
			UUID uuid = profile.getId();
			if (uuid != null && playerDataStore.remove(uuid)) {
				changed++;
			}
		}

		if (changed > 0) {
			playerDataStore.save();
		}
		return changed;
	}

	public boolean isExempt(ServerPlayerEntity player) {
		return enabled && playerDataStore.find(player.getUuid())
				.map(PlayerRecord::isResetExempt)
				.orElse(false);
	}

	public void refreshTrackedPlayerName(ServerPlayerEntity player) {
		String currentName = player.getName().getString();
		if (playerDataStore.refreshName(player.getUuid(), currentName)) {
			logger.info("Refreshed saved name for tracked player {}: {}", player.getUuid(), currentName);
		}
	}

	public List<String> listExemptions(MinecraftServer server) {
		return playerDataStore.getRecords().stream()
				.filter(PlayerRecord::isResetExempt)
				.sorted(Comparator.comparing(PlayerRecord::getUuid))
				.map(record -> {
					UUID uuid = UUID.fromString(record.getUuid());
					return resolvePlayerDisplayName(server, uuid, record) + " (" + uuid + ")";
				})
				.toList();
	}

	private String resolvePlayerDisplayName(MinecraftServer server, UUID uuid, PlayerRecord record) {
		ServerPlayerEntity onlinePlayer = server.getPlayerManager().getPlayer(uuid);
		if (onlinePlayer != null) {
			return onlinePlayer.getName().getString();
		}

		String cachedName = server.getUserCache().getByUuid(uuid)
				.map(GameProfile::getName)
				.filter(name -> name != null && !name.isBlank())
				.orElse(null);
		if (cachedName != null) {
			return cachedName;
		}

		if (record.getName() != null && !record.getName().isBlank()) {
			return record.getName();
		}
		return "unknown";
	}
}
