/*
 * (c) the authors Licensed under the Apache License, Version 2.0.
 */
package io.evitadb.roaringbitmap;


import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.io.Serial;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.CharBuffer;
import java.util.Arrays;
import java.util.Iterator;

/**
 * Run-length-encoded {@link Container}: represents the set as sorted, non-overlapping runs of
 * consecutive values instead of as individual elements.
 *
 * Runs are packed pairwise into `valueslength`: run `i` starts at `valueslength[2*i]` and carries
 * run-length-minus-one in `valueslength[2*i + 1]`, so it covers `start .. start + length` inclusive
 * (a single-value run stores length `0`). `nbrruns` counts the active runs; the backing array may
 * keep trailing scratch capacity beyond `2 * nbrruns`. Runs are held strictly ascending and
 * non-adjacent (at least one absent value separates consecutive runs), so a binary search over the
 * interleaved starts locates any value in `O(log nbrruns)`.
 *
 * This form is chosen when values cluster into long consecutive runs, where it is the cheapest of
 * the three container types ({@link ArrayContainer}, {@link BitmapContainer}, run). Adding and
 * removing content fragments the runs and can make it wasteful, so regular calls to
 * {@link #runOptimize()} are warranted; `runOptimize` and the internal `toEfficientContainer`
 * downgrade to an array or bitmap container whenever their serialized form would be smaller.
 *
 * Not thread-safe: mutating operations rewrite `valueslength` and `nbrruns` in place.
 */
public final class RunContainer extends Container implements Cloneable {
	/**
	 * Number of runs the backing array is sized for when no capacity hint is supplied.
	 */
	private static final int DEFAULT_INIT_SIZE = 4;

	/**
	 * Compile-time switch for galloping (exponential) skips in run intersection; kept `false` because
	 * benchmarks on real data showed a minor net loss versus a plain sequential advance.
	 */
	private static final boolean ENABLE_GALLOPING_AND = false;

	@Serial private static final long serialVersionUID = 1L;

	/**
	 * Textbook binary search over the run starts (the even slots of `array`), used when the run count
	 * is small enough that the hybrid variant's extra branch does not pay off.
	 *
	 * @param array interleaved run pairs `(start, length-1)`
	 * @param begin first run index to consider (inclusive)
	 * @param end   one past the last run index to consider (exclusive)
	 * @param k     unsigned 16-bit value to locate
	 * @return index of the run whose start equals `k`; otherwise `-(insertionPoint + 1)` where
	 * `insertionPoint` is where a run starting at `k` would be inserted. Runs in `O(log n)`.
	 */
	private static int branchyUnsignedInterleavedBinarySearch(
		@Nonnull final char[] array, final int begin, final int end, final char k) {
		int low = begin;
		int high = end - 1;
		while (low <= high) {
			final int middleIndex = (low + high) >>> 1;
			final int middleValue = (array[2 * middleIndex]);
			if (middleValue < (int) (k)) {
				low = middleIndex + 1;
			} else if (middleValue > (int) (k)) {
				high = middleIndex - 1;
			} else {
				return middleIndex;
			}
		}
		return -(low + 1);
	}

	/**
	 * Binary search over the run starts that switches to a linear scan once the remaining window fits
	 * inside a cache line (16 entries), trading a few comparisons for fewer branch mispredictions near
	 * the target.
	 *
	 * @param array interleaved run pairs `(start, length-1)`
	 * @param begin first run index to consider (inclusive)
	 * @param end   one past the last run index to consider (exclusive)
	 * @param k     unsigned 16-bit value to locate
	 * @return index of the run whose start equals `k`; otherwise `-(insertionPoint + 1)`. Runs in
	 * `O(log n)`.
	 */
	private static int hybridUnsignedInterleavedBinarySearch(
		@Nonnull final char[] array, final int begin, final int end, final char k) {
		int low = begin;
		int high = end - 1;
		// 16 in the next line matches the size of a cache line
		while (low + 16 <= high) {
			final int middleIndex = (low + high) >>> 1;
			final int middleValue = (array[2 * middleIndex]);
			if (middleValue < (int) (k)) {
				low = middleIndex + 1;
			} else if (middleValue > (int) (k)) {
				high = middleIndex - 1;
			} else {
				return middleIndex;
			}
		}
		// we finish the job with a sequential search
		int x = low;
		for (; x <= high; ++x) {
			final int val = (array[2 * x]);
			if (val >= (int) (k)) {
				if (val == (int) (k)) {
					return x;
				}
				break;
			}
		}
		return -(x + 1);
	}

	/**
	 * Serialized size in bytes of a run container holding `numberOfRuns` runs: a 2-byte run count plus
	 * two 2-byte entries (start and length) per run. Used to compare representations when deciding
	 * whether to downgrade to an array or bitmap container.
	 *
	 * @param numberOfRuns number of runs to account for
	 * @return the serialized size in bytes
	 */
	protected static int serializedSizeInBytes(final int numberOfRuns) {
		return 2 + 2 * 2 * numberOfRuns; // each run requires 2 2-byte entries.
	}

	/**
	 * Shared search entry point that dispatches to the hybrid or branchy binary search per
	 * `Util.USE_HYBRID_BINSEARCH`. Backs `contains`, `add`, `remove` and the range/navigation methods.
	 *
	 * @param array interleaved run pairs `(start, length-1)`
	 * @param begin first run index to consider (inclusive)
	 * @param end   one past the last run index to consider (exclusive)
	 * @param k     unsigned 16-bit value to locate
	 * @return index of the run starting at `k`, or `-(insertionPoint + 1)` if absent
	 */
	private static int unsignedInterleavedBinarySearch(
		@Nonnull final char[] array, final int begin, final int end, final char k) {
		if (Util.USE_HYBRID_BINSEARCH) {
			return hybridUnsignedInterleavedBinarySearch(array, begin, end, k);
		} else {
			return branchyUnsignedInterleavedBinarySearch(array, begin, end, k);
		}
	}

	/**
	 * Interleaved run pairs: run `i` occupies `valueslength[2*i]` (the run start) and
	 * `valueslength[2*i + 1]` (run length minus one), so it covers `start .. start + length`
	 * inclusive. For example values `11,12,13,14,15` are stored as the pair `11,4`, and
	 * `1,0 20,0 31,2` encodes `1,2,...,11 20 31,32,33`. Only the first `2 * nbrruns` entries are live;
	 * the array may be longer (trailing scratch capacity) and is replaced on growth. Never null.
	 */
	@Nonnull private char[] valueslength;

	/**
	 * Number of active runs. Always fits in 16 bits: a container spans at most 65536 values, so at
	 * most 32768 non-adjacent runs. Only `valueslength[0 .. 2 * nbrruns - 1]` is meaningful.
	 */
	int nbrruns = 0;

	/**
	 * Create a container with default capacity
	 */
	public RunContainer() {
		this(DEFAULT_INIT_SIZE);
	}

	/**
	 * Builds a run container from a sorted {@link ArrayContainer} by coalescing its consecutive values
	 * into runs. The caller supplies the exact run count so the backing array is sized once. Runs in
	 * `O(arr.cardinality)`.
	 *
	 * @param arr     source array container, its `content` strictly ascending
	 * @param nbrRuns number of runs the values collapse into
	 */
	protected RunContainer(@Nonnull final ArrayContainer arr, final int nbrRuns) {
		this.nbrruns = nbrRuns;
		this.valueslength = new char[2 * nbrRuns];
		if (nbrRuns == 0) {
			return;
		}

		int prevVal = -2;
		int runLen = 0;
		int runCount = 0;

		for (int i = 0; i < arr.cardinality; i++) {
			int curVal = arr.content[i];
			if (curVal == prevVal + 1) {
				++runLen;
			} else {
				if (runCount > 0) {
					setLength(runCount - 1, (char) runLen);
				}
				setValue(runCount, (char) curVal);
				runLen = 0;
				++runCount;
			}
			prevVal = curVal;
		}
		setLength(runCount - 1, (char) runLen);
	}

	/**
	 * Create an run container with a run of ones from firstOfRun to lastOfRun.
	 *
	 * @param firstOfRun first index
	 * @param lastOfRun  last index (range is exclusive)
	 */
	public RunContainer(final int firstOfRun, final int lastOfRun) {
		this.nbrruns = 1;
		this.valueslength = new char[]{(char) firstOfRun, (char) (lastOfRun - 1 - firstOfRun)};
	}

	/**
	 * Builds a run container from a {@link BitmapContainer} by scanning its words for maximal blocks
	 * of set bits, emitting one run per block. The caller supplies the precomputed run count so the
	 * backing array is sized once. Runs in `O(bitmap words)` via word-at-a-time bit tricks.
	 *
	 * @param bc      source bitmap container
	 * @param nbrRuns number of runs of set bits in `bc`
	 */
	protected RunContainer(@Nonnull final BitmapContainer bc, final int nbrRuns) {
		this.nbrruns = nbrRuns;
		this.valueslength = new char[2 * nbrRuns];
		if (nbrRuns == 0) {
			return;
		}

		int longCtr = 0; // index of current long in bitmap
		long curWord = bc.bitmap[0]; // its value
		int runCount = 0;
		while (true) {
			// potentially multiword advance to first 1 bit
			while (curWord == 0L && longCtr < bc.bitmap.length - 1) {
				curWord = bc.bitmap[++longCtr];
			}

			if (curWord == 0L) {
				// wrap up, no more runs
				return;
			}
			int localRunStart = Long.numberOfTrailingZeros(curWord);
			int runStart = localRunStart + 64 * longCtr;
			// stuff 1s into number's LSBs
			long curWordWith1s = curWord | (curWord - 1);

			// find the next 0, potentially in a later word
			int runEnd;
			while (curWordWith1s == -1L && longCtr < bc.bitmap.length - 1) {
				curWordWith1s = bc.bitmap[++longCtr];
			}

			if (curWordWith1s == -1L) {
				// a final unterminated run of 1s (32 of them)
				runEnd = 64 + longCtr * 64;
				setValue(runCount, (char) runStart);
				setLength(runCount, (char) (runEnd - runStart - 1));
				return;
			}
			int localRunEnd = Long.numberOfTrailingZeros(~curWordWith1s);
			runEnd = localRunEnd + longCtr * 64;
			setValue(runCount, (char) runStart);
			setLength(runCount, (char) (runEnd - runStart - 1));
			runCount++;
			// now, zero out everything right of runEnd.
			curWord = curWordWith1s & (curWordWith1s + 1);
			// We've lathered and rinsed, so repeat...
		}
	}

	/**
	 * Creates an empty run container whose backing array is pre-sized for `capacity` runs.
	 *
	 * @param capacity number of runs to reserve space for (allocates `2 * capacity` chars)
	 */
	public RunContainer(final int capacity) {
		this.valueslength = new char[2 * capacity];
	}

	/**
	 * Copy constructor used by {@link #clone()}: stores a full defensive copy of `valueslength` so the
	 * two containers can be mutated independently.
	 *
	 * @param nbrruns      number of active runs
	 * @param valueslength interleaved run pairs to copy
	 */
	private RunContainer(final int nbrruns, @Nonnull final char[] valueslength) {
		this.nbrruns = nbrruns;
		this.valueslength = Arrays.copyOf(valueslength, valueslength.length);
	}

	/**
	 * Constructs a run container backed directly by the provided array (no copy). Subsequent mutation
	 * may replace the array if it needs to grow.
	 *
	 * @param array   interleaved run pairs `(start, length-1)`; must hold at least `2 * numRuns` chars
	 * @param numRuns number of active runs
	 * @throws RuntimeException if `array` is too small for `numRuns`
	 */
	public RunContainer(@Nonnull final char[] array, final int numRuns) {
		if (array.length < 2 * numRuns) {
			throw new RuntimeException("Mismatch between buffer and numRuns");
		}
		this.nbrruns = numRuns;
		this.valueslength = array;
	}

	/**
	 * Returns a clone with `[begin, end)` added; leaves this container untouched.
	 */
	@Nonnull
	@Override
	public Container add(final int begin, final int end) {
		RunContainer rc = (RunContainer) clone();
		return rc.iadd(begin, end);
	}

	/**
	 * Inserts a single value in place, extending or fusing adjacent runs where possible so the
	 * non-adjacency invariant is preserved. Locates the run in `O(log nbrruns)`; inserting a fresh run
	 * may shift up to `nbrruns` pairs.
	 *
	 * @param k value to add
	 * @return this container (mutated); already a run container, never converts here
	 */
	@Nonnull
	@Override
	public Container add(final char k) {
		// it might be better and simpler to do return
		// toBitmapOrArrayContainer(getCardinality()).add(k)
		// but note that some unit tests use this method to build up test runcontainers without calling
		// runOptimize
		int index = unsignedInterleavedBinarySearch(this.valueslength, 0, this.nbrruns, k);
		if (index >= 0) {
			return this; // already there
		}
		index = -index - 2; // points to preceding value, possibly -1
		if (index >= 0) { // possible match
			int offset = (k) - (getValue(index));
			int le = (getLength(index));
			if (offset <= le) {
				return this;
			}
			if (offset == le + 1) {
				// we may need to fuse
				if (index + 1 < this.nbrruns) {
					if ((getValue(index + 1)) == (k) + 1) {
						// indeed fusion is needed
						setLength(index, (char) (getValue(index + 1) + getLength(index + 1) - getValue(index)));
						recoverRoomAtIndex(index + 1);
						return this;
					}
				}
				incrementLength(index);
				return this;
			}
			if (index + 1 < this.nbrruns) {
				// we may need to fuse
				if ((getValue(index + 1)) == (k) + 1) {
					// indeed fusion is needed
					setValue(index + 1, k);
					setLength(index + 1, (char) (getLength(index + 1) + 1));
					return this;
				}
			}
		}
		if (index == -1) {
			// we may need to extend the first run
			if (0 < this.nbrruns) {
				if (getValue(0) == k + 1) {
					incrementLength(0);
					decrementValue(0);
					return this;
				}
			}
		}
		makeRoomAtIndex(index + 1);
		setValue(index + 1, k);
		setLength(index + 1, (char) 0);
		return this;
	}

	/**
	 * Intersects with an array container. The result can only be an array (never larger than `x`), so
	 * it is built directly as an {@link ArrayContainer} by walking `x` against the runs and skipping
	 * gaps with a galloping `advanceUntil`. Runs in roughly `O(x.cardinality + nbrruns)`.
	 */
	@Nonnull
	@Override
	public Container and(@Nonnull final ArrayContainer x) {
		ArrayContainer ac = new ArrayContainer(x.cardinality);
		if (this.nbrruns == 0) {
			return ac;
		}
		int rlepos = 0;
		int arraypos = 0;

		int rleval = (this.getValue(rlepos));
		int rlelength = (this.getLength(rlepos));
		while (arraypos < x.cardinality) {
			int arrayval = (x.content[arraypos]);
			while (rleval + rlelength < arrayval) { // this will frequently be false
				++rlepos;
				if (rlepos == this.nbrruns) {
					return ac; // we are done
				}
				rleval = (this.getValue(rlepos));
				rlelength = (this.getLength(rlepos));
			}
			if (rleval > arrayval) {
				arraypos = Util.advanceUntil(x.content, arraypos, x.cardinality, (char) rleval);
			} else {
				ac.content[ac.cardinality] = (char) arrayval;
				ac.cardinality++;
				arraypos++;
			}
		}
		return ac;
	}

