package com.frankloq.data;

import java.util.UUID;

public final class PlayerRecord {
	private String uuid;
	private String name;
	private boolean resetExempt;

	private PlayerRecord() {
	}

	public PlayerRecord(UUID uuid, String name, boolean resetExempt) {
		this.uuid = uuid.toString();
		this.name = name;
		this.resetExempt = resetExempt;
	}

	public String getUuid() {
		return uuid;
	}

	public void setUuid(UUID uuid) {
		this.uuid = uuid.toString();
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public boolean isResetExempt() {
		return resetExempt;
	}

	public void setResetExempt(boolean resetExempt) {
		this.resetExempt = resetExempt;
	}
}
