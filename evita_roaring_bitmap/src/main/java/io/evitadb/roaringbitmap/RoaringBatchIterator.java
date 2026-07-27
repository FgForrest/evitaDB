package io.evitadb.roaringbitmap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * {@link BatchIterator} over an entire {@link PersistentRoaringBitmap}: it walks the bitmap's
 * {@link RoaringArray} chunk by chunk and delegates each chunk to a per-container
 * {@link ContainerBatchIterator}, stitching their batches into one ascending stream of 32-bit
 * values.
 *
 * To keep large scans allocation-free it caches one reusable cursor per container shape
 * ({@link ArrayBatchIterator}, {@link BitmapBatchIterator}, {@link RunBatchIterator}) and re-targets
 * it via `wrap(...)` when crossing a chunk boundary instead of allocating a fresh cursor each time.
 */
public final class RoaringBatchIterator implements BatchIterator {

	/**
	 * Chunk store being scanned; supplies the container and its 16-bit key at each index.
	 */
	@Nonnull private final RoaringArray highLowContainer;
	/**
	 * Index of the chunk currently being iterated within {@link #highLowContainer}.
	 */
	private int index = 0;
	/**
	 * High 16 bits of the current chunk, pre-shifted into the top half so it can be added directly
	 * onto each container-local value to rebuild the full 32-bit value.
	 */
	private int key;
	/**
	 * Cursor over the current chunk, `null` once every chunk has been consumed (drives {@link #hasNext}).
	 */
	@Nullable private ContainerBatchIterator iterator;
	/**
	 * Cached array-chunk cursor, lazily created and re-targeted via `wrap(...)` to avoid per-chunk allocation.
	 */
	@Nullable private ArrayBatchIterator arrayBatchIterator = null;
	/**
	 * Cached bitmap-chunk cursor, lazily created and re-targeted via `wrap(...)` to avoid per-chunk allocation.
	 */
	@Nullable private BitmapBatchIterator bitmapBatchIterator = null;
	/**
	 * Cached run-chunk cursor, lazily created and re-targeted via `wrap(...)` to avoid per-chunk allocation.
	 */
	@Nullable private RunBatchIterator runBatchIterator = null;

	/**
	 * Creates an iterator positioned on the first chunk of `highLowContainer`.
	 *
	 * @param highLowContainer chunk store of the bitmap to scan
	 */
	public RoaringBatchIterator(@Nonnull final RoaringArray highLowContainer) {
		this.highLowContainer = highLowContainer;
		nextIterator();
	}

	/**
	 * Fills `buffer` by draining the current chunk and rolling onto the next until the buffer is full
	 * or the bitmap is exhausted, so a single batch may span several chunks.
	 */
	@Override
	public int nextBatch(@Nonnull final int[] buffer) {
		int consumed = 0;
		while (this.iterator != null && consumed < buffer.length) {
			consumed += this.iterator.next(this.key, buffer, consumed);
			if (consumed < buffer.length || !this.iterator.hasNext()) {
				nextContainer();
			}
		}
		return consumed;
	}

	/**
	 * Exhausted exactly when the current-chunk cursor has been cleared to `null`.
	 */
	@Override
	public boolean hasNext() {
		return null != this.iterator;
	}

	/**
	 * Forks an independent cursor: the active chunk cursor is cloned, but the cached per-shape cursors
	 * are reset to `null` so the fork rebuilds its own and never shares this iterator's scratch
	 * cursors.
	 */
	@Nonnull
	@Override
	public BatchIterator clone() {
		try {
			final RoaringBatchIterator it = (RoaringBatchIterator) super.clone();
			if (null != this.iterator) {
				it.iterator = this.iterator.clone();
			}
			it.arrayBatchIterator = null;
			it.bitmapBatchIterator = null;
			it.runBatchIterator = null;
			return it;
		} catch (CloneNotSupportedException e) {
			// won't happen
			throw new IllegalStateException();
		}
	}

	/**
	 * Skips forward in two phases: first drops whole chunks whose high 16 bits are below `target`,
	 * then — if a chunk shares `target`'s high bits — delegates the low-bits skip to that chunk's
	 * cursor.
	 */
	@Override
	public void advanceIfNeeded(int target) {
		while (null != this.iterator && this.key >>> 16 < target >>> 16) {
			nextContainer();
		}
		if (null != this.iterator && this.key >>> 16 == target >>> 16) {
			this.iterator.advanceIfNeeded((char) target);
			if (!this.iterator.hasNext()) {
				nextContainer();
			}
		}
	}

	/**
	 * Advances to the next chunk index and rebuilds the delegate cursor for it.
	 */
	private void nextContainer() {
		++this.index;
		nextIterator();
	}

	/**
	 * Releases the previous chunk's container, then binds {@link #iterator} to a cursor over the chunk
	 * at {@link #index} — reusing the cached per-shape cursor — and caches its pre-shifted key. Clears
	 * {@link #iterator} to `null` once no chunks remain.
	 */
	private void nextIterator() {
		if (null != this.iterator) {
			this.iterator.releaseContainer();
		}
		if (this.index < this.highLowContainer.size()) {
			final Container container = this.highLowContainer.getContainerAtIndex(this.index);
			if (container instanceof ArrayContainer) {
				nextIterator((ArrayContainer) container);
			} else if (container instanceof BitmapContainer) {
				nextIterator((BitmapContainer) container);
			} else if (container instanceof RunContainer) {
				nextIterator((RunContainer) container);
			}
			this.key = this.highLowContainer.getKeyAtIndex(this.index) << 16;
		} else {
			this.iterator = null;
		}
	}

	/**
	 * Lazily creates or re-wraps the cached {@link ArrayBatchIterator} for `array`.
	 */
	private void nextIterator(@Nonnull final ArrayContainer array) {
		if (null == this.arrayBatchIterator) {
			this.arrayBatchIterator = new ArrayBatchIterator(array);
		} else {
			this.arrayBatchIterator.wrap(array);
		}
		this.iterator = this.arrayBatchIterator;
	}

	/**
	 * Lazily creates or re-wraps the cached {@link BitmapBatchIterator} for `bitmap`.
	 */
	private void nextIterator(@Nonnull final BitmapContainer bitmap) {
		if (null == this.bitmapBatchIterator) {
			this.bitmapBatchIterator = new BitmapBatchIterator(bitmap);
		} else {
			this.bitmapBatchIterator.wrap(bitmap);
		}
		this.iterator = this.bitmapBatchIterator;
	}

	/**
	 * Lazily creates or re-wraps the cached {@link RunBatchIterator} for `run`.
	 */
	private void nextIterator(@Nonnull final RunContainer run) {
		if (null == this.runBatchIterator) {
			this.runBatchIterator = new RunBatchIterator(run);
		} else {
			this.runBatchIterator.wrap(run);
		}
		this.iterator = this.runBatchIterator;
	}
}
