package io.evitadb.roaringbitmap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins two properties that together keep an emptied chunk from turning into an alias of a live
 * container.
 *
 * {@link RunContainer}'s lazy XOR has degenerate branches for an empty operand, and `xor` is an
 * out-of-place operator whose result the caller owns and mutates — so those branches must hand back
 * a private container rather than one of the two operands. That only matters where a fold keeps an
 * emptied chunk alive as a receiver, and {@link FastAggregation#horizontal_xor} is the one place
 * that can: it folds every input sharing a key into a single accumulator, so two cancelling inputs
 * leave that accumulator empty while further inputs are still to come.
 *
 * The second property is the other half of the same story: a chunk holding no values must not reach
 * the result at all. Left there it makes {@link PersistentRoaringBitmap#isEmpty()} disagree with the
 * cardinality, and it hands every later operator an empty receiver — moving the degenerate branch
 * within reach of callers that never went near `horizontal_xor`.
 */
@DisplayName("horizontal_xor must not adopt an input's container through an emptied chunk")
public class HorizontalXorEmptyChunkTest {

	/**
	 * Cardinality of the array-shaped input. It must stay below the threshold at which
	 * {@link RunContainer#xor(ArrayContainer)} stops taking its lazy path, or the degenerate branch
	 * this class exists to pin is never reached.
	 */
	private static final int ARRAY_CARDINALITY = 20;

	/** A single chunk held as a {@link RunContainer}. */
	private static PersistentRoaringBitmap runChunk(final int key) {
		final PersistentRoaringBitmap b = new PersistentRoaringBitmap();
		b.add((((long) key) << 16), (((long) key) << 16) + 12_000);
		b.runOptimize();
		assertTrue(b.getContainerAtIndex(0) instanceof RunContainer, "fixture drifted off the run shape");
		return b;
	}

	/** A single sparse chunk held as an {@link ArrayContainer}. */
	private static PersistentRoaringBitmap arrayChunk(final int key) {
		final PersistentRoaringBitmap b = new PersistentRoaringBitmap();
		for (int i = 0; i < ARRAY_CARDINALITY; i++) {
			b.add((key << 16) | (40_000 + i * 7));
		}
		assertTrue(
			b.getContainerAtIndex(0) instanceof ArrayContainer, "fixture drifted off the array shape");
		return b;
	}

	/**
	 * Non-vacuity guard for the whole class: the two run chunks must actually cancel to an **empty
	 * run container**. Nothing below proves anything if they cancel to some other shape, or if the
	 * fold stops producing an empty accumulator at all.
	 */
	@Test
	@DisplayName("two identical run chunks still cancel to an empty run container")
	public void fixtureStillProducesAnEmptyRunReceiver() {
		final Container cancelled =
			runChunk(0).getContainerAtIndex(0).xor(runChunk(0).getContainerAtIndex(0));

		assertTrue(cancelled.isEmpty(), "the two run chunks no longer cancel");
		assertTrue(
			cancelled instanceof RunContainer,
			"the cancelled chunk is a " + cancelled.getClass().getSimpleName() + ", so the empty-run "
				+ "receiver this class pins is no longer reachable from this fixture");
		assertTrue(
			ARRAY_CARDINALITY < 32,
			"the array input is no longer sparse enough for RunContainer.xor to take its lazy path");
	}

	@Test
	@DisplayName("an empty receiver's xor/ixor never hands back its argument")
	public void emptyReceiverNeverReturnsItsArgument() {
		final Container array = arrayChunk(0).getContainerAtIndex(0);
		final Container run = runChunk(0).getContainerAtIndex(0);

		assertNotSame(array, new RunContainer().xor(array), "emptyRun.xor(array) returned its argument");
		assertNotSame(array, new RunContainer().ixor(array), "emptyRun.ixor(array) returned its argument");
		assertNotSame(run, new ArrayContainer().xor(run), "emptyArray.xor(run) returned its argument");
		assertNotSame(run, new ArrayContainer().ixor(run), "emptyArray.ixor(run) returned its argument");
	}

	@Test
	@DisplayName("the result never aliases an input container")
	public void resultNeverAliasesAnInput() {
		final PersistentRoaringBitmap b1 = runChunk(0);
		final PersistentRoaringBitmap b2 = runChunk(0);
		final PersistentRoaringBitmap b3 = arrayChunk(0);

		final PersistentRoaringBitmap answer = FastAggregation.horizontal_xor(b1, b2, b3);

		for (final PersistentRoaringBitmap input : new PersistentRoaringBitmap[]{b1, b2, b3}) {
			for (int i = 0; i < input.highLowContainer.size(); i++) {
				for (int a = 0; a < answer.highLowContainer.size(); a++) {
					assertNotSame(
						input.getContainerAtIndex(i), answer.getContainerAtIndex(a),
						"the result adopted an input's container outright");
				}
			}
		}
	}

	@Test
	@DisplayName("writing into the result never writes through to an input")
	public void mutatingTheResultDoesNotCorruptAnInput() {
		final PersistentRoaringBitmap b1 = runChunk(0);
		final PersistentRoaringBitmap b2 = runChunk(0);
		final PersistentRoaringBitmap b3 = arrayChunk(0);
		final int[] b3Before = b3.toArray();

		final PersistentRoaringBitmap answer = FastAggregation.horizontal_xor(b1, b2, b3);
		for (int v = 11; v < 1 << 16; v += 1021) {
			answer.add(v);
		}

		assertArrayEquals(b3Before, b3.toArray(), "the third input was written through");
	}

	@Test
	@DisplayName("two cancelling inputs leave no empty chunk behind")
	public void cancellingInputsLeaveNoEmptyChunk() {
		final PersistentRoaringBitmap answer = FastAggregation.horizontal_xor(runChunk(0), runChunk(0));

		assertEquals(0, answer.getCardinality(), "the symmetric difference should be empty");
		assertTrue(answer.isEmpty(), "isEmpty() disagrees with a cardinality of zero");
		assertEquals(
			0, answer.highLowContainer.size(), "a chunk holding no values was left in the result");
	}

	@Test
	@DisplayName("the symmetric difference is still the symmetric difference")
	public void symmetricDifferenceContentIsCorrect() {
		final PersistentRoaringBitmap b1 = runChunk(0);
		final PersistentRoaringBitmap b2 = runChunk(0);
		final PersistentRoaringBitmap b3 = arrayChunk(0);

		assertArrayEquals(
			b3.toArray(), FastAggregation.horizontal_xor(b1, b2, b3).toArray(),
			"horizontal_xor no longer computes the symmetric difference");
	}
}
