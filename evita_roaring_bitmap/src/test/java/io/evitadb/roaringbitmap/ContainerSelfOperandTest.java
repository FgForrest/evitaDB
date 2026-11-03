package io.evitadb.roaringbitmap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.BitSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Pins binary container operations when the receiver is also the operand. Compact and spare-capacity
 * storage exercise both merging paths, and lazy unions are repaired before their results are read.
 */
public class ContainerSelfOperandTest {

	/**
	 * A compact run array must survive self-union without the scratch prefix introducing value zero.
	 */
	@Test
	void runIorWithSelfMustNotIntroduceZero() {
		final RunContainer container = new RunContainer(new char[]{10, 10, 100, 100}, 2);
		final BitSet expected = contents(container);

		final Container result = container.ior(container);

		assertFalse(result.contains((char) 0), "self ior introduced value 0 into runs [10,20] and [100,200]");
		assertContents(expected, result, "run.ior(self)");
	}

	/**
	 * The generic lazy dispatcher reaches the same run merge, so repair must preserve the original set.
	 */
	@Test
	void runLazyIORWithSelfMustNotIntroduceZero() {
		final RunContainer container = new RunContainer(new char[]{10, 10, 100, 100}, 2);
		final BitSet expected = contents(container);

		final Container result = container.lazyIOR(container).repairAfterLazy();

		assertFalse(result.contains((char) 0), "self lazyIOR introduced value 0 into runs [10,20] and [100,200]");
		assertContents(expected, result, "run.lazyIOR(self)");
	}

	/**
	 * Self-union is idempotent for every encoding, including array promotion and full-run shortcuts.
	 */
	@ParameterizedTest(name = "{0}")
	@MethodSource("containers")
	void iorWithSelfPreservesContents(final String name, final Container container) {
		final BitSet expected = contents(container);

		assertContents(expected, container.ior(container), name + ".ior(self)");
	}

	/**
	 * Self-intersection must retain every value even when the implementation delegates or demotes.
	 */
	@ParameterizedTest(name = "{0}")
	@MethodSource("containers")
	void iandWithSelfPreservesContents(final String name, final Container container) {
		final BitSet expected = contents(container);

		assertContents(expected, container.iand(container), name + ".iand(self)");
	}

	/**
	 * Every value cancels in a self-xor, including values on either side of a word boundary.
	 */
	@ParameterizedTest(name = "{0}")
	@MethodSource("containers")
	void ixorWithSelfIsEmpty(final String name, final Container container) {
		assertContents(new BitSet(), container.ixor(container), name + ".ixor(self)");
	}

	/**
	 * Subtracting the same object must remove all values without corrupting the merge's unread input.
	 */
	@ParameterizedTest(name = "{0}")
	@MethodSource("containers")
	void iandNotWithSelfIsEmpty(final String name, final Container container) {
		assertContents(new BitSet(), container.iandNot(container), name + ".iandNot(self)");
	}

	/**
	 * Lazy self-union has the same set result as eager union once its cardinality is repaired.
	 */
	@ParameterizedTest(name = "{0}")
	@MethodSource("containers")
	void lazyIORWithSelfPreservesContents(final String name, final Container container) {
		final BitSet expected = contents(container);

		assertContents(expected, container.lazyIOR(container).repairAfterLazy(), name + ".lazyIOR(self)");
	}

	/**
	 * Bitmap containers expose a same-type lazy mutator as well as the generic dispatcher; both must
	 * tolerate aliasing, including the cardinality invalidation that precedes the word loop.
	 */
	@ParameterizedTest(name = "{0}")
	@MethodSource("bitmaps")
	void ilazyorWithSelfPreservesContents(final String name, final BitmapContainer container) {
		final BitSet expected = contents(container);

		assertContents(expected, container.ilazyor(container).repairAfterLazy(), name + ".ilazyor(self)");
	}

	/**
	 * Self ORNOT fills the bounded prefix and retains the receiver's values above it; empty, partial-word
	 * and full-universe bounds pin the complement's clipping independently of the container encoding.
	 */
	@ParameterizedTest(name = "{0}")
	@MethodSource("containers")
	void iorNotWithSelfFillsThePrefix(final String name, final Container container) {
		for (final int end : new int[]{0, 1, 63, 64, 65, 128, 129, 32768, 65535, 65536}) {
			final Container operand = container.clone();
			final BitSet expected = contents(container);
			expected.set(0, end);

			assertContents(expected, operand.iorNot(operand, end), name + ".iorNot(self, " + end + ")");
		}
	}