	/**
	 * Validates the representation: at least one run, runs strictly ascending and non-adjacent, each
	 * length non-negative, and the run form no larger (serialized) than the equivalent array or bitmap
	 * container. Returns `false` if any check fails.
	 */
	@Nonnull
	@Override
	public Boolean validate() {
		if (this.nbrruns == 0) {
			return false;
		}
		int runEnd = -2;
		for (int rlepos = 0; rlepos < this.nbrruns; ++rlepos) {
			int runStart = (this.getValue(rlepos));
			if (runStart <= runEnd + 1) {
				return false;
			}
			runEnd = runStart + (this.getLength(rlepos));
			if (runStart > runEnd) {
				return false;
			}
		}

		int sizeAsRunContainer = RunContainer.serializedSizeInBytes(this.nbrruns);
		int sizeAsBitmapContainer = BitmapContainer.serializedSizeInBytes(0);
		int card = this.getCardinality();
		int sizeAsArrayContainer = ArrayContainer.serializedSizeInBytes(card);
		if (sizeAsRunContainer <= Math.min(sizeAsBitmapContainer, sizeAsArrayContainer)) {
			return true;
		}
		return false;
	}

	/**
	 * Intersects with a bitmap container. When this container's cardinality already fits an array the
	 * result is materialised as an {@link ArrayContainer} by probing `x`; otherwise a bitmap is cloned
	 * and the gaps between runs are cleared, downgrading to an array afterwards if it becomes small
	 * enough.
	 */
	@Nonnull
	@Override
	public Container and(@Nonnull final BitmapContainer x) {
		// could be implemented as return toBitmapOrArrayContainer().iand(x);
		int card = this.getCardinality();
		if (card <= ArrayContainer.DEFAULT_MAX_SIZE) {
			// result can only be an array (assuming that we never make a RunContainer)
			if (card > x.cardinality) {
				card = x.cardinality;
			}
			ArrayContainer answer = new ArrayContainer(card);
			answer.cardinality = 0;
			for (int rlepos = 0; rlepos < this.nbrruns; ++rlepos) {
				int runStart = (this.getValue(rlepos));
				int runEnd = runStart + (this.getLength(rlepos));
				for (int runValue = runStart; runValue <= runEnd; ++runValue) {
					if (x.contains((char) runValue)) { // it looks like contains() should be cheap enough if
						// accessed sequentially
						answer.content[answer.cardinality++] = (char) runValue;
					}
				}
			}
			return answer;
		}
		// we expect the answer to be a bitmap (if we are lucky)
		BitmapContainer answer = x.clone();
		int start = 0;
		for (int rlepos = 0; rlepos < this.nbrruns; ++rlepos) {
			int end = (this.getValue(rlepos));
			int prevOnes = answer.cardinalityInRange(start, end);
			Util.resetBitmapRange(answer.bitmap, start, end); // had been x.bitmap
			answer.updateCardinality(prevOnes, 0);
			start = end + (this.getLength(rlepos)) + 1;
		}
		int ones = answer.cardinalityInRange(start, BitmapContainer.MAX_CAPACITY);
		Util.resetBitmapRange(answer.bitmap, start, BitmapContainer.MAX_CAPACITY); // had been x.bitmap
		answer.updateCardinality(ones, 0);
		if (answer.getCardinality() > ArrayContainer.DEFAULT_MAX_SIZE) {
			return answer;
		} else {
			return answer.toArrayContainer();
		}
	}

	/**
	 * Intersects two run containers with a single linear merge of the two run lists (advancing
	 * whichever run ends first and emitting the overlap of any pair that intersects), then downgrades
	 * the result to the most compact form. Runs in `O(nbrruns + x.nbrruns)`; galloping skips are
	 * gated behind `ENABLE_GALLOPING_AND`.
	 */
	@Nonnull
	@Override
	public Container and(@Nonnull final RunContainer x) {
		int maxRunsAfterIntersection = this.nbrruns + x.nbrruns;
		RunContainer answer = new RunContainer(new char[2 * maxRunsAfterIntersection], 0);
		if (isEmpty()) {
			return answer;
		}
		int rlepos = 0;
		int xrlepos = 0;
		int start = this.getValue(rlepos);
		int end = start + this.getLength(rlepos) + 1;
		int xstart = x.getValue(xrlepos);
		int xend = xstart + x.getLength(xrlepos) + 1;
		while (rlepos < this.nbrruns && xrlepos < x.nbrruns) {
			if (end <= xstart) {
				if (ENABLE_GALLOPING_AND) {
					rlepos = skipAhead(this, rlepos, xstart); // skip over runs until we have end > xstart (or
					// rlepos is advanced beyond end)
				} else {
					++rlepos;
				}

				if (rlepos < this.nbrruns) {
					start = this.getValue(rlepos);
					end = start + this.getLength(rlepos) + 1;
				}
			} else if (xend <= start) {
				// exit the second run
				if (ENABLE_GALLOPING_AND) {
					xrlepos = skipAhead(x, xrlepos, start);
				} else {
					++xrlepos;
				}

				if (xrlepos < x.nbrruns) {
					xstart = x.getValue(xrlepos);
					xend = xstart + x.getLength(xrlepos) + 1;
				}
			} else { // they overlap
				final int lateststart = Math.max(start, xstart);
				int earliestend;
				if (end == xend) { // improbable
					earliestend = end;
					rlepos++;
					xrlepos++;
					if (rlepos < this.nbrruns) {
						start = this.getValue(rlepos);
						end = start + this.getLength(rlepos) + 1;
					}
					if (xrlepos < x.nbrruns) {
						xstart = x.getValue(xrlepos);
						xend = xstart + x.getLength(xrlepos) + 1;
					}
				} else if (end < xend) {
					earliestend = end;
					rlepos++;
					if (rlepos < this.nbrruns) {
						start = this.getValue(rlepos);
						end = start + this.getLength(rlepos) + 1;
					}

				} else { // end > xend
					earliestend = xend;
					xrlepos++;
					if (xrlepos < x.nbrruns) {
						xstart = x.getValue(xrlepos);
						xend = xstart + x.getLength(xrlepos) + 1;
					}
				}
				answer.valueslength[2 * answer.nbrruns] = (char) lateststart;
				answer.valueslength[2 * answer.nbrruns + 1] = (char) (earliestend - lateststart - 1);
				answer.nbrruns++;
			}
		}
		return answer.toEfficientContainer(); // subsequent trim() may be required to avoid wasted
		// space.
	}

	/**
	 * Counts the intersection with an array container without materialising it, walking `x` against
	 * the runs. Runs in roughly `O(x.cardinality + nbrruns)`.
	 */
	@Override
	public int andCardinality(@Nonnull final ArrayContainer x) {
		if (this.nbrruns == 0) {
			return x.cardinality;
		}
		int rlepos = 0;
		int arraypos = 0;
		int andCardinality = 0;
		int rleval = (this.getValue(rlepos));
		int rlelength = (this.getLength(rlepos));
		while (arraypos < x.cardinality) {
			int arrayval = (x.content[arraypos]);
			while (rleval + rlelength < arrayval) { // this will frequently be false
				++rlepos;
				if (rlepos == this.nbrruns) {
					return andCardinality; // we are done
				}
				rleval = (this.getValue(rlepos));
				rlelength = (this.getLength(rlepos));
			}
			if (rleval > arrayval) {
				arraypos = Util.advanceUntil(x.content, arraypos, x.cardinality, this.getValue(rlepos));
			} else {
				andCardinality++;
				arraypos++;
			}
		}
		return andCardinality;
	}

	/**
	 * Counts the intersection with a bitmap container by summing, per run, the set bits `x` holds over
	 * that run's range. Runs in `O(nbrruns)` plus the per-range bit population cost.
	 */
	@Override
	public int andCardinality(@Nonnull final BitmapContainer x) {
		// could be implemented as return toBitmapOrArrayContainer().iand(x);
		int cardinality = 0;
		for (int rlepos = 0; rlepos < this.nbrruns; ++rlepos) {
			int runStart = this.getValue(rlepos);
			int runEnd = runStart + this.getLength(rlepos);
			cardinality += x.cardinalityInRange(runStart, runEnd + 1);
		}
		return cardinality;
	}

	/**
	 * Counts the intersection of two run containers with a single linear merge of the run lists,
	 * accumulating the width of each overlap. Runs in `O(nbrruns + x.nbrruns)`.
	 */
	@Override
	public int andCardinality(@Nonnull final RunContainer x) {
		int cardinality = 0;
		int rlepos = 0;
		int xrlepos = 0;
		int start = (this.getValue(rlepos));
		int end = start + (this.getLength(rlepos)) + 1;
		int xstart = (x.getValue(xrlepos));
		int xend = xstart + (x.getLength(xrlepos)) + 1;
		while ((rlepos < this.nbrruns) && (xrlepos < x.nbrruns)) {
			if (end <= xstart) {
				if (ENABLE_GALLOPING_AND) {
					rlepos = skipAhead(this, rlepos, xstart); // skip over runs until we have end > xstart (or
					// rlepos is advanced beyond end)
				} else {
					++rlepos;
				}

				if (rlepos < this.nbrruns) {
					start = (this.getValue(rlepos));
					end = start + (this.getLength(rlepos)) + 1;
				}
			} else if (xend <= start) {
				// exit the second run
				if (ENABLE_GALLOPING_AND) {
					xrlepos = skipAhead(x, xrlepos, start);
				} else {
					++xrlepos;
				}

				if (xrlepos < x.nbrruns) {
					xstart = (x.getValue(xrlepos));
					xend = xstart + (x.getLength(xrlepos)) + 1;
				}
			} else { // they overlap
				final int lateststart = Math.max(start, xstart);
				int earliestend;
				if (end == xend) { // improbable
					earliestend = end;
					rlepos++;
					xrlepos++;
					if (rlepos < this.nbrruns) {
						start = (this.getValue(rlepos));
						end = start + (this.getLength(rlepos)) + 1;
					}
					if (xrlepos < x.nbrruns) {
						xstart = (x.getValue(xrlepos));
						xend = xstart + (x.getLength(xrlepos)) + 1;
					}
				} else if (end < xend) {
					earliestend = end;
					rlepos++;
					if (rlepos < this.nbrruns) {
						start = (this.getValue(rlepos));
						end = start + (this.getLength(rlepos)) + 1;
					}

				} else { // end > xend
					earliestend = xend;
					xrlepos++;
					if (xrlepos < x.nbrruns) {
						xstart = (x.getValue(xrlepos));
						xend = xstart + (x.getLength(xrlepos)) + 1;
					}
				}
				// earliestend - lateststart are all values that are true.
				cardinality += earliestend - lateststart;
			}
		}
		return cardinality;
	}

	/**
	 * Removes the elements of an array container. When `x` is small the result is guessed to stay a
	 * run container (built lazily, then compacted); otherwise it is materialised as an array or bitmap
	 * depending on this container's cardinality.
	 */
	@Nonnull
	@Override
	public Container andNot(@Nonnull final ArrayContainer x) {
		// when x is small, we guess that the result will still be a run container
		final int arbitrary_threshold = 32; // this is arbitrary
		if (x.getCardinality() < arbitrary_threshold) {
			return lazyandNot(x).toEfficientContainer();
		}
		// otherwise we generate either an array or bitmap container
		final int card = getCardinality();
		if (card <= ArrayContainer.DEFAULT_MAX_SIZE) {
			// if the cardinality is small, we construct the solution in place
			ArrayContainer ac = new ArrayContainer(card);
			ac.cardinality =
				Util.unsignedDifference(this.getCharIterator(), x.getCharIterator(), ac.content);
			return ac;
		}
		// otherwise, we generate a bitmap
		return toBitmapOrArrayContainer(card).iandNot(x);
	}

	/**
	 * Removes the elements of a bitmap container. Produces an array directly when this container's
	 * cardinality is small; otherwise clones `x`, clears the gaps and flips the runs, then downgrades
	 * to an array if the result becomes small enough.
	 */
	@Nonnull
	@Override
	public Container andNot(@Nonnull final BitmapContainer x) {
		// could be implemented as toTemporaryBitmap().iandNot(x);
		int card = this.getCardinality();
		if (card <= ArrayContainer.DEFAULT_MAX_SIZE) {
			// result can only be an array (assuming that we never make a RunContainer)
			ArrayContainer answer = new ArrayContainer(card);
			answer.cardinality = 0;
			for (int rlepos = 0; rlepos < this.nbrruns; ++rlepos) {
				int runStart = (this.getValue(rlepos));
				int runEnd = runStart + (this.getLength(rlepos));
				for (int runValue = runStart; runValue <= runEnd; ++runValue) {
					if (!x.contains((char) runValue)) { // it looks like contains() should be cheap enough if
						// accessed sequentially
						answer.content[answer.cardinality++] = (char) runValue;
					}
				}
			}
			return answer;
		}
		// we expect the answer to be a bitmap (if we are lucky)
		BitmapContainer answer = x.clone();
		int lastPos = 0;
		for (int rlepos = 0; rlepos < this.nbrruns; ++rlepos) {
			int start = (this.getValue(rlepos));
			int end = start + (this.getLength(rlepos)) + 1;
			int prevOnes = answer.cardinalityInRange(lastPos, start);
			int flippedOnes = answer.cardinalityInRange(start, end);
			Util.resetBitmapRange(answer.bitmap, lastPos, start);
			Util.flipBitmapRange(answer.bitmap, start, end);
			answer.updateCardinality(prevOnes + flippedOnes, end - start - flippedOnes);
			lastPos = end;
		}
		int ones = answer.cardinalityInRange(lastPos, BitmapContainer.MAX_CAPACITY);
		Util.resetBitmapRange(answer.bitmap, lastPos, BitmapContainer.MAX_CAPACITY);
		answer.updateCardinality(ones, 0);
		if (answer.getCardinality() > ArrayContainer.DEFAULT_MAX_SIZE) {
			return answer;
		} else {
			return answer.toArrayContainer();
		}
	}

