package io.evitadb.roaringbitmap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static io.evitadb.roaringbitmap.SeededTestData.TestDataSet.testCase;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/**
 * Regression tests for {@link FastAggregation}, ported from the upstream RoaringBitmap test
 * suite. They pin down the aggregate set operations across multiple bitmaps — union (`or`),
 * intersection (`and`) and symmetric difference (`xor`) — covering the naive, priority-queue
 * and work-shy variants together with the cardinality and intersection short-circuits.
 */
@Execution(ExecutionMode.CONCURRENT)
@DisplayName("FastAggregation")
public class TestFastAggregation {

	/**
	 * Marker subtype used to verify that aggregations accept and correctly handle subclasses of
	 * {@link PersistentRoaringBitmap}.
	 */
	private static class ExtendedRoaringBitmap extends PersistentRoaringBitmap {
	}

	@Nested
	@DisplayName("OR aggregation")
	class Or {

		@Test
		@DisplayName("horizontal_or unions a list of bitmaps")
		public void horizontal_or() {
			PersistentRoaringBitmap rb1 = PersistentRoaringBitmap.bitmapOf(0, 1, 2);
			PersistentRoaringBitmap rb2 = PersistentRoaringBitmap.bitmapOf(0, 5, 6);
			PersistentRoaringBitmap rb3 = PersistentRoaringBitmap.bitmapOf(1 << 16, 2 << 16);
			PersistentRoaringBitmap result = FastAggregation.horizontal_or(Arrays.asList(rb1, rb2, rb3));
			PersistentRoaringBitmap expected = PersistentRoaringBitmap.bitmapOf(0, 1, 2, 5, 6, 1 << 16, 2 << 16);
			assertEquals(expected, result);
		}

		@Test
		@DisplayName("or unions bitmaps passed as varargs")
		public void or() {
			PersistentRoaringBitmap rb1 = PersistentRoaringBitmap.bitmapOf(0, 1, 2);
			PersistentRoaringBitmap rb2 = PersistentRoaringBitmap.bitmapOf(0, 5, 6);
			PersistentRoaringBitmap rb3 = PersistentRoaringBitmap.bitmapOf(1 << 16, 2 << 16);
			PersistentRoaringBitmap result = FastAggregation.or(rb1, rb2, rb3);
			PersistentRoaringBitmap expected = PersistentRoaringBitmap.bitmapOf(0, 1, 2, 5, 6, 1 << 16, 2 << 16);
			assertEquals(expected, result);
		}

		@Test
		@DisplayName("horizontal_or unions bitmaps passed as varargs")
		public void horizontal_or2() {
			PersistentRoaringBitmap rb1 = PersistentRoaringBitmap.bitmapOf(0, 1, 2);
			PersistentRoaringBitmap rb2 = PersistentRoaringBitmap.bitmapOf(0, 5, 6);
			PersistentRoaringBitmap rb3 = PersistentRoaringBitmap.bitmapOf(1 << 16, 2 << 16);
			PersistentRoaringBitmap result = FastAggregation.horizontal_or(rb1, rb2, rb3);
			PersistentRoaringBitmap expected = PersistentRoaringBitmap.bitmapOf(0, 1, 2, 5, 6, 1 << 16, 2 << 16);
			assertEquals(expected, result);
		}

		@Test
		@DisplayName("priorityqueue_or unions bitmaps from an iterator")
		public void priorityqueue_or() {
			PersistentRoaringBitmap rb1 = PersistentRoaringBitmap.bitmapOf(0, 1, 2);
			PersistentRoaringBitmap rb2 = PersistentRoaringBitmap.bitmapOf(0, 5, 6);
			PersistentRoaringBitmap rb3 = PersistentRoaringBitmap.bitmapOf(1 << 16, 2 << 16);
			PersistentRoaringBitmap result =
				FastAggregation.priorityqueue_or(Arrays.asList(rb1, rb2, rb3).iterator());
			PersistentRoaringBitmap expected = PersistentRoaringBitmap.bitmapOf(0, 1, 2, 5, 6, 1 << 16, 2 << 16);
			assertEquals(expected, result);
		}

