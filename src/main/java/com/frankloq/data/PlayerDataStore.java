package com.frankloq.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.slf4j.Logger;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class PlayerDataStore {
	private static final String FILE_NAME = "hardcoreworldreset.data.json";

	private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
	private final Path dataFile;
	private final Logger logger;
	private final Map<UUID, PlayerRecord> playerRecords = new LinkedHashMap<>();
	private boolean loadFailed;

	public PlayerDataStore(Path configDirectory, Logger logger) {
		this.dataFile = configDirectory.resolve(FILE_NAME);
		this.logger = logger;
	}

	public void load() {
		playerRecords.clear();
		loadFailed = false;

		if (!Files.exists(dataFile)) {
			logger.info("Player data file does not exist yet: {}", dataFile);
			return;
		}

		try (Reader reader = Files.newBufferedReader(dataFile)) {
			PlayerDataFile data = gson.fromJson(reader, PlayerDataFile.class);
			if (data == null || data.players == null) {
				throw new IllegalStateException("Expected a JSON object containing a players array.");
			}

			for (PlayerRecord record : data.players) {
				if (record == null || record.getUuid() == null || record.getUuid().isBlank()) {
					throw new IllegalStateException("A player data record is missing its UUID.");
				}

				try {
					UUID uuid = UUID.fromString(record.getUuid().trim());
					record.setUuid(uuid);
					record.setName(record.getName() == null ? "" : record.getName());
					playerRecords.put(uuid, record);
				} catch (IllegalArgumentException e) {
					throw new IllegalStateException("A player data record has an invalid UUID: " + record.getUuid(), e);
				}
			}

			logger.info("Loaded player data: {} record(s).", playerRecords.size());
		} catch (Exception e) {
			playerRecords.clear();
			loadFailed = true;
			logger.error("Failed to load player data file '{}'. No players will be treated as exempt this run, and the broken file will not be overwritten automatically.", dataFile, e);
		}
	}

	public boolean isAvailable() {
		return !loadFailed;
	}

	public Optional<PlayerRecord> find(UUID uuid) {
		return Optional.ofNullable(playerRecords.get(uuid));
	}

	public List<PlayerRecord> getRecords() {
		return List.copyOf(playerRecords.values());
	}

	public boolean addOrUpdateResetExemption(UUID uuid, String name) {
		PlayerRecord record = playerRecords.get(uuid);
		if (record == null) {
			playerRecords.put(uuid, new PlayerRecord(uuid, name, true));
			return true;
		}

		boolean changed = !record.isResetExempt() || !Objects.equals(record.getName(), name);
		record.setUuid(uuid);
		record.setName(name);
		record.setResetExempt(true);
		return changed;
	}

	public boolean remove(UUID uuid) {
		return playerRecords.remove(uuid) != null;
	}

	public boolean refreshName(UUID uuid, String name) {
		PlayerRecord record = playerRecords.get(uuid);
		if (record == null || Objects.equals(record.getName(), name)) {
			return false;
		}

		record.setName(name);
		save();
		return true;
	}

	public boolean save() {
		if (loadFailed) {
			logger.error("Refusing to overwrite malformed player data file '{}'. Fix it and restart the server.", dataFile);
			return false;
		}

		PlayerDataFile data = new PlayerDataFile();
		data.players = playerRecords.values().stream()
				.sorted(Comparator.comparing(PlayerRecord::getUuid))
				.toList();

		try {
			if (dataFile.getParent() != null) {
				Files.createDirectories(dataFile.getParent());
			}
			try (Writer writer = Files.newBufferedWriter(dataFile)) {
				gson.toJson(data, writer);
			}
			logger.info("Saved player data: {} record(s).", data.players.size());
			return true;
		} catch (Exception e) {
			logger.error("Failed to save player data file '{}'!", dataFile, e);
			return false;
		}
	}
}
