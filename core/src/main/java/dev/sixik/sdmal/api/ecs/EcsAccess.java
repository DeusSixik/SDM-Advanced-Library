package dev.sixik.sdmal.api.ecs;

import dev.sixik.sdmal.core.utils.ObjectArrayView;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Immutable world-wide component access declaration used to cache system dependency groups.
 */
public final class EcsAccess {
    /**
     * Empty declaration for systems without component resources.
     */
    public static final EcsAccess NONE = new EcsAccess(ObjectArrayView.of(), ObjectArrayView.of());

    /**
     * Read-only component resources, including compatible subtypes.
     */
    private final ObjectArrayView<Class<? extends Component>> reads;

    /**
     * Mutable component resources, including compatible subtypes.
     */
    private final ObjectArrayView<Class<? extends Component>> writes;

    /**
     * Restrictions known directly from the declared resource classes.
     */
    private final int flags;

    /**
     * Copies access declarations and caches their annotation restrictions.
     *
     * @param reads  read-only resources
     * @param writes mutable resources
     */
    private EcsAccess(
            List<Class<? extends Component>> reads,
            List<Class<? extends Component>> writes) {
        this.reads = ObjectArrayView.copyOf(reads);
        this.writes = ObjectArrayView.copyOf(writes);
        int resolved = 0;
        for (var type : reads) resolved |= EcsExecutionPolicy.flags(type);
        for (var type : writes) resolved |= EcsExecutionPolicy.flags(type);
        flags = resolved;
    }

    /**
     * Returns a declaration with additional read-only resources.
     *
     * @param types component classes, non-null
     * @return new immutable declaration
     */
    @SafeVarargs
    public final EcsAccess read(Class<? extends Component>... types) {
        return new EcsAccess(append(reads, types), writes);
    }

    /**
     * Returns a declaration with additional mutable resources.
     *
     * @param types component classes, non-null
     * @return new immutable declaration
     */
    @SafeVarargs
    public final EcsAccess write(Class<? extends Component>... types) {
        return new EcsAccess(reads, append(writes, types));
    }

    /**
     * Copies and deduplicates additional classes during registration, outside tick loops.
     *
     * @param original existing resource classes
     * @param extra    additional resource classes
     * @return independent deduplicated declaration list
     */
    private static List<Class<? extends Component>> append(
            List<Class<? extends Component>> original,
            Class<? extends Component>[] extra) {
        Objects.requireNonNull(extra, "types");
        var result = new ObjectArrayList<>(original);
        for (var type : extra)
            if (!result.contains(Objects.requireNonNull(type, "component class"))) result.add(type);
        return result;
    }

    /**
     * Tests read/write hazards, conservatively including related base classes and interfaces.
     *
     * @param other another system's declaration
     * @return true when at least one shared compatible resource is written
     */
    public boolean conflictsWith(EcsAccess other) {
        return overlaps(writes, other.reads)
                || overlaps(writes, other.writes)
                || overlaps(reads, other.writes);
    }

    /**
     * Checks declared resource type overlap.
     *
     * @param left  first resource set
     * @param right second resource set
     * @return true if resources can describe the same component instance
     */
    private static boolean overlaps(
            ObjectArrayView<Class<? extends Component>> left,
            ObjectArrayView<Class<? extends Component>> right) {
        Object[] leftTypes = left.elements();
        Object[] rightTypes = right.elements();
        for (int i = 0; i < leftTypes.length; i++) {
            var a = (Class<?>) leftTypes[i];
            for (int j = 0; j < rightTypes.length; j++) {
                var b = (Class<?>) rightTypes[j];
                if (a.isAssignableFrom(b)
                        || b.isAssignableFrom(a)
                        || a.isInterface()
                        || b.isInterface()) return true;
            }
        }
        return false;
    }

    /**
     * Checks whether a concrete component is covered by this declaration.
     *
     * @param type actual attached component class
     * @return true for matching read or write access
     */
    boolean matches(Class<?> type) {
        for (int i = 0; i < reads.size(); i++) if (reads.get(i).isAssignableFrom(type)) return true;
        for (int i = 0; i < writes.size(); i++)
            if (writes.get(i).isAssignableFrom(type)) return true;
        return false;
    }

    /**
     * Returns restrictions cached from declared classes.
     *
     * @return execution restriction bit mask
     */
    int flags() {
        return flags;
    }
}
