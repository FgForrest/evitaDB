package io.evitadb.roaringbitmap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static io.evitadb.roaringbitmap.ValidationRangeConsumer.Value.ABSENT;
import static io.evitadb.roaringbitmap.ValidationRangeConsumer.Value.PRESENT;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.NoSuchElementException;
import java.util.Random;
import java.util.stream.Stream;
import javax.annotation.Nonnull;

/**
 * Regression tests for {@link BitmapContainer}, ported from the upstream RoaringBitmap test
 * suite. They pin down the container's construction and representation, its set operations
 * (and / or / xor / andNot and their `orNot` / `lazyor` variants), cardinality bookkeeping,
 * containment checks against every container type, range/bit navigation
 * (`nextSetBit`, `nextValue`, `nextAbsentValue` and their reverse counterparts) and
 * bulk iteration through range consumers.
 */
@Execution(ExecutionMode.CONCURRENT)
@DisplayName("BitmapContainer")
public class TestBitmapContainer {

	@Nested
	@DisplayName("Construction and representation")
	class ConstructionAndRepresentation {

		@Test
		@DisplayName("toString lists every set value in ascending unsigned order")
		public void testToString() {
			final BitmapContainer bc2 = new BitmapContainer(5, 15);
			bc2.add((char) -19);
			bc2.add((char) -3);
			assertEquals("{5,6,7,8,9,10,11,12,13,14,65517,65533}", bc2.toString());
		}

		@Test
		@DisplayName("Range constructor, add, iadd, remove and not agree across 4096-step ranges")
		public void runConstructorForBitmap() {
			System.out.println("runConstructorForBitmap");
			for (int start = 0; start <= (1 << 16); start += 4096) {
				for (int end = start; end <= (1 << 16); end += 4096) {
					final BitmapContainer bc = new BitmapContainer(start, end);
					final BitmapContainer bc2 = new BitmapContainer();
					final BitmapContainer bc3 = (BitmapContainer) bc2.add(start, end);
					bc2.iadd(start, end);
					assertEquals(bc.getCardinality(), end - start);
					assertEquals(bc2.getCardinality(), end - start);
					assertEquals(bc, bc2);
					assertEquals(bc, bc3);
					assertEquals(0, bc2.remove(start, end).getCardinality());
					assertEquals(bc2.getCardinality(), end - start);
					assertEquals(0, bc2.not(start, end).getCardinality());
				}
			}
		}

		@Test
		@DisplayName("Range constructor, add, iadd, remove and not agree across 63-step ranges")
		public void runConstructorForBitmap2() {
			System.out.println("runConstructorForBitmap2");
			for (int start = 0; start <= (1 << 16); start += 63) {
				for (int end = start; end <= (1 << 16); end += 63) {
					final BitmapContainer bc = new BitmapContainer(start, end);
					final BitmapContainer bc2 = new BitmapContainer();
					final BitmapContainer bc3 = (BitmapContainer) bc2.add(start, end);
					bc2.iadd(start, end);
					assertEquals(bc.getCardinality(), end - start);
					assertEquals(bc2.getCardinality(), end - start);
					assertEquals(bc, bc2);
					assertEquals(bc, bc3);
					assertEquals(0, bc2.remove(start, end).getCardinality());
					assertEquals(bc2.getCardinality(), end - start);
					assertEquals(0, bc2.not(start, end).getCardinality());
				}
			}
		}

		@Test
		@DisplayName("numberOfRunsLowerBound never exceeds the exact run count across densities")
		public void numberOfRunsLowerBound1() {
			System.out.println("numberOfRunsLowerBound1");
			final Random r = new Random(12345);

			for (double density = 0.001; density < 0.8; density *= 2) {

				final ArrayList<Integer> values = new ArrayList<Integer>();
				for (int i = 0; i < 65536; ++i) {
					if (r.nextDouble() < density) {
						values.add(i);
					}
				}
				final Integer[] positions = values.toArray(new Integer[0]);
				final BitmapContainer bc = new BitmapContainer();

				for (final int position : positions) {
					bc.add((char) position);
				}

				assertTrue(bc.numberOfRunsLowerBound(1) > 1);
				assertTrue(bc.numberOfRunsLowerBound(100) <= bc.numberOfRuns());

				// a big parameter like 100000 ensures that the full lower bound
				// is taken

				assertTrue(bc.numberOfRunsLowerBound(100000) <= bc.numberOfRuns());
				assertEquals(
					bc.numberOfRuns(), bc.numberOfRunsLowerBound(100000) + bc.numberOfRunsAdjustment());

				/*
				 * the unrolled guys are commented out, did not help performance and slated for removal
				 * soon...
				 *
				 * assertTrue(bc.numberOfRunsLowerBoundUnrolled2(1) > 1);
				 * assertTrue(bc.numberOfRunsLowerBoundUnrolled2(100) <= bc.numberOfRuns());
				 *
				 * assertEquals(bc.numberOfRunsLowerBound(100000),
				 * bc.numberOfRunsLowerBoundUnrolled2(100000));
				 */
			}
		}
	}

	@Nested
	@DisplayName("Adding and removing values")
	class AddAndRemove {

		@Test
		@DisplayName("add rejects a reversed range")
		public void addInvalidRange() {
			assertThrows(
				IllegalArgumentException.class,
				() -> {
					final Container bc = new BitmapContainer();
					bc.add(13, 1);
				}
			);
		}

		@Test
		@DisplayName("iadd rejects a reversed range")
		public void iaddInvalidRange() {
			assertThrows(
				IllegalArgumentException.class,
				() -> {
					final Container bc = new BitmapContainer();
					bc.iadd(13, 1);
				}
			);
		}

		@Test
		@DisplayName("In-place remove of an empty range leaves the container empty")
		public void iremoveEmptyRange() {
			Container bc = new BitmapContainer();
			bc = bc.iremove(1, 1);
			assertEquals(0, bc.getCardinality());
		}

		@Test
		@DisplayName("iremove rejects a reversed range")
		public void iremoveInvalidRange() {
			assertThrows(
				IllegalArgumentException.class,
				() -> {
					final Container ac = new BitmapContainer();
					ac.iremove(13, 1);
				}
			);
		}

		@Test
		@DisplayName("In-place remove deletes a trailing range")
		public void iremove() {
			Container bc = new BitmapContainer();
			bc = bc.add(1, 10);
			bc = bc.iremove(5, 10);
			assertEquals(4, bc.getCardinality());
			for (int i = 1; i < 5; i++) {
				assertTrue(bc.contains((char) i));
			}
		}

		@Test
		@DisplayName("In-place remove deletes a leading range from a large container")
		public void iremove2() {
			Container bc = new BitmapContainer();
			bc = bc.add(1, 8092);
			bc = bc.iremove(1, 10);
			assertEquals(8082, bc.getCardinality());
			for (int i = 10; i < 8092; i++) {
				assertTrue(bc.contains((char) i));
			}
		}

		@Test
		@DisplayName("remove deletes a leading range from a large container")
		public void remove() {
			Container bc = new BitmapContainer();
			bc = bc.add(1, 8092);
			bc = bc.remove(1, 10);
			assertEquals(8082, bc.getCardinality());
			for (int i = 10; i < 8092; i++) {
				assertTrue(bc.contains((char) i));
			}
		}

		@Test
		@DisplayName("remove rejects a reversed range")
		public void removeInvalidRange() {
			assertThrows(
				IllegalArgumentException.class,
				() -> {
					final Container ac = new BitmapContainer();
					ac.remove(13, 1);
				}
			);
		}

		@Test
		@DisplayName("select throws when the position exceeds the cardinality")
		public void selectInvalidPosition() {
			assertThrows(
				IllegalArgumentException.class,
				() -> {
					Container bc = new BitmapContainer();
					bc = bc.add(1, 13);
					bc.select(100);
				}
			);
		}
	}

	@Nested
	@DisplayName("Intersection: and / andNot")
	class IntersectionOperations {

		@Test
		@DisplayName("In-place and keeps only the bits shared with the other bitmap")
		public void testAND() {
			BitmapContainer bc = new BitmapContainer(100, 10000);
			final BitmapContainer bc2 = new BitmapContainer();
			final BitmapContainer bc3 = new BitmapContainer();

			for (int i = 100; i < 10000; ++i) {
				if ((i % 2) == 0) {
					bc2.add((char) i);
				} else {
					bc3.add((char) i);
				}
			}
			bc = (BitmapContainer) bc.iand(bc2);
			assertEquals(bc, bc2);
			assertEquals(0, bc.iand(bc3).getCardinality());
		}