		@Test
		@DisplayName("priorityqueue_or unions bitmaps passed as varargs")
		public void priorityqueue_or2() {
			PersistentRoaringBitmap rb1 = PersistentRoaringBitmap.bitmapOf(0, 1, 2);
			PersistentRoaringBitmap rb2 = PersistentRoaringBitmap.bitmapOf(0, 5, 6);
			PersistentRoaringBitmap rb3 = PersistentRoaringBitmap.bitmapOf(1 << 16, 2 << 16);
			PersistentRoaringBitmap result = FastAggregation.priorityqueue_or(rb1, rb2, rb3);
			PersistentRoaringBitmap expected = PersistentRoaringBitmap.bitmapOf(0, 1, 2, 5, 6, 1 << 16, 2 << 16);
			assertEquals(expected, result);
		}

		@Test
		@DisplayName("or over an iterator unions bitmaps, including subclasses")
		public void testOrWithIterator() {
			final PersistentRoaringBitmap b1 = PersistentRoaringBitmap.bitmapOf(1, 2);
			final PersistentRoaringBitmap b2 = PersistentRoaringBitmap.bitmapOf(2, 3);
			final PersistentRoaringBitmap bItResult = FastAggregation.or(Arrays.asList(b1, b2).iterator());
			assertTrue(bItResult.contains(1));
			assertTrue(bItResult.contains(2));
			assertTrue(bItResult.contains(3));

			final ExtendedRoaringBitmap eb1 = new ExtendedRoaringBitmap();
			eb1.add(1);
			eb1.add(2);
			final ExtendedRoaringBitmap eb2 = new ExtendedRoaringBitmap();
			eb2.add(2);
			eb2.add(3);
			final PersistentRoaringBitmap ebItResult = FastAggregation.or(Arrays.asList(b1, b2).iterator());
			assertTrue(ebItResult.contains(1));
			assertTrue(ebItResult.contains(2));
			assertTrue(ebItResult.contains(3));
		}

		@Test
		@DisplayName("naive_or over an iterator unions bitmaps, including subclasses")
		public void testNaiveOrWithIterator() {
			final PersistentRoaringBitmap b1 = PersistentRoaringBitmap.bitmapOf(1, 2);
			final PersistentRoaringBitmap b2 = PersistentRoaringBitmap.bitmapOf(2, 3);
			final PersistentRoaringBitmap bResult = FastAggregation.naive_or(Arrays.asList(b1, b2).iterator());
			assertTrue(bResult.contains(1));
			assertTrue(bResult.contains(2));
			assertTrue(bResult.contains(3));

			final ExtendedRoaringBitmap eb1 = new ExtendedRoaringBitmap();
			eb1.add(1);
			eb1.add(2);
			final ExtendedRoaringBitmap eb2 = new ExtendedRoaringBitmap();
			eb2.add(2);
			eb2.add(3);
			final PersistentRoaringBitmap ebResult = FastAggregation.naive_or(Arrays.asList(b1, b2).iterator());
			assertTrue(ebResult.contains(1));
			assertTrue(ebResult.contains(2));
			assertTrue(ebResult.contains(3));
		}
	}

	@Nested
	@DisplayName("AND aggregation")
	class And {

		@Test
		@DisplayName("workShyAnd intersects bitmaps using a shared buffer")
		public void testWorkShyAnd() {
			final PersistentRoaringBitmap b1 = PersistentRoaringBitmap.bitmapOf(1, 2, 0x10001, 0x20001, 0x30001);
			final PersistentRoaringBitmap b2 = PersistentRoaringBitmap.bitmapOf(2, 3, 0x20002, 0x30001);
			final PersistentRoaringBitmap bResult = FastAggregation.workShyAnd(new long[1024], b1, b2);
			assertFalse(bResult.contains(1));
			assertTrue(bResult.contains(2));
			assertFalse(bResult.contains(3));
		}

		@Test
		@DisplayName("and over an iterator intersects bitmaps, including subclasses")
		public void testAndWithIterator() {
			final PersistentRoaringBitmap b1 = PersistentRoaringBitmap.bitmapOf(1, 2);
			final PersistentRoaringBitmap b2 = PersistentRoaringBitmap.bitmapOf(2, 3);
			final PersistentRoaringBitmap bResult = FastAggregation.and(Arrays.asList(b1, b2).iterator());
			assertFalse(bResult.contains(1));
			assertTrue(bResult.contains(2));
			assertFalse(bResult.contains(3));

			final ExtendedRoaringBitmap eb1 = new ExtendedRoaringBitmap();
			eb1.add(1);
			eb1.add(2);
			final ExtendedRoaringBitmap eb2 = new ExtendedRoaringBitmap();
			eb2.add(2);
			eb2.add(3);
			final PersistentRoaringBitmap ebResult = FastAggregation.and(Arrays.asList(b1, b2).iterator());
			assertFalse(ebResult.contains(1));
			assertTrue(ebResult.contains(2));
			assertFalse(ebResult.contains(3));
		}

