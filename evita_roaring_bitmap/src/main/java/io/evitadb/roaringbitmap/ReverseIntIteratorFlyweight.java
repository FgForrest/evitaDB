/*
 * (c) the authors Licensed under the Apache License, Version 2.0.
 */

package io.evitadb.roaringbitmap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Objects;

/**
 * Fast iterator minimizing the stress on the garbage collector. You can create one reusable
 * instance of this class and then {@link #wrap(PersistentRoaringBitmap)}
 *
 * This iterator enumerates the stored values in reverse (starting from the end).
 *
 * Walks the bitmap chunk by chunk from the last chunk down, dispatching each to a cached per-shape
 * reverse char cursor ({@link ReverseArrayContainerCharIterator},
 * {@link ReverseBitmapContainerCharIterator}, {@link ReverseRunContainerCharIterator}) reused across
 * chunks to stay allocation-free, OR-ing every emitted char with the chunk's high 16 bits to rebuild
 * the full 32-bit value.
 *
 * @author Borislav Ivanov
 **/
public class ReverseIntIteratorFlyweight implements IntIterator {

	/**
	 * High 16 bits of the current chunk, pre-shifted into the top half and OR-ed onto each container-local char to rebuild the full 32-bit value.
	 */
	private int hs;

	/**
	 * Reverse char cursor over the current chunk, re-targeted per container shape; `null` before the first {@link #wrap}.
	 */
	@Nullable
	private CharIterator iter;

	/**
	 * Cached reverse array-chunk char cursor, re-targeted via `wrap(...)` to avoid per-chunk allocation.
	 */
	@Nonnull
	private final ReverseArrayContainerCharIterator arrIter = new ReverseArrayContainerCharIterator();

	/**
	 * Cached reverse bitmap-chunk char cursor, re-targeted via `wrap(...)` to avoid per-chunk allocation.
	 */
	@Nonnull
	private final ReverseBitmapContainerCharIterator bitmapIter = new ReverseBitmapContainerCharIterator();

	/**
	 * Cached reverse run-chunk char cursor, re-targeted via `wrap(...)` to avoid per-chunk allocation.
	 */
	@Nonnull
	private final ReverseRunContainerCharIterator runIter = new ReverseRunContainerCharIterator();

	/**
	 * Index of the chunk currently being iterated, decremented toward `-1` which marks exhaustion.
	 */
	private int pos;

	/**
	 * Bitmap being iterated; bound by {@link #wrap(PersistentRoaringBitmap)}. A freshly constructed
	 * flyweight (no-arg constructor) leaves this `null` until the first `wrap` — every iteration
	 * method is only legal after wrapping.
	 */
	@Nullable
	private PersistentRoaringBitmap roaringBitmap;

	/**
	 * Creates an instance that is not ready for iteration. You must first call
	 * {@link #wrap(PersistentRoaringBitmap)}.
	 */
	public ReverseIntIteratorFlyweight() {
	}

	/**
	 * Creates an instance that is ready for iteration.
	 *
	 * @param r bitmap to be iterated over
	 */
	public ReverseIntIteratorFlyweight(@Nonnull final PersistentRoaringBitmap r) {
		wrap(r);
	}

	/**
	 * Forks an independent iterator, deep-copying the active reverse char cursor so the fork advances
	 * without disturbing this one.
	 */
	@Nonnull
	@Override
	public IntIterator clone() {
		try {
			final ReverseIntIteratorFlyweight x = (ReverseIntIteratorFlyweight) super.clone();
			if (this.iter != null) {
				x.iter = this.iter.clone();
			}
			return x;
		} catch (CloneNotSupportedException e) {
			// unreachable: ReverseIntIteratorFlyweight implements Cloneable
			throw new IllegalStateException(e);
		}
	}

	/**
	 * Exhausted once the descent passes the first chunk (`pos < 0`).
	 */
	@Override
	public boolean hasNext() {
		return this.pos >= 0;
	}

	/**
	 * Returns the next value in descending order — the current chunk's high bits OR-ed onto the
	 * container-local char — and steps down to the previous chunk once the current one is drained.
	 */
	@Override
	public int next() {
		final CharIterator cursor = Objects.requireNonNull(
			this.iter, "ReverseIntIteratorFlyweight has no active cursor (not wrapped or exhausted)");
		final int x = cursor.nextAsInt() | this.hs;
		if (!cursor.hasNext()) {
			--this.pos;
			nextContainer();
		}
		return x;
	}

	/**
	 * Binds {@link #iter} to a reverse char cursor over the chunk at {@link #pos}, choosing and
	 * re-wrapping the cached cursor for the container's shape, and caches the chunk's pre-shifted high
	 * bits in {@link #hs}.
	 */
	private void nextContainer() {
		final PersistentRoaringBitmap bitmap = Objects.requireNonNull(
			this.roaringBitmap, "flyweight iterator has not been wrapped around a bitmap");

		if (this.pos >= 0) {

			final Container container = bitmap.highLowContainer.getContainerAtIndex(this.pos);
			if (container instanceof BitmapContainer) {
				this.bitmapIter.wrap(((BitmapContainer) container).bitmap);
				this.iter = this.bitmapIter;
			} else if (container instanceof ArrayContainer) {
				this.arrIter.wrap((ArrayContainer) container);
				this.iter = this.arrIter;
			} else {
				this.runIter.wrap((RunContainer) container);
				this.iter = this.runIter;
			}
			this.hs = (bitmap.highLowContainer.getKeyAtIndex(this.pos)) << 16;
		}
	}

	/**
	 * Re-targets this reusable iterator at bitmap `r`, resetting all cursor state to its last chunk so
	 * one instance can be recycled without allocation.
	 *
	 * @param r bitmap to be iterated over
	 */
	public void wrap(@Nonnull final PersistentRoaringBitmap r) {
		this.roaringBitmap = r;
		this.hs = 0;
		this.pos = this.roaringBitmap.highLowContainer.size() - 1;
		this.nextContainer();
	}
}
