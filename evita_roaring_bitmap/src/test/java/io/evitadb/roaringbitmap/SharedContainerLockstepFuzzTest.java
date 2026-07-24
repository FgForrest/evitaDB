package io.evitadb.roaringbitmap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Randomized stress test for the two copy-on-write bookkeeping invariants of
 * {@link PersistentRoaringBitmap}.
 *
 * LOCKSTEP — the parallel `shared[]` flag array must always be long enough to describe every live
 * slot of `highLowContainer`, so that no raw `shared[i]` index can run off the end. Every path that
 * grows, shrinks or rebuilds the container array has to keep the two in step; historically that is
 * where this class has broken.
 *
 * ALIASING — whenever two distinct bitmaps hold the very same {@link Container} instance, BOTH must
 * have that slot flagged shared. A single unflagged slot over an aliased container is the precise
 * precondition for silent cross-bitmap corruption: the unflagged owner believes it may mutate in
 * place, and the peer sees the write. Checking the alias graph directly catches the desync at the
 * moment it is created, rather than waiting for a later mutation to expose it.
 *
 * The fuzz drives a pool of bitmaps through the in-place operators (whose bulk-merge rebuild is the
 * riskiest path), the structural mutators and the static/cloning producers, re-checking both
 * invariants plus every bitmap's contents against a reference model after every single step.
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
	 * Chunk keys the fuzz draws from — deliberately few and interleavable, so that in-place operators
	 * hit the bulk-merge rebuild rather than the plain tail-append.
	 */
	private static final int[] CHUNK_KEYS = {0, 1, 2, 3, 5, 8, 13, 400};

	/**
	 * Asserts that `shared[]` covers every live slot of the container array.
	 */
	private static void assertLockstep(final PersistentRoaringBitmap bitmap, final String where) {
		assertTrue(
			bitmap.shared.length >= bitmap.highLowContainer.size(),
			() -> where + ": shared[] holds " + bitmap.shared.length + " flags for "
				+ bitmap.highLowContainer.size() + " containers");
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
		switch (random.nextInt(16)) {
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
