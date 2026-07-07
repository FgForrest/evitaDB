package io.evitadb.roaringbitmap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static io.evitadb.roaringbitmap.ValidationRangeConsumer.Value.ABSENT;
import static io.evitadb.roaringbitmap.ValidationRangeConsumer.Value.PRESENT;

import com.google.common.primitives.Ints;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;
import javax.annotation.Nonnull;

/**
 * Regression tests for {@link ArrayContainer}, ported from the upstream RoaringBitmap test
 * suite. They pin down the container's construction and equality, its set operations
 * (and / or / andNot / orNot), containment checks against every container type and its
 * value-navigation semantics (next / previous / absent value).
 */
@Execution(ExecutionMode.CONCURRENT)
@DisplayName("ArrayContainer")
public class TestArrayContainer {

	@Nested
	@DisplayName("Construction, equality and representation")
	class ConstructionAndRepresentation {

		@Test
		@DisplayName("Range and array constructors produce equal containers")
		public void testConst() {
			final ArrayContainer ac1 = new ArrayContainer(5, 15);
			final char[] data = {5, 6, 7, 8, 9, 10, 11, 12, 13, 14};
			final ArrayContainer ac2 = new ArrayContainer(data);
			assertEquals(ac1, ac2);
		}

		@Test
		@DisplayName("Array container is never reported as full")
		public void arrayContainersNeverFull() {
			assertFalse(new ArrayContainer(5, 15).isFull());
		}

		@Test
		@DisplayName("toString lists values in unsigned order")
		public void testToString() {
			final ArrayContainer ac1 = new ArrayContainer(5, 15);
			ac1.add((char) -3);
			ac1.add((char) -17);
			assertEquals("{5,6,7,8,9,10,11,12,13,14,65519,65533}", ac1.toString());
		}

		@Test
		@DisplayName("equals compares only the cardinality prefix, ignoring trailing scratch capacity")
		public void equalsIgnoresTrailingScratchCapacity() {
			// a container whose backing array is far larger than its cardinality: slots past
			// index 2 hold leftover zeros that must not participate in equality
			final ArrayContainer withScratch = new ArrayContainer(16);
			withScratch.add((char) 3);
			withScratch.add((char) 9);
			withScratch.add((char) 40000); // unsigned value above 0x7FFF
			assertEquals(3, withScratch.getCardinality());

			// a container holding the same values in a tightly-sized backing array
			final ArrayContainer tight = new ArrayContainer(new char[]{3, 9, (char) 40000});

			// equality must hold in both directions despite the differing array lengths
			assertEquals(withScratch, tight);
			assertEquals(tight, withScratch);

			// a strict superset sharing the same prefix must not be equal
			final ArrayContainer longer =
				new ArrayContainer(new char[]{3, 9, (char) 40000, (char) 50000});
			assertNotEquals(tight, longer);
			assertNotEquals(longer, tight);
		}
	}

	@Nested
	@DisplayName("Adding and removing values")
	class AddAndRemove {

		@Test
		@DisplayName("Removing the last value yields the shorter range")
		public void testRemove() {
			final ArrayContainer ac1 = new ArrayContainer(5, 15);
			ac1.remove((char) 14);
			final ArrayContainer ac2 = new ArrayContainer(5, 14);
			assertEquals(ac1, ac2);
		}

		@Test
		@DisplayName("Adding an empty range leaves the container empty")
		public void addEmptyRange() {
			Container ac = new ArrayContainer();
			ac = ac.add(1, 1);
			assertEquals(0, ac.getCardinality());
		}

		@Test
		@DisplayName("add rejects a reversed range")
		public void addInvalidRange() {
			assertThrows(
				IllegalArgumentException.class,
				() -> {
					final Container ac = new ArrayContainer();
					ac.add(13, 1);
				}
			);
		}

		@Test
		@DisplayName("In-place add of an empty range leaves the container empty")
		public void iaddEmptyRange() {
			Container ac = new ArrayContainer();
			ac = ac.iadd(1, 1);
			assertEquals(0, ac.getCardinality());
		}

		@Test
		@DisplayName("iadd rejects a reversed range")
		public void iaddInvalidRange() {
			assertThrows(
				IllegalArgumentException.class,
				() -> {
					final Container ac = new ArrayContainer();
					ac.iadd(13, 1);
				}
			);
		}

