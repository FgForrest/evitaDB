/*
 * (c) the authors Licensed under the Apache License, Version 2.0.
 */

package io.evitadb.roaringbitmap;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.PriorityQueue;

/**
 * Static strategies for combining *many* bitmaps at once — `and` / `or` / `xor` plus
 * cardinality-only counts and intersection tests — each tuned for a different input shape.
 *
 * The plain {@link #and}, {@link #or} and {@link #xor} entry points pick a sensible default; the
 * specialised variants let a caller force one of three algorithmic families:
 *
 * - **naive** (`naive_and` / `naive_or` / `naive_xor`) folds the inputs pairwise into a single
 * accumulator. Linear in the number of bitmaps and allocates the least, so it wins for a handful
 * of inputs. `naive_and` additionally seeds from the smallest input and bails out as soon as the
 * running result becomes empty.
 * - **horizontal** (`horizontal_or` / `horizontal_xor`) merges the inputs key by key through a
 * {@link PriorityQueue} of {@link ContainerPointer} cursors, combining all containers that share
 * a 16-bit key in one lazy pass. Linearithmic (`O(n log n)`) in the number of bitmaps and keeps
 * peak memory low, since only the containers for the current key are live at once.
 * - **priority-queue** (`priorityqueue_or` / `priorityqueue_xor`) repeatedly merges the two
 * currently smallest (by serialized size) partial results, Huffman-style, so the cheapest merges
 * happen first. Also linearithmic in the number of bitmaps.
 *
 * For AND the `workShy` variants intersect the container keys first and materialise only the
 * containers that can possibly survive, skipping keys absent from any input — a large win when the
 * inputs overlap little. All methods return a non-`null` bitmap, empty when there is nothing to
 * aggregate.
 *
 * @author Daniel Lemire
 */
public final class FastAggregation {

	/**
	 * Failure message asserting that a {@link ContainerPointer} polled from an aggregation priority
	 * queue exposes a live container. Pointers are enqueued only while {@code getContainer() != null}
	 * (and re-checked before re-adding), so every polled pointer is guaranteed to have a non-null
	 * container until it is advanced past its end.
	 */
	private static final String LIVE_CONTAINER_POINTER_MSG =
		"live container pointer polled from the aggregation queue must have a non-null container";

	/**
	 * Failure message asserting that a poll from a queue proven non-empty returns a non-null element.
	 * Every guarded {@code poll()} runs only while its queue is known to hold at least one entry — a
	 * `!isEmpty()` or `size() > 1` guard, or a non-empty start — so the polled value cannot be null.
	 */
	private static final String NON_EMPTY_QUEUE_MSG =
		"poll from a queue proven non-empty must return a non-null element";

	/**
	 * Aggregates all inputs with a boolean AND (set intersection).
	 *
	 * General-purpose entry point for an unbounded stream of bitmaps: it delegates to
	 * {@link #naive_and(Iterator)}, folding the inputs pairwise in linear time.
	 *
	 * @param bitmaps input bitmaps, consumed in iteration order
	 * @return their intersection; an empty (never `null`) bitmap when the iterator is empty
	 */
	@Nonnull
	public static PersistentRoaringBitmap and(@Nonnull final Iterator<? extends PersistentRoaringBitmap> bitmaps) {
		return naive_and(bitmaps);
	}

	/**
	 * Aggregates all inputs with a boolean AND (set intersection).
	 *
	 * Picks a strategy by input count: for more than 10 bitmaps it uses the key-intersecting
	 * {@link #workShyAnd(long[], PersistentRoaringBitmap...)} (which skips keys missing from any
	 * input), otherwise the cheaper {@link #naive_and(PersistentRoaringBitmap...)}.
	 *
	 * @param bitmaps input bitmaps
	 * @return their intersection; an empty (never `null`) bitmap when no input is given
	 */
	@Nonnull
	public static PersistentRoaringBitmap and(@Nonnull final PersistentRoaringBitmap... bitmaps) {
		if (bitmaps.length > 10) {
			return workShyAnd(new long[1024], bitmaps);
		}
		return naive_and(bitmaps);
	}

	/**
	 * Aggregates all inputs with a boolean AND, reusing a caller-supplied scratch buffer.
	 *
	 * Behaves like {@link #and(PersistentRoaringBitmap...)} but, on the `workShyAnd` path (more than
	 * 10 bitmaps), borrows `aggregationBuffer` instead of allocating one, letting a hot loop
	 * aggregate repeatedly without churning garbage. The buffer is zeroed again before returning, so
	 * the same array can be passed back in.
	 *
	 * @param aggregationBuffer scratch space of at least 1024 longs; reset to zero on return
	 * @param bitmaps           input bitmaps
	 * @return their intersection; an empty (never `null`) bitmap when no input is given
	 * @throws IllegalArgumentException if more than 10 bitmaps are given and the buffer is shorter
	 *                                  than 1024 longs
	 */
	@Nonnull
	public static PersistentRoaringBitmap and(
		@Nonnull final long[] aggregationBuffer, @Nonnull final PersistentRoaringBitmap... bitmaps) {
		if (bitmaps.length > 10) {
			if (aggregationBuffer.length < 1024) {
				throw new IllegalArgumentException("buffer should have at least 1024 elements.");
			}
			try {
				return workShyAnd(aggregationBuffer, bitmaps);
			} finally {
				Arrays.fill(aggregationBuffer, 0L);
			}
		}
		return naive_and(bitmaps);
	}