		@Test
		@DisplayName("naive_and over an iterator intersects bitmaps, including subclasses")
		public void testNaiveAndWithIterator() {
			final PersistentRoaringBitmap b1 = PersistentRoaringBitmap.bitmapOf(1, 2);
			final PersistentRoaringBitmap b2 = PersistentRoaringBitmap.bitmapOf(2, 3);
			final PersistentRoaringBitmap bResult = FastAggregation.naive_and(Arrays.asList(b1, b2).iterator());
			assertFalse(bResult.contains(1));
			assertTrue(bResult.contains(2));
			assertFalse(bResult.contains(3));

			final ExtendedRoaringBitmap eb1 = new ExtendedRoaringBitmap();
			eb1.add(1);
			eb1.add(2);
			final ExtendedRoaringBitmap eb2 = new ExtendedRoaringBitmap();
			eb2.add(2);
			eb2.add(3);
			final PersistentRoaringBitmap ebResult = FastAggregation.naive_and(Arrays.asList(b1, b2).iterator());
			assertFalse(ebResult.contains(1));
			assertTrue(ebResult.contains(2));
			assertFalse(ebResult.contains(3));
		}
	}

	@Nested
	@DisplayName("XOR aggregation")
	class Xor {

		@Test
		@DisplayName("naive_xor over an iterator yields the symmetric difference, including subclasses")
		public void testNaiveXorWithIterator() {
			final PersistentRoaringBitmap b1 = PersistentRoaringBitmap.bitmapOf(1, 2);
			final PersistentRoaringBitmap b2 = PersistentRoaringBitmap.bitmapOf(2, 3);
			final PersistentRoaringBitmap bResult = FastAggregation.naive_xor(Arrays.asList(b1, b2).iterator());
			assertTrue(bResult.contains(1));
			assertFalse(bResult.contains(2));
			assertTrue(bResult.contains(3));

			final ExtendedRoaringBitmap eb1 = new ExtendedRoaringBitmap();
			eb1.add(1);
			eb1.add(2);
			final ExtendedRoaringBitmap eb2 = new ExtendedRoaringBitmap();
			eb2.add(2);
			eb2.add(3);
			final PersistentRoaringBitmap ebResult = FastAggregation.naive_xor(Arrays.asList(b1, b2).iterator());
			assertTrue(ebResult.contains(1));
			assertFalse(ebResult.contains(2));
			assertTrue(ebResult.contains(3));
		}
	}

	/**
	 * Supplies mixed container-layout bitmap triples (array / bitmap / run in varying positions)
	 * used by the parameterized aggregation tests below.
	 */
	public static Stream<Arguments> bitmaps() {
		return Stream.of(
			Arguments.of(
				Arrays.asList(
					testCase().withBitmapAt(0).withArrayAt(1).withRunAt(2).build(),
					testCase().withBitmapAt(0).withArrayAt(1).withRunAt(2).build(),
					testCase().withBitmapAt(0).withArrayAt(1).withRunAt(2).build()
				)),
			Arguments.of(
				Arrays.asList(
					testCase().withBitmapAt(0).withRunAt(1).withArrayAt(2).build(),
					testCase().withBitmapAt(0).withRunAt(1).withArrayAt(2).build(),
					testCase().withBitmapAt(0).withRunAt(1).withArrayAt(2).build()
				)),
			Arguments.of(
				Arrays.asList(
					testCase().withArrayAt(0).withRunAt(1).withBitmapAt(2).build(),
					testCase().withArrayAt(0).withRunAt(1).withBitmapAt(2).build(),
					testCase().withArrayAt(0).withRunAt(1).withBitmapAt(2).build()
				)),
			Arguments.of(
				Arrays.asList(
					testCase().withBitmapAt(0).withArrayAt(1).withRunAt(2).build(),
					testCase().withBitmapAt(0).withArrayAt(3).withRunAt(4).build(),
					testCase().withBitmapAt(0).withArrayAt(1).withRunAt(2).build()
				)),
			Arguments.of(
				Arrays.asList(
					testCase().withArrayAt(0).withBitmapAt(1).withRunAt(2).build(),
					testCase().withRunAt(0).withArrayAt(1).withBitmapAt(2).build(),
					testCase().withBitmapAt(0).withRunAt(1).withArrayAt(2).build()
				)),
			Arguments.of(
				Arrays.asList(
					testCase().withBitmapAt(0).withArrayAt(1).withRunAt(2).build(),
					testCase().withBitmapAt(0).withArrayAt(2).withRunAt(4).build(),
					testCase().withBitmapAt(0).withArrayAt(1).withRunAt(2).build()
				)),
			Arguments.of(
				Arrays.asList(
					testCase().withArrayAt(0).withArrayAt(1).withArrayAt(2).build(),
					testCase().withBitmapAt(0).withBitmapAt(2).withBitmapAt(4).build(),
					testCase().withRunAt(0).withRunAt(1).withRunAt(2).build()
				)),
			Arguments.of(
				Arrays.asList(
					testCase().withArrayAt(0).withArrayAt(1).withArrayAt(2).build(),
					testCase().withBitmapAt(0).withBitmapAt(2).withArrayAt(4).build(),
					testCase().withRunAt(0).withRunAt(1).withArrayAt(2).build()
				)),
			Arguments.of(
				Arrays.asList(
					testCase().withArrayAt(0).withArrayAt(1).withBitmapAt(2).build(),
					testCase().withBitmapAt(0).withBitmapAt(2).withBitmapAt(4).build(),
					testCase().withRunAt(0).withRunAt(1).withBitmapAt(2).build()
				)),
			Arguments.of(
				Arrays.asList(
					testCase().withArrayAt(20).build(),
					testCase().withBitmapAt(0).withBitmapAt(1).withBitmapAt(4).build(),
					testCase().withRunAt(0).withRunAt(1).withBitmapAt(3).build()
				))
		);
	}

