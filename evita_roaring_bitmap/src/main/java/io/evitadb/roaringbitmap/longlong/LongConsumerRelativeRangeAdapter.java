package io.evitadb.roaringbitmap.longlong;

import io.evitadb.roaringbitmap.LongConsumer;
import io.evitadb.roaringbitmap.RelativeRangeConsumer;

import javax.annotation.Nonnull;

/**
 * Bridges a {@link RelativeRangeConsumer} (which reports positions relative to a scanned range) to a
 * {@link LongConsumer} (which expects absolute values), by adding the range's `start` offset to each
 * relative position. Absent positions are dropped, since a plain value enumeration only cares about
 * the values that are present.
 */
public class LongConsumerRelativeRangeAdapter implements RelativeRangeConsumer {
	/**
	 * Absolute base value of the scanned range, added to every relative position.
	 */
	final long start;
	/**
	 * Downstream consumer receiving the reconstructed absolute values.
	 */
	@Nonnull final LongConsumer absolutePositionConsumer;

	public LongConsumerRelativeRangeAdapter(long start, @Nonnull final LongConsumer lc) {
		this.start = start;
		this.absolutePositionConsumer = lc;
	}

	@Override
	public void acceptPresent(int relativePos) {
		this.absolutePositionConsumer.accept(this.start + relativePos);
	}

	@Override
	public void acceptAbsent(int relativePos) {
		// nothing to do
	}

	@Override
	public void acceptAllPresent(int relativeFrom, int relativeTo) {
		for (long pos = this.start + relativeFrom; pos < this.start + relativeTo; pos++) {
			this.absolutePositionConsumer.accept(pos);
		}
	}

	@Override
	public void acceptAllAbsent(int relativeFrom, int relativeTo) {
		// nothing to do
	}
}
