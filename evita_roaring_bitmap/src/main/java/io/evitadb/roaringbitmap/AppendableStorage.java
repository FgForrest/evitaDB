package io.evitadb.roaringbitmap;

import javax.annotation.Nonnull;

/**
 * Append-only, key-ordered storage of 16-bit containers keyed by the high 16 bits of a value.
 *
 * Keys must arrive in non-decreasing order: each appended key has to be greater than or equal to
 * the last appended key (the current mark). Backed by {@link RoaringArray} and fed by the roaring
 * bitmap writers {@link ContainerAppender} and {@link ConstantMemoryContainerAppender}.
 *
 * @param <T> the type of stored container
 */
interface AppendableStorage<T> {

	/**
	 * Appends the container under the given key at the end of the storage.
	 *
	 * The key must not be smaller than the last appended key; the append itself runs in amortized
	 * `O(1)` time (an occasional backing-array growth aside).
	 *
	 * @param key       the high 16 bits the container is stored under
	 * @param container the 16-bit container to append
	 * @throws IllegalArgumentException if `key` is less than the last appended key
	 */
	void append(char key, @Nonnull T container);
}
