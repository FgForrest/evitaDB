package io.evitadb.roaringbitmap;

import javax.annotation.Nonnull;

/**
 * Bridges the present/absent {@link RelativeRangeConsumer} view back onto a plain
 * {@link IntConsumer} that expects absolute positions.
 *
 * Wrapping an `IntConsumer` in this adapter lets a present-only traversal such as
 * {@link PersistentRoaringBitmap#forEachInRange} reuse the range-scan machinery of
 * {@link PersistentRoaringBitmap#forAllInRange}: each present value is translated from its relative
 * offset back to the absolute index (`start + relativePos`) and forwarded to the wrapped consumer,
 * while absent values are dropped.
 */
class IntConsumerRelativeRangeAdapter implements RelativeRangeConsumer {
	/**
	 * Absolute index that relative offset `0` maps to; added back to every relative position.
	 */
	final int start;
	/**
	 * Downstream consumer that receives the absolute positions of the present values.
	 */
	@Nonnull final IntConsumer absolutePositionConsumer;

	/**
	 * @param start absolute index corresponding to relative offset `0`
	 * @param lc    consumer to receive the absolute position of each present value
	 */
	public IntConsumerRelativeRangeAdapter(final int start, @Nonnull final IntConsumer lc) {
		this.start = start;
		this.absolutePositionConsumer = lc;
	}

	@Override
	public void acceptPresent(final int relativePos) {
		this.absolutePositionConsumer.accept(this.start + relativePos);
	}

	@Override
	public void acceptAbsent(final int relativePos) {
		// nothing to do
	}

	@Override
	public void acceptAllPresent(final int relativeFrom, final int relativeTo) {
		for (int pos = this.start + relativeFrom; pos < this.start + relativeTo; pos++) {
			this.absolutePositionConsumer.accept(pos);
		}
	}

	@Override
	public void acceptAllAbsent(final int relativeFrom, final int relativeTo) {
		// nothing to do
	}
}