		@Test
		@DisplayName("In-place and with a run container keeps only the overlapping bits")
		public void iandRun() {
			Container bc = new BitmapContainer();
			bc = bc.add(0, 8092);
			Container rc = new RunContainer();
			rc = rc.add(1, 10);
			bc = bc.iand(rc);
			assertEquals(9, bc.getCardinality());
			for (int i = 1; i < 10; i++) {
				assertTrue(bc.contains((char) i));
			}
		}

		@Test
		@DisplayName("In-place andNot removes the subtracted bits and preserves equality and hashCode")
		public void testANDNOT() {
			BitmapContainer bc = new BitmapContainer(100, 10000);
			final BitmapContainer bc2 = new BitmapContainer();
			final BitmapContainer bc3 = new BitmapContainer();

			for (int i = 100; i < 10000; ++i) {
				if ((i % 2) == 0) {
					bc2.add((char) i);
				} else {
					bc3.add((char) i);
				}
			}
			final RunContainer rc = new RunContainer();
			rc.iadd(0, 1 << 16);
			bc = (BitmapContainer) bc.iand(rc);
			bc = (BitmapContainer) bc.iandNot(bc2);
			assertEquals(bc, bc3);
			assertEquals(bc.hashCode(), bc3.hashCode());
			assertEquals(0, bc.iandNot(bc3).getCardinality());
			bc3.clear();
			assertEquals(0, bc3.getCardinality());
		}
	}

	@Nested
	@DisplayName("Union: or / lazyor / orNot / iorNot")
	class UnionOperations {

		@Test
		@DisplayName("In-place or merges two disjoint halves back into the original")
		public void testOR() {
			final BitmapContainer bc = new BitmapContainer(100, 10000);
			BitmapContainer bc2 = new BitmapContainer();
			final BitmapContainer bc3 = new BitmapContainer();

			for (int i = 100; i < 10000; ++i) {
				if ((i % 2) == 0) {
					bc2.add((char) i);
				} else {
					bc3.add((char) i);
				}
			}
			bc2 = (BitmapContainer) bc2.ior(bc3);
			assertEquals(bc, bc2);
			bc2 = (BitmapContainer) bc2.ior(bc);
			assertEquals(bc, bc2);
			final RunContainer rc = new RunContainer();
			rc.iadd(0, 1 << 16);
			assertEquals(0, bc.iandNot(rc).getCardinality());
		}

		@Test
		@DisplayName("In-place or with a run container unions the overlapping ranges")
		public void iorRun() {
			Container bc = new BitmapContainer();
			bc = bc.add(1, 5);
			Container rc = new RunContainer();
			rc = rc.add(4, 10);
			bc.ior(rc);
			assertEquals(9, bc.getCardinality());
			for (int i = 1; i < 10; i++) {
				assertTrue(bc.contains((char) i));
			}
		}

		@Test
		@DisplayName("or of two full halves yields a full RunContainer")
		public void orFullToRunContainer() {
			final BitmapContainer bc = new BitmapContainer(0, 1 << 15);
			final BitmapContainer half = new BitmapContainer(1 << 15, 1 << 16);
			final Container result = bc.or(half);
			assertEquals(1 << 16, result.getCardinality());
			assertTrue(result instanceof RunContainer);
		}

		@Test
		@DisplayName("or with a completing ArrayContainer half yields a full RunContainer")
		public void orFullToRunContainer2() {
			final BitmapContainer bc = new BitmapContainer(0, 1 << 15);
			final ArrayContainer half = new ArrayContainer(1 << 15, 1 << 16);
			final Container result = bc.or(half);
			assertEquals(1 << 16, result.getCardinality());
			assertTrue(result instanceof RunContainer);
		}

		@Test
		@DisplayName("or and ior of overlapping halves both yield a full RunContainer")
		public void orFullToRunContainer3() {
			final BitmapContainer bc = new BitmapContainer(0, 1 << 15);
			final BitmapContainer bc2 = new BitmapContainer(3210, 1 << 16);
			final Container result = bc.or(bc2);
			final Container iresult = bc.ior(bc2);
			assertEquals(1 << 16, result.getCardinality());
			assertEquals(1 << 16, iresult.getCardinality());
			assertTrue(result instanceof RunContainer);
			assertTrue(iresult instanceof RunContainer);
		}

		@Test
		@DisplayName("ior with a completing run-of-ones range yields a full RunContainer")
		public void orFullToRunContainer4() {
			final BitmapContainer bc = new BitmapContainer(0, 1 << 15);
			final Container bc2 = Container.rangeOfOnes(3210, 1 << 16);
			final Container iresult = bc.ior(bc2);
			assertEquals(1 << 16, iresult.getCardinality());
			assertTrue(iresult instanceof RunContainer);
		}

		@Test
		@DisplayName("lazyor and ilazyor of full bitmap halves repair to a full RunContainer")
		public void testLazyORFull() {
			final BitmapContainer bc = new BitmapContainer(0, 1 << 15);
			final BitmapContainer bc2 = new BitmapContainer(3210, 1 << 16);
			final Container result = bc.lazyor(bc2);
			final Container iresult = bc.ilazyor(bc2);
			assertEquals(-1, result.getCardinality());
			assertEquals(-1, iresult.getCardinality());
			final Container repaired = result.repairAfterLazy();
			final Container irepaired = iresult.repairAfterLazy();
			assertEquals(1 << 16, repaired.getCardinality());
			assertEquals(1 << 16, irepaired.getCardinality());
			assertTrue(repaired instanceof RunContainer);
			assertTrue(irepaired instanceof RunContainer);
		}

		@Test
		@DisplayName("lazyor and ilazyor with a full ArrayContainer repair to a full RunContainer")
		public void testLazyORFull2() {
			final BitmapContainer bc = new BitmapContainer((1 << 10) - 200, 1 << 16);
			final ArrayContainer ac = new ArrayContainer(0, 1 << 10);
			final Container result = bc.lazyor(ac);
			final Container iresult = bc.ilazyor(ac);
			assertEquals(-1, result.getCardinality());
			assertEquals(-1, iresult.getCardinality());
			final Container repaired = result.repairAfterLazy();
			final Container irepaired = iresult.repairAfterLazy();
			assertEquals(1 << 16, repaired.getCardinality());
			assertEquals(1 << 16, irepaired.getCardinality());
			assertTrue(repaired instanceof RunContainer);
			assertTrue(irepaired instanceof RunContainer);
		}

		@Test
		@DisplayName("lazyor and ilazyor with a full RunContainer repair to a full RunContainer")
		public void testLazyORFull3() {
			final BitmapContainer bc = new BitmapContainer(0, 1 << 15);
			final Container rc = Container.rangeOfOnes(1 << 15, 1 << 16);
			final Container result = bc.lazyor((RunContainer) rc);
			final Container iresult = bc.ilazyor((RunContainer) rc);
			assertEquals(-1, result.getCardinality());
			assertEquals(-1, iresult.getCardinality());
			final Container repaired = result.repairAfterLazy();
			final Container irepaired = iresult.repairAfterLazy();
			assertEquals(1 << 16, repaired.getCardinality());
			assertEquals(1 << 16, irepaired.getCardinality());
			assertTrue(repaired instanceof RunContainer);
			assertTrue(irepaired instanceof RunContainer);
		}

		@Test
		@DisplayName("In-place orNot with an ArrayContainer fills the complement up to the bound")
		public void iorNot() {
			Container rc1 = new BitmapContainer();
			final Container rc2 = new ArrayContainer();

			rc1.iadd(257, 258);
			rc2.iadd(128, 256);
			rc1 = rc1.iorNot(rc2, 258);
			assertEquals(130, rc1.getCardinality());

			final PeekableCharIterator iterator = rc1.getCharIterator();
			for (int i = 0; i < 128; i++) {
				assertTrue(iterator.hasNext());
				assertEquals(i, iterator.next());
			}
			assertTrue(iterator.hasNext());
			assertEquals(256, iterator.next());

			assertTrue(iterator.hasNext());
			assertEquals(257, iterator.next());

			assertFalse(iterator.hasNext());
		}

