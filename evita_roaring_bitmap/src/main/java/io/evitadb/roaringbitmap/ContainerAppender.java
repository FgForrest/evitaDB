package io.evitadb.roaringbitmap;

import static io.evitadb.roaringbitmap.Util.highbits;
import static io.evitadb.roaringbitmap.Util.lowbits;
import static io.evitadb.roaringbitmap.Util.partialRadixSort;

import javax.annotation.Nonnull;
import java.util.function.Supplier;

/**
 * Builds a roaring bitmap by buffering values into one container at a time and appending each
 * completed container to the underlying key-ordered storage. This is the general-purpose appender:
 * the buffered `container` grows and switches representation (array to bitmap to run) as values
 * accumulate, so its peak memory scales with the densest 65536-value chunk. When a fixed footprint
 * matters more than avoiding a dense buffer per key, prefer {@link ConstantMemoryContainerAppender}.
 *
 * Append protocol: values are expected in ascending order. Each {@link #add(int)} writes into the
 * container for the current high-16-bit key; when a value carries a higher key the buffered
 * container is flushed to the underlying storage and a fresh one started. Values whose key falls
 * *below* the current mark take a slow path that adds them straight to the finished underlying
 * bitmap. The last buffered container is only appended by {@link #flush()}, so flushing when done is
 * mandatory — though flushing more often than necessary defeats the batching that makes this class
 * fast. The payoff is workload-dependent; benchmark before adopting.
 *
 * Instances are stateful builders and are not thread-safe.
 *
 * ```java
 * RoaringBitmapWriter<PersistentRoaringBitmap> writer = RoaringBitmapWriter.writer().get();
 * for (int i : ...) {
 * writer.add(i);
 * }
 * writer.flush(); // important
 * ```
 */
class ContainerAppender<
	C extends WordStorage<C>, T extends BitmapDataProvider & AppendableStorage<C>>
	implements RoaringBitmapWriter<T> {

	/**
	 * Whether {@link #addMany(int...)} partially radix-sorts a batch by high 16 bits first.
	 */
	private final boolean doPartialSort;
	/**
	 * Whether each container is run-optimized before being appended to the underlying storage.
	 */
	private final boolean runCompress;
	/**
	 * Factory for empty buffer containers; invoked at construction, after every append, and on reset.
	 */
	@Nonnull private final Supplier<C> newContainer;
	/**
	 * Factory for a fresh underlying bitmap; invoked at construction and on reset.
	 */
	@Nonnull private final Supplier<T> newUnderlying;
	/**
	 * Buffer accumulating the low 16 bits of values sharing {@link #currentKey}; replaced when a mutation promotes it to a denser representation and after each append.
	 */
	@Nonnull private C container;
	/**
	 * The bitmap being assembled and handed out by {@link #getUnderlying()}.
	 */
	@Nonnull private T underlying;
	/**
	 * High 16 bits of the chunk currently buffered — the mark that later keys must not drop below without taking the slow path.
	 */
	private int currentKey;

	/**
	 * Initializes an appender writing into a freshly supplied underlying bitmap.
	 *
	 * @param doPartialSort whether {@link #addMany(int...)} partially sorts a batch by high 16 bits
	 * @param runCompress   whether to run-optimize each container before appending it
	 * @param newUnderlying supplier of the bitmap the assembled containers are appended to
	 * @param newContainer  supplier of empty buffer containers (array or run)
	 */
	ContainerAppender(
		final boolean doPartialSort,
		final boolean runCompress,
		@Nonnull final Supplier<T> newUnderlying,
		@Nonnull final Supplier<C> newContainer
	) {
		this.doPartialSort = doPartialSort;
		this.runCompress = runCompress;
		this.newUnderlying = newUnderlying;
		this.underlying = newUnderlying.get();
		this.newContainer = newContainer;
		this.container = newContainer.get();
	}

	/**
	 * Returns the bitmap being assembled. Values still sitting in the buffer are not visible until
	 * {@link #flush()} appends them, so call `flush` first when a complete result is needed.
	 *
	 * @return the underlying bitmap, never `null`
	 */
	@Nonnull
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
	public void add(int value) {
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
		final C tmp = this.container.add(lowbits(value));
		if (tmp != this.container) {
			this.container = tmp;
		}
	}

	@Override
	public void add(long min, long max) {
		appendToUnderlying();
		this.underlying.add(min, max);
		final int mark = (int) ((max >>> 16) + 1);
		if (this.currentKey < mark) {
			this.currentKey = mark;
		}
	}

	@Override
	public void addMany(@Nonnull final int... values) {
		if (this.doPartialSort) {
			partialRadixSort(values);
		}
		for (final int i : values) {
			add(i);
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
		this.container = this.newContainer.get();
		this.underlying = this.newUnderlying.get();
	}

	/**
	 * Flushes the buffered container under {@link #currentKey} and starts a fresh empty one. Run-
	 * optimizes the container first when `runCompress` is set. The append itself is amortized `O(1)`;
	 * an optional `runOptimize` adds a single `O(container size)` scan.
	 *
	 * @return `1` when a non-empty container was appended, so the caller can advance the key; `0` when
	 * the buffer was empty and nothing was written
	 */
	private int appendToUnderlying() {
		if (!this.container.isEmpty()) {
			assert this.currentKey <= 0xFFFF;
			this.underlying.append(
				(char) this.currentKey, this.runCompress ? this.container.runOptimize() : this.container);
			this.container = this.newContainer.get();
			return 1;
		}
		return 0;
	}
}
