package io.evitadb.roaringbitmap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the copy-on-write bookkeeping of {@link PersistentRoaringBitmap#lazyorfromlazyinputs} and of
 * {@link FastAggregation#priorityqueue_or}, the one fold that reaches it.
 *
 * That helper adopts both operands' containers **by reference**, which the fold's `istmp[]` guard
 * justifies by saying the operands are temporaries it built and may consume. Under evitaDB's
 * structural sharing that is not the same as owning them: the fold's "one temporary, one caller
 * input" branch unions an input into a temporary with {@link PersistentRoaringBitmap#lazyor}, which
 * BORROWS the input's chunks rather than cloning them as upstream does. A temporary can therefore be
 * carrying a chunk a caller's bitmap still holds, and the answer has to inherit that co-ownership
 * instead of assuming the chunk is its own.
 *
 * The corruption this guards against is one-way, and therefore easy to miss: the input raises its own
 * `shared` flag when it lends the chunk, so it clones before writing; only the answer, handed the
 * reference unflagged, would write in place and be seen from the input.
 *
 * {@link #consumedOperandsHandOverTheirBorrowedChunksFlagged()} drives the helper directly, so it
 * pins the contract without depending on how the queue happens to schedule anything. The tests below
 * it go through the public fold instead, which is what makes the defect reachable, but they rely on
 * {@link #CARDINALITIES} producing a particular schedule.
 */
@DisplayName("priorityqueue_or must not hand out containers its inputs still own")
public class PriorityQueueOrSharingTest {

	/** Chunk key each input occupies; all five are disjoint, so no chunk is ever recombined. */
	private static final int[] KEYS = {0, 1, 2, 3, 4};

	/**
	 * Cardinalities chosen so the queue — which orders by serialized size — schedules the sequence
	 * that exercises the borrow: inputs 0+1 fold into a temporary, that temporary then borrows input
	 * 2, and the borrow is handed to `lazyorfromlazyinputs` when the temporary meets the 3+4
	 * temporary.
	 */
	private static final int[] CARDINALITIES = {10, 20, 30, 40, 45};

	/** A sparse chunk, held as an {@link ArrayContainer} so `repairAfterLazy` hands it back as-is. */
	private static PersistentRoaringBitmap sparseChunk(final int key, final int count) {
		final PersistentRoaringBitmap b = new PersistentRoaringBitmap();
		for (int i = 0; i < count; i++) {
			b.add((key << 16) | (i * 97 + 3));
		}
		return b;
	}

	/** The five inputs, rebuilt for every test so no test observes another's mutations. */
	private static PersistentRoaringBitmap[] inputs() {
		final PersistentRoaringBitmap[] in = new PersistentRoaringBitmap[KEYS.length];
		for (int i = 0; i < KEYS.length; i++) {
			in[i] = sparseChunk(KEYS[i], CARDINALITIES[i]);
		}
		return in;
	}

	/**
	 * Reproduces by hand the one situation the fold can put the helper in: a lazy operand that has
	 * borrowed a chunk from a bitmap nobody is consuming.
	 *
	 * The assertions run **before** `repairAfterLazy`, because that is where the aliasing is
	 * observable. The fold's closing repair clones every slot it finds flagged, so by the time
	 * `priorityqueue_or` returns, a correctly flagged chunk has already become a private copy — which
	 * is exactly why a test that only looks at the finished result cannot tell a flag that was right
	 * from a chunk that was never shared in the first place.
	 */
	@Test
	@DisplayName("a consumed operand hands over its borrowed chunks still flagged")
	public void consumedOperandsHandOverTheirBorrowedChunksFlagged() {
		final PersistentRoaringBitmap live = sparseChunk(2, CARDINALITIES[2]);
		final PersistentRoaringBitmap lazyLeft =
			PersistentRoaringBitmap.lazyor(sparseChunk(0, CARDINALITIES[0]), sparseChunk(1, CARDINALITIES[1]));
		// the borrow: `live` is not consumed by anything, but lazyLeft now points at its chunk
		lazyLeft.lazyor(live);
		final PersistentRoaringBitmap lazyRight =
			PersistentRoaringBitmap.lazyor(sparseChunk(3, CARDINALITIES[3]), sparseChunk(4, CARDINALITIES[4]));

		final PersistentRoaringBitmap answer =
			PersistentRoaringBitmap.lazyorfromlazyinputs(lazyLeft, lazyRight);

		int aliases = 0;
		for (int s = 0; s < live.highLowContainer.size(); s++) {
			for (int a = 0; a < answer.highLowContainer.size(); a++) {
				if (answer.getContainerAtIndex(a) == live.getContainerAtIndex(s)) {
					aliases++;
					assertTrue(
						answer.isShared(a) && live.isShared(s),
						"the answer's slot " + a + " and live slot " + s + " alias one container, "
							+ "flagged " + answer.isShared(a) + "/" + live.isShared(s));
				}
			}
		}
		assertTrue(
			aliases > 0,
			"the helper no longer adopts the operand's chunks by reference, so this test has stopped "
				+ "exercising the co-ownership it exists to pin");
	}

	@Test
	@DisplayName("no input container reaches the result with the result's shared flag cleared")
	public void resultNeverOwnsAContainerAnInputStillHolds() {
		final PersistentRoaringBitmap[] in = inputs();
		final PersistentRoaringBitmap answer = FastAggregation.priorityqueue_or(in);

		for (int i = 0; i < in.length; i++) {
			for (int s = 0; s < in[i].highLowContainer.size(); s++) {
				final Container c = in[i].getContainerAtIndex(s);
				for (int a = 0; a < answer.highLowContainer.size(); a++) {
					if (answer.getContainerAtIndex(a) == c) {
						assertTrue(
							answer.isShared(a) && in[i].isShared(s),
							"input " + i + " slot " + s + " and the result's slot " + a
								+ " alias one container, flagged " + in[i].isShared(s) + "/"
								+ answer.isShared(a));
					}
				}
			}
		}
	}

	@Test
	@DisplayName("writing into the result never writes through to an input")
	public void mutatingTheResultDoesNotCorruptAnInput() {
		final PersistentRoaringBitmap[] in = inputs();
		final int[][] before = new int[in.length][];
		for (int i = 0; i < in.length; i++) {
			before[i] = in[i].toArray();
		}

		final PersistentRoaringBitmap answer = FastAggregation.priorityqueue_or(in);

		// write INSIDE each chunk the answer already holds - a value in a fresh key would allocate a
		// new container and never touch a borrowed one
		for (int a = 0; a < answer.highLowContainer.size(); a++) {
			final int key = answer.getKeyAtIndex(a);
			for (int v = 11; v < 1 << 16; v += 1021) {
				answer.add((key << 16) | v);
			}
		}

		for (int i = 0; i < in.length; i++) {
			assertArrayEquals(
				before[i], in[i].toArray(),
				"input " + i + " was written through when the union result was mutated");
		}
	}

	@Test
	@DisplayName("writing into an input never changes the result")
	public void mutatingAnInputDoesNotCorruptTheResult() {
		for (int victim = 0; victim < KEYS.length; victim++) {
			final PersistentRoaringBitmap[] in = inputs();
			final PersistentRoaringBitmap answer = FastAggregation.priorityqueue_or(in);
			final int[] answerBefore = answer.toArray();

			final int key = KEYS[victim];
			for (int v = 11; v < 1 << 16; v += 1021) {
				in[victim].add((key << 16) | v);
			}

			assertArrayEquals(
				answerBefore, answer.toArray(),
				"the union result changed when input " + victim + " was mutated");
		}
	}

	@Test
	@DisplayName("the union is still the union")
	public void unionContentIsCorrect() {
		final PersistentRoaringBitmap[] in = inputs();
		final PersistentRoaringBitmap expected = new PersistentRoaringBitmap();
		for (final PersistentRoaringBitmap b : in) {
			for (final int v : b.toArray()) {
				expected.add(v);
			}
		}

		assertTrue(expected.getCardinality() > 0, "fixture is empty");
		assertArrayEquals(
			expected.toArray(), FastAggregation.priorityqueue_or(inputs()).toArray(),
			"priorityqueue_or no longer computes the union");
	}
}