		@Test
		@DisplayName("In-place orNot with a gapped ArrayContainer fills the complement")
		public void iorNot2() {
			Container rc1 = new BitmapContainer();
			final Container rc2 = new ArrayContainer();
			rc2.iadd(128, 256).iadd(257, 260);
			rc1 = rc1.iorNot(rc2, 261);
			assertEquals(130, rc1.getCardinality());

			final PeekableCharIterator iterator = rc1.getCharIterator();
			for (int i = 0; i < 128; i++) {
				assertTrue(iterator.hasNext());
				assertEquals(i, iterator.next());
			}
			assertTrue(iterator.hasNext());
			assertEquals(256, iterator.next());

			assertTrue(iterator.hasNext());
			assertEquals(260, iterator.next());

			assertFalse(iterator.hasNext());
		}

		@Test
		@DisplayName("In-place orNot with a BitmapContainer fills the complement up to the bound")
		public void iorNot3() {
			Container rc1 = new BitmapContainer();
			final Container rc2 = new BitmapContainer();

			rc1.iadd(257, 258);
			rc2.iadd(128, 256);
			rc1 = rc1.iorNot(rc2, 258);
			assertEquals(130, rc1.getCardinality());

			final PeekableCharIterator iterator = rc1.getCharIterator();
			for (int i = 0; i < 128; i++) {
				assertTrue(iterator.hasNext());
				assertEquals(i, iterator.next());
			}
			assertTrue(iterator.hasNext());
			assertEquals(256, iterator.next());

			assertTrue(iterator.hasNext());
			assertEquals(257, iterator.next());

			assertFalse(iterator.hasNext());
		}

		@Test
		@DisplayName("In-place orNot with a RunContainer fills the complement up to the bound")
		public void iorNot4() {
			Container rc1 = new BitmapContainer();
			final Container rc2 = new RunContainer();

			rc1.iadd(257, 258);
			rc2.iadd(128, 256);
			rc1 = rc1.iorNot(rc2, 258);
			assertEquals(130, rc1.getCardinality());

			final PeekableCharIterator iterator = rc1.getCharIterator();
			for (int i = 0; i < 128; i++) {
				assertTrue(iterator.hasNext());
				assertEquals(i, iterator.next());
			}
			assertTrue(iterator.hasNext());
			assertEquals(256, iterator.next());

			assertTrue(iterator.hasNext());
			assertEquals(257, iterator.next());

			assertFalse(iterator.hasNext());
		}

		@Test
		@DisplayName("orNot fills the complement for array, bitmap and run operands")
		public void orNot() {
			final Container rc1 = new BitmapContainer();

			{
				final Container rc2 = new ArrayContainer();
				rc2.iadd(128, 256);
				final Container res = rc1.orNot(rc2, 257);
				assertEquals(129, res.getCardinality());

				final PeekableCharIterator iterator = res.getCharIterator();
				for (int i = 0; i < 128; i++) {
					assertTrue(iterator.hasNext());
					assertEquals(i, iterator.next());
				}
				assertTrue(iterator.hasNext());
				assertEquals(256, iterator.next());

				assertFalse(iterator.hasNext());
			}

			{
				final Container rc2 = new BitmapContainer();
				rc2.iadd(128, 256);
				final Container res = rc1.orNot(rc2, 257);
				assertEquals(129, res.getCardinality());

				final PeekableCharIterator iterator = res.getCharIterator();
				for (int i = 0; i < 128; i++) {
					assertTrue(iterator.hasNext());
					assertEquals(i, iterator.next());
				}
				assertTrue(iterator.hasNext());
				assertEquals(256, iterator.next());

				assertFalse(iterator.hasNext());
			}

			{
				final Container rc2 = new RunContainer();
				rc2.iadd(128, 256);
				final Container res = rc1.orNot(rc2, 257);
				assertEquals(129, res.getCardinality());

				final PeekableCharIterator iterator = res.getCharIterator();
				for (int i = 0; i < 128; i++) {
					assertTrue(iterator.hasNext());
					assertEquals(i, iterator.next());
				}
				assertTrue(iterator.hasNext());
				assertEquals(256, iterator.next());

				assertFalse(iterator.hasNext());
			}
		}

		@Test
		@DisplayName("orNot with a gapped ArrayContainer fills the complement")
		public void orNot2() {
			Container rc1 = new BitmapContainer();
			final Container rc2 = new ArrayContainer();
			rc2.iadd(128, 256).iadd(257, 260);
			rc1 = rc1.orNot(rc2, 261);
			assertEquals(130, rc1.getCardinality());

			final PeekableCharIterator iterator = rc1.getCharIterator();
			for (int i = 0; i < 128; i++) {
				assertTrue(iterator.hasNext());
				assertEquals(i, iterator.next());
			}
			assertTrue(iterator.hasNext());
			assertEquals(256, iterator.next());

			assertTrue(iterator.hasNext());
			assertEquals(260, iterator.next());

			assertFalse(iterator.hasNext());
		}
	}

	@Nested
	@DisplayName("Symmetric difference: xor / ixor")
	class SymmetricDifferenceOperations {

		@Test
		@DisplayName("In-place xor of complementary halves cancels to an empty container")
		public void testXOR() {
			BitmapContainer bc = new BitmapContainer(100, 10000);
			final BitmapContainer bc2 = new BitmapContainer();
			final BitmapContainer bc3 = new BitmapContainer();

			for (int i = 100; i < 10000; ++i) {
				if ((i % 2) == 0) {
					bc2.add((char) i);
				} else {
					bc3.add((char) i);
				}
			}
			bc = (BitmapContainer) bc.ixor(bc2);
			assertEquals(0, bc.ixor(bc3).getCardinality());
		}

		@Test
		@DisplayName("In-place xor with a run container toggles the overlapping bits")
		public void ixorRun() {
			Container bc = new BitmapContainer();
			bc = bc.add(1, 10);
			Container rc = new RunContainer();
			rc = rc.add(5, 15);
			bc = bc.ixor(rc);
			assertEquals(9, bc.getCardinality());
			for (int i = 1; i < 5; i++) {
				assertTrue(bc.contains((char) i));
			}
			for (int i = 10; i < 15; i++) {
				assertTrue(bc.contains((char) i));
			}
		}

		@Test
		@DisplayName("In-place xor with a leading run container clears the overlapped prefix")
		public void ixorRun2() {
			Container bc = new BitmapContainer();
			bc = bc.add(1, 8092);
			Container rc = new RunContainer();
			rc = rc.add(1, 10);
			bc = bc.ixor(rc);
			assertEquals(8082, bc.getCardinality());
			for (int i = 10; i < 8092; i++) {
				assertTrue(bc.contains((char) i));
			}
		}
	}

	@Nested
	@DisplayName("Cardinality after range operations")
	class RangeCardinality {

		@Test
		@DisplayName("add of a range updates the cardinality")
		public void testRangeCardinality() {
			BitmapContainer bc = generateContainer((char) 100, (char) 10000, 5);
			bc = (BitmapContainer) bc.add(200, 2000);
			assertEquals(8280, bc.cardinality);
		}

		@Test
		@DisplayName("iadd of a range updates the cardinality")
		public void testRangeCardinality2() {
			final BitmapContainer bc = generateContainer((char) 100, (char) 10000, 5);
			bc.iadd(200, 2000);
			assertEquals(8280, bc.cardinality);
		}

		@Test
		@DisplayName("ior with a run container updates the cardinality")
		public void testRangeCardinality3() {
			final BitmapContainer bc = generateContainer((char) 100, (char) 10000, 5);
			final RunContainer rc = new RunContainer(new char[]{7, 300, 400, 900, 1400, 2200}, 3);
			bc.ior(rc);
			assertEquals(8677, bc.cardinality);
		}

		@Test
		@DisplayName("andNot with a run container updates the cardinality")
		public void testRangeCardinality4() {
			BitmapContainer bc = generateContainer((char) 100, (char) 10000, 5);
			final RunContainer rc = new RunContainer(new char[]{7, 300, 400, 900, 1400, 2200}, 3);
			bc = (BitmapContainer) bc.andNot(rc);
			assertEquals(5274, bc.cardinality);
		}

