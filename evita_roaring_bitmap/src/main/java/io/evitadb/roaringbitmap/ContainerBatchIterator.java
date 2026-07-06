package io.evitadb.roaringbitmap;

import javax.annotation.Nonnull;

/**
 * Bulk cursor over the values of a single container, feeding {@link RoaringBatchIterator}. Values
 * are emitted in ascending order, each OR-ed with the container's 16-bit key so the caller
 * reconstructs the full 32-bit value.
 *
 * Implementations are {@link Cloneable} (see {@link #clone()}) and may pin their backing container
 * until {@link #releaseContainer()} lets it be collected.
 */
interface ContainerBatchIterator extends Cloneable {

	/**
	 * Writes this container's remaining values, each OR-ed with `key` in the high 16 bits, into
	 * `buffer` starting at `offset`, and returns how many were written.
	 *
	 * @param key    the 16-bit container key placed in the high bits of every emitted value
	 * @param buffer the array to write values into
	 * @param offset the first index in `buffer` to write to
	 * @return the number of values written
	 */
	int next(int key, @Nonnull int[] buffer, int offset);

	/**
	 * Convenience overload of {@link #next(int, int[], int)} that writes from index `0`.
	 *
	 * @param key    the 16-bit container key placed in the high bits of every emitted value
	 * @param buffer the array to write values into
	 * @return the number of values written
	 */
	default int next(int key, @Nonnull int[] buffer) {
		return next(key, buffer, 0);
	}

	/**
	 * Tells whether the underlying container still has values to emit.
	 *
	 * @return `true` if data remains
	 */
	boolean hasNext();

	/**
	 * Forks an independent cursor at the current position, sharing the same backing container.
	 *
	 * @return an independent copy of this iterator
	 */
	@Nonnull
	ContainerBatchIterator clone();

	/**
	 * Discards the reference to the backing container so it can be garbage-collected, called once the
	 * container has been exhausted.
	 */
	void releaseContainer();

	/**
	 * Skips ahead within the container so the next emitted value is `>=` `target` (compared as an
	 * unsigned 16-bit key); a no-op when already at or past `target`.
	 *
	 * @param target the container-local key to skip forward to (inclusive threshold)
	 */
	void advanceIfNeeded(char target);
}
