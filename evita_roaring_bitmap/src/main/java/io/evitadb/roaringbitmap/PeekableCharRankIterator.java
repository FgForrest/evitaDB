package io.evitadb.roaringbitmap;

import javax.annotation.Nonnull;

/**
 * {@link PeekableCharIterator} that also exposes the rank of the upcoming value, maintained while
 * iterating so callers obtain it without a separate scan of the container.
 */
interface PeekableCharRankIterator extends PeekableCharIterator {

	/**
	 * Returns the in-container rank of the value {@link #peekNext()} would return, without advancing.
	 * Rank is the value's 1-based position among the container's values, so it lies in `1`–`65536`;
	 * the result is an `int` because that range does not fit an unsigned `char`.
	 *
	 * @return the 1-based in-container rank of the next value
	 */
	int peekNextRank();

	/**
	 * Forks an independent cursor at the current position, narrowing the return type to
	 * {@link PeekableCharRankIterator}.
	 *
	 * @return an independent copy of this iterator
	 */
	@Nonnull
	@Override
	PeekableCharRankIterator clone();
}