		@Test
		@DisplayName("iandNot with a run container updates the cardinality")
		public void testRangeCardinality5() {
			final BitmapContainer bc = generateContainer((char) 100, (char) 10000, 5);
			final RunContainer rc = new RunContainer(new char[]{7, 300, 400, 900, 1400, 2200}, 3);
			bc.iandNot(rc);
			assertEquals(5274, bc.cardinality);
		}

		@Test
		@DisplayName("iand with a run container updates the cardinality")
		public void testRangeCardinality6() {
			BitmapContainer bc = generateContainer((char) 100, (char) 10000, 5);
			final RunContainer rc = new RunContainer(new char[]{7, 300, 400, 900, 1400, 5200}, 3);
			bc = (BitmapContainer) bc.iand(rc);
			assertEquals(5046, bc.cardinality);
		}

		@Test
		@DisplayName("ixor with a run container updates the cardinality")
		public void testRangeCardinality7() {
			final BitmapContainer bc = generateContainer((char) 100, (char) 10000, 5);
			final RunContainer rc = new RunContainer(new char[]{7, 300, 400, 900, 1400, 2200}, 3);
			bc.ixor(rc);
			assertEquals(6031, bc.cardinality);
		}
	}

	@Nested
	@DisplayName("contains(Container) across container types")
	class ContainsContainer {

		@Test
		@DisplayName("empty bitmap contains an empty bitmap")
		public void testContainsBitmapContainer_EmptyContainsEmpty() {
			final Container bc = new BitmapContainer();
			final Container subset = new BitmapContainer();
			assertTrue(bc.contains(subset));
		}

		@Test
		@DisplayName("bitmap contains a proper bitmap subset")
		public void testContainsBitmapContainer_IncludeProperSubset() {
			final Container bc = new BitmapContainer().add(0, 10);
			final Container subset = new BitmapContainer().add(0, 9);
			assertTrue(bc.contains(subset));
		}

		@Test
		@DisplayName("bitmap contains an equal bitmap")
		public void testContainsBitmapContainer_IncludeSelf() {
			final Container bc = new BitmapContainer().add(0, 10);
			final Container subset = new BitmapContainer().add(0, 10);
			assertTrue(bc.contains(subset));
		}

		@Test
		@DisplayName("bitmap does not contain a bitmap superset")
		public void testContainsBitmapContainer_ExcludeSuperSet() {
			final Container bc = new BitmapContainer().add(0, 10);
			final Container superset = new BitmapContainer().add(0, 20);
			assertFalse(bc.contains(superset));
		}

		@Test
		@DisplayName("bitmap contains a run subset that starts later")
		public void testContainsBitmapContainer_IncludeProperSubsetDifferentStart() {
			final Container bc = new BitmapContainer().add(0, 10);
			final Container subset = new RunContainer().add(2, 9);
			assertTrue(bc.contains(subset));
		}

		@Test
		@DisplayName("bitmap does not contain a shifted bitmap")
		public void testContainsBitmapContainer_ExcludeShiftedSet() {
			final Container bc = new BitmapContainer().add(0, 10);
			final Container shifted = new BitmapContainer().add(2, 12);
			assertFalse(bc.contains(shifted));
		}

		@Test
		@DisplayName("disjoint bitmaps do not contain each other")
		public void testContainsBitmapContainer_ExcludeDisJointSet() {
			final Container bc = new BitmapContainer().add(0, 10);
			final Container disjoint = new BitmapContainer().add(20, 40);
			assertFalse(bc.contains(disjoint));
			assertFalse(disjoint.contains(bc));
		}

		@Test
		@DisplayName("empty bitmap contains an empty container")
		public void testContainsRunContainer_EmptyContainsEmpty() {
			final Container bc = new BitmapContainer();
			final Container subset = new BitmapContainer();
			assertTrue(bc.contains(subset));
		}

		@Test
		@DisplayName("bitmap contains a proper run subset")
		public void testContainsRunContainer_IncludeProperSubset() {
			final Container bc = new BitmapContainer().add(0, 10);
			final Container subset = new RunContainer().add(0, 9);
			assertTrue(bc.contains(subset));
		}

		@Test
		@DisplayName("bitmap contains an equal run")
		public void testContainsRunContainer_IncludeSelf() {
			final Container bc = new BitmapContainer().add(0, 10);
			final Container subset = new RunContainer().add(0, 10);
			assertTrue(bc.contains(subset));
		}

		@Test
		@DisplayName("bitmap does not contain a run superset")
		public void testContainsRunContainer_ExcludeSuperSet() {
			final Container bc = new BitmapContainer().add(0, 10);
			final Container superset = new RunContainer().add(0, 20);
			assertFalse(bc.contains(superset));
		}

		@Test
		@DisplayName("bitmap contains a run subset that starts later")
		public void testContainsRunContainer_IncludeProperSubsetDifferentStart() {
			final Container bc = new BitmapContainer().add(0, 10);
			final Container subset = new RunContainer().add(2, 9);
			assertTrue(bc.contains(subset));
		}

		@Test
		@DisplayName("bitmap does not contain a shifted run")
		public void testContainsRunContainer_ExcludeShiftedSet() {
			final Container bc = new BitmapContainer().add(0, 10);
			final Container shifted = new RunContainer().add(2, 12);
			assertFalse(bc.contains(shifted));
		}

		@Test
		@DisplayName("disjoint bitmap and run do not contain each other")
		public void testContainsRunContainer_ExcludeDisJointSet() {
			final Container bc = new BitmapContainer().add(0, 10);
			final Container disjoint = new RunContainer().add(20, 40);
			assertFalse(bc.contains(disjoint));
			assertFalse(disjoint.contains(bc));
		}

		@Test
		@DisplayName("empty bitmap contains an empty array container")
		public void testContainsArrayContainer_EmptyContainsEmpty() {
			final Container bc = new BitmapContainer();
			final Container subset = new ArrayContainer();
			assertTrue(bc.contains(subset));
		}

		@Test
		@DisplayName("bitmap contains a proper array subset")
		public void testContainsArrayContainer_IncludeProperSubset() {
			final Container bc = new BitmapContainer().add(0, 10);
			final Container subset = new ArrayContainer().add(0, 9);
			assertTrue(bc.contains(subset));
		}

		@Test
		@DisplayName("bitmap contains an equal array")
		public void testContainsArrayContainer_IncludeSelf() {
			final Container bc = new BitmapContainer().add(0, 10);
			final Container subset = new ArrayContainer().add(0, 10);
			assertTrue(bc.contains(subset));
		}

		@Test
		@DisplayName("bitmap does not contain an array superset")
		public void testContainsArrayContainer_ExcludeSuperSet() {
			final Container bc = new BitmapContainer().add(0, 10);
			final Container superset = new ArrayContainer().add(0, 20);
			assertFalse(bc.contains(superset));
		}

		@Test
		@DisplayName("bitmap contains an array subset that starts later")
		public void testContainsArrayContainer_IncludeProperSubsetDifferentStart() {
			final Container bc = new BitmapContainer().add(0, 10);
			final Container subset = new ArrayContainer().add(2, 9);
			assertTrue(bc.contains(subset));
		}

		@Test
		@DisplayName("bitmap does not contain a shifted array")
		public void testContainsArrayContainer_ExcludeShiftedSet() {
			final Container bc = new BitmapContainer().add(0, 10);
			final Container shifted = new ArrayContainer().add(2, 12);
			assertFalse(bc.contains(shifted));
		}

		@Test
		@DisplayName("disjoint bitmap and array do not contain each other")
		public void testContainsArrayContainer_ExcludeDisJointSet() {
			final Container bc = new BitmapContainer().add(0, 10);
			final Container disjoint = new ArrayContainer().add(20, 40);
			assertFalse(bc.contains(disjoint));
			assertFalse(disjoint.contains(bc));
		}
	}

	@Nested
	@DisplayName("contains(range)")
	class ContainsRange {

		@Test
		@DisplayName("contains(range) within a single word")
		public void testContainsRangeSingleWord() {
			final long[] bitmap = evenBits();
			bitmap[10] = -1L;
			final int cardinality = 32 + 1 << 15;
			final BitmapContainer container = new BitmapContainer(bitmap, cardinality);
			assertTrue(container.contains(0, 1));
			assertTrue(container.contains(64 * 10, 64 * 11));
			assertFalse(container.contains(64 * 10, 2 + 64 * 11));
			assertTrue(container.contains(1 + 64 * 10, (64 * 11) - 1));
		}

