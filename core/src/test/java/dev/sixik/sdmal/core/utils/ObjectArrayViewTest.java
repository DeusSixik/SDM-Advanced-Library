package dev.sixik.sdmal.core.utils;

import static org.junit.jupiter.api.Assertions.*;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Array ownership, null rejection, safe runtime array types and the protected public boundary. */
class ObjectArrayViewTest {
    /** All empty inputs reuse one immutable zero-length view. */
    @Test void factories_emptyInputs_reuseSharedView() {
        assertSame(ObjectArrayView.of(), ObjectArrayView.copyOf(List.of()));
        assertSame(ObjectArrayView.of(), ObjectArrayView.copyOf(new String[0]));
        assertSame(ObjectArrayView.of(), ObjectArrayView.wrapOwned(new String[0]));
    }

    /** Snapshot factories never alias the caller's mutable input array. */
    @Test void of_sourceArrayChanges_preservesSnapshot() {
        String[] source = {"a", "b"};
        var view = ObjectArrayView.of(source); source[0] = "changed";
        assertEquals(List.of("a", "b"), view);
        assertNotSame(source, view.elements());
        assertEquals(2, view.elements().length);
        assertThrows(UnsupportedOperationException.class, () -> view.set(0, "new"));
    }

    /** Collection snapshots work for erased Object[] storage without a hidden String[] cast. */
    @Test void copyOf_genericCollection_exposesObjectArrayWithoutClassCast() {
        var source = new ObjectArrayList<>(List.of("a", "b"));
        ObjectArrayView<String> view = ObjectArrayView.copyOf(source);
        source.clear();
        Object[] array = view.elements();
        assertArrayEquals(new Object[] {"a", "b"}, array);
        assertEquals("a", view.get(0));
        assertThrows(IndexOutOfBoundsException.class, () -> view.get(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> view.get(2));
    }

    /** Typed factories retain their concrete runtime array without copying it a second time. */
    @Test void copyOf_typedFactory_retainsRuntimeArrayType() {
        var view = ObjectArrayView.copyOf(List.of("a", "b"), String[]::new);
        assertInstanceOf(String[].class, view.elements());
        assertEquals(List.of("a", "b"), view);
    }

    /** Ownership transfer retains exact identity, allowing hot paths to avoid a redundant copy. */
    @Test void wrapOwned_exclusiveArray_retainsIdentityAndLength() {
        String[] owned = {"a", "b"};
        var view = ObjectArrayView.wrapOwned(owned);
        assertSame(owned, view.elements()); assertEquals(owned.length, view.size());
        assertSame(view.elements(), view.elements());
    }

    /** Cloning always owns different storage; shared elements remain shallow references. */
    @Test void clone_changesToRawClone_doNotModifyOriginal() {
        var source = ObjectArrayView.of("a", "b"); var clone = source.clone();
        assertEquals(source, clone); assertEquals(source.hashCode(), clone.hashCode());
        assertNotSame(source.elements(), clone.elements());
        clone.elements()[0] = "private clone change";
        assertEquals("a", source.get(0));
    }

    /** Public wrappers reject even no-op mutation paths and never expose the internal raw array. */
    @Test void readOnly_allMutationPaths_rejectsAndHidesRawStorage() {
        var view = ObjectArrayView.of("a", "b"); var exposed = view.readOnly();
        assertFalse(exposed instanceof ObjectArrayView<?>);
        assertThrows(UnsupportedOperationException.class, () -> exposed.add("c"));
        assertThrows(UnsupportedOperationException.class, () -> exposed.set(0, "c"));
        assertThrows(UnsupportedOperationException.class, () -> exposed.remove("missing"));
        assertThrows(UnsupportedOperationException.class, () -> exposed.removeIf(value -> false));
        assertThrows(UnsupportedOperationException.class, () -> exposed.sort(String::compareTo));
        assertThrows(UnsupportedOperationException.class, () -> exposed.replaceAll(value -> value));
        assertThrows(UnsupportedOperationException.class, () -> exposed.subList(0, 1).clear());
        Object[] copy = exposed.toArray(); copy[0] = "changed";
        assertEquals("a", view.get(0));
    }

    /** Replacements retain List.of/copyOf's rejection of nulls rather than silently accepting them. */
    @Test void factories_nullInputOrElement_rejects() {
        assertThrows(NullPointerException.class, () -> ObjectArrayView.of((String[]) null));
        assertThrows(NullPointerException.class, () -> ObjectArrayView.of("a", null));
        assertThrows(NullPointerException.class, () -> ObjectArrayView.copyOf(Arrays.asList("a", null)));
        assertThrows(NullPointerException.class, () -> ObjectArrayView.wrapOwned(new String[] {null}));
        assertThrows(NullPointerException.class, () -> ObjectArrayView.copyOf(List.of("a"), null));
    }
}
