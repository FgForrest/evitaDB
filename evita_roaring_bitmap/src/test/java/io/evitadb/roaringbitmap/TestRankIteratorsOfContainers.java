package io.evitadb.roaringbitmap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Random;

/**
 * Regression tests for {@link PeekableCharRankIterator}, ported from the upstream RoaringBitmap
 * test suite. For every container type the iterator's {@code peekNextRank} must agree with
 * {@link Container#rank(char)} on {@code next}, {@code nextAsInt} and {@code advanceIfNeeded}
 * traversals; the overflow case additionally checks ranks when the whole 16-bit range is present.
 */
@DisplayName("Container rank iteration")
public class TestRankIteratorsOfContainers {
	private void testContainerRanksOnNext(Container c) {
		PeekableCharRankIterator iterator = c.getCharRankIterator();
		while (iterator.hasNext()) {
			char bit = iterator.peekNext();
			int rank = iterator.peekNextRank();

			assertEquals(c.rank(bit), rank);

			iterator.next();
		}
	}

	private void testContainerRanksOnNextAsInt(Container c) {
		PeekableCharRankIterator iterator = c.getCharRankIterator();
		while (iterator.hasNext()) {
			char bit = iterator.peekNext();
			int rank = iterator.peekNextRank();

			assertEquals(c.rank(bit), rank);

			iterator.nextAsInt();
		}
	}

	private void testContainerRanksOnAdvance(Container c, int advance) {
		PeekableCharRankIterator iterator = c.getCharRankIterator();
		char bit;
		while (iterator.hasNext()) {
			bit = iterator.peekNext();
			int rank = iterator.peekNextRank();

			assertEquals(c.rank(bit), rank);

			if (((bit) + advance < 65536)) {
				iterator.advanceIfNeeded((char) (bit + advance));
			} else {
				iterator.next();
			}
		}
	}

	private void testContainerIterators(Container container) {
		testContainerRanksOnNext(container);
		testContainerRanksOnNextAsInt(container);
		for (int j = 1; j <= 16; ++j) {
			testContainerRanksOnAdvance(container, j);
			testContainerRanksOnAdvance(container, j * 3);
			testContainerRanksOnAdvance(container, j * 5);
			testContainerRanksOnAdvance(container, j * 7);
			testContainerRanksOnAdvance(container, j * 11);
			testContainerRanksOnAdvance(container, j * 64);
			testContainerRanksOnAdvance(container, j * 128);
			testContainerRanksOnAdvance(container, j * 256);
			testContainerRanksOnAdvance(container, j * 512);
			testContainerRanksOnAdvance(container, j * 1024);
		}
	}

	private void fillRandom(Container container, Random rnd) {
		Container empty = container;

		for (int i = 0; i < 1024; ++i) {
			container.add((char) rnd.nextInt(1 << 10));
		}

		for (int i = 0; i < 1024; ++i) {
			container.add((char) (8192 + rnd.nextInt(1 << 10)));
		}

		for (int i = 0; i < 1024; ++i) {
			container.add((char) (16384 + rnd.nextInt(1 << 10)));
		}

		assertSame(empty, container, "bad test -- container was changed");
	}

	private void fillRange(Container container, int begin, int end) {
		Container empty = container;

		container.iadd(begin, end);

		assertSame(empty, container, "bad test -- container was changed");
	}

	@Nested
	@DisplayName("BitmapContainer rank iteration")
	class BitmapContainerRankIteration {

		@Test
		@DisplayName("Sparse bitmap yields correct ranks on next")
		public void testBitmapContainer1() {
			BitmapContainer container = new BitmapContainer();
			container.add((char) 123);
			container.add((char) 65535);

			testContainerRanksOnNext(container);
		}

		@Test
		@DisplayName("Randomly filled bitmap yields correct ranks across all iterators")
		public void testBitmapContainer2() {
			BitmapContainer container = new BitmapContainer();
			Random rnd = new Random(0);

			fillRandom(container, rnd);

			testContainerIterators(container);
		}

		@Test
		@DisplayName("Fully filled bitmap yields correct ranks across all iterators")
		public void testBitmapContainer3() {
			BitmapContainer container = new BitmapContainer();
			fillRange(container, 0, 65535);

			testContainerIterators(container);
		}

