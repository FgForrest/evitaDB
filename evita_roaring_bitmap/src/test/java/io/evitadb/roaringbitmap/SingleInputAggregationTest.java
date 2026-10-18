package io.evitadb.roaringbitmap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins ownership of single-input aggregation results: callers may mutate them without changing an
 * operand, including when containers or their backing arrays are shared under copy-on-write.
 */
@DisplayName("Single-input aggregations return independently mutable bitmaps")
public class SingleInputAggregationTest {

	/**
	 * Names an overload while giving its lambda the bitmap-to-bitmap type needed by the test source.
	 */
	private static Arguments aggregation(
		final String name, final Function<PersistentRoaringBitmap, PersistentRoaringBitmap> operation
	) {
		return Arguments.of(name, operation);
	}

	/**
	 * Every public bitmap-returning overload in FastAggregation, plus the range-bounded wrappers in
	 * PersistentRoaringBitmap, so another entry point cannot bypass the same ownership checks.
	 */
	private static Stream<Arguments> aggregations() {
		return Stream.concat(
			Stream.of(
				aggregation("naive_or(varargs)", FastAggregation::naive_or),
				aggregation("naive_or(Iterator)", bitmap -> FastAggregation.naive_or(List.of(bitmap).iterator())),
				aggregation("naive_xor(varargs)", FastAggregation::naive_xor),
				aggregation("naive_xor(Iterator)", bitmap -> FastAggregation.naive_xor(List.of(bitmap).iterator())),
				aggregation("naive_and(varargs)", FastAggregation::naive_and),
				aggregation("naive_and(Iterator)", bitmap -> FastAggregation.naive_and(List.of(bitmap).iterator())),
				aggregation("horizontal_or(varargs)", FastAggregation::horizontal_or),
				aggregation("horizontal_or(List)", bitmap -> FastAggregation.horizontal_or(List.of(bitmap))),
				aggregation("horizontal_or(Iterator)", bitmap -> FastAggregation.horizontal_or(List.of(bitmap).iterator())),
				aggregation("horizontal_xor(varargs)", FastAggregation::horizontal_xor),
				aggregation("and(varargs)", FastAggregation::and),
				aggregation("and(Iterator)", bitmap -> FastAggregation.and(List.of(bitmap).iterator())),
				aggregation("and(buffer, varargs)", bitmap -> FastAggregation.and(new long[1024], bitmap)),
				aggregation("or(varargs)", FastAggregation::or),
				aggregation("or(Iterator)", bitmap -> FastAggregation.or(List.of(bitmap).iterator())),
				aggregation("xor(varargs)", FastAggregation::xor),
				aggregation("xor(Iterator)", bitmap -> FastAggregation.xor(List.of(bitmap).iterator())),
				aggregation("workShyAnd(buffer, varargs)", bitmap -> FastAggregation.workShyAnd(new long[1024], bitmap)),
				aggregation("workAndMemoryShyAnd(buffer, varargs)", bitmap -> FastAggregation.workAndMemoryShyAnd(new long[1024], bitmap)),
				aggregation("PersistentRoaringBitmap.and(Iterator, long, long)",
					bitmap -> PersistentRoaringBitmap.and(List.of(bitmap).iterator(), 0L, 3L << 16)),
				aggregation("PersistentRoaringBitmap.and(Iterator, int, int)",
					bitmap -> PersistentRoaringBitmap.and(List.of(bitmap).iterator(), 0, 3 << 16)),
				aggregation("PersistentRoaringBitmap.or(Iterator, long, long)",
					bitmap -> PersistentRoaringBitmap.or(List.of(bitmap).iterator(), 0L, 3L << 16)),
				aggregation("PersistentRoaringBitmap.or(Iterator, int, int)",
					bitmap -> PersistentRoaringBitmap.or(List.of(bitmap).iterator(), 0, 3 << 16)),
				aggregation("PersistentRoaringBitmap.xor(Iterator, long, long)",
					bitmap -> PersistentRoaringBitmap.xor(List.of(bitmap).iterator(), 0L, 3L << 16)),
				aggregation("PersistentRoaringBitmap.xor(Iterator, int, int)",
					bitmap -> PersistentRoaringBitmap.xor(List.of(bitmap).iterator(), 0, 3 << 16)),
				aggregation("priorityqueue_xor(varargs)", FastAggregation::priorityqueue_xor)
			),
			priorityQueueUnions()
		);
	}

	/**
	 * Both priority-queue union overloads, whose single-input paths must avoid repairing the operand.
	 */
	private static Stream<Arguments> priorityQueueUnions() {
		return Stream.of(
			aggregation("priorityqueue_or(varargs)", FastAggregation::priorityqueue_or),
			aggregation("priorityqueue_or(Iterator)", bitmap -> FastAggregation.priorityqueue_or(List.of(bitmap).iterator()))
		);
	}

