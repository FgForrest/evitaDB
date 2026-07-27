package io.evitadb.roaringbitmap;

import javax.annotation.Nonnull;

/**
 * {@link IntIterator} that also lets callers skip ahead with {@link #advanceIfNeeded} and inspect
 * the upcoming value without consuming it via {@link #peekNext}. This richer protocol enables
 * efficient merge and intersection algorithms over iterators of integers.
 */
public interface PeekableIntIterator extends IntIterator {
	/**
	 * Skips ahead while the next value is smaller than `minval`, unless the cursor is already at or
	 * past it. This is a performance shortcut over repeated {@link #next()} calls: the implementation
	 * can jump over intervening data rather than returning every value.
	 *
	 * A common use is intersecting this iterator with another ascending sequence — advance to each
	 * probe value, test it, then advance to the next:
	 *
	 * ```java
	 * PeekableIntIterator j = // get an iterator
	 * int val = // first value from the other data structure
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
	 * @param minval the value to skip forward to (inclusive threshold)
	 */
	public void advanceIfNeeded(int minval);

	/**
	 * Returns the value {@link #next()} would return, without advancing the cursor.
	 *
	 * Peeking lets several iterators be ordered against one another without materialising their
	 * output — for instance, keeping many iterators in a priority queue keyed on their next value to
	 * merge them lazily:
	 *
	 * ```java
	 * PriorityQueue pq = new PriorityQueue(100,
	 * (PeekableIntIterator a, PeekableIntIterator b) -> a.peekNext() - b.peekNext());
	 * // ... populate pq
	 * while (!pq.isEmpty()) {
	 * PeekableIntIterator pi = pq.poll(); // iterator with the smallest next value
	 * int x = pi.next();                  // consume it
	 * // do something with x
	 * if (pi.hasNext()) pq.add(pi);
	 * }
	 * ```
	 *
	 * @return the next value, left in place
	 */
	public int peekNext();

	/**
	 * Forks an independent cursor at the current position, narrowing the return type to
	 * {@link PeekableIntIterator}.
	 *
	 * @return an independent copy of this iterator
	 */
	@Nonnull
	@Override
	PeekableIntIterator clone();
}