		@Test
		@DisplayName("Bitmap with several ranges yields correct ranks across all iterators")
		public void testBitmapContainer4() {
			BitmapContainer container = new BitmapContainer();
			fillRange(container, 1024, 2048);
			fillRange(container, 8192 + 7, 24576 - 7);
			fillRange(container, 65534, 65535);

			testContainerIterators(container);
		}
	}

	@Nested
	@DisplayName("ArrayContainer rank iteration")
	class ArrayContainerRankIteration {

		@Test
		@DisplayName("Single-element array yields correct ranks on next")
		public void testArrayContainer1() {
			ArrayContainer container = new ArrayContainer();
			container.add((char) 123);

			testContainerRanksOnNext(container);
		}

		@Test
		@DisplayName("Randomly filled array yields correct ranks across all iterators")
		public void testArrayContainer2() {
			ArrayContainer container = new ArrayContainer();
			Random rnd = new Random(0);

			fillRandom(container, rnd);

			testContainerIterators(container);
		}

		@Test
		@DisplayName("Array built from several disjoint ranges stays the same instance")
		public void testArrayContainer3() {
			ArrayContainer container = new ArrayContainer();
			fillRange(container, 0, 1024);
			fillRange(container, 2048, 4096);
			fillRange(container, 65535 - 7, 65535 - 5);
		}
	}

	@Nested
	@DisplayName("RunContainer rank iteration")
	class RunContainerRankIteration {

		@Test
		@DisplayName("Single-element run yields correct ranks across all iterators")
		public void testRunContainer1() {
			RunContainer container = new RunContainer();
			container.add((char) 123);
			testContainerIterators(container);
		}

		@Test
		@DisplayName("Randomly filled run yields correct ranks across all iterators")
		public void testRunContainer2() {
			RunContainer container = new RunContainer();
			Random rnd = new Random(0);

			fillRandom(container, rnd);
			testContainerIterators(container);
		}

		@Test
		@DisplayName("Run with several ranges yields correct ranks across all iterators")
		public void testRunContainer3() {
			RunContainer container = new RunContainer();
			fillRange(container, 0, 1024);

			fillRange(container, 1024 + 3, 1024 + 5);
			fillRange(container, 1024 + 30, 1024 + 37);
			fillRange(container, 65535 - 7, 65535 - 5);
			testContainerIterators(container);
		}
	}

	@Test
	@DisplayName("Rank equals value plus one when the entire range is present")
	public void testOverflow() {
		testContainerOverflow(new ArrayContainer(), false); // -- will be converted to BitmapContainer
		testContainerOverflow(new BitmapContainer(), true);
		testContainerOverflow(new RunContainer(), true);
	}

	private void testContainerOverflow(Container container, boolean checkSame) {
		Container c1 = container.iadd(0, 65536);

		if (checkSame) {
			assertSame(container, c1, "bad test -- container was changed");
		}

		PeekableCharRankIterator iterator = container.getCharRankIterator();
		while (iterator.hasNext()) {
			assertEquals((iterator.peekNext()) + 1, iterator.peekNextRank());
			iterator.next();
		}
	}

	/**
	 * rank(Character.MAX_VALUE) must equal getCardinality() for every container type. This is the
	 * path used by rangeCardinality when a query ends on a container boundary (upstream issue #844).
	 */
	@Test
	@DisplayName("Rank of the largest char equals the cardinality for every container type")
	public void rankOfMaxEqualsCardinality() {
		final Random rnd = new Random(42);

		final ArrayContainer ac = new ArrayContainer();
		fillRandom(ac, rnd);
		assertEquals(ac.getCardinality(), ac.rank(Character.MAX_VALUE));
		ac.add(Character.MAX_VALUE);
		assertEquals(ac.getCardinality(), ac.rank(Character.MAX_VALUE));

		final BitmapContainer bc = new BitmapContainer();
		fillRandom(bc, rnd);
		assertEquals(bc.getCardinality(), bc.rank(Character.MAX_VALUE));
		bc.add(Character.MAX_VALUE);
		assertEquals(bc.getCardinality(), bc.rank(Character.MAX_VALUE));

		// empty bitmap container
		assertEquals(0, new BitmapContainer().rank(Character.MAX_VALUE));

		// nearly full / full bitmap
		final BitmapContainer almostFull = new BitmapContainer();
		fillRange(almostFull, 0, 65535);
		assertEquals(almostFull.getCardinality(), almostFull.rank(Character.MAX_VALUE));
		final BitmapContainer full = new BitmapContainer();
		fillRange(full, 0, 65536);
		assertEquals(full.getCardinality(), full.rank(Character.MAX_VALUE));
		assertEquals(65536, full.rank(Character.MAX_VALUE));

		final RunContainer rc = new RunContainer();
		fillRandom(rc, rnd);
		assertEquals(rc.getCardinality(), rc.rank(Character.MAX_VALUE));
		fillRange(rc, 60000, 65536);
		assertEquals(rc.getCardinality(), rc.rank(Character.MAX_VALUE));

		// empty / single-point containers
		assertEquals(0, new ArrayContainer().rank(Character.MAX_VALUE));
		assertEquals(0, new RunContainer().rank(Character.MAX_VALUE));
		final ArrayContainer single = new ArrayContainer();
		single.add(Character.MAX_VALUE);
		assertEquals(1, single.rank(Character.MAX_VALUE));
	}