	// The parameterized tests below stay at the outer class level on purpose: they share the
	// `bitmaps()` @MethodSource factory, and JUnit resolves an unqualified method source in the
	// class that declares the test. Keeping them here guarantees reliable test discovery.

	@MethodSource("bitmaps")
	@ParameterizedTest(name = "testWorkShyAnd")
	public void testWorkShyAnd(List<PersistentRoaringBitmap> list) {
		PersistentRoaringBitmap[] bitmaps = list.toArray(new PersistentRoaringBitmap[0]);
		long[] buffer = new long[1024];
		PersistentRoaringBitmap result = FastAggregation.and(buffer, bitmaps);
		PersistentRoaringBitmap expected = FastAggregation.naive_and(bitmaps);
		assertEquals(expected, result);
		result = FastAggregation.and(bitmaps);
		assertEquals(expected, result);
		result = FastAggregation.workAndMemoryShyAnd(buffer, bitmaps);
		assertEquals(expected, result);
	}

	@MethodSource("bitmaps")
	@ParameterizedTest(name = "testAndCardinality")
	public void testAndCardinality(List<PersistentRoaringBitmap> list) {
		PersistentRoaringBitmap[] bitmaps = list.toArray(new PersistentRoaringBitmap[0]);
		for (int length = 0; length <= bitmaps.length; length++) {
			PersistentRoaringBitmap[] subset = Arrays.copyOf(bitmaps, length);
			PersistentRoaringBitmap and = FastAggregation.and(subset);
			int andCardinality = FastAggregation.andCardinality(subset);
			assertEquals(and.getCardinality(), andCardinality);
		}
	}

	@MethodSource("bitmaps")
	@ParameterizedTest(name = "testIntersects")
	public void testIntersects(List<PersistentRoaringBitmap> list) {
		PersistentRoaringBitmap[] bitmaps = list.toArray(new PersistentRoaringBitmap[0]);
		for (int length = 0; length <= bitmaps.length; length++) {
			PersistentRoaringBitmap[] subset = Arrays.copyOf(bitmaps, length);
			PersistentRoaringBitmap and = FastAggregation.and(subset);
			boolean intersects = FastAggregation.intersects(subset);
			assertEquals(!and.isEmpty(), intersects);
		}
	}

	@MethodSource("bitmaps")
	@ParameterizedTest(name = "testOrCardinality")
	public void testOrCardinality(List<PersistentRoaringBitmap> list) {
		PersistentRoaringBitmap[] bitmaps = list.toArray(new PersistentRoaringBitmap[0]);
		for (int length = 0; length <= bitmaps.length; length++) {
			PersistentRoaringBitmap[] subset = Arrays.copyOf(bitmaps, length);
			PersistentRoaringBitmap or = FastAggregation.or(subset);
			int orCardinality = FastAggregation.orCardinality(subset);
			assertEquals(or.getCardinality(), orCardinality);
		}
	}
}