		@Test
		@DisplayName("contains(range) spanning multiple words")
		public void testContainsRangeMultiWord() {
			final long[] bitmap = evenBits();
			bitmap[10] = -1L;
			bitmap[11] = -1L;
			bitmap[12] |= ((1L << 32) - 1);
			final int cardinality = 32 + 32 + 16 + 1 << 15;
			final BitmapContainer container = new BitmapContainer(bitmap, cardinality);
			assertTrue(container.contains(0, 1));
			assertFalse(container.contains(64 * 10, (64 * 13) - 30));
			assertTrue(container.contains(64 * 10, (64 * 13) - 31));
			assertTrue(container.contains(1 + 64 * 10, (64 * 13) - 32));
			assertTrue(container.contains(64 * 10, 64 * 12));
			assertFalse(container.contains(64 * 10, 2 + 64 * 13));
		}

		@Test
		@DisplayName("contains(range) within a partially filled word")
		public void testContainsRangeSubWord() {
			final long[] bitmap = evenBits();
			bitmap[bitmap.length - 1] = ~((1L << 63) | 1L);
			final int cardinality = 32 + 32 + 16 + 1 << 15;
			final BitmapContainer container = new BitmapContainer(bitmap, cardinality);
			assertFalse(container.contains(64 * 1023, 64 * 1024));
			assertFalse(container.contains(64 * 1023, 64 * 1024 - 1));
			assertTrue(container.contains(1 + 64 * 1023, 64 * 1024 - 1));
			assertTrue(container.contains(1 + 64 * 1023, 64 * 1024 - 2));
			assertFalse(container.contains(64 * 1023, 64 * 1023 + 2));
			assertTrue(container.contains(64 * 1023 + 1, 64 * 1023 + 2));
		}
	}

	@Nested
	@DisplayName("intersects(range)")
	class IntersectsRange {

		@Test
		@DisplayName("intersects reports overlap with ranges touching the low bits")
		public void testIntersectsWithRange() {
			final Container container = new BitmapContainer().add(0, 10);
			assertTrue(container.intersects(0, 1));
			assertTrue(container.intersects(0, 101));
			assertTrue(container.intersects(0, 1 << 16));
			assertFalse(container.intersects(11, lower16Bits(-1)));
		}

		@ParameterizedTest
		@MethodSource("io.evitadb.roaringbitmap.TestBitmapContainer#bitmapsForRangeIntersection")
		@DisplayName("intersects respects the exclusive upper bound")
		public void testIntersectsWithRangeUpperBoundaries(
			final Container container, final int min, final int sup, final boolean intersects) {
			assertEquals(intersects, container.intersects(min, sup));
		}

		@Test
		@DisplayName("intersects scans multiple populated ranges")
		public void testIntersectsWithRangeHitScan() {
			final Container container =
				new BitmapContainer().add(0, 10).add(500, 512).add(lower16Bits(-50), lower16Bits(-10));
			assertTrue(container.intersects(0, 1));
			assertTrue(container.intersects(0, 101));
			assertTrue(container.intersects(0, 1 << 16));
			assertTrue(container.intersects(11, 1 << 16));
			assertTrue(container.intersects(501, 511));
		}

		@Test
		@DisplayName("intersects treats range bounds as unsigned")
		public void testIntersectsWithRangeUnsigned() {
			final Container container = new BitmapContainer().add(lower16Bits(-50), lower16Bits(-10));
			assertFalse(container.intersects(0, 1));
			assertTrue(container.intersects(0, lower16Bits(-40)));
			assertFalse(container.intersects(lower16Bits(-100), lower16Bits(-55)));
			assertFalse(container.intersects(lower16Bits(-9), lower16Bits(-1)));
			// assertTrue(container.intersects(11, (char)-1)); // forbidden
		}

		@Test
		@DisplayName("intersects at the final populated word")
		public void testIntersectsAtEndWord() {
			final Container container = new BitmapContainer().add(lower16Bits(-500), lower16Bits(-10));
			assertTrue(container.intersects(lower16Bits(-50), lower16Bits(-10)));
			assertTrue(container.intersects(lower16Bits(-400), lower16Bits(-11)));
			assertTrue(container.intersects(lower16Bits(-11), lower16Bits(-1)));
			assertFalse(container.intersects(lower16Bits(-10), lower16Bits(-1)));
		}

		@Test
		@DisplayName("intersects at the final word of a wide range")
		public void testIntersectsAtEndWord2() {
			final Container container = new BitmapContainer().add(lower16Bits(500), lower16Bits(-500));
			assertTrue(container.intersects(lower16Bits(-650), lower16Bits(-500)));
			assertTrue(container.intersects(lower16Bits(-501), lower16Bits(-1)));
			assertFalse(container.intersects(lower16Bits(-500), lower16Bits(-1)));
			assertFalse(container.intersects(lower16Bits(-499), 1 << 16));
		}
	}

	@Nested
	@DisplayName("nextSetBit / prevSetBit navigation")
	class NextAndPreviousSetBit {

		@Test
		@DisplayName("nextSetBit throws when the index is above the container range")
		public void testNextTooLarge() {
			assertThrows(
				ArrayIndexOutOfBoundsException.class,
				() -> emptyContainer().nextSetBit(Short.MAX_VALUE + 1)
			);
		}

		@Test
		@DisplayName("nextSetBit throws when the index is negative")
		public void testNextTooSmall() {
			assertThrows(ArrayIndexOutOfBoundsException.class, () -> emptyContainer().nextSetBit(-1));
		}

		@Test
		@DisplayName("prevSetBit throws when the index is above the container range")
		public void testPreviousTooLarge() {
			assertThrows(
				ArrayIndexOutOfBoundsException.class,
				() -> emptyContainer().prevSetBit(Short.MAX_VALUE + 1)
			);
		}

		@Test
		@DisplayName("prevSetBit throws when the index is negative")
		public void testPreviousTooSmall() {
			assertThrows(ArrayIndexOutOfBoundsException.class, () -> emptyContainer().prevSetBit(-1));
		}

		@Test
		@DisplayName("nextSetBit finds the next set bit at or after the index")
		public void testNextSetBit() {
			final BitmapContainer container = new BitmapContainer(evenBits(), 1 << 15);
			assertEquals(0, container.nextSetBit(0));
			assertEquals(2, container.nextSetBit(1));
			assertEquals(2, container.nextSetBit(2));
			assertEquals(4, container.nextSetBit(3));
		}

		@Test
		@DisplayName("nextSetBit returns -1 when no set bit follows the index")
		public void testNextSetBitAfterEnd() {
			final BitmapContainer container = new BitmapContainer(evenBits(), 1 << 15);
			container.bitmap[1023] = 0L;
			container.cardinality -= 32;
			assertEquals(-1, container.nextSetBit((64 * 1023) + 5));
		}

		@Test
		@DisplayName("nextSetBit skips a cleared leading word")
		public void testNextSetBitBeforeStart() {
			final BitmapContainer container = new BitmapContainer(evenBits(), 1 << 15);
			container.bitmap[0] = 0L;
			container.cardinality -= 32;
			assertEquals(64, container.nextSetBit(1));
		}
	}

	@Nested
	@DisplayName("first() and last()")
	class FirstAndLast {

		@Test
		@DisplayName("first throws on an empty container")
		public void testFirst_Empty() {
			assertThrows(NoSuchElementException.class, () -> new BitmapContainer().first());
		}

		@Test
		@DisplayName("last throws on an empty container")
		public void testLast_Empty() {
			assertThrows(NoSuchElementException.class, () -> new BitmapContainer().last());
		}

		@Test
		@DisplayName("first and last track the growing populated range")
		public void testFirstLast() {
			Container rc = new ArrayContainer();
			final int firstInclusive = 1;
			int lastExclusive = firstInclusive;
			for (int i = 0; i < 1 << 16 - 10; ++i) {
				final int newLastExclusive = lastExclusive + 10;
				rc = rc.add(lastExclusive, newLastExclusive);
				assertEquals(firstInclusive, rc.first());
				assertEquals(newLastExclusive - 1, rc.last());
				lastExclusive = newLastExclusive;
			}
		}
	}

	@Nested
	@DisplayName("nextValue / previousValue navigation")
	class NextAndPreviousValue {