		@Test
		@DisplayName("In-place adds of disjoint and overlapping ranges accumulate the union")
		public void iaddSanityTest() {
			Container ac = new ArrayContainer();
			ac = ac.iadd(10, 20);
			// insert disjoint at end
			ac = ac.iadd(30, 70);
			// insert disjoint between
			ac = ac.iadd(25, 26);
			// insert disjoint at start
			ac = ac.iadd(1, 2);
			// insert overlap at end
			ac = ac.iadd(60, 80);
			// insert overlap between
			ac = ac.iadd(10, 30);
			// insert overlap at start
			ac = ac.iadd(1, 20);
			assertEquals(79, ac.getCardinality());
		}

		@Test
		@DisplayName("clear removes all values")
		public void clear() {
			Container ac = new ArrayContainer();
			ac = ac.add(1, 10);
			ac.clear();
			assertEquals(0, ac.getCardinality());
		}
	}

	@Nested
	@DisplayName("Intersection: and / andNot / intersects")
	class IntersectionOperations {

		@Test
		@DisplayName("In-place andNot removes the overlapping bitmap range")
		public void testIandNot() {
			final ArrayContainer ac1 = new ArrayContainer(5, 15);
			final ArrayContainer ac2 = new ArrayContainer(10, 15);
			final BitmapContainer bc = new BitmapContainer(5, 10);
			final ArrayContainer ac3 = ac1.iandNot(bc);
			assertEquals(ac2, ac3);
		}

		@Test
		@DisplayName("intersects returns true for overlapping array containers")
		public void intersectsArray() {
			Container ac = new ArrayContainer();
			ac = ac.add(1, 10);
			Container ac2 = new ArrayContainer();
			ac2 = ac2.add(5, 25);
			assertTrue(ac.intersects(ac2));
		}

		@Test
		@DisplayName("In-place and with a bitmap keeps only the intersection")
		public void iandBitmap() {
			Container ac = new ArrayContainer();
			ac = ac.add(1, 10);
			Container bc = new BitmapContainer();
			bc = bc.add(5, 25);
			ac.iand(bc);
			assertEquals(5, ac.getCardinality());
			for (int i = 5; i < 10; i++) {
				assertTrue(ac.contains((char) i));
			}
		}

		@Test
		@DisplayName("In-place and with a run container keeps only the intersection")
		public void iandRun() {
			Container ac = new ArrayContainer();
			ac = ac.add(1, 10);
			Container rc = new RunContainer();
			rc = rc.add(5, 25);
			ac = ac.iand(rc);
			assertEquals(5, ac.getCardinality());
			for (int i = 5; i < 10; i++) {
				assertTrue(ac.contains((char) i));
			}
		}

		@Test
		@DisplayName("intersects(range) for a low value block")
		public void testIntersectsWithRange() {
			final Container container = new ArrayContainer().add(0, 10);
			assertTrue(container.intersects(0, 1));
			assertTrue(container.intersects(0, 101));
			assertTrue(container.intersects(0, lower16Bits(-1)));
			assertFalse(container.intersects(11, lower16Bits(-1)));
		}

		@Test
		@DisplayName("intersects(range) for a high, unsigned value block")
		public void testIntersectsWithRange2() {
			final Container container =
				new ArrayContainer().add(lower16Bits(-50), lower16Bits(-10));
			assertFalse(container.intersects(0, 1));
			assertTrue(container.intersects(0, lower16Bits(-40)));
			assertFalse(container.intersects(lower16Bits(-100), lower16Bits(-55)));
			assertFalse(container.intersects(lower16Bits(-9), lower16Bits(-1)));
			assertTrue(container.intersects(11, 1 << 16));
		}

		@Test
		@DisplayName("intersects(range) for a sparse set")
		public void testIntersectsWithRange3() {
			final Container container =
				new ArrayContainer().add((char) 1).add((char) 300).add((char) 1024);
			assertTrue(container.intersects(0, 300));
			assertTrue(container.intersects(1, 300));
			assertFalse(container.intersects(2, 300));
			assertFalse(container.intersects(2, 299));
			assertTrue(container.intersects(0, lower16Bits(-1)));
			assertFalse(container.intersects(1025, 1 << 16));
		}
	}

	@Nested
	@DisplayName("Union: or / lazyor / orNot / iorNot")
	class UnionOperations {

		@Test
		@DisplayName("or with a completing bitmap collapses to a run container")
		public void orFullToRunContainer() {
			final ArrayContainer ac = new ArrayContainer(0, 1 << 12);
			final BitmapContainer half = new BitmapContainer(1 << 12, 1 << 16);
			final Container result = ac.or(half);
			assertEquals(1 << 16, result.getCardinality());
			assertTrue(result instanceof RunContainer);
		}

