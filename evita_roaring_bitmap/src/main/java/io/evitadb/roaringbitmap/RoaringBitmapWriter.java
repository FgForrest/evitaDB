package io.evitadb.roaringbitmap;

import javax.annotation.Nonnull;
import java.util.function.Supplier;

/**
 * Builder-and-writer contract for assembling a roaring bitmap by appending values in key order.
 *
 * A writer buffers additions and flushes them into the best container representation before
 * appending to the underlying bitmap, which makes bulk construction considerably faster than
 * repeated {@code add} calls on a finished bitmap. Instances are obtained through the fluent
 * {@link Wizard} builder returned by {@link #writer()} and can be reused via {@link #reset()}.
 *
 * As a {@link Supplier}, {@link #get()} flushes any pending values and returns the bitmap.
 *
 * @param <T> the concrete bitmap data provider being written to
 */
public interface RoaringBitmapWriter<T extends BitmapDataProvider> extends Supplier<T> {

	/**
	 * Creates a builder for writers backed by {@link PersistentRoaringBitmap} containers.
	 *
	 * @return a fresh wizard configured with default options
	 */
	@Nonnull
	static Wizard<Container, PersistentRoaringBitmap> writer() {
		return new RoaringBitmapWizard();
	}

	/**
	 * Fluent builder collecting the options used to construct a {@link RoaringBitmapWriter}.
	 *
	 * The builder is self-typed so that each option returns the same wizard for chaining, and calling
	 * {@link #get()} on it is repeatable because the wizard is always kept in a valid state.
	 *
	 * @param <C> the word-storage container type buffered before appending
	 * @param <T> the bitmap data provider the built writer appends to
	 */
	abstract class Wizard<
		C extends WordStorage<C>, T extends BitmapDataProvider & AppendableStorage<C>>
		implements Supplier<RoaringBitmapWriter<T>> {

		protected int initialCapacity = RoaringArray.INITIAL_CAPACITY;
		protected boolean constantMemory;
		protected boolean partiallySortValues = false;
		protected boolean runCompress = true;
		protected Supplier<C> containerSupplier;
		protected int expectedContainerSize = 16;

		/**
		 * Creates a wizard defaulting to array containers.
		 */
		Wizard() {
			this.containerSupplier = arraySupplier();
		}

		/**
		 * Chooses array containers, best when most containers are expected to be sparse.
		 *
		 * @return this wizard
		 */
		@Nonnull
		public Wizard<C, T> optimiseForArrays() {
			this.containerSupplier = arraySupplier();
			return this;
		}

		/**
		 * Chooses run containers, best when the bitmap is expected to be RLE compressible.
		 *
		 * @return this wizard
		 */
		@Nonnull
		public Wizard<C, T> optimiseForRuns() {
			this.containerSupplier = runSupplier();
			return this;
		}

		/**
		 * Controls whether run compression is applied on the fly rather than only at the end.
		 *
		 * @param runCompress `true` to run-compress while building, `false` to defer it
		 * @return this wizard
		 */
		@Nonnull
		public Wizard<C, T> runCompress(final boolean runCompress) {
			this.runCompress = runCompress;
			return this;
		}

		/**
		 * Hints how densely each 65536-value range is expected to be populated and picks a matching
		 * container strategy: arrays for sparse, constant memory for medium, runs for dense ranges.
		 *
		 * @param count how many values are expected to fall within any 65536-value range
		 * @return this wizard
		 */
		@Nonnull
		public Wizard<C, T> expectedValuesPerContainer(final int count) {
			sanityCheck(count);
			this.expectedContainerSize = count;
			if (count < ArrayContainer.DEFAULT_MAX_SIZE) {
				return optimiseForArrays();
			} else if (count < 1 << 14) {
				return constantMemory();
			} else {
				return optimiseForRuns();
			}
		}

		/**
		 * Buffers all writes into a single 8kB buffer before appending the best container
		 * representation, overriding any {@code optimiseFor...} choice.
		 *
		 * @return this wizard
		 */
		@Nonnull
		public Wizard<C, T> constantMemory() {
			this.constantMemory = true;
			return this;
		}

		/**
		 * Influences the default container choice from the expected overall bitmap density.
		 *
		 * @param density the expected density in `[0.0, 1.0]`
		 * @return this wizard
		 */
		@Nonnull
		public Wizard<C, T> expectedDensity(final double density) {
			return expectedValuesPerContainer((int) (0xFFFF * density));
		}

		/**
		 * Estimates the number of prefix keys from the value range, assuming every prefix in the range
		 * is used. A good heuristic for a contiguous bitmap and a poor one for two far-apart values.
		 *
		 * @param min the inclusive minimum value
		 * @param max the exclusive maximum value
		 * @return this wizard
		 */
		@Nonnull
		public Wizard<C, T> expectedRange(final long min, final long max) {
			return initialCapacity((int) ((max - min) >>> 16) + 1);
		}

		/**
		 * Pre-sizes the prefix key array when its size can be precalculated or estimated, potentially
		 * saving many array allocations while building the bitmap.
		 *
		 * @param count an estimate of the number of prefix keys required
		 * @return this wizard
		 */
		@Nonnull
		public Wizard<C, T> initialCapacity(final int count) {
			sanityCheck(count);
			this.initialCapacity = count;
			return this;
		}

		/**
		 * Enables partial radix sorting of values, which allocates `O(n)` temporary memory but can
		 * significantly speed up adding unsorted values.
		 *
		 * @return this wizard
		 */
		@Nonnull
		public Wizard<C, T> doPartialRadixSort() {
			this.partiallySortValues = true;
			return this;
		}

		/**
		 * Supplies fresh array containers sized for the configured expected container size.
		 *
		 * @return a supplier of empty array containers
		 */
		@Nonnull
		protected abstract Supplier<C> arraySupplier();

		/**
		 * Supplies fresh run containers.
		 *
		 * @return a supplier of empty run containers
		 */
		@Nonnull
		protected abstract Supplier<C> runSupplier();

		/**
		 * Creates the underlying bitmap the built writer will append to.
		 *
		 * @param initialCapacity the initial prefix-array capacity
		 * @return a new empty underlying bitmap
		 */
		@Nonnull
		protected abstract T createUnderlying(int initialCapacity);

		/**
		 * Builds a writer from the configured options. Repeatable, because the wizard is always kept in
		 * a valid state.
		 *
		 * @return a new writer
		 */
		@Nonnull
		@Override
		public RoaringBitmapWriter<T> get() {
			final int capacity = this.initialCapacity;
			return new ContainerAppender<>(
				this.partiallySortValues, this.runCompress, () -> createUnderlying(capacity),
				this.containerSupplier
			);
		}

		/**
		 * Validates that a capacity or count is within the allowed `[0, 65536)` range.
		 *
		 * @param count the value to validate
		 * @throws IllegalArgumentException if `count` is negative or not below 65536
		 */
		private static void sanityCheck(final int count) {
			if (count >= 0xFFFF) {
				throw new IllegalArgumentException(count + " > 65536");
			}
			if (count < 0) {
				throw new IllegalArgumentException(count + " < 0");
			}
		}
	}

