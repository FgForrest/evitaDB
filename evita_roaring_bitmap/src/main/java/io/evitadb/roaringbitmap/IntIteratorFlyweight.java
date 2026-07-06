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
 * Implements {@link PeekableIntIterator}: it walks the bitmap chunk by chunk and dispatches each
 * chunk to a cached per-shape char cursor ({@link ArrayContainerCharIterator},
 * {@link BitmapContainerCharIterator}, {@link RunContainerCharIterator}) reused across chunks to
 * stay allocation-free, OR-ing every emitted char with the chunk's high 16 bits to rebuild the full
 * 32-bit value.
 *
 * For better performance, consider the {@link PersistentRoaringBitmap#forEach} method.
 *
 * @author Borislav Ivanov
 **/
public class IntIteratorFlyweight implements PeekableIntIterator {

	/**
	 * High 16 bits of the current chunk, pre-shifted into the top half and OR-ed onto each container-local char to rebuild the full 32-bit value.
	 */
	private int hs;

	/**
	 * Char cursor over the current chunk, re-targeted per container shape; `null` before the first {@link #wrap}.
	 */
	@Nullable
	private PeekableCharIterator iter;

	/**
	 * Cached array-chunk char cursor, re-targeted via `wrap(...)` to avoid per-chunk allocation.
	 */
	@Nonnull
	private final ArrayContainerCharIterator arrIter = new ArrayContainerCharIterator();

	/**
	 * Cached bitmap-chunk char cursor, re-targeted via `wrap(...)` to avoid per-chunk allocation.
	 */
	@Nonnull
	private final BitmapContainerCharIterator bitmapIter = new BitmapContainerCharIterator();

	/**
	 * Cached run-chunk char cursor, re-targeted via `wrap(...)` to avoid per-chunk allocation.
	 */
	@Nonnull
	private final RunContainerCharIterator runIter = new RunContainerCharIterator();

	/**
	 * Index of the chunk currently being iterated within the bitmap's {@link RoaringArray}.
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
	public IntIteratorFlyweight() {
	}

	/**
	 * Creates an instance that is ready for iteration.
	 *
	 * @param r bitmap to be iterated over
	 */
	public IntIteratorFlyweight(@Nonnull final PersistentRoaringBitmap r) {
		wrap(r);
	}

	/**
	 * Forks an independent iterator, deep-copying the active char cursor so the fork advances without
	 * disturbing this one.
	 */
	@Nonnull
	@Override
	public PeekableIntIterator clone() {
		try {
			final IntIteratorFlyweight x = (IntIteratorFlyweight) super.clone();
			if (this.iter != null) {
				x.iter = this.iter.clone();
			}
			return x;
		} catch (CloneNotSupportedException e) {
			// unreachable: IntIteratorFlyweight implements Cloneable
			throw new IllegalStateException(e);
		}
	}

	/**
	 * Exhausted once every chunk has been consumed.
	 */
	@Override
	public boolean hasNext() {
		return this.pos < Objects.requireNonNull(
			this.roaringBitmap, "flyweight iterator has not been wrapped around a bitmap").highLowContainer.size();
	}

	/**
	 * Returns the next value — the current chunk's high bits OR-ed onto the container-local char — and
	 * rolls onto the next chunk once the current one is drained.
	 */
	@Override
	public int next() {
		final PeekableCharIterator cursor = Objects.requireNonNull(
			this.iter, "IntIteratorFlyweight has no active cursor (not wrapped or exhausted)");
		final int x = cursor.nextAsInt() | this.hs;
		if (!cursor.hasNext()) {
			++this.pos;
			nextContainer();
		}
		return x;
	}

	/**
	 * Binds {@link #iter} to a char cursor over the chunk at {@link #pos}, choosing and re-wrapping
	 * the cached cursor for the container's shape, and caches the chunk's pre-shifted high bits in
	 * {@link #hs}.
	 */
	private void nextContainer() {
		final PersistentRoaringBitmap bitmap = Objects.requireNonNull(
			this.roaringBitmap, "flyweight iterator has not been wrapped around a bitmap");
		if (this.pos < bitmap.highLowContainer.size()) {

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
	 * Re-targets this reusable iterator at bitmap `r`, resetting all cursor state to its first chunk
	 * so one instance can be recycled without allocation.
	 *
	 * @param r bitmap to be iterated over
	 */
	public void wrap(@Nonnull final PersistentRoaringBitmap r) {
		this.hs = 0;
		this.pos = 0;
		this.roaringBitmap = r;
		this.nextContainer();
	}

	/**
	 * Skips forward in two phases: first drops whole chunks whose high 16 bits are below `minval`,
	 * then — if a chunk shares `minval`'s high bits — delegates the low-bits skip to that chunk's
	 * cursor.
	 */
	@Override
	public void advanceIfNeeded(final int minval) {
		while (hasNext() && ((this.hs >>> 16) < (minval >>> 16))) {
			++this.pos;
			nextContainer();
		}
		if (hasNext() && ((this.hs >>> 16) == (minval >>> 16))) {
			final PeekableCharIterator cursor = Objects.requireNonNull(
				this.iter, "IntIteratorFlyweight has no active cursor (not wrapped or exhausted)");
			cursor.advanceIfNeeded(Util.lowbits(minval));
			if (!cursor.hasNext()) {
				++this.pos;
				nextContainer();
			}
		}
	}

	/**
	 * Returns the upcoming value — the current char OR-ed with the chunk's high bits — without consuming it.
	 */
	@Override
	public int peekNext() {
		return (Objects.requireNonNull(
			this.iter, "IntIteratorFlyweight has no active cursor (not wrapped or exhausted)").peekNext()) | this.hs;
	}
}