		@Test
		@DisplayName("nextValue returns the value at or after a sparse key")
		public void testNextValue() {
			final BitmapContainer container = new ArrayContainer(new char[]{10, 20, 30}).toBitmapContainer();
			assertEquals(10, container.nextValue((char) 10));
			assertEquals(20, container.nextValue((char) 11));
			assertEquals(30, container.nextValue((char) 30));
		}

		@Test
		@DisplayName("nextValue returns -1 past the last value")
		public void testNextValueAfterEnd() {
			final BitmapContainer container = new ArrayContainer(new char[]{10, 20, 30}).toBitmapContainer();
			assertEquals(-1, container.nextValue((char) 31));
		}

		@Test
		@DisplayName("nextValue walks a single contiguous run")
		public void testNextValue2() {
			final BitmapContainer container = new BitmapContainer().iadd(64, 129).toBitmapContainer();
			assertEquals(64, container.nextValue((char) 0));
			assertEquals(64, container.nextValue((char) 64));
			assertEquals(65, container.nextValue((char) 65));
			assertEquals(128, container.nextValue((char) 128));
			assertEquals(-1, container.nextValue((char) 129));
			assertEquals(-1, container.nextValue((char) 5000));
		}

		@Test
		@DisplayName("nextValue jumps across the gap between runs")
		public void testNextValueBetweenRuns() {
			final BitmapContainer container =
				new BitmapContainer().iadd(64, 129).iadd(256, 321).toBitmapContainer();
			assertEquals(64, container.nextValue((char) 0));
			assertEquals(64, container.nextValue((char) 64));
			assertEquals(65, container.nextValue((char) 65));
			assertEquals(128, container.nextValue((char) 128));
			assertEquals(256, container.nextValue((char) 129));
			assertEquals(-1, container.nextValue((char) 512));
		}

		@Test
		@DisplayName("nextValue walks multiple runs")
		public void testNextValue3() {
			final BitmapContainer container =
				new ArrayContainer().iadd(64, 129).iadd(200, 501).iadd(5000, 5201).toBitmapContainer();
			assertEquals(64, container.nextValue((char) 0));
			assertEquals(64, container.nextValue((char) 63));
			assertEquals(64, container.nextValue((char) 64));
			assertEquals(65, container.nextValue((char) 65));
			assertEquals(128, container.nextValue((char) 128));
			assertEquals(200, container.nextValue((char) 129));
			assertEquals(200, container.nextValue((char) 199));
			assertEquals(200, container.nextValue((char) 200));
			assertEquals(250, container.nextValue((char) 250));
			assertEquals(5000, container.nextValue((char) 2500));
			assertEquals(5000, container.nextValue((char) 5000));
			assertEquals(5200, container.nextValue((char) 5200));
			assertEquals(-1, container.nextValue((char) 5201));
		}

		@Test
		@DisplayName("nextValue handles values in the upper unsigned half")
		public void testNextValueUnsigned() {
			final BitmapContainer container =
				new ArrayContainer(new char[]{(char) ((1 << 15) | 5), (char) ((1 << 15) | 7)})
					.toBitmapContainer();
			assertEquals(((1 << 15) | 5), container.nextValue((char) ((1 << 15) | 4)));
			assertEquals(((1 << 15) | 5), container.nextValue((char) ((1 << 15) | 5)));
			assertEquals(((1 << 15) | 7), container.nextValue((char) ((1 << 15) | 6)));
			assertEquals(((1 << 15) | 7), container.nextValue((char) ((1 << 15) | 7)));
			assertEquals(-1, container.nextValue((char) ((1 << 15) | 8)));
		}

		@Test
		@DisplayName("previousValue returns the value at or before a key in one run")
		public void testPreviousValue1() {
			final BitmapContainer container = new ArrayContainer().iadd(64, 129).toBitmapContainer();
			assertEquals(-1, container.previousValue((char) 0));
			assertEquals(-1, container.previousValue((char) 63));
			assertEquals(64, container.previousValue((char) 64));
			assertEquals(65, container.previousValue((char) 65));
			assertEquals(128, container.previousValue((char) 128));
			assertEquals(128, container.previousValue((char) 129));
		}

		@Test
		@DisplayName("previousValue walks back across multiple runs")
		public void testPreviousValue2() {
			final BitmapContainer container =
				new ArrayContainer().iadd(64, 129).iadd(200, 501).iadd(5000, 5201).toBitmapContainer();
			assertEquals(-1, container.previousValue((char) 0));
			assertEquals(-1, container.previousValue((char) 63));
			assertEquals(64, container.previousValue((char) 64));
			assertEquals(65, container.previousValue((char) 65));
			assertEquals(128, container.previousValue((char) 128));
			assertEquals(128, container.previousValue((char) 129));
			assertEquals(128, container.previousValue((char) 199));
			assertEquals(200, container.previousValue((char) 200));
			assertEquals(250, container.previousValue((char) 250));
			assertEquals(500, container.previousValue((char) 2500));
			assertEquals(5000, container.previousValue((char) 5000));
			assertEquals(5200, container.previousValue((char) 5200));
		}

		@Test
		@DisplayName("previousValue returns -1 before the first value")
		public void testPreviousValueBeforeStart() {
			final BitmapContainer container = new ArrayContainer(new char[]{10, 20, 30}).toBitmapContainer();
			assertEquals(-1, container.previousValue((char) 5));
		}

		@Test
		@DisplayName("previousValue returns the value at or before a sparse key")
		public void testPreviousValueSparse() {
			final BitmapContainer container = new ArrayContainer(new char[]{10, 20, 30}).toBitmapContainer();
			assertEquals(-1, container.previousValue((char) 9));
			assertEquals(10, container.previousValue((char) 10));
			assertEquals(10, container.previousValue((char) 11));
			assertEquals(20, container.previousValue((char) 21));
			assertEquals(30, container.previousValue((char) 30));
		}

		@Test
		@DisplayName("previousValue returns the last value past the end")
		public void testPreviousValueAfterEnd() {
			final BitmapContainer container = new ArrayContainer(new char[]{10, 20, 30}).toBitmapContainer();
			assertEquals(30, container.previousValue((char) 31));
		}

		@Test
		@DisplayName("previousValue over an even-bits pattern")
		public void testPreviousEvenBits() {
			final BitmapContainer container = new BitmapContainer(evenBits(), 1 << 15);
			assertEquals(0, container.previousValue((char) 0));
			assertEquals(0, container.previousValue((char) 1));
			assertEquals(2, container.previousValue((char) 2));
			assertEquals(2, container.previousValue((char) 3));
		}

		@Test
		@DisplayName("previousValue handles values in the upper unsigned half")
		public void testPreviousValueUnsigned() {
			final BitmapContainer container =
				new ArrayContainer(new char[]{(char) ((1 << 15) | 5), (char) ((1 << 15) | 7)})
					.toBitmapContainer();
			assertEquals(-1, container.previousValue((char) ((1 << 15) | 4)));
			assertEquals(((1 << 15) | 5), container.previousValue((char) ((1 << 15) | 5)));
			assertEquals(((1 << 15) | 5), container.previousValue((char) ((1 << 15) | 6)));
			assertEquals(((1 << 15) | 7), container.previousValue((char) ((1 << 15) | 7)));
			assertEquals(((1 << 15) | 7), container.previousValue((char) ((1 << 15) | 8)));
		}
	}

	@Nested
	@DisplayName("nextAbsentValue / previousAbsentValue navigation")
	class NextAndPreviousAbsentValue {

		@Test
		@DisplayName("previousAbsentValue finds the gap before one run")
		public void testPreviousAbsentValue1() {
			final BitmapContainer container = new ArrayContainer().iadd(64, 129).toBitmapContainer();
			assertEquals(0, container.previousAbsentValue((char) 0));
			assertEquals(63, container.previousAbsentValue((char) 63));
			assertEquals(63, container.previousAbsentValue((char) 64));
			assertEquals(63, container.previousAbsentValue((char) 65));
			assertEquals(63, container.previousAbsentValue((char) 128));
			assertEquals(129, container.previousAbsentValue((char) 129));
		}