	/**
	 * Wizard specialization producing writers backed by {@link Container} storage.
	 *
	 * @param <T> the persistent roaring bitmap subtype being written to
	 */
	abstract class RoaringWizard<T extends PersistentRoaringBitmap> extends Wizard<Container, T> {

		@Nonnull
		@Override
		protected Supplier<Container> arraySupplier() {
			final int size = this.expectedContainerSize;
			return () -> new ArrayContainer(size);
		}

		@Nonnull
		@Override
		protected Supplier<Container> runSupplier() {
			return RunContainer::new;
		}

		@Nonnull
		@Override
		public RoaringBitmapWriter<T> get() {
			if (this.constantMemory) {
				final int capacity = this.initialCapacity;
				return new ConstantMemoryContainerAppender<>(
					this.partiallySortValues, this.runCompress, () -> createUnderlying(capacity));
			}
			return super.get();
		}
	}

	/**
	 * Concrete wizard building writers over a plain {@link PersistentRoaringBitmap}.
	 */
	class RoaringBitmapWizard extends RoaringWizard<PersistentRoaringBitmap> {

		@Nonnull
		@Override
		protected PersistentRoaringBitmap createUnderlying(final int initialCapacity) {
			return new PersistentRoaringBitmap(new RoaringArray(initialCapacity));
		}
	}

	/**
	 * Returns the bitmap being written to without flushing pending values.
	 *
	 * @return the underlying bitmap
	 */
	@Nonnull
	T getUnderlying();

	/**
	 * Buffers a single value to be added to the bitmap.
	 *
	 * @param value the value to add
	 */
	void add(int value);

	/**
	 * Buffers a range of values to be added to the bitmap.
	 *
	 * @param min the inclusive minimum value
	 * @param max the exclusive maximum value
	 */
	void add(long min, long max);

	/**
	 * Buffers many values to be added to the bitmap.
	 *
	 * @param values the values to add
	 */
	void addMany(@Nonnull int... values);

	/**
	 * Flushes all pending buffered values into the underlying bitmap.
	 */
	void flush();

	/**
	 * Flushes pending values and returns the completed bitmap.
	 *
	 * @return the underlying bitmap
	 */
	@Nonnull
	default T get() {
		flush();
		return getUnderlying();
	}

	/**
	 * Resets the writer for reuse, releasing the reference to the underlying bitmap.
	 */
	void reset();
}
