package dev.sixik.sdmal.core.utils;

import it.unimi.dsi.fastutil.objects.AbstractObjectList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import it.unimi.dsi.fastutil.objects.ObjectImmutableList;

import java.util.Collection;
import java.util.Objects;
import java.util.RandomAccess;
import java.util.function.IntFunction;

/**
 * Fixed-size array-backed view with null-rejecting snapshot factories and no list mutation methods.
 * The raw array is an internal read-only contract: callers must never modify it after publication.
 * Expose {@link #readOnly()} when consumers must not obtain the backing storage. Unchecked generic
 * array casts are avoided: {@link #elements()} always returns Object[], even for collection copies.
 * @param <TValue> element type
 */
public final class ObjectArrayView<TValue> extends AbstractObjectList<TValue> implements RandomAccess, Cloneable {
    /** Shared empty view; its zero-length array cannot be modified. */
    private static final ObjectArrayView<?> EMPTY = new ObjectArrayView<>(new Object[0]);
    /** Exact-size storage owned by this snapshot; no unused capacity slots exist. */
    private final Object[] elements;

    /** @param elements owned exact-size storage without null entries */
    private ObjectArrayView(Object[] elements) {
        this.elements = elements;
    }

    /** @param <TValue> element type @return shared empty view without allocating an array */
    @SuppressWarnings("unchecked")
    public static <TValue> ObjectArrayView<TValue> of() {
        return (ObjectArrayView<TValue>) EMPTY;
    }

    /** @param values source array copied before publication @param <TValue> element type
     * @return independent view @throws NullPointerException if the array or an element is null */
    @SafeVarargs
    public static <TValue> ObjectArrayView<TValue> of(TValue... values) {
        return copyOf(values);
    }

    /** @param collection source collection copied before publication @param <TValue> element type
     * @return independent view @throws NullPointerException if the collection or an element is null */
    public static <TValue> ObjectArrayView<TValue> copyOf(Collection<? extends TValue> collection) {
        Objects.requireNonNull(collection);
        return checked(collection.toArray());
    }

    /** @param collection source collection @param arrayFactory exact-size array factory
     * @param <TValue> element type @return independent view with the requested runtime array type
     * @throws NullPointerException if any argument or element is null */
    public static <TValue> ObjectArrayView<TValue> copyOf(Collection<? extends TValue> collection,
                                                          IntFunction<TValue[]> arrayFactory) {
        Objects.requireNonNull(collection);
        Objects.requireNonNull(arrayFactory);
        return checked(collection.toArray(arrayFactory));
    }

    /** @param values source array copied before publication @param <TValue> element type
     * @return independent view @throws NullPointerException if the array or an element is null */
    @SafeVarargs
    public static <TValue> ObjectArrayView<TValue> copyOf(TValue... values) {
        Objects.requireNonNull(values);
        return checked(values.clone());
    }

    /** @param values owned array @param <TValue> element type @return checked view or shared empty */
    private static <TValue> ObjectArrayView<TValue> checked(Object[] values) {
        if (values.length == 0) return of();
        for (Object value : values) {
            Objects.requireNonNull(value);
        }
        return new ObjectArrayView<>(values);
    }

    /**
     * Wraps freshly allocated storage without copying it; ownership transfers to the view.
     * @param values exact-size exclusively owned array; never mutate it after this call
     * @param <TValue> element type
     * @return read-only-by-contract view retaining this exact array
     * @throws NullPointerException if the array or an element is null
     */
    public static <TValue> ObjectArrayView<TValue> wrapOwned(TValue[] values) {
        return checked(Objects.requireNonNull(values, "values"));
    }

    /** @return exact-size internal backing array; callers must treat every slot as read-only */
    public Object[] elements() {
        return elements;
    }

    /** @return read-only list hiding the raw array; the empty view needs no extra wrapper */
    @SuppressWarnings("unchecked")
    public ObjectList<TValue> readOnly() {
        return isEmpty() ? ObjectLists.emptyList() : new ObjectImmutableList<>((TValue[]) elements);
    }

    /** @param index element index in [0, size) @return non-null element
     * @throws IndexOutOfBoundsException if the index is outside the view */
    @SuppressWarnings("unchecked")
    @Override
    public TValue get(int index) {
        return (TValue) elements[index];
    }

    /** @return exact number of stored elements, never negative */
    @Override
    public int size() {
        return elements.length;
    }

    /** @return independent copy of this view's array */
    @Override
    public ObjectArrayView<TValue> clone() {
        return checked(elements.clone());
    }
}
