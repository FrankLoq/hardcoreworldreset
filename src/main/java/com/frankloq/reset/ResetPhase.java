package com.frankloq.reset;

// Tracks which phase of the world reset we are currently in.
public enum ResetPhase {
    IDLE,
    WAITING_FOR_LIMBO,
    UNLOADING,
    DELETING,
    REGENERATING,
    DONE
}