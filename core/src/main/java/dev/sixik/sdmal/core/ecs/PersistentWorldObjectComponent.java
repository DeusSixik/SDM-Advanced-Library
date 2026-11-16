package dev.sixik.sdmal.core.ecs;

import net.minecraft.nbt.CompoundTag;

/** Component whose state can be recreated from a registered type and a versioned NBT payload. */
public interface PersistentWorldObjectComponent extends WorldObjectComponent {
    /**
     * Writes state into a compound which may already contain unknown fields from a previous save.
     * @param nbt destination payload, non-null
     */
    void writeNbt(CompoundTag nbt);

    /**
     * Reads current-version state before ownership and lifecycle callbacks are installed.
     * @param nbt current-version payload owned by this load operation, non-null
     */
    void readNbt(CompoundTag nbt);

    /**
     * Declares the persisted component schema version.
     * @return non-negative, stable schema version; initially one
     */
    default int dataVersion() { return 1; }

    /**
     * Migrates an older payload into the current schema without accessing a world or owner.
     * Future versions are never passed here. Override when incrementing dataVersion.
     * @param savedVersion non-negative older schema version
     * @param nbt independent copy of the previous payload, non-null
     * @return current-version payload, non-null
     * @throws WorldObjectPersistenceException if no migration is implemented
     */
    default CompoundTag migrateNbt(int savedVersion, CompoundTag nbt) {
        throw new WorldObjectPersistenceException("No migration from component version " + savedVersion + " to " + dataVersion());
    }
}
