package io.evitadb.roaringbitmap;

import javax.annotation.Nonnull;

/**
 * {@link PeekableIntIterator} that also exposes the rank of the upcoming value, maintained while
 * iterating so callers obtain it without a separate scan.
 */
public interface PeekableIntRankIterator extends PeekableIntIterator {
	/**
	 * Returns the rank of the value {@link #peekNext()} would return, without advancing the cursor.
	 *
	 * @return the rank of the next value
	 */
	int peekNextRank();

	/**
	 * Forks an independent cursor at the current position, narrowing the return type to
	 * {@link PeekableIntRankIterator}.
	 *
	 * @return an independent copy of this iterator
	 */
	@Nonnull
	@Override
	PeekableIntRankIterator clone();
}