		@Test
		@DisplayName("or with a completing array collapses to a run container")
		public void orFullToRunContainer2() {
			final ArrayContainer ac = new ArrayContainer(0, 1 << 15);
			final ArrayContainer half = new ArrayContainer(1 << 15, 1 << 16);
			final Container result = ac.or(half);
			assertEquals(1 << 16, result.getCardinality());
			assertTrue(result instanceof RunContainer);
		}

		@Test
		@DisplayName("lazyor leaves cardinality invalid until repaired into a run container")
		public void testLazyORFull() {
			final ArrayContainer ac = new ArrayContainer(0, 1 << 15);
			final ArrayContainer ac2 = new ArrayContainer(1 << 15, 1 << 16);
			final Container rbc = ac.lazyor(ac2);
			assertEquals(-1, rbc.getCardinality());
			final Container repaired = rbc.repairAfterLazy();
			assertEquals(1 << 16, repaired.getCardinality());
			assertTrue(repaired instanceof RunContainer);
		}

		@Test
		@DisplayName("In-place or grows capacity without losing existing values")
		public void iorNotIncreaseCapacity() {
			final Container ac1 = new ArrayContainer();
			final Container ac2 = new ArrayContainer();
			ac1.add((char) 128);
			ac1.add((char) 256);
			ac2.add((char) 1024);

			ac1.ior(ac2);
			assertTrue(ac1.contains((char) 128));
			assertTrue(ac1.contains((char) 256));
			assertTrue(ac1.contains((char) 1024));
		}

		@Test
		@DisplayName("In-place or grows capacity for a larger existing set")
		public void iorIncreaseCapacity() {
			final Container ac1 = new ArrayContainer();
			final Container ac2 = new ArrayContainer();
			ac1.add((char) 128);
			ac1.add((char) 256);
			ac1.add((char) 512);
			ac1.add((char) 513);
			ac2.add((char) 1024);

			ac1.ior(ac2);
			assertTrue(ac1.contains((char) 128));
			assertTrue(ac1.contains((char) 256));
			assertTrue(ac1.contains((char) 512));
			assertTrue(ac1.contains((char) 513));
			assertTrue(ac1.contains((char) 1024));
		}

		@Test
		@DisplayName("In-place or with a disjoint set includes both ranges")
		public void iorSanityCheck() {
			final Container ac = new ArrayContainer().add(0, 10);
			final Container disjoint = new ArrayContainer().add(20, 40);
			ac.ior(disjoint);
			assertTrue(ac.contains(disjoint));
		}

