/*
 * (c) the authors Licensed under the Apache License, Version 2.0.
 */

package io.evitadb.roaringbitmap;

import javax.annotation.Nonnull;

/**
 * Primitive cursor over `int` values. Preferring an `IntIterator` to an `Integer`-based
 * {@link java.util.Iterator} avoids per-element boxing; in some benchmarks it runs nearly twice as
 * fast.
 *
 * Implementations are {@link Cloneable}: {@link #clone()} forks an independent cursor that shares
 * the same backing data. The traversal protocol mirrors {@link java.util.Iterator}; the traversal
 * order is fixed by the factory that produced the iterator, not by this interface.
 */
public interface IntIterator extends Cloneable {
	/**
	 * Forks an independent cursor at the current position. Cursor state is duplicated while the
	 * backing data is shared, so the copy can be advanced without disturbing this iterator.
	 *
	 * @return an independent copy of this iterator
	 */
	@Nonnull
	IntIterator clone();

	/**
	 * Tells whether another value remains, mirroring {@link java.util.Iterator#hasNext()}.
	 *
	 * @return `true` if {@link #next()} would return a further value
	 */
	boolean hasNext();

	/**
	 * Returns the next value and advances the cursor.
	 *
	 * @return the next `int` value
	 */
	int next();
}