		@Test
		@DisplayName("previousAbsentValue finds gaps across multiple runs")
		public void testPreviousAbsentValue2() {
			final BitmapContainer container =
				new ArrayContainer().iadd(64, 129).iadd(200, 501).iadd(5000, 5201).toBitmapContainer();
			assertEquals(0, container.previousAbsentValue((char) 0));
			assertEquals(63, container.previousAbsentValue((char) 63));
			assertEquals(63, container.previousAbsentValue((char) 64));
			assertEquals(63, container.previousAbsentValue((char) 65));
			assertEquals(63, container.previousAbsentValue((char) 128));
			assertEquals(129, container.previousAbsentValue((char) 129));
			assertEquals(199, container.previousAbsentValue((char) 199));
			assertEquals(199, container.previousAbsentValue((char) 200));
			assertEquals(199, container.previousAbsentValue((char) 250));
			assertEquals(2500, container.previousAbsentValue((char) 2500));
			assertEquals(4999, container.previousAbsentValue((char) 5000));
			assertEquals(4999, container.previousAbsentValue((char) 5200));
		}

		@Test
		@DisplayName("previousAbsentValue on an empty container returns the key")
		public void testPreviousAbsentValueEmpty() {
			final BitmapContainer container = new ArrayContainer().toBitmapContainer();
			for (int i = 0; i < 1000; i++) {
				assertEquals(i, container.previousAbsentValue((char) i));
			}
		}

		@Test
		@DisplayName("previousAbsentValue skips sparse present values")
		public void testPreviousAbsentValueSparse() {
			final BitmapContainer container = new ArrayContainer(new char[]{10, 20, 30}).toBitmapContainer();
			assertEquals(9, container.previousAbsentValue((char) 9));
			assertEquals(9, container.previousAbsentValue((char) 10));
			assertEquals(11, container.previousAbsentValue((char) 11));
			assertEquals(21, container.previousAbsentValue((char) 21));
			assertEquals(29, container.previousAbsentValue((char) 30));
		}

		@Test
		@DisplayName("previousAbsentValue over an even-bits pattern")
		public void testPreviousAbsentEvenBits() {
			final BitmapContainer container = new BitmapContainer(evenBits(), 1 << 15);
			for (int i = 0; i < 1 << 10; i += 2) {
				assertEquals(i - 1, container.previousAbsentValue((char) i));
				assertEquals(i + 1, container.previousAbsentValue((char) (i + 1)));
			}
		}

		@Test
		@DisplayName("previousAbsentValue handles the upper unsigned half")
		public void testPreviousAbsentValueUnsigned() {
			final BitmapContainer container =
				new ArrayContainer(new char[]{(char) ((1 << 15) | 5), (char) ((1 << 15) | 7)})
					.toBitmapContainer();
			assertEquals(((1 << 15) | 4), container.previousAbsentValue((char) ((1 << 15) | 4)));
			assertEquals(((1 << 15) | 4), container.previousAbsentValue((char) ((1 << 15) | 5)));
			assertEquals(((1 << 15) | 6), container.previousAbsentValue((char) ((1 << 15) | 6)));
			assertEquals(((1 << 15) | 6), container.previousAbsentValue((char) ((1 << 15) | 7)));
			assertEquals(((1 << 15) | 8), container.previousAbsentValue((char) ((1 << 15) | 8)));
		}

		@Test
		@DisplayName("nextAbsentValue finds the gap after one run")
		public void testNextAbsentValue1() {
			final BitmapContainer container = new ArrayContainer().iadd(64, 129).toBitmapContainer();
			assertEquals(0, container.nextAbsentValue((char) 0));
			assertEquals(63, container.nextAbsentValue((char) 63));
			assertEquals(129, container.nextAbsentValue((char) 64));
			assertEquals(129, container.nextAbsentValue((char) 65));
			assertEquals(129, container.nextAbsentValue((char) 128));
			assertEquals(129, container.nextAbsentValue((char) 129));
		}

		@Test
		@DisplayName("nextAbsentValue finds gaps across multiple runs")
		public void testNextAbsentValue2() {
			final BitmapContainer container =
				new ArrayContainer().iadd(64, 129).iadd(200, 501).iadd(5000, 5201).toBitmapContainer();
			assertEquals(0, container.nextAbsentValue((char) 0));
			assertEquals(63, container.nextAbsentValue((char) 63));
			assertEquals(129, container.nextAbsentValue((char) 64));
			assertEquals(129, container.nextAbsentValue((char) 65));
			assertEquals(129, container.nextAbsentValue((char) 128));
			assertEquals(129, container.nextAbsentValue((char) 129));
			assertEquals(199, container.nextAbsentValue((char) 199));
			assertEquals(501, container.nextAbsentValue((char) 200));
			assertEquals(501, container.nextAbsentValue((char) 250));
			assertEquals(2500, container.nextAbsentValue((char) 2500));
			assertEquals(5201, container.nextAbsentValue((char) 5000));
			assertEquals(5201, container.nextAbsentValue((char) 5200));
		}

		@Test
		@DisplayName("nextAbsentValue on an empty container returns the key")
		public void testNextAbsentValueEmpty() {
			final BitmapContainer container = new ArrayContainer().toBitmapContainer();
			for (int i = 0; i < 1000; i++) {
				assertEquals(i, container.nextAbsentValue((char) i));
			}
		}

		@Test
		@DisplayName("nextAbsentValue skips sparse present values")
		public void testNextAbsentValueSparse() {
			final BitmapContainer container = new ArrayContainer(new char[]{10, 20, 30}).toBitmapContainer();
			assertEquals(9, container.nextAbsentValue((char) 9));
			assertEquals(11, container.nextAbsentValue((char) 10));
			assertEquals(11, container.nextAbsentValue((char) 11));
			assertEquals(21, container.nextAbsentValue((char) 21));
			assertEquals(31, container.nextAbsentValue((char) 30));
		}

		@Test
		@DisplayName("nextAbsentValue over an even-bits pattern")
		public void testNextAbsentEvenBits() {
			final BitmapContainer container = new BitmapContainer(evenBits(), 1 << 15);
			for (int i = 0; i < 1 << 10; i += 2) {
				assertEquals(i + 1, container.nextAbsentValue((char) i));
				assertEquals(i + 1, container.nextAbsentValue((char) (i + 1)));
			}
		}

		@Test
		@DisplayName("nextAbsentValue handles the upper unsigned half")
		public void testNextAbsentValueUnsigned() {
			final BitmapContainer container =
				new ArrayContainer(new char[]{(char) ((1 << 15) | 5), (char) ((1 << 15) | 7)})
					.toBitmapContainer();
			assertEquals(((1 << 15) | 4), container.nextAbsentValue((char) ((1 << 15) | 4)));
			assertEquals(((1 << 15) | 6), container.nextAbsentValue((char) ((1 << 15) | 5)));
			assertEquals(((1 << 15) | 6), container.nextAbsentValue((char) ((1 << 15) | 6)));
			assertEquals(((1 << 15) | 8), container.nextAbsentValue((char) ((1 << 15) | 7)));
			assertEquals(((1 << 15) | 8), container.nextAbsentValue((char) ((1 << 15) | 8)));
		}
	}

	@Nested
	@DisplayName("Iteration and serialization")
	class IterationAndSerialization {

		@Test
		@DisplayName("Container survives an external write/read round-trip")
		public void roundtrip() throws Exception {
			Container bc = new BitmapContainer();
			bc = bc.add(1, 5);
			final ByteArrayOutputStream bos = new ByteArrayOutputStream();
			try (ObjectOutputStream oo = new ObjectOutputStream(bos)) {
				bc.writeExternal(oo);
			}
			final Container bc2 = new BitmapContainer();
			final ByteArrayInputStream bis = new ByteArrayInputStream(bos.toByteArray());
			bc2.readExternal(new ObjectInputStream(bis));

			assertEquals(4, bc2.getCardinality());
			for (int i = 1; i < 5; i++) {
				assertTrue(bc2.contains((char) i));
			}
		}