		@Test
		@DisplayName("In-place orNot with an array complement fills the gap")
		public void iorNot() {
			Container rc1 = new ArrayContainer();
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
		@DisplayName("In-place orNot with an array complement over two runs")
		public void iorNot2() {
			Container rc1 = new ArrayContainer();
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
		@DisplayName("In-place orNot with a bitmap complement fills the gap")
		public void iorNot3() {
			Container rc1 = new ArrayContainer();
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
		@DisplayName("In-place orNot with a run complement fills the gap")
		public void iorNot4() {
			Container rc1 = new ArrayContainer();
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
		@DisplayName("orNot produces the complement for array, bitmap and run inputs")
		public void orNot() {
			final Container rc1 = new ArrayContainer();

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
		@DisplayName("orNot with an array complement over two runs")
		public void orNot2() {
			Container rc1 = new ArrayContainer();
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
	@DisplayName("contains(Container) across container types")
	class ContainsContainer {

		@Test
		@DisplayName("contains(bitmap) is false for a shifted set")
		public void testContainsBitmapContainer_ExcludeShiftedSet() {
			final Container ac = new ArrayContainer().add(0, 10);
			final Container subset = new BitmapContainer().add(2, 12);
			assertFalse(ac.contains(subset));
		}

		@Test
		@DisplayName("contains(bitmap) is false even for an equal bitmap set")
		public void testContainsBitmapContainer_AlwaysFalse() {
			final Container ac = new ArrayContainer().add(0, 10);
			final Container subset = new BitmapContainer().add(0, 10);
			assertFalse(ac.contains(subset));
		}

		@Test
		@DisplayName("contains(bitmap) is false for a superset")
		public void testContainsBitmapContainer_ExcludeSuperSet() {
			final Container ac = new ArrayContainer().add(0, 10);
			final Container superset = new BitmapContainer().add(0, 20);
			assertFalse(ac.contains(superset));
		}

		@Test
		@DisplayName("contains(bitmap) is false for disjoint sets, both directions")
		public void testContainsBitmapContainer_ExcludeDisJointSet() {
			final Container ac = new ArrayContainer().add(0, 10);
			final Container disjoint = new BitmapContainer().add(20, 40);
			assertFalse(ac.contains(disjoint));
			assertFalse(disjoint.contains(ac));
		}

		@Test
		@DisplayName("empty container contains an empty run")
		public void testContainsRunContainer_EmptyContainsEmpty() {
			final Container ac = new ArrayContainer();
			final Container subset = new RunContainer();
			assertTrue(ac.contains(subset));
		}

		@Test
		@DisplayName("contains a proper run subset")
		public void testContainsRunContainer_IncludeProperSubset() {
			final Container ac = new ArrayContainer().add(0, 10);
			final Container subset = new RunContainer().add(0, 9);
			assertTrue(ac.contains(subset));
		}

		@Test
		@DisplayName("contains an equal run")
		public void testContainsRunContainer_IncludeSelf() {
			final Container ac = new ArrayContainer().add(0, 10);
			final Container subset = new RunContainer().add(0, 10);
			assertTrue(ac.contains(subset));
		}

		@Test
		@DisplayName("does not contain a run superset")
		public void testContainsRunContainer_ExcludeSuperSet() {
			final Container ac = new ArrayContainer().add(0, 10);
			final Container superset = new RunContainer().add(0, 20);
			assertFalse(ac.contains(superset));
		}

		@Test
		@DisplayName("contains a proper run subset with a different start")
		public void testContainsRunContainer_IncludeProperSubsetDifferentStart() {
			final Container ac = new ArrayContainer().add(0, 10);
			final Container subset = new RunContainer().add(1, 9);
			assertTrue(ac.contains(subset));
		}

		@Test
		@DisplayName("does not contain a shifted run set")
		public void testContainsRunContainer_ExcludeShiftedSet() {
			final Container ac = new ArrayContainer().add(0, 10);
			final Container subset = new RunContainer().add(2, 12);
			assertFalse(ac.contains(subset));
		}

		@Test
		@DisplayName("does not contain a disjoint run, both directions")
		public void testContainsRunContainer_ExcludeDisJointSet() {
			final Container ac = new ArrayContainer().add(0, 10);
			final Container disjoint = new RunContainer().add(20, 40);
			assertFalse(ac.contains(disjoint));
			assertFalse(disjoint.contains(ac));
		}

		@Test
		@DisplayName("contains a single-value run inside the range")
		public void testContainsRunContainer_Issue723Case1() {
			final Container ac = new ArrayContainer().add(0, 10);
			final Container subset = new RunContainer().add(5, 6);
			assertTrue(ac.contains(subset));
		}

		@Test
		@DisplayName("does not contain a run extending past the range")
		public void testContainsRunContainer_Issue723Case2() {
			final Container ac = new ArrayContainer().add(0, 10);
			final Container rc = new RunContainer().add(5, 11);
			assertFalse(ac.contains(rc));
		}

		@Test
		@DisplayName("empty container contains an empty array")
		public void testContainsArrayContainer_EmptyContainsEmpty() {
			final Container ac = new ArrayContainer();
			final Container subset = new ArrayContainer();
			assertTrue(ac.contains(subset));
		}

		@Test
		@DisplayName("contains a proper array subset")
		public void testContainsArrayContainer_IncludeProperSubset() {
			final Container ac = new ArrayContainer().add(0, 10);
			final Container subset = new ArrayContainer().add(0, 9);
			assertTrue(ac.contains(subset));
		}

		@Test
		@DisplayName("contains a proper array subset with a different start")
		public void testContainsArrayContainer_IncludeProperSubsetDifferentStart() {
			final Container ac = new ArrayContainer().add(0, 10);
			final Container subset = new ArrayContainer().add(2, 9);
			assertTrue(ac.contains(subset));
		}

		@Test
		@DisplayName("does not contain a shifted array set")
		public void testContainsArrayContainer_ExcludeShiftedSet() {
			final Container ac = new ArrayContainer().add(0, 10);
			final Container shifted = new ArrayContainer().add(2, 12);
			assertFalse(ac.contains(shifted));
		}

		@Test
		@DisplayName("contains an equal array")
		public void testContainsArrayContainer_IncludeSelf() {
			final Container ac = new ArrayContainer().add(0, 10);
			final Container subset = new ArrayContainer().add(0, 10);
			assertTrue(ac.contains(subset));
		}

		@Test
		@DisplayName("does not contain an array superset")
		public void testContainsArrayContainer_ExcludeSuperSet() {
			final Container ac = new ArrayContainer().add(0, 10);
			final Container superset = new ArrayContainer().add(0, 20);
			assertFalse(ac.contains(superset));
		}

		@Test
		@DisplayName("does not contain a disjoint array, both directions")
		public void testContainsArrayContainer_ExcludeDisJointSet() {
			final Container ac = new ArrayContainer().add(0, 10);
			final Container disjoint = new ArrayContainer().add(20, 40);
			assertFalse(ac.contains(disjoint));
			assertFalse(disjoint.contains(ac));
		}
	}

	@Nested
	@DisplayName("contains(range)")
	class ContainsRange {

		@Test
		@DisplayName("contains(range) for a single contiguous block")
		public void testContainsRange() {
			final Container ac = new ArrayContainer().add(20, 100);
			assertFalse(ac.contains(1, 21));
			assertFalse(ac.contains(1, 19));
			assertTrue(ac.contains(20, 100));
			assertTrue(ac.contains(20, 99));
			assertTrue(ac.contains(21, 100));
			assertFalse(ac.contains(21, 101));
			assertFalse(ac.contains(19, 99));
			assertFalse(ac.contains(190, 9999));
		}

		@Test
		@DisplayName("contains(range) with leading sparse values")
		public void testContainsRange2() {
			final Container ac = new ArrayContainer().add((char) 1).add((char) 10).add(20, 100);
			assertFalse(ac.contains(1, 21));
			assertFalse(ac.contains(1, 20));
			assertTrue(ac.contains(1, 2));
		}

		@Test
		@DisplayName("contains(range) with unsigned boundaries")
		public void testContainsRangeUnsigned() {
			final Container ac = new ArrayContainer().add(1 << 15, 1 << 8 | 1 << 15);
			assertTrue(ac.contains(1 << 15, 1 << 8 | 1 << 15));
			assertTrue(ac.contains(1 + (1 << 15), (1 << 8 | 1 << 15) - 1));
			assertFalse(ac.contains(1 + (1 << 15), (1 << 8 | 1 << 15) + 1));
			assertFalse(ac.contains((1 << 15) - 1, (1 << 8 | 1 << 15) - 1));
			assertFalse(ac.contains(0, 1 << 15));
			assertFalse(ac.contains(1 << 8 | 1 << 15 | 1, 1 << 16));
		}
	}

	@Nested
	@DisplayName("first() and last()")
	class FirstAndLast {

		@Test
		@DisplayName("first on an empty container throws")
		public void testFirst_Empty() {
			assertThrows(NoSuchElementException.class, () -> new ArrayContainer().first());
		}

		@Test
		@DisplayName("last on an empty container throws")
		public void testLast_Empty() {
			assertThrows(NoSuchElementException.class, () -> new ArrayContainer().last());
		}

		@Test
		@DisplayName("first and last track the growing value range")
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
		@DisplayName("nextValue before the first value returns the first value")
		public void testNextValueBeforeStart() {
			final ArrayContainer container = new ArrayContainer(new char[]{10, 20, 30});
			assertEquals(10, container.nextValue((char) 5));
		}

		@Test
		@DisplayName("nextValue at and between stored values")
		public void testNextValue() {
			final ArrayContainer container = new ArrayContainer(new char[]{10, 20, 30});
			assertEquals(10, container.nextValue((char) 10));
			assertEquals(20, container.nextValue((char) 11));
			assertEquals(30, container.nextValue((char) 30));
		}

		@Test
		@DisplayName("nextValue after the last value returns -1")
		public void testNextValueAfterEnd() {
			final ArrayContainer container = new ArrayContainer(new char[]{10, 20, 30});
			assertEquals(-1, container.nextValue((char) 31));
		}

		@Test
		@DisplayName("nextValue across a single run")
		public void testNextValue2() {
			final Container container = new ArrayContainer().iadd(64, 129);
			assertTrue(container instanceof ArrayContainer);
			assertEquals(64, container.nextValue((char) 0));
			assertEquals(64, container.nextValue((char) 64));
			assertEquals(65, container.nextValue((char) 65));
			assertEquals(128, container.nextValue((char) 128));
			assertEquals(-1, container.nextValue((char) 129));
			assertEquals(-1, container.nextValue((char) 5000));
		}

		@Test
		@DisplayName("nextValue across two runs")
		public void testNextValueBetweenRuns() {
			final Container container = new ArrayContainer().iadd(64, 129).iadd(256, 321);
			assertTrue(container instanceof ArrayContainer);
			assertEquals(64, container.nextValue((char) 0));
			assertEquals(64, container.nextValue((char) 64));
			assertEquals(65, container.nextValue((char) 65));
			assertEquals(128, container.nextValue((char) 128));
			assertEquals(256, container.nextValue((char) 129));
			assertEquals(-1, container.nextValue((char) 512));
		}

		@Test
		@DisplayName("nextValue across three runs")
		public void testNextValue3() {
			final Container container =
				new ArrayContainer().iadd(64, 129).iadd(200, 501).iadd(5000, 5201);
			assertTrue(container instanceof ArrayContainer);
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
		@DisplayName("previousValue across a single run")
		public void testPreviousValue1() {
			final Container container = new ArrayContainer().iadd(64, 129);
			assertTrue(container instanceof ArrayContainer);
			assertEquals(-1, container.previousValue((char) 0));
			assertEquals(-1, container.previousValue((char) 63));
			assertEquals(64, container.previousValue((char) 64));
			assertEquals(65, container.previousValue((char) 65));
			assertEquals(128, container.previousValue((char) 128));
			assertEquals(128, container.previousValue((char) 129));
		}

		@Test
		@DisplayName("previousValue across three runs")
		public void testPreviousValue2() {
			final Container container =
				new ArrayContainer().iadd(64, 129).iadd(200, 501).iadd(5000, 5201);
			assertTrue(container instanceof ArrayContainer);
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
		@DisplayName("previousValue before the first value returns -1")
		public void testPreviousValueBeforeStart() {
			final ArrayContainer container = new ArrayContainer(new char[]{10, 20, 30});
			assertEquals(-1, container.previousValue((char) 5));
		}

		@Test
		@DisplayName("previousValue on a sparse set")
		public void testPreviousValueSparse() {
			final ArrayContainer container = new ArrayContainer(new char[]{10, 20, 30});
			assertEquals(-1, container.previousValue((char) 9));
			assertEquals(10, container.previousValue((char) 10));
			assertEquals(10, container.previousValue((char) 11));
			assertEquals(20, container.previousValue((char) 21));
			assertEquals(30, container.previousValue((char) 30));
		}

		@Test
		@DisplayName("previousValue with unsigned values")
		public void testPreviousValueUnsigned() {
			final ArrayContainer container =
				new ArrayContainer(new char[]{(char) ((1 << 15) | 5), (char) ((1 << 15) | 7)});
			assertEquals(-1, container.previousValue((char) ((1 << 15) | 4)));
			assertEquals(((1 << 15) | 5), container.previousValue((char) ((1 << 15) | 5)));
			assertEquals(((1 << 15) | 5), container.previousValue((char) ((1 << 15) | 6)));
			assertEquals(((1 << 15) | 7), container.previousValue((char) ((1 << 15) | 7)));
			assertEquals(((1 << 15) | 7), container.previousValue((char) ((1 << 15) | 8)));
		}

		@Test
		@DisplayName("nextValue with unsigned values")
		public void testNextValueUnsigned() {
			final ArrayContainer container =
				new ArrayContainer(new char[]{(char) ((1 << 15) | 5), (char) ((1 << 15) | 7)});
			assertEquals(((1 << 15) | 5), container.nextValue((char) ((1 << 15) | 4)));
			assertEquals(((1 << 15) | 5), container.nextValue((char) ((1 << 15) | 5)));
			assertEquals(((1 << 15) | 7), container.nextValue((char) ((1 << 15) | 6)));
			assertEquals(((1 << 15) | 7), container.nextValue((char) ((1 << 15) | 7)));
			assertEquals(-1, container.nextValue((char) ((1 << 15) | 8)));
		}

		@Test
		@DisplayName("previousValue after the last value returns the last value")
		public void testPreviousValueAfterEnd() {
			final ArrayContainer container = new ArrayContainer(new char[]{10, 20, 30});
			assertEquals(30, container.previousValue((char) 31));
		}
	}

	@Nested
	@DisplayName("nextAbsentValue / previousAbsentValue navigation")
	class NextAndPreviousAbsentValue {

		@Test
		@DisplayName("previousAbsentValue across a single run")
		public void testPreviousAbsentValue1() {
			final Container container = new ArrayContainer().iadd(64, 129);
			assertEquals(0, container.previousAbsentValue((char) 0));
			assertEquals(63, container.previousAbsentValue((char) 63));
			assertEquals(63, container.previousAbsentValue((char) 64));
			assertEquals(63, container.previousAbsentValue((char) 65));
			assertEquals(63, container.previousAbsentValue((char) 128));
			assertEquals(129, container.previousAbsentValue((char) 129));
		}

		@Test
		@DisplayName("previousAbsentValue across three runs")
		public void testPreviousAbsentValue2() {
			final Container container =
				new ArrayContainer().iadd(64, 129).iadd(200, 501).iadd(5000, 5201);
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
		@DisplayName("previousAbsentValue on an empty container returns the query")
		public void testPreviousAbsentValueEmpty() {
			final ArrayContainer container = new ArrayContainer();
			for (int i = 0; i < 1000; i++) {
				assertEquals(i, container.previousAbsentValue((char) i));
			}
		}

		@Test
		@DisplayName("previousAbsentValue on a sparse set")
		public void testPreviousAbsentValueSparse() {
			final ArrayContainer container = new ArrayContainer(new char[]{10, 20, 30});
			assertEquals(9, container.previousAbsentValue((char) 9));
			assertEquals(9, container.previousAbsentValue((char) 10));
			assertEquals(11, container.previousAbsentValue((char) 11));
			assertEquals(21, container.previousAbsentValue((char) 21));
			assertEquals(29, container.previousAbsentValue((char) 30));
		}

		@Test
		@DisplayName("previousAbsentValue with unsigned values")
		public void testPreviousAbsentValueUnsigned() {
			final ArrayContainer container =
				new ArrayContainer(new char[]{(char) ((1 << 15) | 5), (char) ((1 << 15) | 7)});
			assertEquals(((1 << 15) | 4), container.previousAbsentValue((char) ((1 << 15) | 4)));
			assertEquals(((1 << 15) | 4), container.previousAbsentValue((char) ((1 << 15) | 5)));
			assertEquals(((1 << 15) | 6), container.previousAbsentValue((char) ((1 << 15) | 6)));
			assertEquals(((1 << 15) | 6), container.previousAbsentValue((char) ((1 << 15) | 7)));
			assertEquals(((1 << 15) | 8), container.previousAbsentValue((char) ((1 << 15) | 8)));
		}

		@Test
		@DisplayName("nextAbsentValue across a single run")
		public void testNextAbsentValue1() {
			final Container container = new ArrayContainer().iadd(64, 129);
			assertEquals(0, container.nextAbsentValue((char) 0));
			assertEquals(63, container.nextAbsentValue((char) 63));
			assertEquals(129, container.nextAbsentValue((char) 64));
			assertEquals(129, container.nextAbsentValue((char) 65));
			assertEquals(129, container.nextAbsentValue((char) 128));
			assertEquals(129, container.nextAbsentValue((char) 129));
		}

		@Test
		@DisplayName("nextAbsentValue across three runs")
		public void testNextAbsentValue2() {
			final Container container =
				new ArrayContainer().iadd(64, 129).iadd(200, 501).iadd(5000, 5201);
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
		@DisplayName("nextAbsentValue on an empty container returns the query")
		public void testNextAbsentValueEmpty() {
			final ArrayContainer container = new ArrayContainer();
			for (int i = 0; i < 1000; i++) {
				assertEquals(i, container.nextAbsentValue((char) i));
			}
		}

		@Test
		@DisplayName("nextAbsentValue on a sparse set")
		public void testNextAbsentValueSparse() {
			final ArrayContainer container = new ArrayContainer(new char[]{10, 20, 30});
			assertEquals(9, container.nextAbsentValue((char) 9));
			assertEquals(11, container.nextAbsentValue((char) 10));
			assertEquals(11, container.nextAbsentValue((char) 11));
			assertEquals(21, container.nextAbsentValue((char) 21));
			assertEquals(31, container.nextAbsentValue((char) 30));
		}

		@Test
		@DisplayName("nextAbsentValue with unsigned values")
		public void testNextAbsentValueUnsigned() {
			final ArrayContainer container =
				new ArrayContainer(new char[]{(char) ((1 << 15) | 5), (char) ((1 << 15) | 7)});
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
		@DisplayName("Cloned reverse iterator yields the same values as the original")
		public void testReverseArrayContainerShortIterator() {
			final ArrayContainer ac1 = new ArrayContainer(5, 15);
			final ReverseArrayContainerCharIterator rac1 =
				new ReverseArrayContainerCharIterator(ac1);
			final CharIterator rac2 = rac1.clone();
			assertNotNull(rac2);
			assertEquals(asList(rac1), asList(rac2));
		}

		@Test
		@DisplayName("Container survives an external write/read round-trip")
		public void roundtrip() throws Exception {
			Container ac = new ArrayContainer();
			ac = ac.add(1, 5);
			final ByteArrayOutputStream bos = new ByteArrayOutputStream();
			try (final ObjectOutputStream oo = new ObjectOutputStream(bos)) {
				ac.writeExternal(oo);
			}
			final Container ac2 = new ArrayContainer();
			final ByteArrayInputStream bis = new ByteArrayInputStream(bos.toByteArray());
			ac2.readExternal(new ObjectInputStream(bis));

			assertEquals(4, ac2.getCardinality());
			for (int i = 1; i < 5; i++) {
				assertTrue(ac2.contains((char) i));
			}
		}

		@Test
		@DisplayName("Range consumers visit present and absent values correctly")
		public void testRangeConsumer() {
			final char[] entries = new char[]{3, 4, 7, 8, 10, 65530, 65534, 65535};
			ArrayContainer container = new ArrayContainer(entries);

			final ValidationRangeConsumer consumer =
				ValidationRangeConsumer.validate(
					new ValidationRangeConsumer.Value[]{
						ABSENT, ABSENT, ABSENT, PRESENT, PRESENT,
						ABSENT, ABSENT, PRESENT, PRESENT, ABSENT,
						PRESENT
					});
			container.forAllUntil(0, (char) 11, consumer);
			assertEquals(11, consumer.getNumberOfValuesConsumed());

			final ValidationRangeConsumer consumer2 =
				ValidationRangeConsumer.validate(
					new ValidationRangeConsumer.Value[]{
						PRESENT, ABSENT, ABSENT, PRESENT, PRESENT
					});
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

			container = new ArrayContainer();
			final ValidationRangeConsumer consumer6 =
				ValidationRangeConsumer.ofSize(BitmapContainer.MAX_CAPACITY);
			container.forAll(0, consumer6);
			consumer6.assertAllAbsent();

			container = new ArrayContainer();
			final Container c = container.iadd(0, ArrayContainer.DEFAULT_MAX_SIZE);
			assertTrue(container == c, "Container type changed!");
			final ValidationRangeConsumer consumer7 =
				ValidationRangeConsumer.ofSize(ArrayContainer.DEFAULT_MAX_SIZE);
			container.forAllUntil(0, (char) ArrayContainer.DEFAULT_MAX_SIZE, consumer7);
			consumer7.assertAllPresent();
		}
	}

	@Nested
	@DisplayName("Empty-container value navigation")
	class EmptyContainerNavigation {

		/**
		 * Guards against a regression where `nextValue` on an empty container dereferenced
		 * `content[cardinality - 1] == content[-1]` and threw {@link ArrayIndexOutOfBoundsException}
		 * instead of honouring its documented "-1 if none" contract (as the sibling `previousValue`
		 * already does).
		 */
		@Test
		@DisplayName("nextValue on an empty container returns -1 (no stored value >= query)")
		public void nextValueOnEmptyContainerReturnsMinusOne() {
			final ArrayContainer empty = new ArrayContainer();
			assertEquals(0, empty.getCardinality());
			// contract: "first stored value >= fromValue, or -1 if none"
			assertEquals(-1, empty.nextValue((char) 5));
		}
	}

	/**
	 * Collects every value produced by the given iterator into a list, preserving order.
	 *
	 * @param ints the iterator to drain; must not be {@code null}
	 * @return the values produced by the iterator, in iteration order
	 */
	@Nonnull
	private static List<Integer> asList(@Nonnull CharIterator ints) {
		int[] values = new int[10];
		int size = 0;
		while (ints.hasNext()) {
			if (!(size < values.length)) {
				values = Arrays.copyOf(values, values.length * 2);
			}
			values[size++] = ints.next();
		}
		return Ints.asList(Arrays.copyOf(values, size));
	}

	/**
	 * Returns the lower 16 bits of the given value as an unsigned integer.
	 *
	 * @param x the value to truncate
	 * @return the lower 16 bits of {@code x} interpreted as an unsigned value
	 */
	private static int lower16Bits(final int x) {
		return ((char) x) & 0xFFFF;
	}
}
