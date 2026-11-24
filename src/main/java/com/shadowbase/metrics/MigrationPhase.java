package com.shadowbase.metrics;

public enum MigrationPhase {
    PROVISIONING,
    TRIGGER_SYNC,
    BACKFILLING,
    VERIFYING,
    CUTOVER,
    COMPLETED,
    ROLLED_BACK,
    FAILED
}
