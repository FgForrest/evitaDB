/*
 * (c) the authors Licensed under the Apache License, Version 2.0.
 */
package io.evitadb.roaringbitmap;

import javax.annotation.Nonnull;

/**
 * {@link LongIterator} that also lets callers skip ahead with {@link #advanceIfNeeded} and inspect
 * the upcoming value without consuming it via {@link #peekNext}. This richer protocol enables
 * efficient merge and intersection algorithms over iterators of longs.
 */
public interface PeekableLongIterator extends LongIterator {
	/**
	 * Skips ahead to the first value on the correct side of `thresholdVal`, unless already there:
	 *
	 * - a forward iterator advances while the next value is smaller than `thresholdVal`
	 * - a reverse iterator advances while the next value is greater than `thresholdVal`
	 *
	 * This is a performance shortcut over repeated {@link #next()} calls, letting the implementation
	 * jump over intervening data. It is typically used to intersect this iterator with another
	 * ordered sequence — advance to each probe value, test it, then advance to the next:
	 *
	 * ```java
	 * PeekableLongIterator j = // get an iterator
	 * long val = // first value from the other data structure
	 * j.advanceIfNeeded(val);
	 * while (j.hasNext()) {
	 * if (j.next() == val) {
	 * // val is in the intersection: do something, then fetch the next probe value
	 * val = // next value
	 * }
	 * j.advanceIfNeeded(val);
	 * }
	 * ```
	 *
	 * @param thresholdVal the value to skip toward (inclusive threshold)
	 */
	public void advanceIfNeeded(long thresholdVal);

	/**
	 * Returns the value {@link #next()} would return, without advancing the cursor. Peeking lets
	 * several iterators be ordered against one another (e.g. in a priority queue keyed on their next
	 * value) without materialising their output.
	 *
	 * @return the next value, left in place
	 */
	public long peekNext();

	/**
	 * Forks an independent cursor at the current position, narrowing the return type to
	 * {@link PeekableLongIterator}.
	 *
	 * @return an independent copy of this iterator
	 */
	@Nonnull
	@Override
	PeekableLongIterator clone();
}
