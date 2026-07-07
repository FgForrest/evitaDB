package io.evitadb.roaringbitmap;

import javax.annotation.Nonnull;

/**
 * Word-level (16-bit) storage contract for a single roaring container.
 *
 * Implementations hold the low 16 bits of the values sharing a common high-16-bit key.
 * The interface is self-typed: `T` is the concrete storage type (for example
 * `Container implements WordStorage<Container>`), so mutating operations return the resulting
 * storage, which may be a different instance when the backing representation changes.
 *
 * @param <T> the concrete word-storage type returned by mutating operations
 */
interface WordStorage<T> {

	/**
	 * Adds a single 16-bit value to the storage.
	 *
	 * May return a new instance when the backing representation has to change (for example when a
	 * sparse container is promoted to a denser one); otherwise the same instance is returned.
	 *
	 * @param value the 16-bit value to add
	 * @return the resulting storage, possibly a new instance
	 */
	@Nonnull
	T add(char value);

	/**
	 * Tells whether the storage holds no values.
	 *
	 * @return `true` when no value is stored, `false` otherwise
	 */
	boolean isEmpty();

	/**
	 * Rewrites the storage into a more compact representation when that is smaller.
	 *
	 * May return a new instance holding the optimized representation; otherwise the same instance is
	 * returned unchanged.
	 *
	 * @return the optimized storage, possibly a new instance
	 */
	@Nonnull
	T runOptimize();
}