	/**
	 * Subtracts another run container with a single linear merge, emitting the parts of each run not
	 * covered by `x`, then downgrades to the most compact form. Runs in `O(nbrruns + x.nbrruns)`.
	 */
	@Nonnull
	@Override
	public Container andNot(@Nonnull final RunContainer x) {
		RunContainer answer = new RunContainer(new char[2 * (this.nbrruns + x.nbrruns)], 0);
		int rlepos = 0;
		int xrlepos = 0;
		int start = (this.getValue(rlepos));
		int end = start + (this.getLength(rlepos)) + 1;
		int xstart = (x.getValue(xrlepos));
		int xend = xstart + (x.getLength(xrlepos)) + 1;
		while ((rlepos < this.nbrruns) && (xrlepos < x.nbrruns)) {
			if (end <= xstart) {
				// output the first run
				answer.valueslength[2 * answer.nbrruns] = (char) start;
				answer.valueslength[2 * answer.nbrruns + 1] = (char) (end - start - 1);
				answer.nbrruns++;
				rlepos++;
				if (rlepos < this.nbrruns) {
					start = (this.getValue(rlepos));
					end = start + (this.getLength(rlepos)) + 1;
				}
			} else if (xend <= start) {
				// exit the second run
				xrlepos++;
				if (xrlepos < x.nbrruns) {
					xstart = (x.getValue(xrlepos));
					xend = xstart + (x.getLength(xrlepos)) + 1;
				}
			} else {
				if (start < xstart) {
					answer.valueslength[2 * answer.nbrruns] = (char) start;
					answer.valueslength[2 * answer.nbrruns + 1] = (char) (xstart - start - 1);
					answer.nbrruns++;
				}
				if (xend < end) {
					start = xend;
				} else {
					rlepos++;
					if (rlepos < this.nbrruns) {
						start = (this.getValue(rlepos));
						end = start + (this.getLength(rlepos)) + 1;
					}
				}
			}
		}
		if (rlepos < this.nbrruns) {
			answer.valueslength[2 * answer.nbrruns] = (char) start;
			answer.valueslength[2 * answer.nbrruns + 1] = (char) (end - start - 1);
			answer.nbrruns++;
			rlepos++;
			if (rlepos < this.nbrruns) {
				System.arraycopy(
					this.valueslength,
					2 * rlepos,
					answer.valueslength,
					2 * answer.nbrruns,
					2 * (this.nbrruns - rlepos)
				);
				answer.nbrruns = answer.nbrruns + this.nbrruns - rlepos;
			}
		}
		return answer.toEfficientContainer();
	}

	/**
	 * Extends the run at `index` so it reaches `value` (no-op if the run already covers it).
	 */
	private void appendValueLength(final int value, final int index) {
		int previousValue = (getValue(index));
		int length = (getLength(index));
		int offset = value - previousValue;
		if (offset > length) {
			setLength(index, (char) offset);
		}
	}

	/**
	 * Whether `value` sits immediately below the run at `index` (so it can extend it downward).
	 */
	private boolean canPrependValueLength(final int value, final int index) {
		if (index < this.nbrruns) {
			int nextValue = (getValue(index));
			return nextValue == value + 1;
		}
		return false;
	}

	/**
	 * Empties the container by resetting the run count; the backing array is retained for reuse.
	 */
	@Override
	public void clear() {
		this.nbrruns = 0;
	}

	/**
	 * Returns an independent deep copy (the backing run array is duplicated).
	 */
	@Nonnull
	@Override
	public Container clone() {
		return new RunContainer(this.nbrruns, this.valueslength);
	}

	/**
	 * Whether the container holds no values (no active runs).
	 */
	@Override
	public boolean isEmpty() {
		return this.nbrruns == 0;
	}

	/**
	 * Shortens the run at `index` so its last value is exactly `value`.
	 */
	private void closeValueLength(final int value, final int index) {
		int initialValue = (getValue(index));
		setLength(index, (char) (value - initialValue));
	}

	/**
	 * Tests membership of a single value by binary search over the run starts, `O(log nbrruns)`.
	 */
	@Override
	public boolean contains(final char x) {
		int index = unsignedInterleavedBinarySearch(this.valueslength, 0, this.nbrruns, x);
		if (index >= 0) {
			return true;
		}
		index = -index - 2; // points to preceding value, possibly -1
		if (index != -1) { // possible match
			int offset = x - getValue(index);
			int le = getLength(index);
			return offset <= le;
		}
		return false;
	}

