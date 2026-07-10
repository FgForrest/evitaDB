package io.evitadb.roaringbitmap;

import javax.annotation.Nonnull;

/**
 * Bulk cursor that yields set bits in array-sized batches rather than one value at a time, trading
 * a caller-supplied buffer for far lower per-value overhead during large scans.
 *
 * Values are produced in ascending order. A typical loop fills a buffer with {@link #nextBatch}
 * until {@link #hasNext} reports exhaustion, while {@link #advanceIfNeeded} lets intersection-style
 * algorithms skip ahead cheaply. Implementations are {@link Cloneable} (see {@link #clone()}).
 */
public interface BatchIterator extends Cloneable {

	/**
	 * Writes the next batch of values into `buffer`, filling it as far as possible, and returns how
	 * many entries were written. A return of `0` signals exhaustion; callers keep invoking this
	 * method (or consult {@link #hasNext}) until then.
	 *
	 * @param buffer the array to write values into
	 * @return the number of values written, `0` once no values remain
	 */
	int nextBatch(@Nonnull int[] buffer);

	/**
	 * Tells whether any values remain to be read.
	 *
	 * @return `true` if a further {@link #nextBatch} call may still write values
	 */
	boolean hasNext();

	/**
	 * Forks an independent cursor at the current position. Cursor state is duplicated while the
	 * backing bitmap is shared, so the copy can be advanced without disturbing this iterator.
	 *
	 * @return an independent copy of this iterator
	 */
	@Nonnull
	BatchIterator clone();

	/**
	 * Adapts this batch iterator to a value-at-a-time {@link IntIterator}, draining batches through
	 * `buffer` as scratch space. A buffer of 128–256 entries usually balances call overhead against
	 * memory.
	 *
	 * @param buffer scratch array the wrapper buffers values through (ideally 128–256 entries)
	 * @return an {@link IntIterator} view backed by this batch iterator
	 */
	@Nonnull
	default IntIterator asIntIterator(@Nonnull int[] buffer) {
		return new BatchIntIterator(this, buffer);
	}

	/**
	 * Skips ahead so the next value produced is `>=` `target`, unless the cursor is already there.
	 *
	 * This is a performance shortcut over repeated {@link #nextBatch} calls: the implementation can
	 * jump over runs of smaller values instead of materialising them. It is typically used to
	 * intersect this iterator with another ascending sequence — advance to each probe value, scan the
	 * batch for a match, then advance to the next probe:
	 *
	 * ```java
	 * int[] buffer = new int[128];
	 * BatchIterator j = // get an iterator
	 * int val = // first value from the other data structure
	 * j.advanceIfNeeded(val);
	 * while (j.hasNext()) {
	 * int limit = j.nextBatch(buffer);
	 * for (int i = 0; i < limit; i++) {
	 * if (buffer[i] == val) {
	 * // matched: do something, then fetch the next probe value
	 * val = // next value
	 * }
	 * }
	 * j.advanceIfNeeded(val);
	 * }
	 * ```
	 *
	 * @param target the value to skip forward to (inclusive threshold)
	 */
	void advanceIfNeeded(int target);
}