	/**
	 * Builds one array, bitmap and run chunk so mutations exercise all three container representations.
	 */
	private static PersistentRoaringBitmap input() {
		final PersistentRoaringBitmap bitmap = new PersistentRoaringBitmap();
		for (int i = 0; i < 64; i++) {
			bitmap.add(i * 97 + 3);
		}
		for (int i = 0; i < ArrayContainer.DEFAULT_MAX_SIZE + 256; i++) {
			bitmap.add((1 << 16) | (i * 3 + 3));
		}
		bitmap.add((2L << 16) + 3, (2L << 16) + 20_003);
		bitmap.runOptimize();
		assertTrue(bitmap.getContainerAtIndex(0) instanceof ArrayContainer, "fixture lost its array chunk");
		assertTrue(bitmap.getContainerAtIndex(1) instanceof BitmapContainer, "fixture lost its bitmap chunk");
		assertTrue(bitmap.getContainerAtIndex(2) instanceof RunContainer, "fixture lost its run chunk");
		return bitmap;
	}

	/**
	 * A distinct result with the same values is required even when there is no merge to perform.
	 */
	@ParameterizedTest(name = "{0}")
	@MethodSource("aggregations")
	public void singleInputReturnsDistinctEqualBitmap(
		final String name, final Function<PersistentRoaringBitmap, PersistentRoaringBitmap> operation
	) {
		final PersistentRoaringBitmap input = PersistentRoaringBitmap.bitmapOf(3, 100, (1 << 16) | 3, (2 << 16) | 3);
		final int[] before = input.toArray();

		final PersistentRoaringBitmap result = operation.apply(input);

		assertNotSame(input, result, name + " returned its input");
		assertArrayEquals(before, result.toArray(), name + " changed the result values");
		assertArrayEquals(before, input.toArray(), name + " changed the input values");
	}

	/**
	 * An empty operand still belongs to its caller; adding to the result must leave it empty.
	 */
	@ParameterizedTest(name = "{0}")
	@MethodSource("aggregations")
	public void singleEmptyInputReturnsDistinctBitmap(
		final String name, final Function<PersistentRoaringBitmap, PersistentRoaringBitmap> operation
	) {
		final PersistentRoaringBitmap input = new PersistentRoaringBitmap();
		final PersistentRoaringBitmap result = operation.apply(input);

		assertNotSame(input, result, name + " returned its empty input");
		assertTrue(result.isEmpty(), name + " changed the empty result");
		result.add(3);
		assertTrue(input.isEmpty(), name + " wrote through to its empty input");
	}

	/**
	 * Each mutator starts from a fresh result so no earlier write can hide an unflagged alias by
	 * detaching its container. Writes stay inside existing chunks to reach their backing storage.
	 */
	@ParameterizedTest(name = "{0}")
	@MethodSource("aggregations")
	public void resultMutationsLeaveInputUnchanged(
		final String name, final Function<PersistentRoaringBitmap, PersistentRoaringBitmap> operation
	) {
		for (int mutation = 0; mutation < 4; mutation++) {
			final PersistentRoaringBitmap input = input();
			final int[] before = input.toArray();
			final PersistentRoaringBitmap result = operation.apply(input);
			final PersistentRoaringBitmap additions = PersistentRoaringBitmap.bitmapOf(4, (1 << 16) | 4, (2 << 16) | 2);
			final PersistentRoaringBitmap retained = PersistentRoaringBitmap.bitmapOf(3, (1 << 16) | 3, (2 << 16) | 3);
			if (mutation == 0) {
				for (final int value : additions.toArray()) {
					result.add(value);
				}
			} else if (mutation == 1) {
				for (final int value : retained.toArray()) {
					result.remove(value);
				}
			} else if (mutation == 2) {
				result.or(additions);
			} else {
				result.and(retained);
			}
			assertArrayEquals(before, input.toArray(), name + " wrote through to its input with mutation " + mutation);
		}
	}

	/**
	 * Any container lent to a result must be marked shared on both sides before either side can write.
	 */
	@ParameterizedTest(name = "{0}")
	@MethodSource("aggregations")
	public void aliasedContainersAreFlaggedShared(
		final String name, final Function<PersistentRoaringBitmap, PersistentRoaringBitmap> operation
	) {
		final PersistentRoaringBitmap input = input();
		final PersistentRoaringBitmap result = operation.apply(input);

		for (int r = 0; r < result.highLowContainer.size(); r++) {
			for (int i = 0; i < input.highLowContainer.size(); i++) {
				if (result.getContainerAtIndex(r) == input.getContainerAtIndex(i)) {
					assertTrue(result.isShared(r), name + " result slot " + r + " aliases an input container without a shared flag");
					assertTrue(input.isShared(i), name + " input slot " + i + " lent a container without a shared flag");
				}
			}
		}
	}

	/**
	 * Repairing a one-value run replaces it with an array container, so this detects writes during the
	 * aggregation itself even if the operand's logical values happen to stay equal.
	 */
	@ParameterizedTest(name = "{0}")
	@MethodSource("priorityQueueUnions")
	public void singleInputUnionDoesNotRepairOperand(
		final String name, final Function<PersistentRoaringBitmap, PersistentRoaringBitmap> operation
	) {
		final Container original = new RunContainer(7, 8);
		final RoaringArray array = new RoaringArray();
		array.append((char) 0, original);
		final PersistentRoaringBitmap input = new PersistentRoaringBitmap(array);
		final int[] before = input.toArray();

		final PersistentRoaringBitmap result = operation.apply(input);

		assertSame(original, input.getContainerAtIndex(0), name + " repaired its input container");
		assertArrayEquals(before, input.toArray(), name + " changed the input values");
		assertArrayEquals(before, result.toArray(), name + " changed the result values");
	}
}
