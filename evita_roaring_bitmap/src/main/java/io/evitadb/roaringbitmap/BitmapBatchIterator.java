package io.evitadb.roaringbitmap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Objects;

import static java.lang.Long.numberOfTrailingZeros;

/**
 * {@link ContainerBatchIterator} over a {@link BitmapContainer}: scans the container's 1024-word
 * `long[]` bitmap in ascending order and emits one value per set bit, rebuilt as
 * `key + 64 * wordIndex + trailingZeros(word)`. Reusable via {@link #wrap(BitmapContainer)} so a
 * single instance can be recycled across chunks without allocating.
 */
final class BitmapBatchIterator implements ContainerBatchIterator {

	/**
	 * Index of the 64-bit word in {@link BitmapContainer#bitmap} being drained; `1024` marks exhaustion.
	 */
	private int wordIndex = 0;
	/**
	 * Not-yet-emitted set bits of the current word, consumed lowest-first; `0` means advance to the next word.
	 */
	private long word;
	/**
	 * Container currently being iterated, or `null` after {@link #releaseContainer()}.
	 */
	@Nullable private BitmapContainer bitmap;

	/**
	 * Creates an iterator positioned at the start of `bitmap`.
	 *
	 * @param bitmap container to iterate
	 */
	public BitmapBatchIterator(@Nonnull final BitmapContainer bitmap) {
		wrap(bitmap);
	}

	/**
	 * Emits set bits into `buffer` until it is full or all 1024 words are drained. Each value is
	 * `key + 64 * wordIndex + trailingZeros(word)` (disjoint halves, so the additions act as an OR),
	 * and the emitted bit is cleared with `word &= word - 1` before the next iteration.
	 */
	@Override
	public int next(final int key, @Nonnull final int[] buffer, final int offset) {
		int consumed = 0;
		final long[] words = Objects.requireNonNull(
			this.bitmap, "BitmapBatchIterator: container released or never wrapped").bitmap;
		while ((consumed + offset) < buffer.length) {
			while (this.word == 0) {
				++this.wordIndex;
				if (this.wordIndex == 1024) {
					return consumed;
				}
				this.word = words[this.wordIndex];
			}
			buffer[offset + consumed++] = key + (64 * this.wordIndex) + numberOfTrailingZeros(this.word);
			this.word &= (this.word - 1);
		}
		return consumed;
	}

	/**
	 * Reports whether any set bit remains, scanning forward over empty words as a side effect so the
	 * cursor is left on the next set bit (or at the 1024-word end).
	 */
	@Override
	public boolean hasNext() {
		if (this.wordIndex > 1023) {
			return false;
		}
		final long[] words = Objects.requireNonNull(
			this.bitmap, "BitmapBatchIterator: container released or never wrapped").bitmap;
		while (this.word == 0) {
			++this.wordIndex;
			if (this.wordIndex == 1024) { // reached end without a non-empty word
				return false;
			}
			this.word = words[this.wordIndex];
		}
		return true; // found some non-empty word, so hasNext
	}

	/**
	 * Shallow fork — the copy shares the backing container and resumes from the current word and bit.
	 */
	@Nonnull
	@Override
	public ContainerBatchIterator clone() {
		try {
			return (ContainerBatchIterator) super.clone();
		} catch (CloneNotSupportedException e) {
			// won't happen
			throw new IllegalStateException(e);
		}
	}

	/**
	 * Drops the backing-container reference once drained so it can be garbage-collected.
	 */
	@Override
	public void releaseContainer() {
		this.bitmap = null;
	}

	/**
	 * Repositions onto the word holding `target` (`target >>> 6`) and masks off every bit below
	 * `target` with `word &= -(1L << target)`, so the next emitted value is `>=` `target`.
	 */
	@Override
	public void advanceIfNeeded(final char target) {
		this.wordIndex = target >>> 6;
		this.word = Objects.requireNonNull(
			this.bitmap, "BitmapBatchIterator: container released or never wrapped").bitmap[this.wordIndex];
		this.word &= -(1L << target);
	}

	/**
	 * Re-targets this iterator at `bitmap` and rewinds to its first word, letting one instance be
	 * reused across containers without allocation.
	 *
	 * @param bitmap container to iterate next
	 */
	void wrap(@Nonnull final BitmapContainer bitmap) {
		this.bitmap = bitmap;
		this.word = bitmap.bitmap[0];
		this.wordIndex = 0;
	}
}