	/**
	 * Whether the whole half-open range `[minimum, supremum)` lies inside a single run. Scans runs in
	 * order and stops once a run starts at or beyond `supremum`.
	 */
	@Override
	public boolean contains(final int minimum, final int supremum) {
		for (int i = 0; i < numberOfRuns(); ++i) {
			int start = getValue(i);
			int length = getLength(i);
			int stop = start + length + 1;
			if (start >= supremum) {
				break;
			}
			if (minimum >= start && supremum <= stop) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Whether every run of `runContainer` is covered by a run of this container (subset test).
	 */
	@Override
	protected boolean contains(@Nonnull final RunContainer runContainer) {
		int i1 = 0, i2 = 0;
		while (i1 < numberOfRuns() && i2 < runContainer.numberOfRuns()) {
			int start1 = (getValue(i1));
			int stop1 = start1 + (getLength(i1));
			int start2 = (runContainer.getValue(i2));
			int stop2 = start2 + (runContainer.getLength(i2));
			if (start1 > start2) {
				return false;
			} else {
				if (stop1 > stop2) {
					i2++;
				} else if (stop1 == stop2) {
					i1++;
					i2++;
				} else {
					i1++;
				}
			}
		}
		return i2 == runContainer.numberOfRuns();
	}

	/**
	 * Whether every value of `arrayContainer` falls inside a run of this container (subset test).
	 */
	@Override
	protected boolean contains(@Nonnull final ArrayContainer arrayContainer) {
		final int cardinality = getCardinality();
		final int runCount = numberOfRuns();
		if (arrayContainer.getCardinality() > cardinality) {
			return false;
		}
		int ia = 0, ir = 0;
		while (ia < arrayContainer.getCardinality() && ir < runCount) {
			int start = (this.getValue(ir));
			int stop = start + (getLength(ir));
			int ac = (arrayContainer.content[ia]);
			if (ac < start) {
				return false;
			} else if (ac > stop) {
				++ir;
			} else {
				++ia;
			}
		}
		return ia == arrayContainer.getCardinality();
	}

	/**
	 * Whether every set bit of `bitmapContainer` lies inside a run here (subset test).
	 */
	@Override
	protected boolean contains(@Nonnull final BitmapContainer bitmapContainer) {
		final int cardinality = getCardinality();
		if (bitmapContainer.getCardinality() != -1 && bitmapContainer.getCardinality() > cardinality) {
			return false;
		}
		final int runCount = numberOfRuns();
		char ib = 0, ir = 0;
		int start = getValue(ir);
		int stop = start + getLength(ir);
		while (ib < bitmapContainer.bitmap.length && ir < runCount) {
			long w = bitmapContainer.bitmap[ib];
			while (w != 0) {
				long r = ib * 64L + Long.numberOfTrailingZeros(w);
				if (r < start) {
					return false;
				} else if (r > stop) {
					++ir;
					if (ir == runCount) {
						break;
					}
					start = getValue(ir);
					stop = start + getLength(ir);
				} else if (ib * 64 + 64 < stop) {
					ib = (char) (stop / 64);
					w = bitmapContainer.bitmap[ib];
				} else {
					w &= w - 1;
				}
			}
			if (w == 0) {
				++ib;
			} else {
				return false;
			}
		}
		if (ib < bitmapContainer.bitmap.length) {
			for (; ib < bitmapContainer.bitmap.length; ib++) {
				if (bitmapContainer.bitmap[ib] != 0) {
					return false;
				}
			}
		}
		return true;
	}

	/**
	 * Cheap post-lazy-op guard: once the run count exceeds `ArrayContainer.DEFAULT_MAX_SIZE` (4096)
	 * the value set can no longer be cheaper as runs, so it is rewritten into a bitmap (with lazy
	 * cardinality `-1`); otherwise `this` is returned unchanged. Avoids computing the cardinality.
	 */
	@Nonnull
	private Container convertToLazyBitmapIfNeeded() {
		// when nbrruns exceed ArrayContainer.DEFAULT_MAX_SIZE, then we know it should be stored as a
		// bitmap, always
		if (this.nbrruns > ArrayContainer.DEFAULT_MAX_SIZE) {
			BitmapContainer answer = new BitmapContainer();
			for (int rlepos = 0; rlepos < this.nbrruns; ++rlepos) {
				int start = (this.getValue(rlepos));
				int end = start + (this.getLength(rlepos)) + 1;
				Util.setBitmapRange(answer.bitmap, start, end);
			}
			answer.cardinality = -1;
			return answer;
		}
		return this;
	}

	/**
	 * Shifts all runs to start at run index `offset`, growing the array if needed, freeing the front
	 * as scratch space for an in-place merge (used by the lazy/in-place `or` family).
	 */
	private void copyToOffset(final int offset) {
		if (!ensureCapacity(offset, 2 * (offset + this.nbrruns))) {
			// efficient case where we just copy
			copyValuesLength(this.valueslength, 0, this.valueslength, offset, this.nbrruns);
		}
	}

	/**
	 * Copies `length` run pairs between arrays, translating run indices to char offsets.
	 */
	private static void copyValuesLength(
		@Nonnull final char[] src, final int srcIndex, @Nonnull final char[] dst, final int dstIndex,
		final int length
	) {
		System.arraycopy(src, 2 * srcIndex, dst, 2 * dstIndex, 2 * length);
	}

	private void decrementLength(final int index) {
		this.valueslength[2 * index + 1]--; // caller is responsible to ensure that value is non-zero
	}

	private void decrementValue(final int index) {
		this.valueslength[2 * index]--;
	}

	/**
	 * Reads the run count and interleaved run pairs (little-endian), growing the array if needed.
	 */
	@Override
	public void deserialize(@Nonnull final DataInput in) throws IOException {
		this.nbrruns = Character.reverseBytes(in.readChar());
		if (this.valueslength.length < 2 * this.nbrruns) {
			this.valueslength = new char[2 * this.nbrruns];
		}
		for (int k = 0; k < 2 * this.nbrruns; ++k) {
			this.valueslength[k] = Character.reverseBytes(in.readChar());
		}
	}

	/**
	 * Ensures the backing array can hold `minNbRuns` runs, reallocating (with geometric growth) and
	 * copying the existing runs so they start at run index `offset` if so.
	 *
	 * @param offset    run index the existing runs should occupy after a reallocation
	 * @param minNbRuns minimum number of runs the array must accommodate
	 * @return `true` if the array was reallocated, `false` if it already had room
	 */
	boolean ensureCapacity(final int offset, final int minNbRuns) {
		final int minCapacity = 2 * minNbRuns;
		if (this.valueslength.length < minCapacity) {
			int newCapacity = this.valueslength.length;
			while (newCapacity < minCapacity) {
				newCapacity = computeCapacity(newCapacity);
			}
			char[] nv = new char[newCapacity];
			copyValuesLength(this.valueslength, 0, nv, offset, this.nbrruns);
			this.valueslength = nv;
			return true;
		}
		return false;
	}

	/**
	 * Value equality against any container: same-type comparisons take a direct fast path, otherwise
	 * the two element streams are compared after a cheap cardinality check.
	 */
	@Override
	public boolean equals(@Nullable final Object o) {
		if (o instanceof RunContainer) {
			return equals((RunContainer) o);
		} else if (o instanceof ArrayContainer) {
			return equals((ArrayContainer) o);
		} else if (o instanceof Container) {
			if (((Container) o).getCardinality() != this.getCardinality()) {
				return false; // should be a frequent branch if they differ
			}
			// next bit could be optimized if needed:
			// cross-type content equality legitimately iterates both value streams (allocates iterators)
			CharIterator me = this.getCharIterator();
			CharIterator you = ((Container) o).getCharIterator();
			while (me.hasNext()) {
				if (me.next() != you.next()) {
					return false;
				}
			}
			return true;
		}
		return false;
	}

	/**
	 * Fast path for two run containers: compares only the live regions of the two run arrays.
	 */
	private boolean equals(@Nonnull final RunContainer rc) {
		return Arrays.equals(this.valueslength, 0, 2 * this.nbrruns, rc.valueslength, 0, 2 * rc.nbrruns);
	}

	/**
	 * Whether the runs expand to exactly the values of `arrayContainer`, in the same order.
	 */
	private boolean equals(@Nonnull final ArrayContainer arrayContainer) {
		int pos = 0;
		for (char i = 0; i < this.nbrruns; ++i) {
			char runStart = getValue(i);
			int length = (getLength(i));
			if (pos + length >= arrayContainer.getCardinality()) {
				return false;
			}
			if (arrayContainer.content[pos] != runStart) {
				return false;
			}
			if (arrayContainer.content[pos + length] != (char) ((runStart) + length)) {
				return false;
			}
			pos += length + 1;
		}
		return pos == arrayContainer.getCardinality();
	}

	/**
	 * Expands every value into `x` starting at position `i`, OR-ing each with `mask` (typically the
	 * high 16 bits) to reconstruct full 32-bit keys.
	 */
	@Override
	public void fillLeastSignificant16bits(@Nonnull final int[] x, final int i, final int mask) {
		int pos = i;
		for (int k = 0; k < this.nbrruns; ++k) {
			final int limit = (this.getLength(k));
			final int base = (this.getValue(k));
			for (int le = 0; le <= limit; ++le) {
				x[pos++] = (base + le) | mask;
			}
		}
	}

	/**
	 * Toggles a single value: removes it if present, otherwise adds it.
	 */
	@Nonnull
	@Override
	public Container flip(final char x) {
		if (this.contains(x)) {
			return this.remove(x);
		} else {
			return this.add(x);
		}
	}

	/**
	 * Serialized byte size of the run array including its 2-byte run-count header.
	 */
	@Override
	public int getArraySizeInBytes() {
		return 2 + 4 * this.nbrruns; // "array" includes its size
	}

	/**
	 * Total number of values, summed as `nbrruns + Σ length` over the runs, in `O(nbrruns)`.
	 */
	@Override
	public int getCardinality() {
		int sum = this.nbrruns; // lengths are returned -1
		for (int k = 1; k < this.nbrruns * 2; k += 2) {
			sum += this.valueslength[k];
		}
		return sum;
	}

	/**
	 * Gets the length of the run at the index.
	 *
	 * @param index the index of the run.
	 * @return the length of the run at the index.
	 * @throws ArrayIndexOutOfBoundsException if index is negative or larger than the index of the
	 *                                        last run.
	 */
	public char getLength(final int index) {
		return this.valueslength[2 * index + 1];
	}

	/**
	 * Descending value iterator that expands runs on the fly.
	 */
	@Nonnull
	@Override
	public PeekableCharIterator getReverseCharIterator() {
		return new ReverseRunContainerCharIterator(this);
	}

	/**
	 * Ascending value iterator that expands runs on the fly.
	 */
	@Nonnull
	@Override
	public PeekableCharIterator getCharIterator() {
		return new RunContainerCharIterator(this);
	}

	/**
	 * Ascending value iterator that also tracks each value's 1-based rank.
	 */
	@Nonnull
	@Override
	public PeekableCharRankIterator getCharRankIterator() {
		return new RunContainerCharRankIterator(this);
	}

	/**
	 * Batch iterator that drains runs into caller-supplied buffers.
	 */
	@Nonnull
	@Override
	public ContainerBatchIterator getBatchIterator() {
		return new RunBatchIterator(this);
	}

	/**
	 * In-memory footprint of the run data plus header, in bytes.
	 */
	@Override
	public int getSizeInBytes() {
		return this.nbrruns * 4 + 4;
	}

	/**
	 * Heap footprint: this object (an `int` run count and one reference) plus the `valueslength` array
	 * measured at its allocated length. Two chars per run, so the array holds `2 * nbrruns` used entries and
	 * however many more it grew to.
	 */
	@Override
	public long getHeapSizeInBytes(@Nonnull HeapLayout layout) {
		return layout.sizeOfObject(Integer.BYTES + layout.referenceSize())
			+ layout.sizeOfArray(this.valueslength.length, Character.BYTES);
	}

	/**
	 * Gets the value of the first element of the run at the index.
	 *
	 * @param index the index of the run.
	 * @return the value of the first element of the run at the index.
	 * @throws ArrayIndexOutOfBoundsException if index is negative or larger than the index of the
	 *                                        last run.
	 */
	public char getValue(final int index) {
		return this.valueslength[2 * index];
	}

	/**
	 * Hash derived from the live run pairs; consistent with {@link #equals(Object)} across types.
	 */
	@Override
	// nbrruns and valueslength are mutable by design
	@SuppressWarnings("NonFinalFieldReferencedInHashCode")
	public int hashCode() {
		int hash = 0;
		for (int k = 0; k < this.nbrruns * 2; ++k) {
			hash += 31 * hash + this.valueslength[k];
		}
		return hash;
	}

	/**
	 * Adds the half-open range `[begin, end)` in place, merging with and between existing runs. Two
	 * binary searches (`O(log nbrruns)`) locate the affected span; the boundary runs are trimmed,
	 * extended or fused and the runs strictly between them are collapsed.
	 *
	 * @param begin inclusive range start
	 * @param end   exclusive range end
	 * @return this container (mutated)
	 * @throws IllegalArgumentException if `begin > end` or `end > 2^16`
	 */
	@Nonnull
	@Override
	public Container iadd(final int begin, final int end) {
		// it might be better and simpler to do return
		// toBitmapOrArrayContainer(getCardinality()).iadd(begin,end)
		if (end == begin) {
			return this;
		}
		if ((begin > end) || (end > (1 << 16))) {
			throw new IllegalArgumentException("Invalid range [" + begin + "," + end + ")");
		}

		if (begin == end - 1) {
			add((char) begin);
			return this;
		}

		int bIndex = unsignedInterleavedBinarySearch(this.valueslength, 0, this.nbrruns, (char) begin);
		int eIndex =
			unsignedInterleavedBinarySearch(
				this.valueslength, bIndex >= 0 ? bIndex : -bIndex - 1, this.nbrruns, (char) (end - 1));

		if (bIndex >= 0 && eIndex >= 0) {
			mergeValuesLength(bIndex, eIndex);
			return this;

		} else if (bIndex >= 0) {
			eIndex = -eIndex - 2;

			if (canPrependValueLength(end - 1, eIndex + 1)) {
				mergeValuesLength(bIndex, eIndex + 1);
				return this;
			}

			appendValueLength(end - 1, eIndex);
			mergeValuesLength(bIndex, eIndex);
			return this;

		} else if (eIndex >= 0) {
			bIndex = -bIndex - 2;

			if (bIndex >= 0) {
				if (valueLengthContains(begin - 1, bIndex)) {
					mergeValuesLength(bIndex, eIndex);
					return this;
				}
			}
			prependValueLength(begin, bIndex + 1);
			mergeValuesLength(bIndex + 1, eIndex);
			return this;

		} else {
			bIndex = -bIndex - 2;
			eIndex = -eIndex - 2;

			if (eIndex >= 0) {
				if (bIndex >= 0) {
					if (!valueLengthContains(begin - 1, bIndex)) {
						if (bIndex == eIndex) {
							if (canPrependValueLength(end - 1, eIndex + 1)) {
								prependValueLength(begin, eIndex + 1);
								return this;
							}
							makeRoomAtIndex(eIndex + 1);
							setValue(eIndex + 1, (char) begin);
							setLength(eIndex + 1, (char) (end - 1 - begin));
							return this;

						} else {
							bIndex++;
							prependValueLength(begin, bIndex);
						}
					}
				} else {
					bIndex = 0;
					prependValueLength(begin, bIndex);
				}

				if (canPrependValueLength(end - 1, eIndex + 1)) {
					mergeValuesLength(bIndex, eIndex + 1);
					return this;
				}

				appendValueLength(end - 1, eIndex);
				mergeValuesLength(bIndex, eIndex);
				return this;

			} else {
				if (canPrependValueLength(end - 1, 0)) {
					prependValueLength(begin, 0);
				} else {
					makeRoomAtIndex(0);
					setValue(0, (char) begin);
					setLength(0, (char) (end - 1 - begin));
				}
				return this;
			}
		}
	}

	/**
	 * In-place intersection; a run container cannot cheaply mutate in place, so delegates to
	 * {@link #and(ArrayContainer)}.
	 */
	@Nonnull
	@Override
	public Container iand(@Nonnull final ArrayContainer x) {
		return and(x);
	}

	/**
	 * In-place intersection; delegates to {@link #and(BitmapContainer)}.
	 */
	@Nonnull
	@Override
	public Container iand(@Nonnull final BitmapContainer x) {
		return and(x);
	}

	/**
	 * In-place intersection; delegates to {@link #and(RunContainer)}.
	 */
	@Nonnull
	@Override
	public Container iand(@Nonnull final RunContainer x) {
		return and(x);
	}

	/**
	 * In-place difference; delegates to {@link #andNot(ArrayContainer)}.
	 */
	@Nonnull
	@Override
	public Container iandNot(@Nonnull final ArrayContainer x) {
		return andNot(x);
	}

	/**
	 * In-place difference; delegates to {@link #andNot(BitmapContainer)}.
	 */
	@Nonnull
	@Override
	public Container iandNot(@Nonnull final BitmapContainer x) {
		return andNot(x);
	}

	/**
	 * In-place difference; delegates to {@link #andNot(RunContainer)}.
	 */
	@Nonnull
	@Override
	public Container iandNot(@Nonnull final RunContainer x) {
		return andNot(x);
	}

	/**
	 * Lazy in-place union with an array container (skips cardinality repair). Short-circuits when this
	 * container is already full, otherwise merges into `this` via `ilazyorToRun`.
	 */
	@Nonnull
	Container ilazyor(@Nonnull final ArrayContainer x) {
		if (isFull()) {
			return this; // this can sometimes solve a lot of computation!
		}
		return ilazyorToRun(x);
	}

	/**
	 * Merges `x` into this run container in place: the existing runs are shifted to the array tail
	 * (see `copyToOffset`) so the merged result can be `smartAppend`-ed back from the front without
	 * extra allocation, then converts to a lazy bitmap if the runs fragmented too far.
	 */
	@Nonnull
	private Container ilazyorToRun(@Nonnull final ArrayContainer x) {
		if (isFull()) {
			return full();
		}
		final int nbrruns = this.nbrruns;
		final int offset = Math.max(nbrruns, x.getCardinality());
		copyToOffset(offset);
		int rlepos = 0;
		this.nbrruns = 0;
		PeekableCharIterator i = x.getCharIterator();
		while (i.hasNext() && (rlepos < nbrruns)) {
			if (getValue(rlepos + offset) - i.peekNext() <= 0) {
				smartAppend(getValue(rlepos + offset), getLength(rlepos + offset));
				rlepos++;
			} else {
				smartAppend(i.next());
			}
		}
		if (i.hasNext()) {
			/*
			 * if(this.nbrruns>0) { // this might be useful if the run container has just one very large
			 * run int lastval = (getValue(nbrruns + offset - 1)) +
			 * (getLength(nbrruns + offset - 1)) + 1; i.advanceIfNeeded((char)
			 * lastval); }
			 */
			while (i.hasNext()) {
				smartAppend(i.next());
			}
		} else {
			while (rlepos < nbrruns) {
				smartAppend(getValue(rlepos + offset), getLength(rlepos + offset));
				rlepos++;
			}
		}
		return convertToLazyBitmapIfNeeded();
	}

	/**
	 * Geometric growth schedule for the backing array: double while tiny, then 1.5x, then 1.25x once
	 * large, to bound reallocation cost while limiting slack.
	 */
	private static int computeCapacity(final int oldCapacity) {
		return oldCapacity == 0
			? DEFAULT_INIT_SIZE
			: oldCapacity < 64
			  ? oldCapacity * 2
				: oldCapacity < 1024 ? oldCapacity * 3 / 2 : oldCapacity * 5 / 4;
	}

	private void incrementLength(final int index) {
		this.valueslength[2 * index + 1]++;
	}

	private void incrementValue(final int index) {
		this.valueslength[2 * index]++;
	}

	/**
	 * Trims the front of the run at `index` to start at `value`, shrinking its length to match.
	 */
	private void initValueLength(final int value, final int index) {
		int initialValue = (getValue(index));
		int length = (getLength(index));
		setValue(index, (char) (value));
		setLength(index, (char) (length - (value - initialValue)));
	}

	/**
	 * Complements the values in `[rangeStart, rangeEnd)` in place when the backing array has room for
	 * the (at most one) extra run the flip may introduce; falls back to {@link #not(int, int)} when
	 * genuine expansion is required. Returns the most compact container form afterwards.
	 *
	 * @param rangeStart inclusive start of the range to flip
	 * @param rangeEnd   exclusive end of the range to flip
	 * @return the flipped container (may be `this`, an array or a bitmap)
	 */
	@Nonnull
	@Override
	public Container inot(final int rangeStart, final int rangeEnd) {
		if (rangeEnd <= rangeStart) {
			return this;
		}

		// write special case code for rangeStart=0; rangeEnd=65535
		// a "sliding" effect where each range records the gap adjacent it
		// can probably be quite fast. Probably have 2 cases: start with a
		// 0 run vs start with a 1 run. If you both start and end with 0s,
		// you will require room for expansion.

		// the +1 below is needed in case the valueslength.length is odd
		if (this.valueslength.length <= 2 * this.nbrruns + 1) {
			// no room for expansion
			// analyze whether this is a case that will require expansion (that we cannot do)
			// this is a bit costly now (4 "contains" checks)

			boolean lastValueBeforeRange = false;
			boolean firstValueInRange;
			boolean lastValueInRange;
			boolean firstValuePastRange = false;

			// contains is based on a binary search and is hopefully fairly fast.
			// however, one binary search could *usually* suffice to find both
			// lastValueBeforeRange AND firstValueInRange. ditto for
			// lastVaueInRange and firstValuePastRange

			// find the start of the range
			if (rangeStart > 0) {
				lastValueBeforeRange = contains((char) (rangeStart - 1));
			}
			firstValueInRange = contains((char) rangeStart);

			if (lastValueBeforeRange == firstValueInRange) {
				// expansion is required if also lastValueInRange==firstValuePastRange

				// tougher to optimize out, but possible.
				lastValueInRange = contains((char) (rangeEnd - 1));
				if (rangeEnd != 65536) {
					firstValuePastRange = contains((char) rangeEnd);
				}

				// there is definitely one more run after the operation.
				if (lastValueInRange == firstValuePastRange) {
					return not(rangeStart, rangeEnd); // can't do in-place: true space limit
				}
			}
		}
		// either no expansion required, or we have room to handle any required expansion for it.

		// remaining code is just a minor variation on not()
		int myNbrRuns = this.nbrruns;

		RunContainer ans = this; // copy on top of self.
		int k = 0;
		ans.nbrruns = 0; // losing this.nbrruns, which is stashed in myNbrRuns.

		// could try using unsignedInterleavedBinarySearch(valueslength, 0, nbrruns, rangeStart) instead
		// of sequential scan
		// to find the starting location

		for (; (k < myNbrRuns) && ((this.getValue(k)) < rangeStart); ++k) {
			// since it is atop self, there is no copying needed
			ans.nbrruns++;
		}
		// We will work left to right, with a read pointer that always stays
		// left of the write pointer. However, we need to give the read pointer a head start.
		// use local variables so we are always reading 1 location ahead.

		char bufferedValue = 0, bufferedLength = 0; // 65535 start and 65535 length would be illegal,
		// could use as sentinel
		char nextValue = 0, nextLength = 0;
		if (k < myNbrRuns) { // prime the readahead variables
			bufferedValue = getValue(k);
			bufferedLength = getLength(k);
		}

		ans.smartAppendExclusive((char) rangeStart, (char) (rangeEnd - rangeStart - 1));

		for (; k < myNbrRuns; ++k) {
			if (ans.nbrruns > k + 1) {
				throw new RuntimeException(
					"internal error in inot, writer has overtaken reader!! " + k + " " + ans.nbrruns);
			}
			if (k + 1 < myNbrRuns) {
				nextValue = getValue(k + 1); // readahead for next iteration
				nextLength = getLength(k + 1);
			}
			ans.smartAppendExclusive(bufferedValue, bufferedLength);
			bufferedValue = nextValue;
			bufferedLength = nextLength;
		}
		// the number of runs can increase by one, meaning (rarely) a bitmap will become better
		// or the cardinality can decrease by a lot, making an array better
		return ans.toEfficientContainer();
	}

	/**
	 * Whether any value of `x` falls inside a run; scans `x` against the runs, stops on first hit.
	 */
	@Override
	public boolean intersects(@Nonnull final ArrayContainer x) {
		if (this.nbrruns == 0) {
			return false;
		}
		int rlepos = 0;
		int arraypos = 0;
		int rleval = this.getValue(rlepos);
		int rlelength = this.getLength(rlepos);
		while (arraypos < x.cardinality) {
			int arrayval = (x.content[arraypos]);
			while (rleval + rlelength < arrayval) { // this will frequently be false
				++rlepos;
				if (rlepos == this.nbrruns) {
					return false;
				}
				rleval = this.getValue(rlepos);
				rlelength = this.getLength(rlepos);
			}
			if (rleval > arrayval) {
				arraypos = Util.advanceUntil(x.content, arraypos, x.cardinality, this.getValue(rlepos));
			} else {
				return true;
			}
		}
		return false;
	}

	/**
	 * Whether any run overlaps a set bit of `x`, testing each run's range against the bitmap.
	 */
	@Override
	public boolean intersects(@Nonnull final BitmapContainer x) {
		for (int run = 0; run < this.nbrruns; ++run) {
			int runStart = this.getValue(run);
			int runEnd = runStart + this.getLength(run);
			if (x.intersects(runStart, runEnd + 1)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Whether the two run lists overlap anywhere, via a linear merge stopping on first overlap.
	 */
	@Override
	public boolean intersects(@Nonnull final RunContainer x) {
		int rlepos = 0;
		int xrlepos = 0;
		int start = this.getValue(rlepos);
		int end = start + this.getLength(rlepos) + 1;
		int xstart = x.getValue(xrlepos);
		int xend = xstart + x.getLength(xrlepos) + 1;
		while (rlepos < this.nbrruns && xrlepos < x.nbrruns) {
			if (end <= xstart) {
				if (ENABLE_GALLOPING_AND) {
					rlepos = skipAhead(this, rlepos, xstart); // skip over runs until we have end > xstart (or
					// rlepos is advanced beyond end)
				} else {
					++rlepos;
				}

				if (rlepos < this.nbrruns) {
					start = (this.getValue(rlepos));
					end = start + (this.getLength(rlepos)) + 1;
				}
			} else if (xend <= start) {
				// exit the second run
				if (ENABLE_GALLOPING_AND) {
					xrlepos = skipAhead(x, xrlepos, start);
				} else {
					++xrlepos;
				}

				if (xrlepos < x.nbrruns) {
					xstart = (x.getValue(xrlepos));
					xend = xstart + (x.getLength(xrlepos)) + 1;
				}
			} else { // they overlap
				return true;
			}
		}
		return false;
	}

	/**
	 * Whether any run overlaps the half-open range `[minimum, supremum)`.
	 *
	 * @throws RuntimeException if the range is out of the valid `[0, 2^16]` bounds
	 */
	@Override
	public boolean intersects(final int minimum, final int supremum) {
		if ((minimum < 0) || (supremum < minimum) || (supremum > (1 << 16))) {
			throw new RuntimeException("This should never happen (bug).");
		}
		for (int i = 0; i < numberOfRuns(); ++i) {
			int runFirstValue = getValue(i);
			int runLastValue = (char) (runFirstValue + getLength(i)) + 1;
			if (supremum > runFirstValue && minimum < runLastValue) {
				return true;
			}
		}
		return false;
	}

	/**
	 * In-place union with an array container. Existing runs are shifted to the tail as scratch, then
	 * the two sorted streams are merged back from the front with `smartAppend`, and the result is
	 * downgraded to the most compact form.
	 */
	@Nonnull
	@Override
	public Container ior(@Nonnull final ArrayContainer x) {
		if (isFull()) {
			return this;
		}
		final int nbrruns = this.nbrruns;
		final int offset = Math.max(nbrruns, x.getCardinality());
		copyToOffset(offset);
		int rlepos = 0;
		this.nbrruns = 0;
		PeekableCharIterator i = x.getCharIterator();
		while (i.hasNext() && (rlepos < nbrruns)) {
			if (getValue(rlepos + offset) - i.peekNext() <= 0) {
				smartAppend(getValue(rlepos + offset), getLength(rlepos + offset));
				rlepos++;
			} else {
				smartAppend(i.next());
			}
		}
		if (i.hasNext()) {
			/*
			 * if(this.nbrruns>0) { // this might be useful if the run container has just one very large
			 * run int lastval = (getValue(nbrruns + offset - 1)) +
			 * (getLength(nbrruns + offset - 1)) + 1; i.advanceIfNeeded((char)
			 * lastval); }
			 */
			while (i.hasNext()) {
				smartAppend(i.next());
			}
		} else {
			while (rlepos < nbrruns) {
				smartAppend(getValue(rlepos + offset), getLength(rlepos + offset));
				rlepos++;
			}
		}
		return toEfficientContainer();
	}

	/**
	 * In-place union with a bitmap; short-circuits when full, otherwise delegates to
	 * {@link #or(BitmapContainer)}.
	 */
	@Nonnull
	@Override
	public Container ior(@Nonnull final BitmapContainer x) {
		if (isFull()) {
			return this;
		}
		return or(x);
	}

	/**
	 * In-place union of two run containers. Existing runs are shifted to the tail as scratch, then the
	 * two run lists are merged back from the front with `smartAppend`, and the result is downgraded to
	 * the most compact form. Runs in `O(nbrruns + x.nbrruns)`.
	 */
	@Nonnull
	@Override
	public Container ior(@Nonnull final RunContainer x) {
		if (isFull()) {
			return this;
		}

		final int nbrruns = this.nbrruns;
		final int xnbrruns = x.nbrruns;
		final int offset = Math.max(nbrruns, xnbrruns);

		// Push all values length to the end of the array (resize array if needed)
		copyToOffset(offset);
		// Aggregate and store the result at the beginning of the array
		this.nbrruns = 0;
		int rlepos = 0;
		int xrlepos = 0;

		// Add values length (smaller first)
		while ((rlepos < nbrruns) && (xrlepos < xnbrruns)) {
			final char value = this.getValue(offset + rlepos);
			final char xvalue = x.getValue(xrlepos);
			final char length = this.getLength(offset + rlepos);
			final char xlength = x.getLength(xrlepos);

			if (value - xvalue <= 0) {
				this.smartAppend(value, length);
				++rlepos;
			} else {
				this.smartAppend(xvalue, xlength);
				++xrlepos;
			}
		}

		while (rlepos < nbrruns) {
			this.smartAppend(this.getValue(offset + rlepos), this.getLength(offset + rlepos));
			++rlepos;
		}

		while (xrlepos < xnbrruns) {
			this.smartAppend(x.getValue(xrlepos), x.getLength(xrlepos));
			++xrlepos;
		}
		return this.toEfficientContainer();
	}

	/**
	 * Removes the half-open range `[begin, end)` in place. Two binary searches locate the boundary
	 * runs (`O(log nbrruns)`); those runs are trimmed or split and any runs fully inside the range are
	 * reclaimed. Removal never increases the array footprint, so it is always done in place.
	 *
	 * @param begin inclusive range start
	 * @param end   exclusive range end
	 * @return this container (mutated)
	 * @throws IllegalArgumentException if `begin > end` or `end > 2^16`
	 */
	@Nonnull
	@Override
	public Container iremove(final int begin, final int end) {
		// it might be better and simpler to do return
		// toBitmapOrArrayContainer(getCardinality()).iremove(begin,end)
		if (end == begin) {
			return this;
		}
		if ((begin > end) || (end > (1 << 16))) {
			throw new IllegalArgumentException("Invalid range [" + begin + "," + end + ")");
		}
		if (begin == end - 1) {
			remove((char) begin);
			return this;
		}

		int bIndex = unsignedInterleavedBinarySearch(this.valueslength, 0, this.nbrruns, (char) begin);
		int eIndex =
			unsignedInterleavedBinarySearch(
				this.valueslength, bIndex >= 0 ? bIndex : -bIndex - 1, this.nbrruns, (char) (end - 1));

		// note, eIndex is looking for (end-1)

		if (bIndex >= 0) { // beginning marks beginning of a run
			if (eIndex < 0) {
				eIndex = -eIndex - 2;
			}
			// eIndex could be a run that begins exactly at "end"
			// or it might be an earlier run

			// if the end is before the first run, we'd have eIndex==-1. But bIndex makes this impossible.

			if (valueLengthContains(end, eIndex)) {
				initValueLength(end, eIndex); // there is something left in the run
				recoverRoomsInRange(bIndex - 1, eIndex - 1);
			} else {
				recoverRoomsInRange(bIndex - 1, eIndex); // nothing left in the run
			}

		} else if (eIndex >= 0) {
			// start does not coincide to a run start, but end does.
			bIndex = -bIndex - 2;

			if (bIndex >= 0) {
				if (valueLengthContains(begin, bIndex)) {
					closeValueLength(begin - 1, bIndex);
				}
			}

			// last run is one shorter
			if (getLength(eIndex) == 0) { // special case where we remove last run
				recoverRoomsInRange(eIndex - 1, eIndex);
			} else {
				incrementValue(eIndex);
				decrementLength(eIndex);
			}
			recoverRoomsInRange(bIndex, eIndex - 1);

		} else {
			bIndex = -bIndex - 2;
			eIndex = -eIndex - 2;

			if (eIndex >= 0) { // end-1 is not before first run.
				if (bIndex >= 0) { // nor is begin
					if (bIndex == eIndex) { // all removal nested properly between
						// one run start and the next
						if (valueLengthContains(begin, bIndex)) {
							if (valueLengthContains(end, eIndex)) {
								// proper nesting within a run, generates 2 sub-runs
								makeRoomAtIndex(bIndex);
								closeValueLength(begin - 1, bIndex);
								initValueLength(end, bIndex + 1);
								return this;
							}
							// removed area extends beyond run.
							closeValueLength(begin - 1, bIndex);
						}
					} else { // begin in one run area, end in a later one.
						if (valueLengthContains(begin, bIndex)) {
							closeValueLength(begin - 1, bIndex);
							// this cannot leave the bIndex run empty.
						}
						if (valueLengthContains(end, eIndex)) {
							// there is additional stuff in the eIndex run
							initValueLength(end, eIndex);
							eIndex--;
						} // run ends at or before the range being removed, can delete it

						recoverRoomsInRange(bIndex, eIndex);
					}

				} else {
					// removed range begins before the first run
					if (valueLengthContains(end, eIndex)) { // had been end-1
						initValueLength(end, eIndex);
						recoverRoomsInRange(bIndex, eIndex - 1);
					} else { // removed range includes all the last run
						recoverRoomsInRange(bIndex, eIndex);
					}
				}
			} // eIndex == -1: whole range is before first run, nothing to delete...
		}
		return this;
	}

	/**
	 * Whether this is the single run `0 .. 65535`, i.e. every value is present.
	 */
	@Override
	public boolean isFull() {
		return (this.nbrruns == 1) && (this.getValue(0) == 0) && (this.getLength(0) == 0xFFFF);
	}

	/**
	 * Returns a full container: one run covering the entire `0 .. 65535` domain.
	 */
	@Nonnull
	public static RunContainer full() {
		return new RunContainer(0, 1 << 16);
	}

	/**
	 * Boxed {@link Character} view over the primitive char iterator, for the `Iterable` contract.
	 */
	@Nonnull
	@Override
	public Iterator<Character> iterator() {
		final CharIterator i = getCharIterator();
		return new Iterator<>() {

			@Override
			public boolean hasNext() {
				return i.hasNext();
			}

			@Nonnull
			@Override
			public Character next() {
				return i.next();
			}

			@Override
			public void remove() {
				i.remove();
			}
		};
	}

	/**
	 * In-place symmetric difference; delegates to {@link #xor(ArrayContainer)}.
	 */
	@Nonnull
	@Override
	public Container ixor(@Nonnull final ArrayContainer x) {
		return xor(x);
	}

	/**
	 * In-place symmetric difference; delegates to {@link #xor(BitmapContainer)}.
	 */
	@Nonnull
	@Override
	public Container ixor(@Nonnull final BitmapContainer x) {
		return xor(x);
	}

	/**
	 * In-place symmetric difference; delegates to {@link #xor(RunContainer)}.
	 */
	@Nonnull
	@Override
	public Container ixor(@Nonnull final RunContainer x) {
		return xor(x);
	}

	/**
	 * Subtracts an array container producing a fresh run container, without the final compaction step
	 * (the caller repairs the type). Single linear merge, in `O(nbrruns + x.cardinality)`.
	 */
	@Nonnull
	private RunContainer lazyandNot(@Nonnull final ArrayContainer x) {
		if (x.isEmpty()) {
			return this;
		}
		RunContainer answer = new RunContainer(new char[2 * (this.nbrruns + x.cardinality)], 0);
		int rlepos = 0;
		int xrlepos = 0;
		int start = (this.getValue(rlepos));
		int end = start + (this.getLength(rlepos)) + 1;
		int xstart = (x.content[xrlepos]);
		while ((rlepos < this.nbrruns) && (xrlepos < x.cardinality)) {
			if (end <= xstart) {
				// output the first run
				answer.valueslength[2 * answer.nbrruns] = (char) start;
				answer.valueslength[2 * answer.nbrruns + 1] = (char) (end - start - 1);
				answer.nbrruns++;
				rlepos++;
				if (rlepos < this.nbrruns) {
					start = (this.getValue(rlepos));
					end = start + (this.getLength(rlepos)) + 1;
				}
			} else if (xstart + 1 <= start) {
				// exit the second run
				xrlepos++;
				if (xrlepos < x.cardinality) {
					xstart = (x.content[xrlepos]);
				}
			} else {
				if (start < xstart) {
					answer.valueslength[2 * answer.nbrruns] = (char) start;
					answer.valueslength[2 * answer.nbrruns + 1] = (char) (xstart - start - 1);
					answer.nbrruns++;
				}
				if (xstart + 1 < end) {
					start = xstart + 1;
				} else {
					rlepos++;
					if (rlepos < this.nbrruns) {
						start = (this.getValue(rlepos));
						end = start + (this.getLength(rlepos)) + 1;
					}
				}
			}
		}
		if (rlepos < this.nbrruns) {
			answer.valueslength[2 * answer.nbrruns] = (char) start;
			answer.valueslength[2 * answer.nbrruns + 1] = (char) (end - start - 1);
			answer.nbrruns++;
			rlepos++;
			if (rlepos < this.nbrruns) {
				System.arraycopy(
					this.valueslength,
					2 * rlepos,
					answer.valueslength,
					2 * answer.nbrruns,
					2 * (this.nbrruns - rlepos)
				);
				answer.nbrruns = answer.nbrruns + this.nbrruns - rlepos;
			}
		}
		return answer;
	}

	/**
	 * Union with an array container that skips cardinality repair; the caller invokes
	 * {@link #repairAfterLazy()}.
	 */
	@Nonnull
	protected Container lazyor(@Nonnull final ArrayContainer x) {
		return lazyorToRun(x);
	}

	/**
	 * Builds the union of this container and an array container as a fresh run container via a single
	 * `smartAppend` merge, converting to a lazy bitmap if the runs fragment past the run threshold.
	 */
	@Nonnull
	private Container lazyorToRun(@Nonnull final ArrayContainer x) {
		if (isFull()) {
			return full();
		}
		// should optimize for the frequent case where we have a single run
		RunContainer answer = new RunContainer(new char[2 * (this.nbrruns + x.getCardinality())], 0);
		int rlepos = 0;
		PeekableCharIterator i = x.getCharIterator();

		while (i.hasNext() && (rlepos < this.nbrruns)) {
			if (getValue(rlepos) - i.peekNext() <= 0) {
				answer.smartAppend(getValue(rlepos), getLength(rlepos));
				// in theory, this next code could help, in practice it doesn't.
				/*
				 * int lastval = (answer.getValue(answer.nbrruns - 1)) +
				 * (answer.getLength(answer.nbrruns - 1)) + 1; i.advanceIfNeeded((char)
				 * lastval);
				 */

				rlepos++;
			} else {
				answer.smartAppend(i.next());
			}
		}
		if (i.hasNext()) {
			/*
			 * if(answer.nbrruns>0) { this might be useful if the run container has just one very large
			 * run int lastval = (answer.getValue(answer.nbrruns - 1)) +
			 * (answer.getLength(answer.nbrruns - 1)) + 1; i.advanceIfNeeded((char)
			 * lastval); }
			 */
			while (i.hasNext()) {
				answer.smartAppend(i.next());
			}
		} else {
			while (rlepos < this.nbrruns) {
				answer.smartAppend(getValue(rlepos), getLength(rlepos));
				rlepos++;
			}
		}
		if (answer.isFull()) {
			return full();
		}
		return answer.convertToLazyBitmapIfNeeded();
	}

	/**
	 * Builds the symmetric difference with an array container as a fresh run container via a single
	 * `smartAppendExclusive` merge, without the final type-repair step. Runs in
	 * `O(nbrruns + x.cardinality)`.
	 */
	@Nonnull
	private Container lazyxor(@Nonnull final ArrayContainer x) {
		if (x.isEmpty()) {
			return this;
		}
		if (this.nbrruns == 0) {
			return x;
		}
		RunContainer answer = new RunContainer(new char[2 * (this.nbrruns + x.getCardinality())], 0);
		int rlepos = 0;
		CharIterator i = x.getCharIterator();
		char cv = i.next();

		while (true) {
			if (getValue(rlepos) < cv) {
				answer.smartAppendExclusive(getValue(rlepos), getLength(rlepos));
				rlepos++;
				if (rlepos == this.nbrruns) {
					answer.smartAppendExclusive(cv);
					while (i.hasNext()) {
						answer.smartAppendExclusive(i.next());
					}
					break;
				}
			} else {
				answer.smartAppendExclusive(cv);
				if (!i.hasNext()) {
					while (rlepos < this.nbrruns) {
						answer.smartAppendExclusive(getValue(rlepos), getLength(rlepos));
						rlepos++;
					}
					break;
				} else {
					cv = i.next();
				}
			}
		}
		return answer;
	}

	/**
	 * Returns a copy holding only the first `maxcardinality` values in ascending order (truncating the
	 * run that straddles the limit). Returns a full clone when the limit is not below the cardinality.
	 */
	@Nonnull
	@Override
	public Container limit(final int maxcardinality) {
		if (maxcardinality >= getCardinality()) {
			return clone();
		}

		int r;
		int cardinality = 0;
		for (r = 0; r < this.nbrruns; ++r) {
			cardinality += (getLength(r)) + 1;
			if (maxcardinality <= cardinality) {
				break;
			}
		}

		RunContainer rc = new RunContainer(Arrays.copyOf(this.valueslength, 2 * (r + 1)), r + 1);
		rc.setLength(r, (char) ((rc.getLength(r)) - cardinality + maxcardinality));
		return rc;
	}

	/**
	 * Opens a slot for one new run at run `index`, growing the array and shifting later runs up.
	 */
	private void makeRoomAtIndex(final int index) {
		if (2 * (this.nbrruns + 1) > this.valueslength.length) {
			int newCapacity = computeCapacity(this.valueslength.length);
			char[] newValuesLength = new char[newCapacity];
			copyValuesLength(this.valueslength, 0, newValuesLength, 0, this.nbrruns);
			this.valueslength = newValuesLength;
		}
		copyValuesLength(this.valueslength, index, this.valueslength, index + 1, this.nbrruns - index);
		this.nbrruns++;
	}

	/**
	 * Fuses runs `begin .. end` (inclusive) into the single run at `begin`, reclaiming the rest.
	 */
	private void mergeValuesLength(final int begin, final int end) {
		if (begin < end) {
			int bValue = (getValue(begin));
			int eValue = (getValue(end));
			int eLength = (getLength(end));
			int newLength = eValue - bValue + eLength;
			setLength(begin, (char) newLength);
			recoverRoomsInRange(begin, end);
		}
	}

	/**
	 * Returns a new container with the values in `[rangeStart, rangeEnd)` complemented, leaving this
	 * one untouched. Copies the runs before the range, flips the range with `smartAppendExclusive`,
	 * then downgrades to the most compact form.
	 *
	 * @param rangeStart inclusive start of the range to flip
	 * @param rangeEnd   exclusive end of the range to flip
	 * @return the flipped container
	 */
	@Nonnull
	@Override
	public Container not(final int rangeStart, final int rangeEnd) {
		if (rangeEnd <= rangeStart) {
			return this.clone();
		}
		RunContainer ans = new RunContainer(this.nbrruns + 1);
		int k = 0;
		for (; (k < this.nbrruns) && ((this.getValue(k)) < rangeStart); ++k) {
			ans.valueslength[2 * k] = this.valueslength[2 * k];
			ans.valueslength[2 * k + 1] = this.valueslength[2 * k + 1];
			ans.nbrruns++;
		}
		ans.smartAppendExclusive((char) rangeStart, (char) (rangeEnd - rangeStart - 1));
		for (; k < this.nbrruns; ++k) {
			ans.smartAppendExclusive(getValue(k), getLength(k));
		}
		// the number of runs can increase by one, meaning (rarely) a bitmap will become better
		// or the cardinality can decrease by a lot, making an array better
		return ans.toEfficientContainer();
	}

	/**
	 * Exact number of runs (`nbrruns`), in `O(1)`.
	 */
	@Override
	public int numberOfRuns() {
		return this.nbrruns;
	}

	/**
	 * Union with an array container, computed lazily and then repaired to the most compact form.
	 */
	@Nonnull
	@Override
	public Container or(@Nonnull final ArrayContainer x) {
		// we guess that, often, the result will still be efficiently expressed as a run container
		return lazyor(x).repairAfterLazy();
	}

	/**
	 * Union with a bitmap container: clones `x` and sets each run's range, returning a full container
	 * if the result covers the whole domain.
	 */
	@Nonnull
	@Override
	public Container or(@Nonnull final BitmapContainer x) {
		if (isFull()) {
			return full();
		}
		// could be implemented as return toTemporaryBitmap().ior(x);
		BitmapContainer answer = x.clone();
		for (int rlepos = 0; rlepos < this.nbrruns; ++rlepos) {
			int start = (this.getValue(rlepos));
			int end = start + (this.getLength(rlepos)) + 1;
			int prevOnesInRange = answer.cardinalityInRange(start, end);
			Util.setBitmapRange(answer.bitmap, start, end);
			answer.updateCardinality(prevOnesInRange, end - start);
		}
		if (answer.isFull()) {
			return full();
		}
		return answer;
	}

	/**
	 * Union of two run containers via a single `smartAppend` merge of the run lists, then downgraded
	 * to the most compact form. Short-circuits to a full container if either side is full. Runs in
	 * `O(nbrruns + x.nbrruns)`.
	 */
	@Nonnull
	@Override
	public Container or(@Nonnull final RunContainer x) {
		if (isFull()) {
			return full();
		}
		if (x.isFull()) {
			return full(); // cheap case that can save a lot of computation
		}
		// we really ought to optimize the rest of the code for the frequent case where there is a
		// single run
		RunContainer answer = new RunContainer(new char[2 * (this.nbrruns + x.nbrruns)], 0);
		int rlepos = 0;
		int xrlepos = 0;

		while ((xrlepos < x.nbrruns) && (rlepos < this.nbrruns)) {
			if (getValue(rlepos) - x.getValue(xrlepos) <= 0) {
				answer.smartAppend(getValue(rlepos), getLength(rlepos));
				rlepos++;
			} else {
				answer.smartAppend(x.getValue(xrlepos), x.getLength(xrlepos));
				xrlepos++;
			}
		}
		while (xrlepos < x.nbrruns) {
			answer.smartAppend(x.getValue(xrlepos), x.getLength(xrlepos));
			xrlepos++;
		}
		while (rlepos < this.nbrruns) {
			answer.smartAppend(getValue(rlepos), getLength(rlepos));
			rlepos++;
		}
		if (answer.isFull()) {
			return full();
		}
		return answer.toEfficientContainer();
	}

	/**
	 * Extends the run at `index` downward to begin at `value`, lengthening it accordingly.
	 */
	private void prependValueLength(final int value, final int index) {
		int initialValue = (getValue(index));
		int length = (getLength(index));
		setValue(index, (char) value);
		setLength(index, (char) (initialValue - value + length));
	}

	/**
	 * Number of present values less than or equal to `lowbits` (its 1-based rank). Scans runs in
	 * order, in `O(nbrruns)`.
	 */
	@Override
	public int rank(final char lowbits) {
		int answer = 0;
		for (int k = 0; k < this.nbrruns; ++k) {
			int value = (getValue(k));
			int length = (getLength(k));
			if ((int) (lowbits) < value) {
				return answer;
			} else if (value + length + 1 > (int) (lowbits)) {
				return answer + (int) (lowbits) - value + 1;
			}
			answer += length + 1;
		}
		return answer;
	}

	/**
	 * `Externalizable` hook that reads the container via {@link #deserialize(DataInput)}.
	 */
	@Override
	public void readExternal(final ObjectInput in) throws IOException {
		deserialize(in);
	}

	/**
	 * Deletes the single run at `index`, shifting later runs down to close the gap.
	 */
	private void recoverRoomAtIndex(final int index) {
		copyValuesLength(this.valueslength, index + 1, this.valueslength, index, this.nbrruns - index - 1);
		this.nbrruns--;
	}

	/**
	 * Deletes runs `begin+1 .. end` (begin exclusive, end inclusive), shifting the tail down.
	 */
	private void recoverRoomsInRange(final int begin, final int end) {
		if (end + 1 < this.nbrruns) {
			copyValuesLength(
				this.valueslength, end + 1, this.valueslength, begin + 1, this.nbrruns - 1 - end);
		}
		this.nbrruns -= end - begin;
	}

	/**
	 * Returns a clone with `[begin, end)` removed; leaves this container untouched.
	 */
	@Nonnull
	@Override
	public Container remove(final int begin, final int end) {
		RunContainer rc = (RunContainer) clone();
		return rc.iremove(begin, end);
	}

	/**
	 * Removes a single value in place, splitting the enclosing run into two when the value is interior.
	 * Locates the run in `O(log nbrruns)`; an interior removal shifts up to `nbrruns` pairs. No-op if
	 * `x` is absent.
	 *
	 * @param x value to remove
	 * @return this container (mutated)
	 */
	@Nonnull
	@Override
	public Container remove(final char x) {
		int index = unsignedInterleavedBinarySearch(this.valueslength, 0, this.nbrruns, x);
		if (index >= 0) {
			if (getLength(index) == 0) {
				recoverRoomAtIndex(index);
			} else {
				incrementValue(index);
				decrementLength(index);
			}
			return this; // already there
		}
		index = -index - 2; // points to preceding value, possibly -1
		if (index >= 0) { // possible match
			int offset = (x) - (getValue(index));
			int le = (getLength(index));
			if (offset < le) {
				// need to break in two
				this.setLength(index, (char) (offset - 1));
				// need to insert
				int newvalue = (x) + 1;
				int newlength = le - offset - 1;
				makeRoomAtIndex(index + 1);
				this.setValue(index + 1, (char) newvalue);
				this.setLength(index + 1, (char) newlength);
				return this;

			} else if (offset == le) {
				decrementLength(index);
			}
		}
		// no match
		return this;
	}

	/**
	 * Restores the canonical container type after a lazy op by compacting to the smallest form.
	 */
	@Nonnull
	@Override
	public Container repairAfterLazy() {
		return toEfficientContainer();
	}

	/**
	 * Convert to Array or Bitmap container if the serialized form would be shorter. Exactly the same
	 * functionality as toEfficientContainer.
	 */
	@Nonnull
	@Override
	public Container runOptimize() {
		return toEfficientContainer();
	}

	/**
	 * Returns the value at ascending rank `j` (0-based), accumulating run lengths until `j` is
	 * reached. Runs in `O(nbrruns)`.
	 *
	 * @param j zero-based rank of the value to select
	 * @return the value at that rank
	 * @throws IllegalArgumentException if `j` is out of range for the cardinality
	 */
	@Override
	public char select(final int j) {
		int offset = 0;
		for (int k = 0; k < this.nbrruns; ++k) {
			int nextOffset = offset + (getLength(k)) + 1;
			if (nextOffset > j) {
				return (char) (getValue(k) + (j - offset));
			}
			offset = nextOffset;
		}
		throw new IllegalArgumentException(
			"Cannot select " + j + " since cardinality is " + getCardinality());
	}

	/**
	 * Serializes via {@link #writeArray(DataOutput)} in the standard Roaring wire format.
	 */
	@Override
	public void serialize(@Nonnull final DataOutput out) throws IOException {
		writeArray(out);
	}

	/**
	 * Serialized size in bytes for the current run count.
	 */
	@Override
	public int serializedSizeInBytes() {
		return serializedSizeInBytes(this.nbrruns);
	}

	private void setLength(final int index, final char v) {
		setLength(this.valueslength, index, v);
	}

	private static void setLength(@Nonnull final char[] valueslength, final int index, final char v) {
		valueslength[2 * index + 1] = v;
	}

	private void setValue(final int index, final char v) {
		setValue(this.valueslength, index, v);
	}

	private static void setValue(@Nonnull final char[] valueslength, final int index, final char v) {
		valueslength[2 * index] = v;
	}

	/**
	 * Galloping (exponential-then-binary) search that returns the first run at or after `pos` whose
	 * end exceeds `targetToExceed`, or `skippingOn.nbrruns` if none does. Always advances at least one
	 * run; runs in `O(log d)` where `d` is the distance skipped. Only reached when
	 * `ENABLE_GALLOPING_AND` is set, since on real data it is a minor net loss over a linear advance.
	 *
	 * @param skippingOn     the run container being scanned
	 * @param pos            current run index to start skipping from
	 * @param targetToExceed run end must be strictly greater than this value
	 * @return index of the first qualifying run, or the run count if none qualifies
	 */
	private static int skipAhead(@Nonnull final RunContainer skippingOn, final int pos, final int targetToExceed) {
		int left = pos;
		int span = 1;
		int probePos;
		int end;
		// jump ahead to find a spot where end > targetToExceed (if it exists)
		do {
			probePos = left + span;
			if (probePos >= skippingOn.nbrruns - 1) {
				// expect it might be quite common to find the container cannot be advanced as far as
				// requested. Optimize for it.
				probePos = skippingOn.nbrruns - 1;
				end = (skippingOn.getValue(probePos)) + (skippingOn.getLength(probePos)) + 1;
				if (end <= targetToExceed) {
					return skippingOn.nbrruns;
				}
			}
			end = (skippingOn.getValue(probePos)) + (skippingOn.getLength(probePos)) + 1;
			span *= 2;
		} while (end <= targetToExceed);
		int right = probePos;
		// left and right are both valid positions. Invariant: left <= targetToExceed && right >
		// targetToExceed
		// do a binary search to discover the spot where left and right are separated by 1, and
		// invariant is maintained.
		while (right - left > 1) {
			int mid = (right + left) / 2;
			int midVal = (skippingOn.getValue(mid)) + (skippingOn.getLength(mid)) + 1;
			if (midVal > targetToExceed) {
				right = mid;
			} else {
				left = mid;
			}
		}
		return right;
	}

	/**
	 * Appends a single value at the tail during an ascending merge (union semantics): extends the last
	 * run if `val` abuts or lies within it, otherwise opens a new run. Assumes `val` is not below the
	 * last run and that capacity already exists.
	 */
	private void smartAppend(final char val) {
		int oldend;
		if ((this.nbrruns == 0)
			|| (val
			> (oldend = (this.valueslength[2 * (this.nbrruns - 1)]) + (this.valueslength[2 * (this.nbrruns - 1) + 1]))
			+ 1)) { // we add a new one
			this.valueslength[2 * this.nbrruns] = val;
			this.valueslength[2 * this.nbrruns + 1] = 0;
			this.nbrruns++;
			return;
		}
		if (val == (char) (oldend + 1)) { // we merge
			this.valueslength[2 * (this.nbrruns - 1) + 1]++;
		}
	}

	/**
	 * Appends a whole run at the tail during an ascending merge (union semantics): merges into the
	 * last run if it abuts or overlaps, otherwise opens a new run (growing the array if needed).
	 *
	 * @param start  run start value
	 * @param length run length minus one
	 */
	void smartAppend(final char start, final char length) {
		int oldend;
		if ((this.nbrruns == 0)
			|| ((start)
			> (oldend = (getValue(this.nbrruns - 1)) + (getLength(this.nbrruns - 1)))
			+ 1)) { // we add a new one
			ensureCapacity(0, this.nbrruns + 1);
			this.valueslength[2 * this.nbrruns] = start;
			this.valueslength[2 * this.nbrruns + 1] = length;
			this.nbrruns++;
			return;
		}
		int newend = (start) + length + 1;
		if (newend > oldend) { // we merge
			setLength(this.nbrruns - 1, (char) (newend - 1 - (getValue(this.nbrruns - 1))));
		}
	}

	/**
	 * Appends a single value at the tail with symmetric-difference (XOR) semantics: a value abutting
	 * the last run extends it, a value already inside it cancels out (splitting or shrinking the run).
	 * Used by the `not`/`xor` merges. Assumes ascending order.
	 */
	private void smartAppendExclusive(final char val) {
		int oldend;
		if ((this.nbrruns == 0)
			|| (val
			> (oldend = getValue(this.nbrruns - 1) + getLength(this.nbrruns - 1) + 1))) { // we add a new one
			this.valueslength[2 * this.nbrruns] = val;
			this.valueslength[2 * this.nbrruns + 1] = 0;
			this.nbrruns++;
			return;
		}
		// We have that val <= oldend.
		if (oldend == val) {
			// we merge
			this.valueslength[2 * (this.nbrruns - 1) + 1]++;
			return;
		}
		// We have that val < oldend.

		int newend = val + 1;
		// We have that newend = val + 1 and val < oldend.
		// so newend <= oldend.

		if (val == getValue(this.nbrruns - 1)) {
			// we wipe out previous
			if (newend != oldend) {
				setValue(this.nbrruns - 1, (char) newend);
				setLength(this.nbrruns - 1, (char) (oldend - newend - 1));
				return;
			} else { // they cancel out
				this.nbrruns--;
				return;
			}
		}
		setLength(this.nbrruns - 1, (char) (val - getValue(this.nbrruns - 1) - 1));
		if (newend < oldend) {
			setValue(this.nbrruns, (char) newend);
			setLength(this.nbrruns, (char) (oldend - newend - 1));
			this.nbrruns++;
		} // otherwise newend == oldend
	}

	/**
	 * Appends a whole run at the tail with symmetric-difference (XOR) semantics: the overlapping part
	 * with the last run cancels while the non-overlapping part survives, possibly splitting or merging
	 * runs. Used by the `not`/`xor` merges. Assumes ascending order.
	 *
	 * @param start  run start value
	 * @param length run length minus one
	 */
	private void smartAppendExclusive(final char start, final char length) {
		int oldend;
		if ((this.nbrruns == 0)
			|| (start
			> (oldend =
			(getValue(this.nbrruns - 1)) + (getLength(this.nbrruns - 1)) + 1))) { // we add a new one
			this.valueslength[2 * this.nbrruns] = start;
			this.valueslength[2 * this.nbrruns + 1] = length;
			this.nbrruns++;
			return;
		}
		if (oldend == start) {
			// we merge; the run length provably fits in a char, so the compound += narrows safely
			this.valueslength[2 * (this.nbrruns - 1) + 1] += length + 1;
			return;
		}

		int newend = start + length + 1;

		if (start == (getValue(this.nbrruns - 1))) {
			// we wipe out previous
			if (newend < oldend) {
				setValue(this.nbrruns - 1, (char) newend);
				setLength(this.nbrruns - 1, (char) (oldend - newend - 1));
				return;
			} else if (newend > oldend) {
				setValue(this.nbrruns - 1, (char) oldend);
				setLength(this.nbrruns - 1, (char) (newend - oldend - 1));
				return;
			} else { // they cancel out
				this.nbrruns--;
				return;
			}
		}
		setLength(this.nbrruns - 1, (char) (start - (getValue(this.nbrruns - 1)) - 1));
		if (newend < oldend) {
			setValue(this.nbrruns, (char) newend);
			setLength(this.nbrruns, (char) (oldend - newend - 1));
			this.nbrruns++;
		} else if (newend > oldend) {
			setValue(this.nbrruns, (char) oldend);
			setLength(this.nbrruns, (char) (newend - oldend - 1));
			this.nbrruns++;
		}
	}

	/**
	 * Convert the container to either a Bitmap or an Array Container, depending on the cardinality.
	 *
	 * @param card the current cardinality
	 * @return new container
	 */
	@Nonnull
	Container toBitmapOrArrayContainer(final int card) {
		// int card = this.getCardinality();
		if (card <= ArrayContainer.DEFAULT_MAX_SIZE) {
			ArrayContainer answer = new ArrayContainer(card);
			answer.cardinality = 0;
			for (int rlepos = 0; rlepos < this.nbrruns; ++rlepos) {
				int runStart = (this.getValue(rlepos));
				int runEnd = runStart + (this.getLength(rlepos));

				for (int runValue = runStart; runValue <= runEnd; ++runValue) {
					answer.content[answer.cardinality++] = (char) runValue;
				}
			}
			return answer;
		}
		BitmapContainer answer = new BitmapContainer();
		for (int rlepos = 0; rlepos < this.nbrruns; ++rlepos) {
			int start = (this.getValue(rlepos));
			int end = start + (this.getLength(rlepos)) + 1;
			Util.setBitmapRange(answer.bitmap, start, end);
		}
		answer.cardinality = card;
		return answer;
	}

	/**
	 * Returns the cheapest representation of the current contents: keeps `this` when the run form is
	 * no larger (serialized) than an equivalent array or bitmap, otherwise converts. Central compaction
	 * step behind `runOptimize`, `repairAfterLazy` and the set operations.
	 */
	@Nonnull
	private Container toEfficientContainer() {
		int sizeAsRunContainer = RunContainer.serializedSizeInBytes(this.nbrruns);
		int sizeAsBitmapContainer = BitmapContainer.serializedSizeInBytes(0);
		int card = this.getCardinality();
		int sizeAsArrayContainer = ArrayContainer.serializedSizeInBytes(card);
		if (sizeAsRunContainer <= Math.min(sizeAsBitmapContainer, sizeAsArrayContainer)) {
			return this;
		}
		return toBitmapOrArrayContainer(card);
	}

	/**
	 * Renders the runs as a sequence of inclusive `[start,end]` intervals, e.g. `[1,3][7,7]`.
	 */
	@Nonnull
	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder("[]".length() + "-123456789,".length() * this.nbrruns);
		for (int k = 0; k < this.nbrruns; ++k) {
			sb.append('[');
			sb.append((int) (this.getValue(k)));
			sb.append(',');
			sb.append((this.getValue(k)) + (this.getLength(k)));
			sb.append(']');
		}
		return sb.toString();
	}

	/**
	 * Shrinks the backing array to exactly `2 * nbrruns` entries, releasing any scratch capacity.
	 */
	@Override
	public void trim() {
		if (this.valueslength.length == 2 * this.nbrruns) {
			return;
		}
		this.valueslength = Arrays.copyOf(this.valueslength, 2 * this.nbrruns);
	}

	/**
	 * Whether the run at `index` covers `value`.
	 */
	private boolean valueLengthContains(final int value, final int index) {
		int initialValue = (getValue(index));
		int length = (getLength(index));

		return value <= initialValue + length;
	}

	/**
	 * Writes the run count and interleaved run pairs to `out` in little-endian order.
	 */
	@Override
	public void writeArray(@Nonnull final DataOutput out) throws IOException {
		out.writeShort(Character.reverseBytes((char) this.nbrruns));
		for (int k = 0; k < 2 * this.nbrruns; ++k) {
			out.writeShort(Character.reverseBytes(this.valueslength[k]));
		}
	}

	/**
	 * Writes the run count and run pairs into a little-endian `ByteBuffer`, advancing its position.
	 */
	@Override
	public void writeArray(@Nonnull final ByteBuffer buffer) {
		assert buffer.order() == ByteOrder.LITTLE_ENDIAN;
		CharBuffer buf = buffer.asCharBuffer();
		buf.put((char) this.nbrruns);
		buf.put(this.valueslength, 0, this.nbrruns * 2);
		int bytesWritten = (this.nbrruns * 2 + 1) * 2;
		buffer.position(buffer.position() + bytesWritten);
	}

	/**
	 * `Externalizable` hook that writes the container via {@link #serialize(DataOutput)}.
	 */
	@Override
	public void writeExternal(final ObjectOutput out) throws IOException {
		serialize(out);
	}

	/**
	 * Symmetric difference with an array container. When `x` is small the result is guessed to stay a
	 * run container (built lazily, then repaired); otherwise it is materialised as an array or bitmap
	 * depending on this container's cardinality.
	 */
	@Nonnull
	@Override
	public Container xor(@Nonnull final ArrayContainer x) {
		// if the cardinality of the array is small, guess that the output will still be a run container
		final int arbitrary_threshold = 32; // 32 is arbitrary here
		if (x.getCardinality() < arbitrary_threshold) {
			return lazyxor(x).repairAfterLazy();
		}
		// otherwise, we expect the output to be either an array or bitmap
		final int card = getCardinality();
		if (card <= ArrayContainer.DEFAULT_MAX_SIZE) {
			// if the cardinality is small, we construct the solution in place
			return x.xor(this.getCharIterator());
		}
		// otherwise, we generate a bitmap (even if runcontainer would be better)
		return toBitmapOrArrayContainer(card).ixor(x);
	}

	/**
	 * Symmetric difference with a bitmap container: clones `x` and flips each run's range, downgrading
	 * to an array afterwards if the result becomes small enough.
	 */
	@Nonnull
	@Override
	public Container xor(@Nonnull final BitmapContainer x) {
		// could be implemented as return toTemporaryBitmap().ixor(x);
		BitmapContainer answer = x.clone();
		for (int rlepos = 0; rlepos < this.nbrruns; ++rlepos) {
			int start = (this.getValue(rlepos));
			int end = start + (this.getLength(rlepos)) + 1;
			int prevOnes = answer.cardinalityInRange(start, end);
			Util.flipBitmapRange(answer.bitmap, start, end);
			answer.updateCardinality(prevOnes, end - start - prevOnes);
		}
		if (answer.getCardinality() > ArrayContainer.DEFAULT_MAX_SIZE) {
			return answer;
		} else {
			return answer.toArrayContainer();
		}
	}

	/**
	 * Symmetric difference of two run containers via a single `smartAppendExclusive` merge of the run
	 * lists, then downgraded to the most compact form. Runs in `O(nbrruns + x.nbrruns)`.
	 */
	@Nonnull
	@Override
	public Container xor(@Nonnull final RunContainer x) {
		if (x.nbrruns == 0) {
			return this.clone();
		}
		if (this.nbrruns == 0) {
			return x.clone();
		}
		RunContainer answer = new RunContainer(new char[2 * (this.nbrruns + x.nbrruns)], 0);
		int rlepos = 0;
		int xrlepos = 0;

		while (true) {
			if (getValue(rlepos) < x.getValue(xrlepos)) {
				answer.smartAppendExclusive(getValue(rlepos), getLength(rlepos));
				rlepos++;

				if (rlepos == this.nbrruns) {
					while (xrlepos < x.nbrruns) {
						answer.smartAppendExclusive(x.getValue(xrlepos), x.getLength(xrlepos));
						xrlepos++;
					}
					break;
				}
			} else {
				answer.smartAppendExclusive(x.getValue(xrlepos), x.getLength(xrlepos));

				xrlepos++;
				if (xrlepos == x.nbrruns) {
					while (rlepos < this.nbrruns) {
						answer.smartAppendExclusive(getValue(rlepos), getLength(rlepos));
						rlepos++;
					}
					break;
				}
			}
		}
		return answer.toEfficientContainer();
	}

	/**
	 * Feeds every value (32-bit, with `msb` as the high half) to `ic` in ascending order.
	 */
	@Override
	public void forEach(final char msb, @Nonnull final IntConsumer ic) {
		int high = msb << 16;
		for (int k = 0; k < this.nbrruns; ++k) {
			int base = this.getValue(k) | high;
			int le = this.getLength(k);
			for (int l = base; l - le <= base; ++l) {
				ic.accept(l);
			}
		}
	}

	/**
	 * Reports the whole `0 .. 65535` domain to `rrc` as alternating present (run) and absent (gap)
	 * ranges, with positions shifted by `offset`. Runs in `O(nbrruns)`.
	 */
	@Override
	public void forAll(final int offset, @Nonnull final RelativeRangeConsumer rrc) {
		int next = 0;
		for (int run = 0; run < this.nbrruns; run++) {
			int runPos = run << 1;
			char runStart = this.valueslength[runPos];
			char runLength = this.valueslength[runPos + 1];
			if (next < runStart) {
				// fill in missing values until runStart
				rrc.acceptAllAbsent(offset + next, offset + runStart);
			}
			rrc.acceptAllPresent(offset + runStart, offset + runStart + runLength + 1);
			next = runStart + runLength + 1;
		}
		if (next <= Character.MAX_VALUE) {
			// fill in the remaining values until end
			rrc.acceptAllAbsent(offset + next, offset + Character.MAX_VALUE + 1);
		}
	}

	/**
	 * Reports the domain from `startValue` (inclusive) to the end as alternating present/absent ranges
	 * relative to `startValue`, skipping runs that end before it.
	 */
	@Override
	public void forAllFrom(final char startValue, @Nonnull final RelativeRangeConsumer rrc) {
		int next = startValue;
		for (int run = 0; run < this.nbrruns; run++) {
			int runPos = run << 1;
			char runStart = this.valueslength[runPos];
			char runLength = this.valueslength[runPos + 1];
			int runEnd = runStart + runLength;
			if (runEnd < startValue) {
				// skip forward
				continue;
			}

			if (runStart < next) { // next == startValue
				assert next == startValue;
				// start is somewhere within the run
				rrc.acceptAllPresent(0, runStart + runLength + 1 - startValue);
			} else {
				// start is before the run
				if (next < runStart) {
					// fill in missing values until runStart
					rrc.acceptAllAbsent(next - startValue, runStart - startValue);
				}
				// take whole run
				rrc.acceptAllPresent(runStart - startValue, runStart + runLength + 1 - startValue);
			}
			next = runStart + runLength + 1;
		}
		if (next <= Character.MAX_VALUE) {
			// fill in the remaining values until end
			rrc.acceptAllAbsent(next - startValue, Character.MAX_VALUE + 1 - startValue);
		}
	}

	/**
	 * Reports the domain from `0` up to `endValue` (exclusive) as alternating present/absent ranges
	 * shifted by `offset`, stopping once `endValue` is reached.
	 */
	@Override
	public void forAllUntil(final int offset, final char endValue, @Nonnull final RelativeRangeConsumer rrc) {
		int next = 0;
		for (int run = 0; run < this.nbrruns; run++) {
			int runPos = run << 1;
			char runStart = this.valueslength[runPos];
			char runLength = this.valueslength[runPos + 1];
			if (endValue <= runStart) {
				// no more relevant values in this run or the following
				break;
			}
			if (next < runStart) {
				// fill in missing values until runStart
				rrc.acceptAllAbsent(offset + next, offset + runStart);
			}
			char runEnd = (char) (runStart + runLength);
			// endValue is exclusive, but runEnd is inclusive.
			if (endValue <= runEnd) {
				// we end within this run
				rrc.acceptAllPresent(offset + runStart, offset + endValue);
				return;
			}
			rrc.acceptAllPresent(offset + runStart, offset + runEnd + 1); // runEnd is inclusive
			next = runEnd + 1;
		}
		if (next < endValue) {
			// fill in the remaining values until end
			rrc.acceptAllAbsent(offset + next, offset + endValue);
		}
	}

	/**
	 * Reports the sub-domain `[startValue, endValue)` as alternating present/absent ranges relative to
	 * `startValue`.
	 *
	 * @throws IllegalArgumentException if `endValue <= startValue`
	 */
	@Override
	public void forAllInRange(final char startValue, final char endValue, @Nonnull final RelativeRangeConsumer rrc) {
		if (endValue <= startValue) {
			throw new IllegalArgumentException(
				"startValue (" + startValue + ") must be less than endValue (" + endValue + ")");
		}
		int next = startValue;
		for (int run = 0; run < this.nbrruns; run++) {
			int runPos = run << 1;
			char runStart = this.valueslength[runPos];
			char runLength = this.valueslength[runPos + 1];
			int runEnd = runStart + runLength;
			if (runEnd < startValue) {
				// skip forward
				continue;
			}
			if (endValue <= runStart) {
				// no more relevant values in this run or the following
				break;
			}
			if (runStart < next) { // next == startValue
				// start is somewhere within the run
				if (endValue <= runEnd) {
					// we also end within this run
					rrc.acceptAllPresent(0, endValue - startValue);
					return;
				}
				rrc.acceptAllPresent(0, runEnd + 1 - startValue);
			} else {
				// start is before the run
				if (next < runStart) {
					// fill in missing values until runStart
					rrc.acceptAllAbsent(next - startValue, runStart - startValue);
				}
				if (endValue <= runEnd) {
					// we end within this run
					rrc.acceptAllPresent(runStart - startValue, endValue - startValue);
					return;
				}
				// take whole run
				rrc.acceptAllPresent(runStart - startValue, runStart + runLength + 1 - startValue);
			}
			next = runStart + runLength + 1;
		}
		if (next < endValue) {
			// fill in the remaining values until end
			rrc.acceptAllAbsent(next - startValue, endValue - startValue);
		}
	}

	/**
	 * Materialises the runs into a new {@link BitmapContainer}, even when that is not smaller.
	 */
	@Nonnull
	@Override
	public BitmapContainer toBitmapContainer() {
		int card = 0;
		BitmapContainer answer = new BitmapContainer();
		for (int rlepos = 0; rlepos < this.nbrruns; ++rlepos) {
			int start = (this.getValue(rlepos));
			int end = start + (this.getLength(rlepos)) + 1;
			card += end - start;
			Util.setBitmapRange(answer.bitmap, start, end);
		}
		assert card == this.getCardinality();
		answer.cardinality = card;
		return answer;
	}

	/**
	 * Sets this container's values as bits in `dest`, offset by `position` 64-bit words.
	 */
	@Override
	public void copyBitmapTo(@Nonnull final long[] dest, final int position) {
		int offset = position * Long.SIZE;
		for (int rlepos = 0; rlepos < this.nbrruns; ++rlepos) {
			int start = offset + this.getValue(rlepos);
			int end = start + this.getLength(rlepos) + 1;
			Util.setBitmapRange(dest, start, end);
		}
	}

	/**
	 * Smallest present value `>= fromValue`, or `-1` if none; binary search in `O(log nbrruns)`.
	 */
	@Override
	public int nextValue(final char fromValue) {
		int index = unsignedInterleavedBinarySearch(this.valueslength, 0, this.nbrruns, fromValue);
		int effectiveIndex = index >= 0 ? index : -index - 2;
		if (effectiveIndex == -1) {
			return first();
		}
		int startValue = (getValue(effectiveIndex));
		int offset = (int) (fromValue) - startValue;
		int le = (getLength(effectiveIndex));
		if (offset <= le) {
			return fromValue;
		}
		if (effectiveIndex + 1 < numberOfRuns()) {
			return (getValue(effectiveIndex + 1));
		}
		return -1;
	}

	/**
	 * Largest present value `<= fromValue`, or `-1` if none; binary search in `O(log nbrruns)`.
	 */
	@Override
	public int previousValue(final char fromValue) {
		int index = unsignedInterleavedBinarySearch(this.valueslength, 0, this.nbrruns, fromValue);
		int effectiveIndex = index >= 0 ? index : -index - 2;
		if (effectiveIndex == -1) {
			return -1;
		}
		int startValue = (getValue(effectiveIndex));
		int offset = (int) (fromValue) - startValue;
		int le = (getLength(effectiveIndex));
		if (offset >= 0 && offset <= le) {
			return fromValue;
		}
		return startValue + le;
	}

	/**
	 * Smallest absent value `>= fromValue` (never `-1`, the domain has gaps); `O(log nbrruns)`.
	 */
	@Override
	public int nextAbsentValue(final char fromValue) {
		int index = unsignedInterleavedBinarySearch(this.valueslength, 0, this.nbrruns, fromValue);
		int effectiveIndex = index >= 0 ? index : -index - 2;
		if (effectiveIndex == -1) {
			return (fromValue);
		}
		int startValue = (getValue(effectiveIndex));
		int offset = (int) (fromValue) - startValue;
		int le = (getLength(effectiveIndex));
		return offset <= le ? startValue + le + 1 : (int) (fromValue);
	}

	/**
	 * Largest absent value `<= fromValue`; `O(log nbrruns)`.
	 */
	@Override
	public int previousAbsentValue(final char fromValue) {
		int index = unsignedInterleavedBinarySearch(this.valueslength, 0, this.nbrruns, fromValue);
		int effectiveIndex = index >= 0 ? index : -index - 2;
		if (effectiveIndex == -1) {
			return (fromValue);
		}
		int startValue = (getValue(effectiveIndex));
		int offset = (int) (fromValue) - startValue;
		int le = (getLength(effectiveIndex));
		return offset <= le ? startValue - 1 : (int) (fromValue);
	}

	/**
	 * Smallest present value (the first run's start), in `O(1)`.
	 */
	@Override
	public int first() {
		assertNonEmpty(numberOfRuns() == 0);
		return (this.valueslength[0]);
	}

	/**
	 * Largest present value (the last run's end), in `O(1)`.
	 */
	@Override
	public int last() {
		assertNonEmpty(numberOfRuns() == 0);
		int index = numberOfRuns() - 1;
		int start = (getValue(index));
		int length = (getLength(index));
		return start + length;
	}
}

/**
 * Ascending value iterator that expands the parent's runs lazily: it walks run by run and, within
 * each run, emits `base .. base + maxlength` one value at a time, so it never materialises the set.
 */
class RunContainerCharIterator implements PeekableCharIterator {
	/**
	 * Index of the run currently being emitted.
	 */
	int pos;

	/**
	 * Offset of the next value inside the current run (`0 .. maxlength`).
	 */
	int le = 0;

	/**
	 * Length-minus-one of the current run (its last emittable offset).
	 */
	int maxlength;

	/**
	 * Start value of the current run.
	 */
	int base;

	/**
	 * Container being iterated; its run array is read but never mutated.
	 */
	RunContainer parent;

	RunContainerCharIterator() {
	}

	RunContainerCharIterator(@Nonnull final RunContainer p) {
		wrap(p);
	}

	@Nonnull
	@Override
	public PeekableCharIterator clone() {
		try {
			return (PeekableCharIterator) super.clone();
		} catch (CloneNotSupportedException e) {
			throw new IllegalStateException(e); // unreachable, this iterator implements Cloneable
		}
	}

	@Override
	public boolean hasNext() {
		return this.pos < this.parent.nbrruns;
	}

	@Override
	public char next() {
		char ans = (char) (this.base + this.le);
		this.le++;
		if (this.le > this.maxlength) {
			this.pos++;
			this.le = 0;
			if (this.pos < this.parent.nbrruns) {
				this.maxlength = (this.parent.getLength(this.pos));
				this.base = (this.parent.getValue(this.pos));
			}
		}
		return ans;
	}

	@Override
	public int nextAsInt() {
		int ans = this.base + this.le;
		this.le++;
		if (this.le > this.maxlength) {
			this.pos++;
			this.le = 0;
			if (this.pos < this.parent.nbrruns) {
				this.maxlength = (this.parent.getLength(this.pos));
				this.base = (this.parent.getValue(this.pos));
			}
		}
		return ans;
	}

	@Override
	public void remove() {
		throw new RuntimeException("Not implemented");
	}

	/**
	 * (Re)binds this iterator to `p` and rewinds it to the first value, enabling instance reuse.
	 */
	void wrap(@Nonnull final RunContainer p) {
		this.parent = p;
		this.pos = 0;
		this.le = 0;
		if (this.pos < this.parent.nbrruns) {
			this.maxlength = (this.parent.getLength(this.pos));
			this.base = (this.parent.getValue(this.pos));
		}
	}

	@Override
	public void advanceIfNeeded(final char minval) {
		while (this.base + this.maxlength < (minval)) {
			this.pos++;
			this.le = 0;
			if (this.pos < this.parent.nbrruns) {
				this.maxlength = (this.parent.getLength(this.pos));
				this.base = (this.parent.getValue(this.pos));
			} else {
				return;
			}
		}
		if (this.base > (minval)) {
			return;
		}
		this.le = (minval) - this.base;
	}

	@Override
	public char peekNext() {
		return (char) (this.base + this.le);
	}
}

/**
 * Ascending iterator that additionally tracks the 1-based rank of the value it is about to return,
 * so callers can obtain position and value in a single pass.
 */
class RunContainerCharRankIterator extends RunContainerCharIterator
	implements PeekableCharRankIterator {

	/**
	 * 1-based rank of the next value to be returned.
	 */
	private int nextRank = 1;

	RunContainerCharRankIterator(@Nonnull final RunContainer p) {
		super(p);
	}

	@Override
	public char next() {
		++this.nextRank;
		return super.next();
	}

	@Override
	public int nextAsInt() {
		++this.nextRank;
		return super.nextAsInt();
	}

	@Override
	public void advanceIfNeeded(final char minval) {
		while (this.base + this.maxlength < (minval)) {
			this.nextRank += this.maxlength - this.le + 1;

			this.pos++;
			this.le = 0;
			if (this.pos < this.parent.nbrruns) {
				this.maxlength = (this.parent.getLength(this.pos));
				this.base = (this.parent.getValue(this.pos));
			} else {
				return;
			}
		}

		if (this.base > (minval)) {
			return;
		}
		int nextLe = (minval) - this.base;

		this.nextRank += nextLe - this.le;
		this.le = nextLe;
	}

	@Override
	public int peekNextRank() {
		return this.nextRank;
	}

	@Nonnull
	@Override
	public RunContainerCharRankIterator clone() {
		return (RunContainerCharRankIterator) super.clone();
	}
}

/**
 * Descending counterpart to {@link RunContainerCharIterator}: walks runs from last to first and,
 * within each run, emits values from the run's end down to its start, expanding runs lazily.
 */
final class ReverseRunContainerCharIterator implements PeekableCharIterator {
	/**
	 * Index of the run currently being emitted (walks downward).
	 */
	int pos;

	/**
	 * Offset from the current run's end of the next value to emit (`0 .. maxlength`).
	 */
	private int le;

	/**
	 * Container being iterated; its run array is read but never mutated.
	 */
	private RunContainer parent;

	/**
	 * Length-minus-one of the current run.
	 */
	private int maxlength;

	/**
	 * Start value of the current run.
	 */
	private int base;

	ReverseRunContainerCharIterator() {
	}

	ReverseRunContainerCharIterator(@Nonnull final RunContainer p) {
		wrap(p);
	}

	@Nonnull
	@Override
	public PeekableCharIterator clone() {
		try {
			return (PeekableCharIterator) super.clone();
		} catch (CloneNotSupportedException e) {
			throw new IllegalStateException(e); // unreachable, this iterator implements Cloneable
		}
	}

	@Override
	public boolean hasNext() {
		return this.pos >= 0;
	}

	@Override
	public char next() {
		char ans = (char) (this.base + this.maxlength - this.le);
		this.le++;
		if (this.le > this.maxlength) {
			this.pos--;
			this.le = 0;
			if (this.pos >= 0) {
				this.maxlength = (this.parent.getLength(this.pos));
				this.base = (this.parent.getValue(this.pos));
			}
		}
		return ans;
	}

	@Override
	public int nextAsInt() {
		int ans = this.base + this.maxlength - this.le;
		this.le++;
		if (this.le > this.maxlength) {
			this.pos--;
			this.le = 0;
			if (this.pos >= 0) {
				this.maxlength = (this.parent.getLength(this.pos));
				this.base = (this.parent.getValue(this.pos));
			}
		}
		return ans;
	}

	@Override
	public void advanceIfNeeded(final char maxval) {
		while (this.base > (maxval)) {
			this.pos--;
			this.le = 0;
			if (this.pos >= 0) {
				this.maxlength = (this.parent.getLength(this.pos));
				this.base = (this.parent.getValue(this.pos));
			} else {
				return;
			}
		}
		if (this.base + this.maxlength < (maxval)) {
			return;
		}
		this.le = this.maxlength + this.base - (maxval);
	}

	@Override
	public char peekNext() {
		return (char) (this.base + this.maxlength - this.le);
	}

	@Override
	public void remove() {
		throw new RuntimeException("Not implemented");
	}

	/**
	 * (Re)binds this iterator to `p` and positions it at the last value, enabling instance reuse.
	 */
	void wrap(@Nonnull final RunContainer p) {
		this.parent = p;
		this.pos = this.parent.nbrruns - 1;
		this.le = 0;
		if (this.pos >= 0) {
			this.maxlength = (this.parent.getLength(this.pos));
			this.base = (this.parent.getValue(this.pos));
		}
	}
}
