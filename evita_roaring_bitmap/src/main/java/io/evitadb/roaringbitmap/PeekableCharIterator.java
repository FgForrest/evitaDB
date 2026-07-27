/*
 * (c) the authors Licensed under the Apache License, Version 2.0.
 */
package io.evitadb.roaringbitmap;

import javax.annotation.Nonnull;

/**
 * {@link CharIterator} that also lets callers skip ahead with {@link #advanceIfNeeded} and inspect
 * the upcoming value without consuming it via {@link #peekNext}. These operations enable efficient
 * merge and intersection algorithms across several iterators at once.
 */
public interface PeekableCharIterator extends CharIterator {
	/**
	 * Skips ahead to the first value on the correct side of `thresholdVal`, unless already there:
	 *
	 * - a forward iterator advances while the next value is smaller than `thresholdVal`
	 * - a reverse iterator advances while the next value is larger than `thresholdVal`
	 *
	 * Values are compared as unsigned 16-bit shorts. Skipping is a performance shortcut over repeated
	 * {@link #next()} calls, letting the implementation jump over intervening data.
	 *
	 * @param thresholdVal the value to skip toward (inclusive threshold)
	 */
	public void advanceIfNeeded(char thresholdVal);

	/**
	 * Returns the value {@link #next()} would return, without advancing the cursor. Useful for
	 * ordering several iterators against one another (e.g. in a priority queue) before deciding which
	 * one to advance.
	 *
	 * @return the next value, left in place
	 */
	public char peekNext();

	/**
	 * Forks an independent cursor at the current position, narrowing the return type to
	 * {@link PeekableCharIterator}.
	 *
	 * @return an independent copy of this iterator
	 */
	@Nonnull
	@Override
	PeekableCharIterator clone();
}
