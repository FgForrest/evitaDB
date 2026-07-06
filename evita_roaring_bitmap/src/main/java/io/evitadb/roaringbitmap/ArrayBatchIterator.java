package io.evitadb.roaringbitmap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Objects;

import static io.evitadb.roaringbitmap.Util.unsignedBinarySearch;

/**
 * {@link ContainerBatchIterator} over an {@link ArrayContainer}: emits the container's sorted
 * `char[]` values in ascending order, each added to the caller's 16-bit key to rebuild the full
 * 32-bit value. Reusable — {@link #wrap(ArrayContainer)} re-targets it at another container and
 * resets the cursor, so one instance can be recycled across chunks without allocating.
 */
final class ArrayBatchIterator implements ContainerBatchIterator {

	/**
	 * Cursor into {@link ArrayContainer#content}; every value below it has already been emitted.
	 */
	private int index = 0;
	/**
	 * Container currently being iterated, or `null` after {@link #releaseContainer()}.
	 */
	@Nullable private ArrayContainer array;

	/**
	 * Creates an iterator positioned at the start of `array`.
	 *
	 * @param array container to iterate
	 */
	public ArrayBatchIterator(@Nonnull final ArrayContainer array) {
		wrap(array);
	}

	/**
	 * Copies values into `buffer` until it is full or the container is drained, adding `key` (the
	 * chunk's high 16 bits) to each 16-bit entry — the halves are disjoint, so the addition rebuilds
	 * the full value.
	 */
	@Override
	public int next(final int key, @Nonnull final int[] buffer, final int offset) {
		int consumed = 0;
		final ArrayContainer container = Objects.requireNonNull(
			this.array, "ArrayBatchIterator: container released or never wrapped");
		final char[] data = container.content;
		while ((offset + consumed) < buffer.length && this.index < container.getCardinality()) {
			buffer[offset + consumed++] = key + (data[this.index++]);
		}
		return consumed;
	}

	/**
	 * More values remain while the cursor sits below the container's cardinality.
	 */
	@Override
	public boolean hasNext() {
		return this.index < Objects.requireNonNull(
			this.array, "ArrayBatchIterator: container released or never wrapped").getCardinality();
	}

	/**
	 * Shallow fork — the copy shares the backing container and resumes from the current cursor.
	 */
	@Nonnull
	@Override
	public ContainerBatchIterator clone() {
		try {
			return (ContainerBatchIterator) super.clone();
		} catch (CloneNotSupportedException e) {
			// won't happen
			throw new IllegalStateException(e);
		}
	}

	/**
	 * Drops the backing-container reference once drained so it can be garbage-collected.
	 */
	@Override
	public void releaseContainer() {
		this.array = null;
	}

	/**
	 * Binary-searches the sorted content to reposition the cursor on the first value `>=` `target`.
	 */
	@Override
	public void advanceIfNeeded(final char target) {
		final ArrayContainer container = Objects.requireNonNull(
			this.array, "ArrayBatchIterator: container released or never wrapped");
		final int position = unsignedBinarySearch(container.content, 0, container.getCardinality(), target);
		this.index = position < 0 ? (-position - 1) : position;
	}

	/**
	 * Re-targets this iterator at `array` and rewinds the cursor to the start, letting one instance be
	 * reused across containers without allocation.
	 *
	 * @param array container to iterate next
	 */
	void wrap(@Nonnull final ArrayContainer array) {
		this.array = array;
		this.index = 0;
	}
}
