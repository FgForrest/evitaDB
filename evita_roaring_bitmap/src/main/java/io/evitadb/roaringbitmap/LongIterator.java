/*
 * (c) the authors Licensed under the Apache License, Version 2.0.
 */
package io.evitadb.roaringbitmap;

import javax.annotation.Nonnull;

/**
 * Primitive cursor over `long` values. Preferring a `LongIterator` to a `Long`-based
 * {@link java.util.Iterator} avoids per-element boxing; in some benchmarks it runs nearly twice as
 * fast.
 *
 * Implementations are {@link Cloneable}: {@link #clone()} forks an independent cursor that shares
 * the same backing data. The traversal protocol mirrors {@link java.util.Iterator}.
 */
public interface LongIterator extends Cloneable {
	/**
	 * Forks an independent cursor at the current position. Cursor state is duplicated while the
	 * backing data is shared, so the copy can be advanced without disturbing this iterator.
	 *
	 * @return an independent copy of this iterator
	 */
	@Nonnull
	LongIterator clone();

	/**
	 * Tells whether another value remains, mirroring {@link java.util.Iterator#hasNext()}.
	 *
	 * @return `true` if {@link #next()} would return a further value
	 */
	boolean hasNext();

	/**
	 * Returns the next value and advances the cursor.
	 *
	 * @return the next `long` value
	 */
	long next();
}