	/**
	 * A lazily-built bitmap container must answer rank(Character.MAX_VALUE) correctly **without** storing
	 * its cardinality. Upstream repairs the cardinality here; the vendored copy deliberately does not,
	 * because `-1` tells {@link BitmapContainer#repairAfterLazy()} that it still has to run and may demote
	 * the container (see `LazyCardinalityProtocolTest`).
	 */
	@Test
	@DisplayName("Rank of the largest char on a lazy bitmap container is correct and leaves the cardinality unknown")
	public void rankOfMaxOnLazyContainerKeepsCardinalityUnknown() {
		final BitmapContainer lazy = new BitmapContainer();
		fillRange(lazy, 0, 10000);
		final ArrayContainer other = new ArrayContainer();
		other.add((char) 20000);
		other.add(Character.MAX_VALUE);
		lazy.ilazyor(other);
		assertTrue(lazy.cardinality < 0, "precondition: lazy or invalidates cardinality");

		assertEquals(10002, lazy.rank(Character.MAX_VALUE));
		assertEquals(lazy.rank((char) 0xFFFE) + 1, lazy.rank(Character.MAX_VALUE));
		assertTrue(lazy.cardinality < 0, "rank must not stand in for repairAfterLazy");

		final Container repaired = lazy.repairAfterLazy();
		assertEquals(10002, repaired.getCardinality());
		assertEquals(10002, repaired.rank(Character.MAX_VALUE));
	}

	/**
	 * A sparse lazy result must still be demoted by repairAfterLazy after its rank was asked for, which is
	 * exactly what storing the cardinality inside rank would break.
	 */
	@Test
	@DisplayName("A rank query on a sparse lazy bitmap container does not stop repairAfterLazy demoting it")
	public void rankOfMaxOnSparseLazyContainerStillDemotes() {
		final BitmapContainer lazy = new BitmapContainer();
		final ArrayContainer few = new ArrayContainer();
		few.add((char) 1000);
		few.add((char) 2000);
		few.add((char) 3000);
		lazy.ilazyor(few);
		assertTrue(lazy.cardinality < 0, "precondition: lazy or invalidates cardinality");

		assertEquals(3, lazy.rank(Character.MAX_VALUE));

		final Container repaired = lazy.repairAfterLazy();
		assertTrue(repaired instanceof ArrayContainer, "a three-value chunk must be demoted to an array");
		assertEquals(3, repaired.getCardinality());
	}

	/** Full-container rangeCardinality must match getCardinality (upstream issue #844). */
	@Test
	@DisplayName("A range covering one whole chunk counts that chunk's cardinality")
	public void rangeCardinalityFullContainer() {
		final PersistentRoaringBitmap rb = new PersistentRoaringBitmap();
		// one sparse container (array)
		for (int i = 0; i < 100; i++) {
			rb.add(i * 3);
		}
		// one dense container (bitmap)
		rb.add(1L << 16, (1L << 16) + 40000);
		// one run-friendly container
		rb.add(2L << 16, (2L << 16) + 65536);

		final ContainerPointer p = rb.getContainerPointer();
		while (p.getContainer() != null) {
			final long key = p.key() & 0xFFFFL;
			final long start = key << 16;
			final long end = start + (1L << 16);
			assertEquals(
				p.getContainer().getCardinality(),
				rb.rangeCardinality(start, end),
				"full-container range for key " + key);
			p.advance();
		}
	}
}