	/**
	 * Counts the elements in the AND aggregate without materialising the intersection bitmap.
	 *
	 * Cheaper than `and(...).getCardinality()` because it never builds the result: it short-circuits
	 * for 0, 1 or 2 inputs and otherwise counts through the key-intersecting work-shy strategy.
	 *
	 * @param bitmaps input bitmaps
	 * @return the cardinality of their intersection; `0` when no input is given
	 */
	public static int andCardinality(@Nonnull final PersistentRoaringBitmap... bitmaps) {
		switch (bitmaps.length) {
			case 0:
				return 0;
			case 1:
				return bitmaps[0].getCardinality();
			case 2:
				return PersistentRoaringBitmap.andCardinality(bitmaps[0], bitmaps[1]);
			default:
				return workShyAndCardinality(bitmaps);
		}
	}

	/**
	 * Tests whether all inputs share at least one common element, stopping at the first proof.
	 *
	 * Cheaper than computing the AND when only the yes/no answer is needed: it returns as soon as a
	 * common element is found. Short-circuits for 0, 1 or 2 inputs.
	 *
	 * @param bitmaps input bitmaps
	 * @return `true` if the intersection of all inputs is non-empty; `false` for no input
	 */
	public static boolean intersects(@Nonnull final PersistentRoaringBitmap... bitmaps) {
		switch (bitmaps.length) {
			case 0:
				return false;
			case 1:
				return !bitmaps[0].isEmpty();
			case 2:
				return PersistentRoaringBitmap.intersects(bitmaps[0], bitmaps[1]);
			default:
				return intersectsMultiple(bitmaps);
		}
	}

