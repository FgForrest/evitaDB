package io.evitadb.roaringbitmap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the precondition the copy-on-write bookkeeping in {@link PersistentRoaringBitmap} relies on
 * when it declines to flag a chunk shared: the out-of-place binary container operators
 * (`or` / `xor` / `andNot`) hand back a container that shares no mutable state with either operand.
 *
 * The static binary operations mark a result chunk owned (not shared) exactly when they produced it
 * by combining both operands' containers. That is only sound if the combined container is genuinely
 * private — if any shape pair could return an operand, or a container aliasing an operand's backing
 * array, the owner would later mutate it in place and silently corrupt the peer.
 *
 * Identity is checked first because it is the cheap and obvious way to violate the property, but the
 * real assertion is behavioural: the result is scribbled over with the in-place mutators and both
 * operands must come through byte-for-byte unchanged. That catches a result which is a distinct
 * object yet wraps an operand's `char[]` / `long[]`.
 *
 * All nine shape pairs are exercised in both operand orders, since the operators dispatch on the
 * runtime shape of the argument and several pairs delegate by swapping the receiver.
 */
@DisplayName("Out-of-place container or/xor/andNot never alias their operands")
public class ContainerBinaryOpFreshnessTest {

	/**
	 * A named container factory, so a failure reports which shape pair broke.
	 */
	private record Shape(String name, java.util.function.IntFunction<Container> factory) {
	}

	/**
	 * The three container shapes, each built to a caller-chosen offset so two operands can be made to
	 * overlap partially rather than coincide or stay disjoint.
	 */
	private static final List<Shape> SHAPES = List.of(
		new Shape("array", ContainerBinaryOpFreshnessTest::arrayContainer),
		new Shape("bitmap", ContainerBinaryOpFreshnessTest::bitmapContainer),
		new Shape("run", ContainerBinaryOpFreshnessTest::runContainer)
	);

	/**
	 * A sparse container that stays below the array/bitmap threshold and does not run-compress.
	 */
	private static Container arrayContainer(final int offset) {
		Container c = new ArrayContainer();
		for (int i = 0; i < 64; i++) {
			c = c.add((char) (offset + i * 97));
		}
		assertTrue(c instanceof ArrayContainer, "fixture drifted off the array shape");
		return c;
	}

	/**
	 * A container dense enough to be held as a bitmap, laid out with a stride so it never collapses
	 * into runs.
	 */
	private static Container bitmapContainer(final int offset) {
		final BitmapContainer c = new BitmapContainer();
		for (int i = 0; i < ArrayContainer.DEFAULT_MAX_SIZE + 512; i++) {
			c.add((char) (offset + i * 2));
		}
		return c;
	}

	/**
	 * A single long run, the shape whose operators short-circuit on `isFull()` and delegate through
	 * `lazyor(...).repairAfterLazy()` — the paths most likely to hand back a non-fresh container.
	 */
	private static Container runContainer(final int offset) {
		return new RunContainer(offset, offset + 20_000);
	}

	/**
	 * Reads out a container's values so the operands can be compared before and after the result is
	 * scribbled over.
	 */
	private static int[] contents(final Container container) {
		final List<Integer> values = new ArrayList<>();
		final CharIterator it = container.getCharIterator();
		while (it.hasNext()) {
			values.add((int) it.next());
		}
		final int[] result = new int[values.size()];
		for (int i = 0; i < result.length; i++) {
			result[i] = values.get(i);
		}
		return result;
	}

	/**
	 * Mutates the container as aggressively as an owning bitmap would, preferring the in-place
	 * operators — those are the ones that write through to a shared backing array. Reassigns as it
	 * goes, because a mutator is free to return a promoted or demoted replacement.
	 */
	private static void scribble(final Container result) {
		Container c = result;
		c = c.ior(new RunContainer(1_000, 30_000));
		c = c.iandNot(new RunContainer(5_000, 6_000));
		for (int v = 3; v < 1 << 16; v += 1021) {
			c = c.add((char) v);
		}
		for (int v = 7; v < 1 << 16; v += 2039) {
			c = c.remove((char) v);
		}
		c.ixor(new RunContainer(40_000, 41_000));
	}

	@Test
	@DisplayName("or/xor/andNot results are private across every shape pair and operand order")
	public void binaryOperatorsReturnPrivateContainers() {
		for (final Shape leftShape : SHAPES) {
			for (final Shape rightShape : SHAPES) {
				for (int op = 0; op < 3; op++) {
					final String opName = op == 0 ? "or" : op == 1 ? "xor" : "andNot";
					final String where = leftShape.name() + "." + opName + "(" + rightShape.name() + ")";

					// partially overlapping operands: neither disjoint (which would make the result a
					// trivial concatenation) nor identical (which would collapse xor/andNot to empty)
					final Container left = leftShape.factory().apply(0);
					final Container right = rightShape.factory().apply(10_000);
					final int[] leftBefore = contents(left);
					final int[] rightBefore = contents(right);

					final Container result = op == 0
						? left.or(right)
						: op == 1 ? left.xor(right) : left.andNot(right);

					assertNotSame(left, result, where + ": result is the left operand");
					assertNotSame(right, result, where + ": result is the right operand");

					scribble(result);

					assertArrayEquals(
						leftBefore, contents(left), where + ": left operand was written through");
					assertArrayEquals(
						rightBefore, contents(right), where + ": right operand was written through");
				}
			}
		}
	}
}
