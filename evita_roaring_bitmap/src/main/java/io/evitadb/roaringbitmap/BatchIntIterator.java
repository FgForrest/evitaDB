package io.evitadb.roaringbitmap;

import javax.annotation.Nonnull;

/**
 * Adapts a {@link BatchIterator} to the value-at-a-time {@link IntIterator} protocol by buffering
 * one batch at a time: {@link #hasNext} refills the shared `buffer` from the delegate when the
 * current batch is drained, and {@link #next} hands out buffered values one at a time. Backs
 * {@link BatchIterator#asIntIterator(int[])}.
 */
class BatchIntIterator implements IntIterator {
	/**
	 * Read cursor into {@link #buffer}: index of the next value {@link #next} will return.
	 */
	private int i;
	/**
	 * Count of valid values in {@link #buffer} (size of the last batch); `i == mark` means it is drained.
	 */
	private int mark;
	/**
	 * Scratch array batches are drained through; supplied by the caller of {@link BatchIterator#asIntIterator(int[])}.
	 */
	@Nonnull private int[] buffer;
	/**
	 * Underlying batch cursor supplying the values.
	 */
	@Nonnull private BatchIterator delegate;

	/**
	 * Full-state constructor used by {@link #clone()} to reproduce cursor position and buffer
	 * contents.
	 *
	 * @param delegate the batch iterator doing the actual iteration
	 * @param i        index of the next buffered value
	 * @param mark     number of valid values currently in `buffer`
	 * @param buffer   scratch buffer batches are drained through
	 */
	private BatchIntIterator(
		@Nonnull final BatchIterator delegate, final int i, final int mark, @Nonnull final int[] buffer) {
		this.delegate = delegate;
		this.i = i;
		this.mark = mark;
		this.buffer = buffer;
	}

	/**
	 * Wraps the batch iterator.
	 *
	 * @param delegate the batch iterator to do the actual iteration
	 * @param buffer   the buffer
	 */
	BatchIntIterator(@Nonnull final BatchIterator delegate, @Nonnull final int[] buffer) {
		this(delegate, 0, -1, buffer);
	}

	/**
	 * Returns `true` while buffered values remain; once the batch is drained it pulls the next batch
	 * from the delegate and rewinds the cursor, reporting `false` only when the delegate yields an
	 * empty batch.
	 */
	@Override
	public boolean hasNext() {
		if (this.i < this.mark) {
			return true;
		}
		if (!this.delegate.hasNext() || (this.mark = this.delegate.nextBatch(this.buffer)) == 0) {
			return false;
		}
		this.i = 0;
		return true;
	}

	/**
	 * Returns the next buffered value; assumes {@link #hasNext()} has confirmed one is available.
	 */
	@Override
	public int next() {
		return this.buffer[this.i++];
	}

	/**
	 * Forks an independent iterator, deep-copying both the delegate cursor and the buffer so the two
	 * advance without interfering.
	 */
	@Nonnull
	@Override
	public IntIterator clone() {
		try {
			final BatchIntIterator it = (BatchIntIterator) super.clone();
			it.delegate = this.delegate.clone();
			it.buffer = this.buffer.clone();
			return it;
		} catch (CloneNotSupportedException e) {
			// won't happen
			throw new IllegalStateException();
		}
	}
}