		@Test
		@DisplayName("Range consumers visit every slot with the correct presence")
		public void testRangeConsumer() {
			final char[] entries = new char[]{3, 4, 7, 8, 10, 65530, 65534, 65535};
			BitmapContainer container = new ArrayContainer(entries).toBitmapContainer();

			final ValidationRangeConsumer consumer =
				ValidationRangeConsumer.validate(
					new ValidationRangeConsumer.Value[]{
						ABSENT, ABSENT, ABSENT, PRESENT, PRESENT, ABSENT, ABSENT, PRESENT, PRESENT, ABSENT,
						PRESENT
					});
			container.forAllUntil(0, (char) 11, consumer);
			assertEquals(11, consumer.getNumberOfValuesConsumed());

			final ValidationRangeConsumer consumer2 =
				ValidationRangeConsumer.validate(
					new ValidationRangeConsumer.Value[]{PRESENT, ABSENT, ABSENT, PRESENT, PRESENT});
			container.forAllInRange((char) 4, (char) 9, consumer2);
			assertEquals(5, consumer2.getNumberOfValuesConsumed());

			final ValidationRangeConsumer consumer3 =
				ValidationRangeConsumer.validate(
					new ValidationRangeConsumer.Value[]{
						PRESENT, ABSENT, ABSENT, ABSENT, PRESENT, PRESENT
					});
			container.forAllFrom((char) 65530, consumer3);
			assertEquals(6, consumer3.getNumberOfValuesConsumed());

			final ValidationRangeConsumer consumer4 =
				ValidationRangeConsumer.ofSize(BitmapContainer.MAX_CAPACITY);
			container.forAll(0, consumer4);
			consumer4.assertAllAbsentExcept(entries, 0);

			final ValidationRangeConsumer consumer5 =
				ValidationRangeConsumer.ofSize(2 * BitmapContainer.MAX_CAPACITY);
			consumer5.acceptAllAbsent(0, BitmapContainer.MAX_CAPACITY);
			container.forAll(BitmapContainer.MAX_CAPACITY, consumer5);
			consumer5.assertAllAbsentExcept(entries, BitmapContainer.MAX_CAPACITY);

			// Completely Empty
			container = new BitmapContainer();
			final ValidationRangeConsumer consumer6 =
				ValidationRangeConsumer.ofSize(BitmapContainer.MAX_CAPACITY);
			container.forAll(0, consumer6);
			consumer6.assertAllAbsent();

			// Completely Full
			container = new BitmapContainer();
			container.iadd(0, BitmapContainer.MAX_CAPACITY);
			final ValidationRangeConsumer consumer7 =
				ValidationRangeConsumer.ofSize(BitmapContainer.MAX_CAPACITY);
			container.forAll(0, consumer7);
			consumer7.assertAllPresent();

			final int middle = BitmapContainer.MAX_CAPACITY / 2;
			final ValidationRangeConsumer consumer8 = ValidationRangeConsumer.ofSize(middle);
			container.forAllFrom((char) middle, consumer8);
			consumer8.assertAllPresent();

			final ValidationRangeConsumer consumer9 = ValidationRangeConsumer.ofSize(middle);
			container.forAllUntil(0, (char) middle, consumer9);
			consumer9.assertAllPresent();

			final int quarter = middle / 2;
			final ValidationRangeConsumer consumer10 = ValidationRangeConsumer.ofSize(middle);
			container.forAllInRange((char) quarter, (char) (middle + quarter), consumer10);
			consumer10.assertAllPresent();
		}
	}

	@Nested
	@DisplayName("Reverse char iterator advanceIfNeeded")
	class ReverseIteratorAdvance {

		@Test
		@DisplayName("Reverse iterator yields every set value in descending order")
		public void shouldIterateSetValuesInDescendingOrder() {
			final BitmapContainer bc = new BitmapContainer();
			bc.add((char) 5);
			bc.add((char) 63);
			bc.add((char) 64);
			bc.add((char) 200);
			bc.add((char) 65535);

			final PeekableCharIterator it = bc.getReverseCharIterator();
			final ArrayList<Integer> seen = new ArrayList<>();
			while (it.hasNext()) {
				seen.add((int) it.next());
			}

			assertEquals(Arrays.asList(65535, 200, 64, 63, 5), seen);
		}

		@Test
		@DisplayName("Reverse advanceIfNeeded retreats to the highest value at or below the threshold")
		public void shouldRetreatToHighestValueAtOrBelowThreshold() {
			// value 200 sits in word 3, value 40 sits in word 0
			final long[] words = new long[BitmapContainer.MAX_CAPACITY / 64];
			words[3] = 1L << 8;   // value 3 * 64 + 8 = 200
			words[0] = 1L << 40;  // value 40
			final BitmapContainer bc = new BitmapContainer(words, 2);

			final PeekableCharIterator it = bc.getReverseCharIterator();
			// 200 > 50 must be skipped; 40 <= 50 is the highest remaining value
			it.advanceIfNeeded((char) 50);

			assertTrue(it.hasNext());
			assertEquals(40, it.peekNext());
			assertEquals(40, it.next());
			assertFalse(it.hasNext());
		}

		/**
		 * Guards against a regression where the reverse iterator's `advanceIfNeeded` broke out of its
		 * descent loop at word 0 before reading `bitmap[0]`, discarding any value stored there. The
		 * defect left `hasNext() == true` with `peekNext() == 65535` (a phantom value) instead of
		 * returning the real value in word 0.
		 */
		@Test
		@DisplayName("Reverse advanceIfNeeded keeps a word-0 value reached by descending across empty words")
		public void shouldReturnWordZeroValueWhenReverseAdvanceDescendsAcrossEmptyWords() {
			// value 130 sits in word 2, value 40 sits in word 0, word 1 is empty
			final long[] words = new long[BitmapContainer.MAX_CAPACITY / 64];
			words[2] = 1L << 2;   // value 2 * 64 + 2 = 130
			words[0] = 1L << 40;  // value 40
			final BitmapContainer bc = new BitmapContainer(words, 2);

			final PeekableCharIterator it = bc.getReverseCharIterator();
			// 130 > 128 must be skipped; 40 <= 128 must be returned as the highest remaining value
			it.advanceIfNeeded((char) 128);

			assertTrue(it.hasNext());
			assertEquals(40, it.peekNext());
			assertEquals(40, it.next());
			assertFalse(it.hasNext());
		}
	}

	/**
	 * Creates an empty {@link BitmapContainer} backed by a single-word bitmap.
	 *
	 * @return a freshly allocated empty container
	 */
	@Nonnull
	private static BitmapContainer emptyContainer() {
		return new BitmapContainer(new long[1], 0);
	}

	/**
	 * Builds a {@link BitmapContainer} populated with every value in the {@code [min, max)}
	 * range whose index is not a multiple of {@code sample}.
	 *
	 * @param min    inclusive lower bound of the generated range
	 * @param max    exclusive upper bound of the generated range
	 * @param sample values whose index is a multiple of this factor are skipped
	 * @return the populated container
	 */
	@Nonnull
	static BitmapContainer generateContainer(char min, char max, int sample) {
		final BitmapContainer bc = new BitmapContainer();
		for (int i = min; i < max; i++) {
			if (i % sample != 0) bc.add((char) i);
		}
		return bc;
	}

	/**
	 * Supplies the argument matrix for {@link IntersectsRange#testIntersectsWithRangeUpperBoundaries}:
	 * a single-value bitmap together with a range and the expected {@code intersects} result.
	 *
	 * @return a stream of {@code (container, min, sup, intersects)} argument tuples
	 */
	@Nonnull
	public static Stream<Arguments> bitmapsForRangeIntersection() {
		return Stream.of(
			Arguments.of(new BitmapContainer().add((char) 60), 0, 61, true),
			Arguments.of(new BitmapContainer().add((char) 60), 0, 60, false),
			Arguments.of(new BitmapContainer().add((char) 1000), 0, 1001, true),
			Arguments.of(new BitmapContainer().add((char) 1000), 0, 1000, false),
			Arguments.of(new BitmapContainer().add((char) 1000), 0, 10000, true)
		);
	}

	/**
	 * Builds a full-length bitmap word array with every even bit set (the `0x5555...` pattern).
	 *
	 * @return a bitmap array of {@code 1 << 10} words with even bits set
	 */
	@Nonnull
	private static long[] evenBits() {
		final long[] bitmap = new long[1 << 10];
		Arrays.fill(bitmap, 0x5555555555555555L);
		return bitmap;
	}

	/**
	 * Returns the lower 16 bits of the given value interpreted as an unsigned char.
	 *
	 * @param x the value to truncate
	 * @return the lower 16 bits of {@code x}
	 */
	private static int lower16Bits(final int x) {
		return (char) x;
	}
}
