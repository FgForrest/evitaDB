package io.evitadb.roaringbitmap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.TreeSet;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Randomized stress test for the two copy-on-write bookkeeping invariants of
 * {@link PersistentRoaringBitmap}.
 *
 * LOCKSTEP — the parallel `shared[]` flag array and `highLowContainer` must keep describing the same
 * slots as the container array grows, shrinks and is rebuilt; historically that is where this class
 * has broken. The array is allowed to be *shorter* than the container array — an all-owned builder
 * appends without touching it and an uncovered slot correctly reads as owned — so what is asserted is
 * that no raised flag survives past the live slots; see {@link #assertLockstep}.
 *
 * ALIASING — whenever two distinct bitmaps hold the very same {@link Container} instance, BOTH must
 * have that slot flagged shared. A single unflagged slot over an aliased container is the precise
 * precondition for silent cross-bitmap corruption: the unflagged owner believes it may mutate in
 * place, and the peer sees the write. Checking the alias graph directly catches the desync at the
 * moment it is created, rather than waiting for a later mutation to expose it.
 *
 * The fuzz drives a pool of bitmaps through the in-place operators (whose bulk-merge rebuild is the
 * riskiest path), the structural mutators, the static/cloning producers and the multi-bitmap folds of
 * {@link FastAggregation}, re-checking both invariants plus every bitmap's contents against a
 * reference model after every single step.
 *
 * The folds belong here for the same reason the binary operators do, and more so: they reach their
 * answer by adopting, borrowing and lazily merging their operands' containers rather than always
 * combining them, so an unflagged alias costs one forgotten flag. A binary operator between two
 * bitmaps cannot produce the shapes they can — a chunk co-owned by two operands of the same fold, an
 * accumulator emptied mid-fold while further inputs are still to come, or a temporary that has
 * absorbed a caller bitmap and then meets another temporary. The random walk would reach those
 * eventually; the two deterministic probes below build them instead, because each depends on an
 * operand count and a size relation too narrow to leave to a draw.
 */
@DisplayName("Copy-on-write shared[] lockstep and aliasing invariants")
public class SharedContainerLockstepFuzzTest {

	/**
	 * A bitmap paired with the reference model of the values it is supposed to hold.
	 */
	private static final class Tracked {
		@SuppressWarnings("checkstyle:VisibilityModifier")
		private final PersistentRoaringBitmap bitmap;
		@SuppressWarnings("checkstyle:VisibilityModifier")
		private final TreeSet<Integer> model;

		private Tracked(final PersistentRoaringBitmap bitmap, final TreeSet<Integer> model) {
			this.bitmap = bitmap;
			this.model = model;
		}
	}

	/**
	 * The set operation a fold's answer must equal, which is all the reference model needs to know
	 * about it.
	 */
	private enum FoldKind {
		UNION, SYMMETRIC_DIFFERENCE, INTERSECTION
	}

	/**
	 * The multi-bitmap folds of {@link FastAggregation}, each paired with the operation its answer has
	 * to equal so the model can be derived from the operands rather than restated per entry.
	 *
	 * These take a different route to their answer than the binary operators do: they adopt, borrow
	 * and lazily merge their operands' containers instead of always combining them, which is exactly
	 * where an unflagged alias is cheap to create. Driving them is therefore the point of this class
	 * rather than an extension of it.
	 */
	private enum Fold {
		NAIVE_OR(FoldKind.UNION, FastAggregation::naive_or),
		PRIORITY_QUEUE_OR(FoldKind.UNION, FastAggregation::priorityqueue_or),
		HORIZONTAL_OR(FoldKind.UNION, FastAggregation::horizontal_or),
		OR(FoldKind.UNION, FastAggregation::or),
		NAIVE_XOR(FoldKind.SYMMETRIC_DIFFERENCE, FastAggregation::naive_xor),
		PRIORITY_QUEUE_XOR(FoldKind.SYMMETRIC_DIFFERENCE, FastAggregation::priorityqueue_xor),
		HORIZONTAL_XOR(FoldKind.SYMMETRIC_DIFFERENCE, FastAggregation::horizontal_xor),
		XOR(FoldKind.SYMMETRIC_DIFFERENCE, FastAggregation::xor),
		AND(FoldKind.INTERSECTION, FastAggregation::and),
		NAIVE_AND(FoldKind.INTERSECTION, FastAggregation::naive_and);

		private final FoldKind kind;
		private final Function<PersistentRoaringBitmap[], PersistentRoaringBitmap> operation;

		Fold(
			final FoldKind kind,
			final Function<PersistentRoaringBitmap[], PersistentRoaringBitmap> operation
		) {
			this.kind = kind;
			this.operation = operation;
		}

		/**
		 * Folds the operands and pairs the answer with the model of what it must hold.
		 *
		 * The operand list may repeat an instance — folding a bitmap together with a union it is
		 * already inside is a shape the folds have to survive — and the model is built by walking the
		 * list in the same order, so a repeat is counted as many times as it appears.
		 *
		 * @param operands the bitmaps to fold, at least two of them
		 * @return the fold's answer, tracked against its reference model
		 */
		@Nonnull
		private Tracked apply(@Nonnull final List<Tracked> operands) {
			final PersistentRoaringBitmap[] bitmaps = new PersistentRoaringBitmap[operands.size()];
			for (int i = 0; i < bitmaps.length; i++) {
				bitmaps[i] = operands.get(i).bitmap;
			}
			final TreeSet<Integer> model = new TreeSet<>(operands.get(0).model);
			for (int i = 1; i < operands.size(); i++) {
				final TreeSet<Integer> operand = operands.get(i).model;
				switch (this.kind) {
					case UNION -> model.addAll(operand);
					case INTERSECTION -> model.retainAll(operand);
					case SYMMETRIC_DIFFERENCE -> {
						for (final Integer value : operand) {
							if (!model.add(value)) {
								model.remove(value);
							}
						}
					}
				}
			}
			return new Tracked(this.operation.apply(bitmaps), model);
		}
	}

	/**
	 * The folds, hoisted so a step does not rebuild the array on every draw.
	 */
	private static final Fold[] FOLDS = Fold.values();

	/**
	 * Chunk cardinalities the equal-size probe sweeps, chosen to land on each container encoding: a
	 * single value, a handful, the shapes the run threshold sits between, and a chunk well past the
	 * array container's ceiling.
	 */
	private static final int[] UNIFORM_CARDINALITIES = {1, 3, 20, 100, 5_000};

	/**
	 * Chunk keys the fuzz draws from — deliberately few and interleavable, so that in-place operators
	 * hit the bulk-merge rebuild rather than the plain tail-append.
	 */
	private static final int[] CHUNK_KEYS = {0, 1, 2, 3, 5, 8, 13, 400};

	/**
	 * Asserts that no raised flag survives past the live slots of the container array.
	 *
	 * `shared[]` deliberately does **not** have to cover the whole container array. An all-owned
	 * builder — `flip`, `add(range)`, `addOffset`, and the horizontal folds of
	 * {@link FastAggregation} — appends through `RoaringArray` without touching the flags, and
	 * {@link PersistentRoaringBitmap#isShared(int)} reads an uncovered slot as owned, which is exactly
	 * what such a builder means. `sharedRemoveAt` states the reasoning: only the three sharing paths
	 * create a co-owned container and each of them grows the array to cover its slot, so a slot past
	 * the end is owned by construction rather than merely unknown. The raw-index hazard is closed
	 * separately — `mergeBulk` and `andNot` grow the array before they write one, and every read
	 * guards the index.
	 *
	 * That the uncovered tail really is owned is what {@link #assertNoUnflaggedAliasing} checks, and
	 * it checks it where it matters: an uncovered slot reads `false`, so the scan fails the moment
	 * another bitmap in the pool is found holding the same container.
	 *
	 * What is left for the array's own length is the opposite direction. A `true` sitting beyond
	 * `size()` is a flag some shrink forgot to clear, and the next append inherits it and declines to
	 * clone a container nobody else holds — a wasted copy at best, and a flag that outlives the
	 * sharing it described at worst.
	 */
	private static void assertLockstep(final PersistentRoaringBitmap bitmap, final String where) {
		for (int i = bitmap.highLowContainer.size(); i < bitmap.shared.length; i++) {
			final int slot = i;
			assertFalse(
				bitmap.shared[i],
				() -> where + ": shared[" + slot + "] is still raised over a slot the container array "
					+ "no longer has, with " + bitmap.highLowContainer.size() + " containers live");
		}
	}

	/**
	 * Asserts that no two bitmaps in the pool alias a container without both flagging it shared.
	 */
	private static void assertNoUnflaggedAliasing(final List<Tracked> pool, final String where) {
		for (int x = 0; x < pool.size(); x++) {
			final PersistentRoaringBitmap left = pool.get(x).bitmap;
			for (int y = x + 1; y < pool.size(); y++) {
				final PersistentRoaringBitmap right = pool.get(y).bitmap;
				for (int i = 0; i < left.highLowContainer.size(); i++) {
					final Container container = left.getContainerAtIndex(i);
					for (int j = 0; j < right.highLowContainer.size(); j++) {
						if (right.getContainerAtIndex(j) == container) {
							assertTrue(
								left.isShared(i) && right.isShared(j),
								where + ": bitmap " + x + " slot " + i + " and bitmap " + y
									+ " slot " + j + " alias one container but are flagged "
									+ left.isShared(i) + "/" + right.isShared(j));
						}
					}
				}
			}
		}
	}

	/**
	 * Asserts every tracked bitmap still holds exactly the values its model records.
	 */
	private static void assertContents(final List<Tracked> pool, final String where) {
		for (int index = 0; index < pool.size(); index++) {
			final Tracked tracked = pool.get(index);
			final int[] expected = new int[tracked.model.size()];
			int cursor = 0;
			for (final Integer value : tracked.model) {
				expected[cursor++] = value;
			}
			final int position = index;
			assertArrayEquals(
				expected, tracked.bitmap.toArray(), where + ": bitmap " + position + " content drifted");
		}
	}

	/**
	 * Runs all invariant checks after a step.
	 */
	private static void assertAllInvariants(final List<Tracked> pool, final String where) {
		for (final Tracked tracked : pool) {
			assertLockstep(tracked.bitmap, where);
		}
		assertNoUnflaggedAliasing(pool, where);
		assertContents(pool, where);
	}

	/**
	 * Draws a random value inside one of the {@link #CHUNK_KEYS} chunks.
	 */
	private static int randomValue(final Random random) {
		final int key = CHUNK_KEYS[random.nextInt(CHUNK_KEYS.length)];
		return (key << 16) + random.nextInt(1 << 16);
	}

	/**
	 * Builds a random bitmap together with its model. Container shapes vary: sparse chunks stay array
	 * chunks, dense ones become bitmap chunks, and one chunk is written as a full run.
	 */
	private static Tracked randomBitmap(final Random random) {
		final PersistentRoaringBitmap bitmap = new PersistentRoaringBitmap();
		final TreeSet<Integer> model = new TreeSet<>();
		final int chunks = 1 + random.nextInt(5);
		for (int chunk = 0; chunk < chunks; chunk++) {
			final int key = CHUNK_KEYS[random.nextInt(CHUNK_KEYS.length)];
			final int shape = random.nextInt(3);
			if (shape == 0) {
				final int count = 1 + random.nextInt(8);
				for (int i = 0; i < count; i++) {
					final int value = (key << 16) + random.nextInt(1 << 16);
					bitmap.add(value);
					model.add(value);
				}
			} else if (shape == 1) {
				final int start = random.nextInt(1 << 15);
				final int count = ArrayContainer.DEFAULT_MAX_SIZE + 1 + random.nextInt(64);
				for (int i = 0; i < count; i++) {
					final int value = (key << 16) + start + i;
					bitmap.add(value);
					model.add(value);
				}
			} else {
				final int start = random.nextInt(1 << 15);
				final int length = 1 + random.nextInt(4096);
				for (int i = 0; i < length; i++) {
					model.add((key << 16) + start + i);
				}
				bitmap.add((long) (key << 16) + start, (long) (key << 16) + start + length);
			}
		}
		if (random.nextBoolean()) {
			bitmap.runOptimize();
		}
		return new Tracked(bitmap, model);
	}

	/**
	 * Applies one random operation to the pool, mutating both the bitmaps and their models in step.
	 */
	private static void step(final Random random, final List<Tracked> pool) {
		final Tracked target = pool.get(random.nextInt(pool.size()));
		final Tracked other = pool.get(random.nextInt(pool.size()));
		switch (random.nextInt(18)) {
			case 0: {
				if (target == other) {
					break;
				}
				target.bitmap.or(other.bitmap);
				target.model.addAll(other.model);
				break;
			}
			case 1: {
				if (target == other) {
					break;
				}
				final TreeSet<Integer> symmetric = new TreeSet<>(target.model);
				for (final Integer value : other.model) {
					if (!symmetric.add(value)) {
						symmetric.remove(value);
					}
				}
				target.bitmap.xor(other.bitmap);
				target.model.clear();
				target.model.addAll(symmetric);
				break;
			}
			case 2: {
				if (target == other) {
					break;
				}
				target.bitmap.and(other.bitmap);
				target.model.retainAll(other.model);
				break;
			}
			case 3: {
				if (target == other) {
					break;
				}
				target.bitmap.andNot(other.bitmap);
				target.model.removeAll(other.model);
				break;
			}
			case 4: {
				if (target == other) {
					break;
				}
				target.bitmap.naivelazyor(other.bitmap);
				target.bitmap.repairAfterLazy();
				target.model.addAll(other.model);
				break;
			}
			case 5: {
				if (target == other) {
					break;
				}
				target.bitmap.lazyor(other.bitmap);
				target.bitmap.repairAfterLazy();
				target.model.addAll(other.model);
				break;
			}
			case 6: {
				final int value = randomValue(random);
				target.bitmap.add(value);
				target.model.add(value);
				break;
			}
			case 7: {
				final int value = target.model.isEmpty() || random.nextBoolean()
					? randomValue(random)
					: target.model.first();
				target.bitmap.remove(value);
				target.model.remove(value);
				break;
			}
			case 8: {
				final int value = randomValue(random);
				target.bitmap.flip(value);
				if (!target.model.remove(value)) {
					target.model.add(value);
				}
				break;
			}
			case 9: {
				final long start = randomValue(random) & 0xFFFFFFFFL;
				final long end = Math.min(start + 1 + random.nextInt(1 << 17), 1L << 32);
				target.bitmap.remove(start, end);
				final Iterator<Integer> iterator = target.model.iterator();
				while (iterator.hasNext()) {
					final long value = iterator.next() & 0xFFFFFFFFL;
					if (value >= start && value < end) {
						iterator.remove();
					}
				}
				break;
			}
			case 10: {
				final long start = randomValue(random) & 0xFFFFFFFFL;
				final long end = Math.min(start + 1 + random.nextInt(1 << 13), 1L << 32);
				target.bitmap.add(start, end);
				for (long value = start; value < end; value++) {
					target.model.add((int) value);
				}
				break;
			}
			case 11: {
				pool.add(new Tracked(target.bitmap.clone(), new TreeSet<>(target.model)));
				break;
			}
			case 12: {
				target.bitmap.runOptimize();
				if (random.nextBoolean()) {
					target.bitmap.trim();
				}
				break;
			}
			case 13: {
				if (target == other) {
					break;
				}
				final TreeSet<Integer> symmetric = new TreeSet<>(target.model);
				for (final Integer value : other.model) {
					if (!symmetric.add(value)) {
						symmetric.remove(value);
					}
				}
				pool.add(
					new Tracked(PersistentRoaringBitmap.xor(target.bitmap, other.bitmap), symmetric));
				break;
			}
			case 14: {
				if (target == other) {
					break;
				}
				final TreeSet<Integer> difference = new TreeSet<>(target.model);
				difference.removeAll(other.model);
				pool.add(
					new Tracked(PersistentRoaringBitmap.andNot(target.bitmap, other.bitmap), difference));
				break;
			}
			case 15:
			case 16: {
				// Arity is not a detail here. A fold of two or three bitmaps never builds two temporaries
				// that then meet each other, so the whole consumed-operand path is unreachable below four
				// inputs; the walk therefore draws two to six. A repeated operand is deliberately allowed
				// too - folding a bitmap together with a union it is already inside is what makes a fold
				// meet a chunk two of its own operands hold, and no binary operator above can produce that.
				final List<Tracked> operands = new ArrayList<>();
				operands.add(target);
				operands.add(other);
				final int extra = random.nextInt(5);
				for (int i = 0; i < extra; i++) {
					operands.add(pool.get(random.nextInt(pool.size())));
				}
				pool.add(FOLDS[random.nextInt(FOLDS.length)].apply(operands));
				break;
			}
			default: {
				if (target == other) {
					break;
				}
				final TreeSet<Integer> union = new TreeSet<>(target.model);
				union.addAll(other.model);
				pool.add(new Tracked(PersistentRoaringBitmap.or(target.bitmap, other.bitmap), union));
				break;
			}
		}
	}

	/**
	 * Drives the pool through many random steps, re-checking the lockstep, aliasing and content
	 * invariants after each one. Seeds are fixed so a failure is reproducible.
	 */
	@Test
	@DisplayName("Random operator sequences keep shared[] in lockstep and never alias unflagged")
	public void randomOperatorSequencesPreserveCopyOnWriteInvariants() {
		for (int seed = 0; seed < 40; seed++) {
			final Random random = new Random(seed);
			final List<Tracked> pool = new ArrayList<>();
			for (int i = 0; i < 3; i++) {
				pool.add(randomBitmap(random));
			}
			assertAllInvariants(pool, "seed " + seed + " setup");

			for (int iteration = 0; iteration < 60; iteration++) {
				step(random, pool);
				assertAllInvariants(pool, "seed " + seed + " step " + iteration);
				// keep the pairwise alias scan affordable
				while (pool.size() > 6) {
					pool.remove(pool.size() - 1);
				}
			}
		}
	}

	/**
	 * Narrower, fully deterministic probe of the bulk-merge entry point: every combination of operand
	 * chunk-key layouts up to five chunks, each run against a freshly cloned peer so the receiver is
	 * frozen and fully shared when the merge fires.
	 */
	@Test
	@DisplayName("Every small key layout survives or/xor/naivelazyor against a cloned peer")
	public void exhaustiveSmallKeyLayoutsPreserveInvariants() {
		for (int leftMask = 0; leftMask < 32; leftMask++) {
			for (int rightMask = 0; rightMask < 32; rightMask++) {
				for (int op = 0; op < 3; op++) {
					final Tracked left = fromKeyMask(leftMask);
					final Tracked right = fromKeyMask(rightMask);
					final PersistentRoaringBitmap peer = left.bitmap.clone();
					final TreeSet<Integer> peerModel = new TreeSet<>(left.model);
					final TreeSet<Integer> rightModel = new TreeSet<>(right.model);

					if (op == 0) {
						left.bitmap.or(right.bitmap);
						left.model.addAll(right.model);
					} else if (op == 1) {
						final TreeSet<Integer> symmetric = new TreeSet<>(left.model);
						for (final Integer value : right.model) {
							if (!symmetric.add(value)) {
								symmetric.remove(value);
							}
						}
						left.bitmap.xor(right.bitmap);
						left.model.clear();
						left.model.addAll(symmetric);
					} else {
						left.bitmap.naivelazyor(right.bitmap);
						left.bitmap.repairAfterLazy();
						left.model.addAll(right.model);
					}

					final List<Tracked> pool = new ArrayList<>();
					pool.add(left);
					pool.add(right);
					pool.add(new Tracked(peer, peerModel));
					final String where =
						"left=" + leftMask + " right=" + rightMask + " op=" + op;
					assertAllInvariants(pool, where);
					assertArrayEquals(
						rightModel.stream().mapToInt(Integer::intValue).toArray(),
						right.bitmap.toArray(), where + ": source operand corrupted");
				}
			}
		}
	}

	/**
	 * Fully deterministic probe of the {@link FastAggregation} folds over operands that co-own their
	 * containers.
	 *
	 * The random walk reaches the folds, but the two shapes that decide whether a fold adopts a
	 * container or copies it are far too specific to leave to a draw. Every fold is therefore run over
	 * `{left, clone-of-left, right}`, which produces both by construction — a clone holds the very same
	 * {@link Container} instances as its original, and exactly the same values:
	 *
	 * - every chunk of `left` is co-owned by two of the fold's own operands, which is what a fold that
	 *   adopts an operand's container by reference has to notice;
	 * - at every key the pair shares, their chunks cancel *exactly*, which leaves a symmetric-difference
	 *   fold holding an emptied accumulator while a further operand is still to come.
	 *
	 * `left` carries run-shaped chunks and `right` sparse ones, because an emptied accumulator only
	 * reaches the degenerate merge branches while it is a {@link RunContainer} and the incoming chunk
	 * is small enough for the operators to guess the answer stays a run.
	 *
	 * The fourth operand is added in a second pass rather than always: a fold that adopted the third
	 * operand's container through a degenerate branch would fold the fourth one straight over it, and
	 * the adoption would leave no trace in the answer. Three operands are what leave the adopted
	 * container standing as the answer's chunk; four are what put a second co-owned pair in front of
	 * the fold. Both are wanted, and neither subsumes the other.
	 */
	@Test
	@DisplayName("Every fold over co-owning operands keeps shared[] in lockstep and never aliases unflagged")
	public void foldsOverCoOwningOperandsPreserveCopyOnWriteInvariants() {
		for (int leftMask = 0; leftMask < 32; leftMask++) {
			for (int rightMask = 0; rightMask < 32; rightMask++) {
				for (int operandCount = 3; operandCount <= 4; operandCount++) {
					for (final Fold fold : FOLDS) {
						final Tracked left = fromKeyMaskAsRuns(leftMask);
						final Tracked right = fromKeyMask(rightMask);

						final List<Tracked> operands = new ArrayList<>();
						operands.add(left);
						operands.add(new Tracked(left.bitmap.clone(), new TreeSet<>(left.model)));
						operands.add(right);
						if (operandCount == 4) {
							operands.add(
								new Tracked(right.bitmap.clone(), new TreeSet<>(right.model)));
						}
						final Tracked result = fold.apply(operands);

						final List<Tracked> pool = new ArrayList<>(operands);
						pool.add(result);
						assertAllInvariants(
							pool,
							"fold=" + fold + " operands=" + operandCount + " left=" + leftMask
								+ " right=" + rightMask);
					}
				}
			}
		}
	}

	/**
	 * Fully deterministic probe of the consumed-operand union — the one path a size-ordered fold
	 * reaches only when two of its own temporaries meet each other.
	 *
	 * Which path such a fold takes is decided by arithmetic rather than by shape, and reaching this one
	 * takes a very particular sequence. Merging two caller bitmaps produces a temporary that owns what
	 * it holds, so two such temporaries meeting each other would hand over nothing worth tracking.
	 * What matters is a temporary that has since absorbed a *caller* bitmap, because that merge
	 * borrows the bitmap's containers rather than copying them — and that temporary then has to meet
	 * another temporary for the borrow to be handed on.
	 *
	 * **Five operands of equal serialized size force exactly that sequence**, and the arithmetic is
	 * worth spelling out because nothing else in this class depends on it. Writing `A` for one
	 * operand's size: the first two merge into a temporary of `2A`; the next two cheapest are the
	 * third and fourth operands, which merge into a second temporary of `2A`; the fifth operand is now
	 * the cheapest entry at `A` and is absorbed into one of the temporaries, which is the merge that
	 * borrows; and the two temporaries are all that is left. Four operands stop one step short — the
	 * two temporaries meet with nothing borrowed — and six or more may or may not get there, so all
	 * three counts are swept rather than argued about.
	 *
	 * The sweep over cardinality and shape is what makes the operands land on every container encoding
	 * while the equal-size property holds.
	 */
	@Test
	@DisplayName("Every fold over equally sized operands leaves no borrowed container unflagged")
	public void foldsOverEquallySizedOperandsPreserveCopyOnWriteInvariants() {
		for (int operandCount = 4; operandCount <= 6; operandCount++) {
			for (final int cardinality : UNIFORM_CARDINALITIES) {
				for (final boolean asRun : new boolean[]{false, true}) {
					for (final Fold fold : FOLDS) {
						final List<Tracked> operands = new ArrayList<>();
						for (int key = 0; key < operandCount; key++) {
							operands.add(uniformChunk(key, cardinality, asRun));
						}

						final Tracked result = fold.apply(operands);

						final List<Tracked> pool = new ArrayList<>(operands);
						pool.add(result);
						assertAllInvariants(
							pool,
							"fold=" + fold + " operands=" + operandCount + " cardinality=" + cardinality
								+ " run=" + asRun);
					}
				}
			}
		}
	}

	/**
	 * Builds a bitmap holding exactly one chunk, at `key`, carrying `cardinality` values — so that two
	 * such bitmaps built with the same arguments but different keys serialize to the same number of
	 * bytes, whatever encoding that cardinality lands on.
	 *
	 * @param cardinality number of values the chunk holds
	 * @param asRun       `true` to lay the values out contiguously and run-optimize the result,
	 *                    `false` to spread them so the chunk keeps its array or bitmap encoding
	 */
	@Nonnull
	private static Tracked uniformChunk(
		final int key, final int cardinality, final boolean asRun) {
		final PersistentRoaringBitmap bitmap = new PersistentRoaringBitmap();
		final TreeSet<Integer> model = new TreeSet<>();
		final int stride = asRun ? 1 : 7;
		for (int i = 0; i < cardinality; i++) {
			final int value = (key << 16) + 1_000 + i * stride;
			bitmap.add(value);
			model.add(value);
		}
		if (asRun) {
			bitmap.runOptimize();
		}
		return new Tracked(bitmap, model);
	}

	/**
	 * Builds a bitmap holding chunks `0 .. 4` selected by the bits of `mask`, each written as one
	 * contiguous range and then run-optimized, so that its chunks are {@link RunContainer}s rather
	 * than the sparse ones {@link #fromKeyMask} produces.
	 *
	 * The range is long enough that the run encoding stays the most compact one — a compact run
	 * survives `toEfficientContainer`, which is the precondition for the degenerate merge branches to
	 * be reachable at all — and short enough that the reference model stays cheap to compare.
	 */
	@Nonnull
	private static Tracked fromKeyMaskAsRuns(final int mask) {
		final PersistentRoaringBitmap bitmap = new PersistentRoaringBitmap();
		final TreeSet<Integer> model = new TreeSet<>();
		for (int key = 0; key < 5; key++) {
			if ((mask & (1 << key)) != 0) {
				final int start = (key << 16) + 1_000;
				bitmap.add((long) start, (long) start + 100);
				for (int value = start; value < start + 100; value++) {
					model.add(value);
				}
			}
		}
		bitmap.runOptimize();
		return new Tracked(bitmap, model);
	}

	/**
	 * Builds a bitmap holding chunks `0 .. 4` selected by the bits of `mask`, three values per chunk.
	 */
	private static Tracked fromKeyMask(final int mask) {
		final PersistentRoaringBitmap bitmap = new PersistentRoaringBitmap();
		final TreeSet<Integer> model = new TreeSet<>();
		for (int key = 0; key < 5; key++) {
			if ((mask & (1 << key)) != 0) {
				for (int offset = 1; offset <= 3; offset++) {
					final int value = (key << 16) + offset * 7;
					bitmap.add(value);
					model.add(value);
				}
			}
		}
		return new Tracked(bitmap, model);
	}
}