	/**
	 * The out-of-place ORNOT counterpart must compute the same bounded self-result while leaving the
	 * original values intact, since it constructs a complement before unioning.
	 */
	@ParameterizedTest(name = "{0}")
	@MethodSource("containers")
	void orNotWithSelfFillsThePrefixWithoutChangingTheOperand(final String name, final Container container) {
		final BitSet original = contents(container);
		for (final int end : new int[]{0, 1, 63, 64, 65, 128, 129, 32768, 65535, 65536}) {
			final BitSet expected = (BitSet) original.clone();
			expected.set(0, end);

			assertContents(expected, container.orNot(container, end), name + ".orNot(self, " + end + ")");
			assertContents(original, container, name + ".orNot changed its operand");
		}
	}

	/**
	 * Builds fresh operands for each operation: multiple runs crossing word and unsigned-char boundaries,
	 * with zero absent and present, plus empty shapes and a full run. Array capacities cover allocation,
	 * reuse and promotion; bitmap densities cover both sides of demotion; run capacities cover scratch
	 * allocation and reuse.
	 */
	private static Stream<Arguments> containers() {
		final Stream.Builder<Arguments> fixtures = Stream.builder();
		for (final boolean includeZero : new boolean[]{false, true}) {
			final BitSet sparse = new BitSet();
			for (final int[] range : new int[][]{
				{10, 21}, {60, 69}, {100, 201}, {255, 259}, {32766, 32770}, {65530, 65536}
			}) {
				sparse.set(range[0], range[1]);
			}
			if (includeZero) {
				sparse.set(0);
			}
			final String suffix = includeZero ? " with zero" : " without zero";
			fixtures.add(Arguments.of(
				"compact array" + suffix, fill(new ArrayContainer(sparse.cardinality()), sparse)));
			fixtures.add(Arguments.of(
				"spare array" + suffix, fill(new ArrayContainer(2 * sparse.cardinality() + 1), sparse)));
			final BitSet large = (BitSet) sparse.clone();
			large.set(500, 3500);
			fixtures.add(Arguments.of("large array" + suffix, fill(new ArrayContainer(), large)));
			fixtures.add(Arguments.of("sparse bitmap" + suffix, fill(new BitmapContainer(), sparse)));
			final BitSet dense = (BitSet) sparse.clone();
			for (int value = 513; value < 18000; value += 2) {
				dense.set(value);
			}
			fixtures.add(Arguments.of("dense bitmap" + suffix, fill(new BitmapContainer(), dense)));
			final int runs = includeZero ? 7 : 6;
			fixtures.add(Arguments.of("compact run" + suffix, fill(new RunContainer(runs), sparse)));
			fixtures.add(Arguments.of("spare run" + suffix, fill(new RunContainer(2 * runs + 1), sparse)));
		}
		fixtures.add(Arguments.of("empty array", new ArrayContainer()));
		fixtures.add(Arguments.of("empty bitmap", new BitmapContainer()));
		fixtures.add(Arguments.of("empty run", new RunContainer()));
		fixtures.add(Arguments.of("full run", RunContainer.full()));
		return fixtures.build();
	}

	/**
	 * Only the bitmap encoding provides an ilazyor overload accepting its own type.
	 */
	private static Stream<Arguments> bitmaps() {
		return containers().filter(arguments -> arguments.get()[1] instanceof BitmapContainer);
	}

	/**
	 * Fills the requested encoding and checks that the fixture did not silently promote to another type.
	 */
	private static Container fill(final Container empty, final BitSet values) {
		Container container = empty;
		for (int value = values.nextSetBit(0); value >= 0; value = values.nextSetBit(value + 1)) {
			container = container.add((char) value);
		}
		assertInstanceOf(empty.getClass(), container, "fixture changed its container encoding");
		return container;
	}

	/**
	 * Captures values before mutation without using the container's binary operations as the oracle.
	 */
	private static BitSet contents(final Container container) {
		final BitSet values = new BitSet();
		final CharIterator iterator = container.getCharIterator();
		while (iterator.hasNext()) {
			values.set(iterator.next());
		}
		return values;
	}

	/**
	 * Checks cardinality and the entire ordered value sequence, so duplicate or unsorted output cannot
	 * hide behind the set oracle.
	 */
	private static void assertContents(final BitSet expected, final Container actual, final String context) {
		assertEquals(expected.cardinality(), actual.getCardinality(), context + ": cardinality");
		final int[] values = new int[actual.getCardinality()];
		actual.fillLeastSignificant16bits(values, 0, 0);
		assertArrayEquals(expected.stream().toArray(), values, context + ": values");
	}
}
