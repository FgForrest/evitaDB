package io.evitadb.roaringbitmap;

import static io.evitadb.roaringbitmap.Util.highbits;
import static io.evitadb.roaringbitmap.Util.lowbits;
import static io.evitadb.roaringbitmap.Util.partialRadixSort;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.function.Supplier;

/**
 * Constant-memory variant of {@link ContainerAppender}. Instead of growing a container per
 * high-16-bit key, it sets bits directly into a single reused `long[1024]` word buffer — a fully
 * dense bitmap covering one 65536-value chunk — and materializes that buffer into the best-fitting
 * container only when the key advances or on {@link #flush()}. The buffer is then zeroed and reused
 * for the next chunk, so the appender's footprint stays fixed at ~8 KB no matter how many keys or
 * values are written, sidestepping the per-container array/bitmap growth of the general appender.
 *
 * Prefer this variant when building large bitmaps where allocation churn from growing containers
 * dominates. It is a poorer fit for very sparse data spread thinly across many keys, since every
 * touched key still pays for a full dense-buffer scan when its chunk is turned into a container.
 *
 * Append protocol matches {@link ContainerAppender}: values are expected in ascending order, keys
 * that drop below the current mark fall back to a direct add on the underlying bitmap, and the last
 * chunk is only appended by {@link #flush()} — so flushing when done is mandatory, while flushing
 * too often defeats the batching. Benchmark before adopting. Instances are stateful and not
 * thread-safe.
 *
 * ```java
 * RoaringBitmapWriter<PersistentRoaringBitmap> writer =
 * RoaringBitmapWriter.writer().constantMemory().get();
 * for (int i : ...) {
 * writer.add(i);
 * }
 * writer.flush(); // important
 * ```
 */
class ConstantMemoryContainerAppender<
	T extends BitmapDataProvider & AppendableStorage<Container>>
	implements RoaringBitmapWriter<T> {

	/**
	 * Whether {@link #addMany(int...)} partially radix-sorts a batch by high 16 bits first.
	 */
	private final boolean doPartialSort;
	/**
	 * Whether the chunk container is run-optimized before being appended to the underlying storage.
	 */
	private final boolean runCompress;
	/**
	 * Word count of the reused buffer: `1024` longs = 65536 bits = one fully dense 16-bit chunk.
	 */
	private static final int WORD_COUNT = 1 << 10;
	/**
	 * Reused word buffer holding the set bits of the current chunk; zeroed after every append.
	 */
	@Nonnull private final long[] bitmap;
	/**
	 * Factory for a fresh underlying bitmap; invoked at construction and on reset.
	 */
	@Nonnull private final Supplier<T> newUnderlying;
	/**
	 * The bitmap being assembled and handed out by {@link #getUnderlying()}.
	 */
	@Nonnull private T underlying;
	/**
	 * Whether any bit has been set in {@link #bitmap} since the last append; skips flushing an all-zero chunk.
	 */
	private boolean dirty = false;
	/**
	 * High 16 bits of the chunk currently buffered — the mark that later keys must not drop below without taking the slow path.
	 */
	private int currentKey;

	/**
	 * Initialize an ConstantMemoryContainerAppender with a receiving bitmap
	 *
	 * @param doPartialSort indicates whether to sort the upper 16 bits of input data in addMany
	 * @param runCompress   whether to run compress appended containers
	 * @param newUnderlying supplier of bitmaps where the data gets written
	 */
	ConstantMemoryContainerAppender(
		final boolean doPartialSort,
		final boolean runCompress,
		@Nonnull final Supplier<T> newUnderlying
	) {
		this.newUnderlying = newUnderlying;
		this.underlying = newUnderlying.get();
		this.doPartialSort = doPartialSort;
		this.runCompress = runCompress;
		this.bitmap = new long[WORD_COUNT];
	}

	/**
	 * Returns the bitmap being assembled. Bits still sitting in the reused buffer are not visible
	 * until {@link #flush()} appends them, so call `flush` first when a complete result is needed.
	 *
	 * @return the underlying bitmap, never `null`
	 */
	@Nonnull
	@Override
	public T getUnderlying() {
		return this.underlying;
	}

	/**
	 * Adds the value to the underlying bitmap. The data might
	 * be added to a temporary buffer. You should call "flush"
	 * when you are done.
	 *
	 * @param value the value to add.
	 */
	@Override
	public void add(final int value) {
		final int key = (highbits(value));
		if (key != this.currentKey) {
			if (key < this.currentKey) {
				this.underlying.add(value);
				return;
			} else {
				appendToUnderlying();
				this.currentKey = key;
			}
		}
		final int low = (lowbits(value));
		this.bitmap[(low >>> 6)] |= (1L << low);
		this.dirty = true;
	}

	@Override
	public void addMany(@Nonnull final int... values) {
		if (this.doPartialSort) {
			partialRadixSort(values);
		}
		for (final int value : values) {
			add(value);
		}
	}

	@Override
	public void add(final long min, final long max) {
		appendToUnderlying();
		this.underlying.add(min, max);
		final int mark = (int) ((max >>> 16) + 1);
		if (this.currentKey < mark) {
			this.currentKey = mark;
		}
	}

	/**
	 * Ensures that any buffered additions are flushed to the underlying bitmap.
	 */
	@Override
	public void flush() {
		this.currentKey += appendToUnderlying();
	}

	@Override
	public void reset() {
		this.currentKey = 0;
		this.underlying = this.newUnderlying.get();
		this.dirty = false;
	}

	/**
	 * Wraps the reused word buffer in the smallest container that fits. A lazy {@link BitmapContainer}
	 * (cardinality left as `-1` to defer counting) is repaired down to an array or run container when
	 * that is cheaper, then optionally run-optimized. When the result is still a
	 * {@link BitmapContainer} it is cloned, because the buffer it wraps is about to be zeroed and
	 * reused for the next chunk and must not be aliased by the appended container.
	 *
	 * @return the container to append, never aliasing the reused buffer, never `null`
	 */
	@Nonnull
	private Container chooseBestContainer() {
		Container container = new BitmapContainer(this.bitmap, -1).repairAfterLazy();
		if (this.runCompress) {
			container = container.runOptimize();
		}
		return container instanceof BitmapContainer ? container.clone() : container;
	}

	/**
	 * Materializes the current chunk from the reused buffer via {@link #chooseBestContainer()},
	 * appends it under {@link #currentKey}, then zeroes the buffer and clears the dirty flag ready for
	 * the next chunk. Because it always scans and refills the full buffer, each flush costs
	 * `O(WORD_COUNT)` words regardless of how many bits the chunk actually held.
	 *
	 * @return `1` when a dirty chunk was appended, so the caller can advance the key; `0` when nothing
	 * had been buffered
	 */
	private int appendToUnderlying() {
		if (this.dirty) {
			assert this.currentKey <= 0xFFFF;
			this.underlying.append((char) this.currentKey, chooseBestContainer());
			Arrays.fill(this.bitmap, 0L);
			this.dirty = false;
			return 1;
		}
		return 0;
	}
}