	/**
	 * Backs {@link #intersects} for 3+ inputs: intersects the keys first, then AND-folds only the
	 * shared keys, returning at the first key whose per-container AND stays non-empty.
	 */
	private static boolean intersectsMultiple(@Nonnull final PersistentRoaringBitmap... bitmaps) {
		final long[] words = new long[1024];
		final char[] keys = Util.intersectKeys(words, bitmaps);
		if (keys.length == 0) {
			return false;
		}
		final int numKeys = keys.length;
		outer:
		for (int i = 0; i < numKeys; i++) {
			Arrays.fill(words, -1L);
			Container tmp = new BitmapContainer(words, -1);
			for (final PersistentRoaringBitmap bitmap : bitmaps) {
				final int index = bitmap.highLowContainer.getIndex(keys[i]);
				final Container container = bitmap.highLowContainer.getContainerAtIndex(index);
				// We only assign to 'tmp' when 'tmp != tmp.iand(container)'
				// as a garbage-collection optimization: we want to avoid
				// the write barrier. (Richard Startin)
				final Container and = tmp.iand(container);
				if (and != tmp) {
					tmp = and;
				}
				if (tmp.isEmpty()) {
					continue outer;
				}
			}
			if (!tmp.repairAfterLazy().isEmpty()) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Counts the elements in the OR aggregate without materialising the union bitmap.
	 *
	 * Short-circuits for 0, 1 or 2 inputs; otherwise counts key by key through a horizontal merge, so
	 * the full union is never held in memory.
	 *
	 * @param bitmaps input bitmaps
	 * @return the cardinality of their union; `0` when no input is given
	 */
	public static int orCardinality(@Nonnull final PersistentRoaringBitmap... bitmaps) {
		switch (bitmaps.length) {
			case 0:
				return 0;
			case 1:
				return bitmaps[0].getCardinality();
			case 2:
				return PersistentRoaringBitmap.orCardinality(bitmaps[0], bitmaps[1]);
			default:
				return horizontalOrCardinality(bitmaps);
		}
	}

	/**
	 * Computes the union of a stream of bitmaps.
	 *
	 * @param bitmaps input bitmaps, consumed in iteration order
	 * @return their union; an empty (never `null`) bitmap when the iterator is empty
	 * @deprecated a misnomer — it delegates to {@link #naive_or(Iterator)} rather than a horizontal
	 * merge; call {@link #naive_or(Iterator)} directly, or {@link #horizontal_or(List)}
	 * for the memory-thrifty key-by-key merge.
	 */
	@Nonnull
	@Deprecated
	public static PersistentRoaringBitmap horizontal_or(
		@Nonnull final Iterator<? extends PersistentRoaringBitmap> bitmaps
	) {
		return naive_or(bitmaps);
	}

	/**
	 * Computes the union through a memory-thrifty horizontal merge.
	 *
	 * Keeps one {@link ContainerPointer} cursor per input in a {@link PriorityQueue} keyed on the
	 * 16-bit container key, then unions all containers sharing a key in a single lazy pass before
	 * moving on. Because only the containers for the current key are live at once, peak memory stays
	 * low even for many inputs. Runs in linearithmic (`O(n log n)`) time in the number of bitmaps.
	 *
	 * @param bitmaps input bitmaps
	 * @return their union; an empty (never `null`) bitmap when the list is empty
	 * @see #or(PersistentRoaringBitmap...)
	 */
	@Nonnull
	public static PersistentRoaringBitmap horizontal_or(
		@Nonnull final List<? extends PersistentRoaringBitmap> bitmaps
	) {
		final PersistentRoaringBitmap answer = new PersistentRoaringBitmap();
		if (bitmaps.isEmpty()) {
			return answer;
		}
		final PriorityQueue<ContainerPointer> pq = new PriorityQueue<>(bitmaps.size());
		for (int k = 0; k < bitmaps.size(); ++k) {
			final ContainerPointer x = bitmaps.get(k).highLowContainer.getContainerPointer();
			if (x.getContainer() != null) {
				pq.add(x);
			}
		}

		while (!pq.isEmpty()) {
			final ContainerPointer x1 = pq.poll();
			final Container c1 = Objects.requireNonNull(x1.getContainer(), LIVE_CONTAINER_POINTER_MSG);
			if (pq.isEmpty() || (pq.peek().key() != x1.key())) {
				answer.highLowContainer.append(x1.key(), c1.clone());
				x1.advance();
				if (x1.getContainer() != null) {
					pq.add(x1);
				}
				continue;
			}
			final ContainerPointer x2 = Objects.requireNonNull(pq.poll(), NON_EMPTY_QUEUE_MSG);
			final Container c2 = Objects.requireNonNull(x2.getContainer(), LIVE_CONTAINER_POINTER_MSG);
			Container newc = c1.lazyOR(c2);
			while (!pq.isEmpty() && (pq.peek().key() == x1.key())) {

				final ContainerPointer x = Objects.requireNonNull(pq.poll(), NON_EMPTY_QUEUE_MSG);
				newc = newc.lazyIOR(Objects.requireNonNull(x.getContainer(), LIVE_CONTAINER_POINTER_MSG));
				x.advance();
				if (x.getContainer() != null) {
					pq.add(x);
				} else if (pq.isEmpty()) {
					break;
				}
			}
			newc = newc.repairAfterLazy();
			answer.highLowContainer.append(x1.key(), newc);
			x1.advance();
			if (x1.getContainer() != null) {
				pq.add(x1);
			}
			x2.advance();
			if (x2.getContainer() != null) {
				pq.add(x2);
			}
		}
		return answer;
	}

	/**
	 * Computes the union through a memory-thrifty horizontal merge.
	 *
	 * Keeps one {@link ContainerPointer} cursor per input in a {@link PriorityQueue} keyed on the
	 * 16-bit container key, then unions all containers sharing a key in a single lazy pass before
	 * moving on. Because only the containers for the current key are live at once, peak memory stays
	 * low even for many inputs. Runs in linearithmic (`O(n log n)`) time in the number of bitmaps.
	 *
	 * @param bitmaps input bitmaps
	 * @return their union; an empty (never `null`) bitmap when no input is given
	 * @see #or(PersistentRoaringBitmap...)
	 */
	@Nonnull
	public static PersistentRoaringBitmap horizontal_or(@Nonnull final PersistentRoaringBitmap... bitmaps) {
		final PersistentRoaringBitmap answer = new PersistentRoaringBitmap();
		if (bitmaps.length == 0) {
			return answer;
		}
		final PriorityQueue<ContainerPointer> pq = new PriorityQueue<>(bitmaps.length);
		for (int k = 0; k < bitmaps.length; ++k) {
			final ContainerPointer x = bitmaps[k].highLowContainer.getContainerPointer();
			if (x.getContainer() != null) {
				pq.add(x);
			}
		}

		while (!pq.isEmpty()) {
			final ContainerPointer x1 = pq.poll();
			final Container c1 = Objects.requireNonNull(x1.getContainer(), LIVE_CONTAINER_POINTER_MSG);
			if (pq.isEmpty() || (pq.peek().key() != x1.key())) {
				answer.highLowContainer.append(x1.key(), c1.clone());
				x1.advance();
				if (x1.getContainer() != null) {
					pq.add(x1);
				}
				continue;
			}
			final ContainerPointer x2 = Objects.requireNonNull(pq.poll(), NON_EMPTY_QUEUE_MSG);
			final Container c2 = Objects.requireNonNull(x2.getContainer(), LIVE_CONTAINER_POINTER_MSG);
			Container newc = c1.lazyOR(c2);
			while (!pq.isEmpty() && (pq.peek().key() == x1.key())) {

				final ContainerPointer x = Objects.requireNonNull(pq.poll(), NON_EMPTY_QUEUE_MSG);
				newc = newc.lazyIOR(Objects.requireNonNull(x.getContainer(), LIVE_CONTAINER_POINTER_MSG));
				x.advance();
				if (x.getContainer() != null) {
					pq.add(x);
				} else if (pq.isEmpty()) {
					break;
				}
			}
			newc = newc.repairAfterLazy();
			answer.highLowContainer.append(x1.key(), newc);
			x1.advance();
			if (x1.getContainer() != null) {
				pq.add(x1);
			}
			x2.advance();
			if (x2.getContainer() != null) {
				pq.add(x2);
			}
		}
		return answer;
	}

	/**
	 * Computes the symmetric difference (XOR) through a memory-thrifty horizontal merge.
	 *
	 * Mirrors {@link #horizontal_or(PersistentRoaringBitmap...)} — a {@link PriorityQueue} of
	 * {@link ContainerPointer} cursors XOR-combines all containers sharing a key in one pass — so
	 * peak memory stays low. Runs in linearithmic (`O(n log n)`) time in the number of bitmaps.
	 *
	 * @param bitmaps input bitmaps
	 * @return their symmetric difference; an empty (never `null`) bitmap when no input is given
	 * @see #xor(PersistentRoaringBitmap...)
	 */
	@Nonnull
	public static PersistentRoaringBitmap horizontal_xor(@Nonnull final PersistentRoaringBitmap... bitmaps) {
		final PersistentRoaringBitmap answer = new PersistentRoaringBitmap();
		if (bitmaps.length == 0) {
			return answer;
		}
		final PriorityQueue<ContainerPointer> pq = new PriorityQueue<>(bitmaps.length);
		for (int k = 0; k < bitmaps.length; ++k) {
			final ContainerPointer x = bitmaps[k].highLowContainer.getContainerPointer();
			if (x.getContainer() != null) {
				pq.add(x);
			}
		}

		while (!pq.isEmpty()) {
			final ContainerPointer x1 = pq.poll();
			final Container c1 = Objects.requireNonNull(x1.getContainer(), LIVE_CONTAINER_POINTER_MSG);
			if (pq.isEmpty() || (pq.peek().key() != x1.key())) {
				answer.highLowContainer.append(x1.key(), c1.clone());
				x1.advance();
				if (x1.getContainer() != null) {
					pq.add(x1);
				}
				continue;
			}
			final ContainerPointer x2 = Objects.requireNonNull(pq.poll(), NON_EMPTY_QUEUE_MSG);
			final Container c2 = Objects.requireNonNull(x2.getContainer(), LIVE_CONTAINER_POINTER_MSG);
			Container newc = c1.xor(c2);
			while (!pq.isEmpty() && (pq.peek().key() == x1.key())) {
				final ContainerPointer x = Objects.requireNonNull(pq.poll(), NON_EMPTY_QUEUE_MSG);
				newc = newc.ixor(Objects.requireNonNull(x.getContainer(), LIVE_CONTAINER_POINTER_MSG));
				x.advance();
				if (x.getContainer() != null) {
					pq.add(x);
				} else if (pq.isEmpty()) {
					break;
				}
			}
			answer.highLowContainer.append(x1.key(), newc);
			x1.advance();
			if (x1.getContainer() != null) {
				pq.add(x1);
			}
			x2.advance();
			if (x2.getContainer() != null) {
				pq.add(x2);
			}
		}
		return answer;
	}

	/**
	 * Computes the intersection by folding the inputs pairwise into an accumulator.
	 *
	 * Runs in linear time in the number of bitmaps and allocates only the accumulator, so it is the
	 * lightest strategy for a small number of inputs. Folding stops early once the running result
	 * becomes empty.
	 *
	 * Performance hint: since the accumulator is seeded from the first input, placing a tiny bitmap
	 * first shrinks it up front and speeds up the remaining folds.
	 *
	 * @param bitmaps input bitmaps, consumed in iteration order
	 * @return their intersection; an empty (never `null`) bitmap when the iterator is empty
	 */
	@Nonnull
	public static PersistentRoaringBitmap naive_and(
		@Nonnull final Iterator<? extends PersistentRoaringBitmap> bitmaps
	) {
		if (!bitmaps.hasNext()) {
			return new PersistentRoaringBitmap();
		}
		final PersistentRoaringBitmap answer = bitmaps.next().clone();
		while (bitmaps.hasNext() && !answer.isEmpty()) {
			answer.and(bitmaps.next());
		}
		return answer;
	}

	/**
	 * Computes the intersection by folding the inputs pairwise into an accumulator.
	 *
	 * Seeds the accumulator from the input with the fewest containers so the running result starts as
	 * small as possible, then folds in the rest, bailing out as soon as it becomes empty. Runs in
	 * linear time in the number of bitmaps and allocates only the accumulator, making it the lightest
	 * strategy for a handful of inputs. Unlike the {@link #naive_and(Iterator)} overload the seed is
	 * chosen automatically, so argument order does not matter here.
	 *
	 * @param bitmaps input bitmaps
	 * @return their intersection; an empty (never `null`) bitmap when no input is given
	 */
	@Nonnull
	public static PersistentRoaringBitmap naive_and(@Nonnull final PersistentRoaringBitmap... bitmaps) {
		if (bitmaps.length == 0) {
			return new PersistentRoaringBitmap();
		}
		PersistentRoaringBitmap smallest = bitmaps[0];
		for (int i = 1; i < bitmaps.length; i++) {
			final PersistentRoaringBitmap bitmap = bitmaps[i];
			if (bitmap.highLowContainer.size() < smallest.highLowContainer.size()) {
				smallest = bitmap;
			}
		}
		final PersistentRoaringBitmap answer = smallest.clone();
		for (int k = 0; k < bitmaps.length && !answer.isEmpty(); ++k) {
			if (bitmaps[k] != smallest) {
				answer.and(bitmaps[k]);
			}
		}
		return answer;
	}

	/**
	 * Computes the intersection by intersecting the container keys first, so containers absent from
	 * any input are never materialised.
	 *
	 * A bitset of the keys common to every input is built in `buffer`; only for those surviving keys
	 * are the containers gathered and AND-folded. This skips most of the per-container work when the
	 * inputs overlap little, which is why {@link #and(PersistentRoaringBitmap...)} routes large (>10)
	 * input sets here. The buffer is scratch space only.
	 *
	 * @param buffer  scratch space of at least 1024 longs (8 KB); its contents are overwritten
	 * @param bitmaps the inputs
	 * @return their intersection; an empty (never `null`) bitmap when the keys do not intersect
	 */
	@Nonnull
	public static PersistentRoaringBitmap workShyAnd(
		@Nonnull final long[] buffer, @Nonnull final PersistentRoaringBitmap... bitmaps) {
		final long[] words = buffer;
		final char[] keys = Util.intersectKeys(words, bitmaps);
		if (keys.length == 0) {
			return new PersistentRoaringBitmap();
		}
		final int numContainers = keys.length;
		final Container[][] containers = new Container[numContainers][bitmaps.length];
		for (int i = 0; i < bitmaps.length; ++i) {
			final PersistentRoaringBitmap bitmap = bitmaps[i];
			int position = 0;
			for (int j = 0; j < bitmap.highLowContainer.size; ++j) {
				final char key = bitmap.highLowContainer.keys[j];
				if ((words[key >>> 6] & (1L << key)) != 0) {
					containers[position++][i] = bitmap.highLowContainer.values[j];
				}
			}
		}

		final RoaringArray array = new RoaringArray(keys, new Container[numContainers], 0);
		outer:
		for (int i = 0; i < numContainers; ++i) {
			final Container[] slice = containers[i];
			Arrays.fill(words, -1L);
			Container tmp = new BitmapContainer(words, -1);
			for (final Container container : slice) {
				// We only assign to 'tmp' when 'tmp != tmp.iand(container)'
				// as a garbage-collection optimization: we want to avoid
				// the write barrier. (Richard Startin)
				final Container and = tmp.iand(container);
				if (and != tmp) {
					tmp = and;
				}
				if (tmp.isEmpty()) {
					continue outer;
				}
			}
			tmp = tmp.repairAfterLazy();
			if (!tmp.isEmpty()) {
				array.append(keys[i], tmp instanceof BitmapContainer ? tmp.clone() : tmp);
			}
		}
		return new PersistentRoaringBitmap(array);
	}

	/**
	 * Cardinality-only counterpart of {@link #workShyAnd}: intersects the keys, then sums the per-key
	 * AND cardinalities without building a result bitmap. Backs {@link #andCardinality}.
	 */
	private static int workShyAndCardinality(@Nonnull final PersistentRoaringBitmap... bitmaps) {
		final long[] words = new long[1024];
		final char[] keys = Util.intersectKeys(words, bitmaps);
		if (keys.length == 0) {
			return 0;
		}
		final int numKeys = keys.length;
		int cardinality = 0;
		outer:
		for (int i = 0; i < numKeys; i++) {
			Arrays.fill(words, -1L);
			Container tmp = new BitmapContainer(words, -1);
			for (final PersistentRoaringBitmap bitmap : bitmaps) {
				final int index = bitmap.highLowContainer.getIndex(keys[i]);
				final Container container = bitmap.highLowContainer.getContainerAtIndex(index);
				// We only assign to 'tmp' when 'tmp != tmp.iand(container)'
				// as a garbage-collection optimization: we want to avoid
				// the write barrier. (Richard Startin)
				final Container and = tmp.iand(container);
				if (and != tmp) {
					tmp = and;
				}
				if (tmp.isEmpty()) {
					continue outer;
				}
			}
			cardinality += tmp.repairAfterLazy().getCardinality();
		}
		return cardinality;
	}

	/**
	 * Counts the union key by key for {@link #orCardinality}: collects the set of keys present in any
	 * input, then sums the cardinality of the per-key lazy OR without materialising the union.
	 */
	private static int horizontalOrCardinality(@Nonnull final PersistentRoaringBitmap... bitmaps) {
		final long[] words = new long[1024];
		int minKey = Character.MAX_VALUE;
		int maxKey = Character.MIN_VALUE;
		for (final PersistentRoaringBitmap bitmap : bitmaps) {
			for (int i = 0; i < bitmap.highLowContainer.size(); i++) {
				final char key = bitmap.highLowContainer.getKeyAtIndex(i);
				words[key >>> 6] |= 1L << key;
				minKey = Math.min(minKey, key);
				maxKey = Math.max(maxKey, key);
			}
		}
		final int numKeys = Util.cardinalityInBitmapRange(words, minKey, maxKey + 1);
		final char[] keys = BitSetUtil.arrayContainerBufferOf(0, words.length, numKeys, words);

		int cardinality = 0;
		for (final char key : keys) {
			Arrays.fill(words, 0);
			Container tmp = new BitmapContainer(words, -1);
			for (final PersistentRoaringBitmap bitmap : bitmaps) {
				final int index = bitmap.highLowContainer.getIndex(key);
				if (index < 0) {
					continue;
				}
				final Container container = bitmap.highLowContainer.getContainerAtIndex(index);
				final Container or = tmp.lazyIOR(container);
				if (or != tmp) {
					tmp = or;
				}
			}
			cardinality += tmp.repairAfterLazy().getCardinality();
		}
		return cardinality;
	}

	/**
	 * Computes the intersection with the smallest possible extra memory, trading speed for footprint.
	 *
	 * Like {@link #workShyAnd} it intersects the keys first, but it re-fetches each container from
	 * every input per key instead of gathering them into a per-key array, so nothing beyond `buffer`
	 * is retained. Expect it to be slower than {@link #workShyAnd} for a memory saving that is often
	 * small. The caller must supply a 1024-long array pre-filled with zeroes; this is not verified.
	 *
	 * @param buffer  a 1024-long array initialised to zero (caller's responsibility)
	 * @param bitmaps the inputs
	 * @return their intersection; an empty (never `null`) bitmap when the keys do not intersect
	 * @throws IllegalArgumentException if the buffer is shorter than 1024 longs
	 */
	@Nonnull
	public static PersistentRoaringBitmap workAndMemoryShyAnd(
		@Nonnull final long[] buffer, @Nonnull final PersistentRoaringBitmap... bitmaps) {
		if (buffer.length < 1024) {
			throw new IllegalArgumentException("buffer should have at least 1024 elements.");
		}
		final long[] words = buffer;
		final char[] keys = Util.intersectKeys(words, bitmaps);
		if (keys.length == 0) {
			return new PersistentRoaringBitmap();
		}
		final int numContainers = keys.length;

		final RoaringArray array = new RoaringArray(keys, new Container[numContainers], 0);
		outer:
		for (int i = 0; i < numContainers; ++i) {
			final char MatchingKey = keys[i];
			Arrays.fill(words, -1L);
			Container tmp = new BitmapContainer(words, -1);
			for (final PersistentRoaringBitmap bitmap : bitmaps) {
				final int idx = bitmap.highLowContainer.getIndex(MatchingKey);
				final Container container = bitmap.highLowContainer.getContainerAtIndex(idx);
				// We only assign to 'tmp' when 'tmp != tmp.iand(container)'
				// as a garbage-collection optimization: we want to avoid
				// the write barrier. (Richard Startin)
				final Container and = tmp.iand(container);
				if (and != tmp) {
					tmp = and;
				}
				if (tmp.isEmpty()) {
					continue outer;
				}
			}
			tmp = tmp.repairAfterLazy();
			if (!tmp.isEmpty()) {
				array.append(keys[i], tmp instanceof BitmapContainer ? tmp.clone() : tmp);
			}
		}
		return new PersistentRoaringBitmap(array);
	}

	/**
	 * Computes the union by folding the inputs pairwise into an accumulator, using lazy container
	 * unions repaired once at the end.
	 *
	 * Runs in linear time in the number of bitmaps and allocates only the accumulator; the lightest
	 * strategy for a small number of inputs.
	 *
	 * @param bitmaps input bitmaps, consumed in iteration order
	 * @return their union; an empty (never `null`) bitmap when the iterator is empty
	 */
	@Nonnull
	public static PersistentRoaringBitmap naive_or(@Nonnull final Iterator<? extends PersistentRoaringBitmap> bitmaps) {
		final PersistentRoaringBitmap answer = new PersistentRoaringBitmap();
		while (bitmaps.hasNext()) {
			answer.naivelazyor(bitmaps.next());
		}
		answer.repairAfterLazy();
		return answer;
	}

	/**
	 * Computes the union by folding the inputs pairwise into an accumulator, using lazy container
	 * unions repaired once at the end.
	 *
	 * Runs in linear time in the number of bitmaps and allocates only the accumulator; the lightest
	 * strategy for a small number of inputs.
	 *
	 * @param bitmaps input bitmaps
	 * @return their union; an empty (never `null`) bitmap when no input is given
	 */
	@Nonnull
	public static PersistentRoaringBitmap naive_or(@Nonnull final PersistentRoaringBitmap... bitmaps) {
		final PersistentRoaringBitmap answer = new PersistentRoaringBitmap();
		for (int k = 0; k < bitmaps.length; ++k) {
			answer.naivelazyor(bitmaps[k]);
		}
		answer.repairAfterLazy();
		return answer;
	}

	/**
	 * Computes the symmetric difference (XOR) by folding the inputs pairwise into an accumulator.
	 *
	 * Runs in linear time in the number of bitmaps; the lightest strategy for a small number of
	 * inputs.
	 *
	 * @param bitmaps input bitmaps, consumed in iteration order
	 * @return their symmetric difference; an empty (never `null`) bitmap when the iterator is empty
	 */
	@Nonnull
	public static PersistentRoaringBitmap naive_xor(
		@Nonnull final Iterator<? extends PersistentRoaringBitmap> bitmaps
	) {
		final PersistentRoaringBitmap answer = new PersistentRoaringBitmap();
		while (bitmaps.hasNext()) {
			answer.xor(bitmaps.next());
		}
		return answer;
	}

	/**
	 * Computes the symmetric difference (XOR) by folding the inputs pairwise into an accumulator.
	 *
	 * Runs in linear time in the number of bitmaps; the lightest strategy for a small number of
	 * inputs.
	 *
	 * @param bitmaps input bitmaps
	 * @return their symmetric difference; an empty (never `null`) bitmap when no input is given
	 */
	@Nonnull
	public static PersistentRoaringBitmap naive_xor(@Nonnull final PersistentRoaringBitmap... bitmaps) {
		final PersistentRoaringBitmap answer = new PersistentRoaringBitmap();
		for (int k = 0; k < bitmaps.length; ++k) {
			answer.xor(bitmaps[k]);
		}
		return answer;
	}

	/**
	 * Computes the union of a stream of bitmaps using the default strategy.
	 *
	 * Convenience entry point delegating to {@link #naive_or(Iterator)}.
	 *
	 * @param bitmaps input bitmaps, consumed in iteration order
	 * @return their union; an empty (never `null`) bitmap when the iterator is empty
	 */
	@Nonnull
	public static PersistentRoaringBitmap or(@Nonnull final Iterator<? extends PersistentRoaringBitmap> bitmaps) {
		return naive_or(bitmaps);
	}

	/**
	 * Computes the union of the inputs using the default strategy.
	 *
	 * Convenience entry point delegating to {@link #naive_or(PersistentRoaringBitmap...)}; switch to
	 * {@link #horizontal_or(PersistentRoaringBitmap...)} or
	 * {@link #priorityqueue_or(PersistentRoaringBitmap...)} when peak memory matters.
	 *
	 * @param bitmaps input bitmaps
	 * @return their union; an empty (never `null`) bitmap when no input is given
	 */
	@Nonnull
	public static PersistentRoaringBitmap or(@Nonnull final PersistentRoaringBitmap... bitmaps) {
		return naive_or(bitmaps);
	}

	/**
	 * Computes the union by repeatedly merging the two currently smallest partial results.
	 *
	 * A {@link PriorityQueue} ordered by serialized size (in bytes) always combines the two cheapest
	 * bitmaps first, Huffman-style, keeping intermediate results small; already-temporary operands
	 * are unioned in place to avoid copies. Runs in linearithmic (`O(n log n)`) time in the number of
	 * bitmaps.
	 *
	 * @param bitmaps input bitmaps, consumed in iteration order
	 * @return their union; an empty (never `null`) bitmap when the iterator is empty
	 * @see #horizontal_or(PersistentRoaringBitmap...)
	 */
	@Nonnull
	public static PersistentRoaringBitmap priorityqueue_or(
		@Nonnull final Iterator<? extends PersistentRoaringBitmap> bitmaps
	) {
		if (!bitmaps.hasNext()) {
			return new PersistentRoaringBitmap();
		}
		// we buffer the call to getSizeInBytes(), hence the code complexity
		final ArrayList<PersistentRoaringBitmap> buffer = new ArrayList<>();
		while (bitmaps.hasNext()) {
			buffer.add(bitmaps.next());
		}
		final long[] sizes = new long[buffer.size()];
		final boolean[] istmp = new boolean[buffer.size()];
		for (int k = 0; k < sizes.length; ++k) {
			sizes[k] = buffer.get(k).getLongSizeInBytes();
		}
		final PriorityQueue<Integer> pq =
			new PriorityQueue<>(
				128,
				new Comparator<Integer>() {
					@Override
					public int compare(Integer a, Integer b) {
						return (int) (sizes[a] - sizes[b]);
					}
				}
			);
		for (int k = 0; k < sizes.length; ++k) {
			pq.add(k);
		}
		while (pq.size() > 1) {
			final Integer x1 = Objects.requireNonNull(pq.poll(), NON_EMPTY_QUEUE_MSG);
			final Integer x2 = Objects.requireNonNull(pq.poll(), NON_EMPTY_QUEUE_MSG);
			if (istmp[x2] && istmp[x1]) {
				buffer.set(x1, PersistentRoaringBitmap.lazyorfromlazyinputs(buffer.get(x1), buffer.get(x2)));
				sizes[x1] = buffer.get(x1).getLongSizeInBytes();
				istmp[x1] = true;
				pq.add(x1);
			} else if (istmp[x2]) {
				buffer.get(x2).lazyor(buffer.get(x1));
				sizes[x2] = buffer.get(x2).getLongSizeInBytes();
				pq.add(x2);
			} else if (istmp[x1]) {
				buffer.get(x1).lazyor(buffer.get(x2));
				sizes[x1] = buffer.get(x1).getLongSizeInBytes();
				pq.add(x1);
			} else {
				buffer.set(x1, PersistentRoaringBitmap.lazyor(buffer.get(x1), buffer.get(x2)));
				sizes[x1] = buffer.get(x1).getLongSizeInBytes();
				istmp[x1] = true;
				pq.add(x1);
			}
		}
		final PersistentRoaringBitmap answer =
			buffer.get(Objects.requireNonNull(pq.poll(), NON_EMPTY_QUEUE_MSG));
		answer.repairAfterLazy();
		return answer;
	}

	/**
	 * Computes the union by repeatedly merging the two currently smallest partial results.
	 *
	 * A {@link PriorityQueue} ordered by serialized size (in bytes) always combines the two cheapest
	 * bitmaps first, Huffman-style, keeping intermediate results small; already-temporary operands
	 * are unioned in place to avoid copies. Runs in linearithmic (`O(n log n)`) time in the number of
	 * bitmaps.
	 *
	 * @param bitmaps input bitmaps
	 * @return their union; an empty (never `null`) bitmap when no input is given
	 * @see #horizontal_or(PersistentRoaringBitmap...)
	 */
	@Nonnull
	public static PersistentRoaringBitmap priorityqueue_or(@Nonnull final PersistentRoaringBitmap... bitmaps) {
		if (bitmaps.length == 0) {
			return new PersistentRoaringBitmap();
		}
		// we buffer the call to getSizeInBytes(), hence the code complexity
		final PersistentRoaringBitmap[] buffer = Arrays.copyOf(bitmaps, bitmaps.length);
		final long[] sizes = new long[buffer.length];
		final boolean[] istmp = new boolean[buffer.length];
		for (int k = 0; k < sizes.length; ++k) {
			sizes[k] = buffer[k].getLongSizeInBytes();
		}
		final PriorityQueue<Integer> pq =
			new PriorityQueue<>(
				128,
				new Comparator<Integer>() {
					@Override
					public int compare(Integer a, Integer b) {
						return (int) (sizes[a] - sizes[b]);
					}
				}
			);
		for (int k = 0; k < sizes.length; ++k) {
			pq.add(k);
		}
		while (pq.size() > 1) {
			final Integer x1 = Objects.requireNonNull(pq.poll(), NON_EMPTY_QUEUE_MSG);
			final Integer x2 = Objects.requireNonNull(pq.poll(), NON_EMPTY_QUEUE_MSG);
			if (istmp[x2] && istmp[x1]) {
				buffer[x1] = PersistentRoaringBitmap.lazyorfromlazyinputs(buffer[x1], buffer[x2]);
				sizes[x1] = buffer[x1].getLongSizeInBytes();
				istmp[x1] = true;
				pq.add(x1);
			} else if (istmp[x2]) {
				buffer[x2].lazyor(buffer[x1]);
				sizes[x2] = buffer[x2].getLongSizeInBytes();
				pq.add(x2);
			} else if (istmp[x1]) {
				buffer[x1].lazyor(buffer[x2]);
				sizes[x1] = buffer[x1].getLongSizeInBytes();
				pq.add(x1);
			} else {
				buffer[x1] = PersistentRoaringBitmap.lazyor(buffer[x1], buffer[x2]);
				sizes[x1] = buffer[x1].getLongSizeInBytes();
				istmp[x1] = true;
				pq.add(x1);
			}
		}
		final PersistentRoaringBitmap answer =
			buffer[Objects.requireNonNull(pq.poll(), NON_EMPTY_QUEUE_MSG)];
		answer.repairAfterLazy();
		return answer;
	}

	/**
	 * Computes the symmetric difference (XOR) by repeatedly merging the two currently smallest
	 * bitmaps.
	 *
	 * A {@link PriorityQueue} ordered by serialized size (in bytes) XOR-combines the two cheapest
	 * bitmaps first, Huffman-style. Runs in linearithmic (`O(n log n)`) time in the number of
	 * bitmaps.
	 *
	 * @param bitmaps input bitmaps
	 * @return their symmetric difference; an empty (never `null`) bitmap when no input is given
	 * @see #horizontal_xor(PersistentRoaringBitmap...)
	 */
	@Nonnull
	public static PersistentRoaringBitmap priorityqueue_xor(@Nonnull final PersistentRoaringBitmap... bitmaps) {
		// This code could be faster, see priorityqueue_or
		if (bitmaps.length == 0) {
			return new PersistentRoaringBitmap();
		}

		final PriorityQueue<PersistentRoaringBitmap> pq =
			new PriorityQueue<>(
				bitmaps.length,
				new Comparator<PersistentRoaringBitmap>() {
					@Override
					public int compare(PersistentRoaringBitmap a, PersistentRoaringBitmap b) {
						return (int) (a.getLongSizeInBytes() - b.getLongSizeInBytes());
					}
				}
			);
		Collections.addAll(pq, bitmaps);
		while (pq.size() > 1) {
			final PersistentRoaringBitmap x1 = Objects.requireNonNull(pq.poll(), NON_EMPTY_QUEUE_MSG);
			final PersistentRoaringBitmap x2 = Objects.requireNonNull(pq.poll(), NON_EMPTY_QUEUE_MSG);
			pq.add(PersistentRoaringBitmap.xor(x1, x2));
		}
		return Objects.requireNonNull(pq.poll(), NON_EMPTY_QUEUE_MSG);
	}

	/**
	 * Computes the symmetric difference (XOR) of a stream of bitmaps using the default strategy.
	 *
	 * Convenience entry point delegating to {@link #naive_xor(Iterator)}.
	 *
	 * @param bitmaps input bitmaps, consumed in iteration order
	 * @return their symmetric difference; an empty (never `null`) bitmap when the iterator is empty
	 */
	@Nonnull
	public static PersistentRoaringBitmap xor(@Nonnull final Iterator<? extends PersistentRoaringBitmap> bitmaps) {
		return naive_xor(bitmaps);
	}

	/**
	 * Computes the symmetric difference (XOR) of the inputs using the default strategy.
	 *
	 * Convenience entry point delegating to {@link #naive_xor(PersistentRoaringBitmap...)}; switch to
	 * {@link #horizontal_xor(PersistentRoaringBitmap...)} or
	 * {@link #priorityqueue_xor(PersistentRoaringBitmap...)} when peak memory matters.
	 *
	 * @param bitmaps input bitmaps
	 * @return their symmetric difference; an empty (never `null`) bitmap when no input is given
	 */
	@Nonnull
	public static PersistentRoaringBitmap xor(@Nonnull final PersistentRoaringBitmap... bitmaps) {
		return naive_xor(bitmaps);
	}

	/**
	 * Private constructor to prevent instantiation of utility class
	 */
	private FastAggregation() {
	}
}
