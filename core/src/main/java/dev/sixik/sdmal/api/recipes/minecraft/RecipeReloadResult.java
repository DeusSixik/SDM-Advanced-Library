package dev.sixik.sdmal.api.recipes.minecraft;

import dev.sixik.sdmal.api.recipes.RecipeSnapshot;
import dev.sixik.sdmal.core.utils.ObjectArrayView;
import it.unimi.dsi.fastutil.objects.ObjectList;

import java.util.Collection;
import java.util.Objects;

/**
 * Immutable coverage report for one successful native rebuild. Unsupported ids are explicit,
 * including recipe types without adapters, custom matching subclasses, and unsupported ingredients.
 */
public final class RecipeReloadResult {
    /**
     * Complete prepared generation atomically published with this report.
     */
    private final RecipeSnapshot snapshot;
    /**
     * Sorted unsupported native ids with no exposed mutable backing storage.
     */
    private final ObjectList<String> unsupported;

    /**
     * Captures coverage without retaining a mutable caller collection.
     *
     * @param snapshot    complete prepared generation
     * @param unsupported ids not representable by installed adapters
     */
    public RecipeReloadResult(RecipeSnapshot snapshot, Collection<String> unsupported) {
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        var sorted = new it.unimi.dsi.fastutil.objects.ObjectArrayList<>(unsupported);
        sorted.sort(String::compareTo);
        this.unsupported = ObjectArrayView.copyOf(sorted).readOnly();
    }

    /**
     * @return prepared generation associated with exactly this report
     */
    public RecipeSnapshot snapshot() {
        return snapshot;
    }

    /**
     * @return immutable unsupported native recipe ids, sorted ascending
     */
    public ObjectList<String> unsupportedIds() {
        return unsupported;
    }
}
