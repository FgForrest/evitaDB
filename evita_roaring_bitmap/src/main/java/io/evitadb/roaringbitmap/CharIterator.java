/*
 * (c) the authors Licensed under the Apache License, Version 2.0.
 */

package io.evitadb.roaringbitmap;

import javax.annotation.Nonnull;

/**
 * Primitive cursor over the `char` values stored inside a single container, visited in ascending
 * order. Iterating with the primitive `char` avoids the per-element boxing a `Character`-based
 * {@link java.util.Iterator} would incur.
 *
 * Implementations are {@link Cloneable}: {@link #clone()} forks an independent cursor that shares
 * the same backing container. The traversal protocol mirrors {@link java.util.Iterator}.
 */
public interface CharIterator extends Cloneable {
	/**
	 * Forks an independent cursor at the current position. Cursor state is duplicated while the
	 * backing container is shared, so the copy can be advanced without disturbing this iterator.
	 *
	 * @return an independent copy of this iterator
	 */
	@Nonnull
	CharIterator clone();

	/**
	 * Tells whether another value remains, mirroring {@link java.util.Iterator#hasNext()}.
	 *
	 * @return `true` if {@link #next()} would return a further value
	 */
	boolean hasNext();

	/**
	 * Returns the next value and advances the cursor.
	 *
	 * @return the next `char` value
	 */
	char next();

	/**
	 * Returns the next value widened to `int` using its least significant 16 bits, i.e. zero-extended
	 * to an unsigned value in the range `0`–`65535`, and advances the cursor.
	 *
	 * @return the next value as an unsigned `int`
	 */
	int nextAsInt();

	/**
	 * Removes the current value from the backing container when the implementation supports removal,
	 * mirroring the optional {@link java.util.Iterator#remove()}.
	 */
	void remove();
}
