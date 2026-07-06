package io.evitadb.roaringbitmap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static io.evitadb.roaringbitmap.ValidationRangeConsumer.Value.ABSENT;
import static io.evitadb.roaringbitmap.ValidationRangeConsumer.Value.PRESENT;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * Verifies {@link Container} and its implementations {@link ArrayContainer},
 * {@link BitmapContainer} and {@link RunContainer}. The suite exercises set algebra
 * (`and`, `or`, `xor`, `not`/`inot`), run counting and run optimization,
 * container-type transitions across the array/bitmap size threshold, range
 * materialization and full value iteration (`forAll` family).
 *
 * Ported from the upstream RoaringBitmap test-suite and retained as a regression guard
 * for the vendored `io.evitadb.roaringbitmap` module.
 */
@SuppressWarnings({"static-method"})
@DisplayName("Container set algebra, run handling and value iteration")
public class TestContainer {

	private static final Class<?>[] CONTAINER_TYPES =
		new Class[]{ArrayContainer.class, BitmapContainer.class, RunContainer.class};

	/**
	 * Iterates over the given container and checks that it yields exactly the expected values in
	 * order. On mismatch the actual and expected contents are printed to help diagnose failures.
	 *
	 * @param c container to inspect
	 * @param s expected values, in ascending order
	 * @return {@code true} when the container content matches {@code s} exactly
	 */
	public static boolean checkContent(@Nonnull Container c, @Nonnull char[] s) {
		CharIterator si = c.getCharIterator();
		int ctr = 0;
		boolean fail = false;
		while (si.hasNext()) {
			if (ctr == s.length) {
				fail = true;
				break;
			}
			if (si.next() != s[ctr]) {
				fail = true;
				break;
			}
			++ctr;
		}
		if (ctr != s.length) {
			fail = true;
		}
		if (fail) {
			System.out.print("fail, found ");
			si = c.getCharIterator();
			while (si.hasNext()) {
				System.out.print(" " + si.next());
			}
			System.out.print("\n expected ");
			for (final char s1 : s) {
				System.out.print(" " + s1);
			}
			System.out.println();
		}
		return !fail;
	}

	/**
	 * Builds a container from the supplied values, starting from an {@link ArrayContainer} and
	 * letting it promote itself as values are added.
	 *
	 * @param ss values to add
	 * @return a container holding exactly the supplied values
	 */
	@Nonnull
	public static Container makeContainer(@Nonnull char[] ss) {
		Container c = new ArrayContainer();
		for (final char s : ss) {
			c = c.add(s);
		}
		return c;
	}

	@Nested
	@DisplayName("Container naming")
	class Naming {

		@Test
		@DisplayName("Reports the canonical name for each container type")
		void testNames() {
			assertEquals(Container.ContainerNames[0], new BitmapContainer().getContainerName());
			assertEquals(Container.ContainerNames[1], new ArrayContainer().getContainerName());
			assertEquals(Container.ContainerNames[2], new RunContainer().getContainerName());
		}
	}

	@Nested
	@DisplayName("AND (intersection) across all container-type pairs")
	class And {

		@Test
		@DisplayName("AND with an empty container yields an empty result")
		void and1() throws InstantiationException, IllegalAccessException {
			System.out.println("and1");
			for (final Class<?> ct : CONTAINER_TYPES) {
				final Container ac = (Container) ct.newInstance();
				ac.add((char) 1);
				ac.add((char) 3);
				ac.add((char) 5);
				ac.add((char) 50000);
				ac.add((char) 50001);
				for (final Class<?> ct1 : CONTAINER_TYPES) {
					final Container ac1 = (Container) ct1.newInstance();
					final Container result = ac.and(ac1);
					assertTrue(checkContent(result, new char[]{}));
					assertEquals(0, result.getCardinality());
					assertEquals(0, ac.andCardinality(ac1));
					assertEquals(0, ac1.andCardinality(ac));
				}
			}
		}

		@Test
		@DisplayName("AND keeps only the single shared element")
		void and2() throws InstantiationException, IllegalAccessException {
			System.out.println("and2");
			for (final Class<?> ct : CONTAINER_TYPES) {
				final Container ac = (Container) ct.newInstance();
				ac.add((char) 1);
				for (final Class<?> ct1 : CONTAINER_TYPES) {
					final Container ac1 = (Container) ct1.newInstance();

					ac1.add((char) 1);
					ac1.add((char) 4);
					ac1.add((char) 5);
					ac1.add((char) 50000);
					ac1.add((char) 50002);
					ac1.add((char) 50003);
					ac1.add((char) 50004);

					final Container result = ac.and(ac1);
					assertTrue(checkContent(result, new char[]{1}));
					assertEquals(result.getCardinality(), ac.andCardinality(ac1));
					assertEquals(result.getCardinality(), ac1.andCardinality(ac));
					assertEquals(1, ac1.andCardinality(ac));
				}
			}
		}

		@Test
		@DisplayName("AND keeps shared elements when the left side ends first")
		void and3() throws InstantiationException, IllegalAccessException {
			System.out.println("and3");
			for (final Class<?> ct : CONTAINER_TYPES) {
				final Container ac = (Container) ct.newInstance();

				ac.add((char) 1);
				ac.add((char) 3);
				ac.add((char) 5);
				ac.add((char) 50000);
				ac.add((char) 50001);

				// array ends first

				for (final Class<?> ct1 : CONTAINER_TYPES) {
					final Container ac1 = (Container) ct1.newInstance();

					ac1.add((char) 1);
					ac1.add((char) 4);
					ac1.add((char) 5);
					ac1.add((char) 50000);
					ac1.add((char) 50002);
					ac1.add((char) 50003);
					ac1.add((char) 50004);

					final Container result = ac.and(ac1);
					assertTrue(checkContent(result, new char[]{1, 5, (char) 50000}));
					assertEquals(result.getCardinality(), ac.andCardinality(ac1));
					assertEquals(result.getCardinality(), ac1.andCardinality(ac));
					assertEquals(3, ac1.andCardinality(ac));
				}
			}
		}

		@Test
		@DisplayName("AND keeps shared elements when the right side ends first")
		void and4() throws InstantiationException, IllegalAccessException {
			System.out.println("and4");
			for (final Class<?> ct : CONTAINER_TYPES) {
				final Container ac = (Container) ct.newInstance();

				ac.add((char) 1);
				ac.add((char) 3);
				ac.add((char) 5);
				ac.add((char) 50000);
				ac.add((char) 50001);
				ac.add((char) 50011);

				// iterator ends first

				for (final Class<?> ct1 : CONTAINER_TYPES) {
					final Container ac1 = (Container) ct1.newInstance();

					ac1.add((char) 1);
					ac1.add((char) 4);
					ac1.add((char) 5);
					ac1.add((char) 50000);
					ac1.add((char) 50002);
					ac1.add((char) 50003);
					ac1.add((char) 50004);

					final Container result = ac.and(ac1);
					assertTrue(checkContent(result, new char[]{1, 5, (char) 50000}));
					assertEquals(result.getCardinality(), ac.andCardinality(ac1));
					assertEquals(result.getCardinality(), ac1.andCardinality(ac));
					assertEquals(3, ac1.andCardinality(ac));
				}
			}
		}

		@Test
		@DisplayName("AND keeps shared elements when both sides end together")
		void and5() throws InstantiationException, IllegalAccessException {
			System.out.println("and5");
			for (final Class<?> ct : CONTAINER_TYPES) {
				final Container ac = (Container) ct.newInstance();

				ac.add((char) 1);
				ac.add((char) 3);
				ac.add((char) 5);
				ac.add((char) 50000);
				ac.add((char) 50001);

				// end together

				for (final Class<?> ct1 : CONTAINER_TYPES) {
					final Container ac1 = (Container) ct1.newInstance();

					ac1.add((char) 1);
					ac1.add((char) 4);
					ac1.add((char) 5);
					ac1.add((char) 50000);
					ac1.add((char) 50001);

					final Container result = ac.and(ac1);
					assertTrue(checkContent(result, new char[]{1, 5, (char) 50000, (char) 50001}));
					assertEquals(result.getCardinality(), ac.andCardinality(ac1));
					assertEquals(result.getCardinality(), ac1.andCardinality(ac));
					assertEquals(4, ac1.andCardinality(ac));
				}
			}
		}
	}

	@Nested
	@DisplayName("In-place NOT (inot)")
	class InPlaceNot {

		@Test
		@DisplayName("In-place NOT over the full range inverts every bit of an array container")
		void inotTest1() {
			// Array container, range is complete
			final char[] content = {1, 3, 5, 7, 9};
			Container c = makeContainer(content);
			c = c.inot(0, 65536);
			final char[] s = new char[65536 - content.length];
			int pos = 0;
			for (int i = 0; i < 65536; ++i) {
				if (Arrays.binarySearch(content, (char) i) < 0) {
					s[pos++] = (char) i;
				}
			}
			assertTrue(checkContent(c, s));
		}

		@Test
		@DisplayName("In-place NOT of a range past all set bits appends the inverted range")
		void inotTest10() {
			System.out.println("inotTest10");
			// Array container, inverting a range past any set bit
			final char[] content = new char[3];
			content[0] = 0;
			content[1] = 2;
			content[2] = 4;
			final Container c = makeContainer(content);
			final Container c1 = c.inot(65190, 65201);
			assertTrue(c1 instanceof ArrayContainer);
			assertEquals(14, c1.getCardinality());
			assertTrue(
				checkContent(
					c1,
					new char[]{
						0,
						2,
						4,
						(char) 65190,
						(char) 65191,
						(char) 65192,
						(char) 65193,
						(char) 65194,
						(char) 65195,
						(char) 65196,
						(char) 65197,
						(char) 65198,
						(char) 65199,
						(char) 65200
					}
				));
		}

		@Test
		@DisplayName("In-place NOT applied twice restores the original content")
		void inotTest2() {
			// Array and then Bitmap container, range is complete
			final char[] content = {1, 3, 5, 7, 9};
			Container c = makeContainer(content);
			c = c.inot(0, 65535);
			c = c.inot(0, 65535);
			assertTrue(checkContent(c, content));
		}

		@Test
		@DisplayName("In-place NOT over the full range on a bitmap inverts and then restores")
		void inotTest3() {
			// Bitmap to bitmap, full range

			Container c = new ArrayContainer();
			for (int i = 0; i < 65536; i += 2) {
				c = c.add((char) i);
			}

			c = c.inot(0, 65536);
			assertTrue(c.contains((char) 3) && !c.contains((char) 4));
			assertEquals(32768, c.getCardinality());
			c = c.inot(0, 65536);
			for (int i = 0; i < 65536; i += 2) {
				assertTrue(c.contains((char) i) && !c.contains((char) (i + 1)));
			}
		}

		@Test
		@DisplayName("In-place NOT of a partial range keeps an array container")
		void inotTest4() {
			// Array container, range is partial, result stays array
			final char[] content = {1, 3, 5, 7, 9};
			Container c = makeContainer(content);
			c = c.inot(4, 1000);
			assertTrue(c instanceof ArrayContainer);
			assertEquals(999 - 4 + 1 - 3 + 2, c.getCardinality());
			c = c.inot(4, 1000); // back
			assertTrue(checkContent(c, content));
		}

		@Test
		@DisplayName("In-place NOT of a partial range keeps a bitmap container")
		void inotTest5() {
			System.out.println("inotTest5");
			// Bitmap container, range is partial, result stays bitmap
			final char[] content = new char[32768 - 5];
			content[0] = 0;
			content[1] = 2;
			content[2] = 4;
			content[3] = 6;
			content[4] = 8;
			for (int i = 10; i <= 32767; ++i) {
				content[i - 10 + 5] = (char) i;
			}
			Container c = makeContainer(content);
			c = c.inot(4, 1000);
			assertTrue(c instanceof BitmapContainer);
			assertEquals(31773, c.getCardinality());
			c = c.inot(4, 1000); // back, as a bitmap
			assertTrue(c instanceof BitmapContainer);
			assertTrue(checkContent(c, content));
		}

		@Test
		@DisplayName("In-place NOT of a partial single-word range keeps a bitmap container")
		void inotTest6() {
			System.out.println("inotTest6");
			// Bitmap container, range is partial and in one word, result
			// stays bitmap
			final char[] content = new char[32768 - 5];
			content[0] = 0;
			content[1] = 2;
			content[2] = 4;
			content[3] = 6;
			content[4] = 8;
			for (int i = 10; i <= 32767; ++i) {
				content[i - 10 + 5] = (char) i;
			}
			Container c = makeContainer(content);
			c = c.inot(4, 9);
			assertTrue(c instanceof BitmapContainer);
			assertEquals(32762, c.getCardinality());
			c = c.inot(4, 9); // back, as a bitmap
			assertTrue(c instanceof BitmapContainer);
			assertTrue(checkContent(c, content));
		}

		@Test
		@DisplayName("In-place NOT of a partial range may flip a bitmap to an array container")
		void inotTest7() {
			System.out.println("inotTest7");
			// Bitmap container, range is partial, result flips to array
			final char[] content = new char[32768 - 5];
			content[0] = 0;
			content[1] = 2;
			content[2] = 4;
			content[3] = 6;
			content[4] = 8;
			for (int i = 10; i <= 32767; ++i) {
				content[i - 10 + 5] = (char) i;
			}
			Container c = makeContainer(content);
			c = c.inot(5, 31001);
			if (c.getCardinality() <= ArrayContainer.DEFAULT_MAX_SIZE) {
				assertTrue(c instanceof ArrayContainer);
			} else {
				assertTrue(c instanceof BitmapContainer);
			}
			assertEquals(1773, c.getCardinality());
			c = c.inot(5, 31001); // back, as a bitmap
			if (c.getCardinality() <= ArrayContainer.DEFAULT_MAX_SIZE) {
				assertTrue(c instanceof ArrayContainer);
			} else {
				assertTrue(c instanceof BitmapContainer);
			}
			assertTrue(checkContent(c, content));
		}

		// case requiring contraction of ArrayContainer.
		@Test
		@DisplayName("In-place NOT that contracts an array container")
		void inotTest8() {
			System.out.println("inotTest8");
			// Array container
			final char[] content = new char[21];
			for (int i = 0; i < 18; ++i) {
				content[i] = (char) i;
			}
			content[18] = 21;
			content[19] = 22;
			content[20] = 23;

			Container c = makeContainer(content);
			c = c.inot(5, 22);
			assertTrue(c instanceof ArrayContainer);

			assertEquals(10, c.getCardinality());
			c = c.inot(5, 22); // back, as a bitmap
			assertTrue(c instanceof ArrayContainer);
			assertTrue(checkContent(c, content));
		}
	}

	@Nested
	@DisplayName("NOT (leaves the original container untouched)")
	class Not {

		// mostly same tests, except for not. (check original unaffected)
		@Test
		@DisplayName("NOT over the full range inverts every bit and leaves the original untouched")
		void notTest1() {
			// Array container, range is complete
			final char[] content = {1, 3, 5, 7, 9};
			final Container c = makeContainer(content);
			final Container c1 = c.not(0, 65536);
			final char[] s = new char[65536 - content.length];
			int pos = 0;
			for (int i = 0; i < 65536; ++i) {
				if (Arrays.binarySearch(content, (char) i) < 0) {
					s[pos++] = (char) i;
				}
			}
			assertTrue(checkContent(c1, s));
			assertTrue(checkContent(c, content));
		}

		@Test
		@DisplayName("NOT of a range past all set bits appends the inverted range")
		void notTest10() {
			System.out.println("notTest10");
			// Array container, inverting a range past any set bit
			// attempting to recreate a bug (but bug required extra space
			// in the array with just the right junk in it.
			final char[] content = new char[40];
			for (int i = 244; i <= 283; ++i) {
				content[i - 244] = (char) i;
			}
			final Container c = makeContainer(content);
			final Container c1 = c.not(51413, 51471);
			assertTrue(c1 instanceof ArrayContainer);
			assertEquals(40 + 58, c1.getCardinality());
			final char[] rightAns = new char[98];
			for (int i = 244; i <= 283; ++i) {
				rightAns[i - 244] = (char) i;
			}
			for (int i = 51413; i <= 51470; ++i) {
				rightAns[i - 51413 + 40] = (char) i;
			}

			assertTrue(checkContent(c1, rightAns));
		}

		@Test
		@DisplayName("NOT of a range before all set bits prepends the inverted range")
		void notTest11() {
			System.out.println("notTest11");
			// Array container, inverting a range before any set bit
			// attempting to recreate a bug (but required extra space
			// in the array with the right junk in it.
			final char[] content = new char[40];
			for (int i = 244; i <= 283; ++i) {
				content[i - 244] = (char) i;
			}
			final Container c = makeContainer(content);
			final Container c1 = c.not(1, 59);
			assertTrue(c1 instanceof ArrayContainer);
			assertEquals(40 + 58, c1.getCardinality());
			final char[] rightAns = new char[98];
			for (int i = 1; i <= 58; ++i) {
				rightAns[i - 1] = (char) i;
			}
			for (int i = 244; i <= 283; ++i) {
				rightAns[i - 244 + 58] = (char) i;
			}

			assertTrue(checkContent(c1, rightAns));
		}

		@Test
		@DisplayName("NOT applied twice restores the original content")
		void notTest2() {
			// Array and then Bitmap container, range is complete
			final char[] content = {1, 3, 5, 7, 9};
			final Container c = makeContainer(content);
			final Container c1 = c.not(0, 65535);
			final Container c2 = c1.not(0, 65535);
			assertTrue(checkContent(c2, content));
		}

		@Test
		@DisplayName("NOT over the full range on a bitmap inverts and then restores")
		void notTest3() {
			// Bitmap to bitmap, full range

			Container c = new ArrayContainer();
			for (int i = 0; i < 65536; i += 2) {
				c = c.add((char) i);
			}

			final Container c1 = c.not(0, 65536);
			assertTrue(c1.contains((char) 3) && !c1.contains((char) 4));
			assertEquals(32768, c1.getCardinality());
			final Container c2 = c1.not(0, 65536);
			for (int i = 0; i < 65536; i += 2) {
				assertTrue(c2.contains((char) i) && !c2.contains((char) (i + 1)));
			}
		}

		@Test
		@DisplayName("NOT of a partial range keeps an array container")
		void notTest4() {
			System.out.println("notTest4");
			// Array container, range is partial, result stays array
			final char[] content = {1, 3, 5, 7, 9};
			final Container c = makeContainer(content);
			final Container c1 = c.not(4, 1000);
			assertTrue(c1 instanceof ArrayContainer);
			assertEquals(999 - 4 + 1 - 3 + 2, c1.getCardinality());
			final Container c2 = c1.not(4, 1000); // back
			assertTrue(checkContent(c2, content));
		}

		@Test
		@DisplayName("NOT of a partial range keeps a bitmap container")
		void notTest5() {
			System.out.println("notTest5");
			// Bitmap container, range is partial, result stays bitmap
			final char[] content = new char[32768 - 5];
			content[0] = 0;
			content[1] = 2;
			content[2] = 4;
			content[3] = 6;
			content[4] = 8;
			for (int i = 10; i <= 32767; ++i) {
				content[i - 10 + 5] = (char) i;
			}
			final Container c = makeContainer(content);
			final Container c1 = c.not(4, 1000);
			assertTrue(c1 instanceof BitmapContainer);
			assertEquals(31773, c1.getCardinality());
			final Container c2 = c1.not(4, 1000); // back, as a bitmap
			assertTrue(c2 instanceof BitmapContainer);
			assertTrue(checkContent(c2, content));
		}

		@Test
		@DisplayName("NOT of a partial single-word range keeps a bitmap container")
		void notTest6() {
			System.out.println("notTest6");
			// Bitmap container, range is partial and in one word, result
			// stays bitmap
			final char[] content = new char[32768 - 5];
			content[0] = 0;
			content[1] = 2;
			content[2] = 4;
			content[3] = 6;
			content[4] = 8;
			for (int i = 10; i <= 32767; ++i) {
				content[i - 10 + 5] = (char) i;
			}
			final Container c = makeContainer(content);
			final Container c1 = c.not(4, 9);
			assertTrue(c1 instanceof BitmapContainer);
			assertEquals(32762, c1.getCardinality());
			final Container c2 = c1.not(4, 9); // back, as a bitmap
			assertTrue(c2 instanceof BitmapContainer);
			assertTrue(checkContent(c2, content));
		}

		@Test
		@DisplayName("NOT of a partial range may flip a bitmap to an array container")
		void notTest7() {
			System.out.println("notTest7");
			// Bitmap container, range is partial, result flips to array
			final char[] content = new char[32768 - 5];
			content[0] = 0;
			content[1] = 2;
			content[2] = 4;
			content[3] = 6;
			content[4] = 8;
			for (int i = 10; i <= 32767; ++i) {
				content[i - 10 + 5] = (char) i;
			}
			final Container c = makeContainer(content);
			final Container c1 = c.not(5, 31001);
			if (c1.getCardinality() <= ArrayContainer.DEFAULT_MAX_SIZE) {
				assertTrue(c1 instanceof ArrayContainer);
			} else {
				assertTrue(c1 instanceof BitmapContainer);
			}
			assertEquals(1773, c1.getCardinality());
			final Container c2 = c1.not(5, 31001); // back, as a bitmap
			if (c2.getCardinality() <= ArrayContainer.DEFAULT_MAX_SIZE) {
				assertTrue(c2 instanceof ArrayContainer);
			} else {
				assertTrue(c2 instanceof BitmapContainer);
			}
			assertTrue(checkContent(c2, content));
		}

		@Test
		@DisplayName("NOT of a range partial at the lower end keeps a bitmap container")
		void notTest8() {
			System.out.println("notTest8");
			// Bitmap container, range is partial on the lower end
			final char[] content = new char[32768 - 5];
			content[0] = 0;
			content[1] = 2;
			content[2] = 4;
			content[3] = 6;
			content[4] = 8;
			for (int i = 10; i <= 32767; ++i) {
				content[i - 10 + 5] = (char) i;
			}
			final Container c = makeContainer(content);
			final Container c1 = c.not(4, 65536);
			assertTrue(c1 instanceof BitmapContainer);
			assertEquals(32773, c1.getCardinality());
			final Container c2 = c1.not(4, 65536); // back, as a bitmap
			assertTrue(c2 instanceof BitmapContainer);
			assertTrue(checkContent(c2, content));
		}

		@Test
		@DisplayName("NOT of a multi-word range partial at the upper end keeps a bitmap container")
		void notTest9() {
			System.out.println("notTest9");
			// Bitmap container, range is partial on the upper end, not
			// single word
			final char[] content = new char[32768 - 5];
			content[0] = 0;
			content[1] = 2;
			content[2] = 4;
			content[3] = 6;
			content[4] = 8;
			for (int i = 10; i <= 32767; ++i) {
				content[i - 10 + 5] = (char) i;
			}
			final Container c = makeContainer(content);
			final Container c1 = c.not(0, 65201);
			assertTrue(c1 instanceof BitmapContainer);
			assertEquals(32438, c1.getCardinality());
			final Container c2 = c1.not(0, 65201); // back, as a bitmap
			assertTrue(c2 instanceof BitmapContainer);
			assertTrue(checkContent(c2, content));
		}
	}

	@Nested
	@DisplayName("Run counting")
	class RunCount {

		@Test
		@DisplayName("All container types report the same number of runs for a fixed set")
		void numberOfRuns() {
			final char[] positions = {3, 4, 5, 10, 11, 13, 15, 62, 63, 64, 65};
			final Container ac = new ArrayContainer();
			final Container bc = new BitmapContainer();
			final Container rc = new RunContainer();
			for (final char position : positions) {
				ac.add(position);
				bc.add(position);
				rc.add(position);
			}
			assertEquals(rc.numberOfRuns(), ac.numberOfRuns());
			assertEquals(rc.numberOfRuns(), bc.numberOfRuns());
		}

		@Test
		@DisplayName("All container types agree on the number of runs across densities")
		void numberOfRuns1() {
			System.out.println("numberOfRuns1");
			final Random r = new Random(12345);

			for (double density = 0.001; density < 0.8; density *= 2) {

				final ArrayList<Integer> values = new ArrayList<Integer>();
				for (int i = 0; i < 65536; ++i) {
					if (r.nextDouble() < density) {
						values.add(i);
					}
				}
				final Integer[] positions = values.toArray(new Integer[0]);
				Container ac = new ArrayContainer(); // at high density becomes Bitmap
				final BitmapContainer bc = new BitmapContainer();
				final Container rc = new RunContainer();
				for (final int position : positions) {
					ac = ac.add((char) position);
					bc.add((char) position);
					rc.add((char) position);
				}

				assertEquals(rc.numberOfRuns(), ac.numberOfRuns());
				assertEquals(rc.numberOfRuns(), bc.numberOfRuns());
				// a limit of 50k assures that the no early bail-out can be taken
				assertEquals(
					bc.numberOfRuns(), bc.numberOfRunsLowerBound(50000) + bc.numberOfRunsAdjustment());
				// inferior approaches to be removed in a future cleanup, now commented...
				// assertEquals(bc.numberOfRunsLowerBound(), bc.numberOfRunsLowerBoundUnrolled());
				// assertEquals(bc.numberOfRunsLowerBound(), bc.numberOfRunsLowerBoundUnrolled2());
				// assertEquals(bc.numberOfRunsAdjustment(), bc.numberOfRunsAdjustmentUnrolled());
			}
		}
	}

	@Nested
	@DisplayName("OR (union) fed from a char iterator")
	class Or {

		@Test
		@DisplayName("OR with an empty iterator returns the original elements")
		void or1() {
			System.out.println("or1");
			final ArrayContainer ac = new ArrayContainer();
			ac.add((char) 1);
			ac.add((char) 3);
			ac.add((char) 5);
			ac.add((char) 50000);
			ac.add((char) 50001);

			final ArrayContainer ac1 = new ArrayContainer(); // empty iterator
			final Container result = ac.or(ac1.getCharIterator());
			assertTrue(checkContent(result, new char[]{1, 3, 5, (char) 50000, (char) 50001}));
		}

		@Test
		@DisplayName("OR from an empty container yields the other side's elements")
		void or2() {
			System.out.println("or2");
			final ArrayContainer ac = new ArrayContainer();
			// empty array

			final ArrayContainer ac1 = new ArrayContainer();
			ac1.add((char) 1);
			ac1.add((char) 4);
			ac1.add((char) 5);
			ac1.add((char) 50000);
			ac1.add((char) 50002);
			ac1.add((char) 50003);
			ac1.add((char) 50004);

			final Container result = ac.or(ac1.getCharIterator());
			assertTrue(
				checkContent(
					result, new char[]{1, 4, 5, (char) 50000, (char) 50002, (char) 50003, (char) 50004}));
		}

		@Test
		@DisplayName("OR merges both sides when the left side ends first")
		void or3() {
			System.out.println("or3");
			final ArrayContainer ac = new ArrayContainer();
			ac.add((char) 1);
			ac.add((char) 3);
			ac.add((char) 5);
			ac.add((char) 50000);
			ac.add((char) 50001);

			// array ends first

			final ArrayContainer ac1 = new ArrayContainer();
			ac1.add((char) 1);
			ac1.add((char) 4);
			ac1.add((char) 5);
			ac1.add((char) 50000);
			ac1.add((char) 50002);
			ac1.add((char) 50003);
			ac1.add((char) 50004);

			final Container result = ac.or(ac1.getCharIterator());
			assertTrue(
				checkContent(
					result,
					new char[]{
						1, 3, 4, 5, (char) 50000, (char) 50001, (char) 50002, (char) 50003, (char) 50004
					}
				));
		}

		@Test
		@DisplayName("OR merges both sides when the right side ends first")
		void or4() {
			System.out.println("or4");
			final ArrayContainer ac = new ArrayContainer();
			ac.add((char) 1);
			ac.add((char) 3);
			ac.add((char) 5);
			ac.add((char) 50000);
			ac.add((char) 50001);
			ac.add((char) 50011);

			// iterator ends first

			final ArrayContainer ac1 = new ArrayContainer();
			ac1.add((char) 1);
			ac1.add((char) 4);
			ac1.add((char) 5);
			ac1.add((char) 50000);
			ac1.add((char) 50002);
			ac1.add((char) 50003);
			ac1.add((char) 50004);

			final Container result = ac.or(ac1.getCharIterator());
			assertTrue(
				checkContent(
					result,
					new char[]{
						1,
						3,
						4,
						5,
						(char) 50000,
						(char) 50001,
						(char) 50002,
						(char) 50003,
						(char) 50004,
						(char) 50011
					}
				));
		}

		@Test
		@DisplayName("OR merges both sides when both end together")
		void or5() {
			System.out.println("or5");
			final ArrayContainer ac = new ArrayContainer();
			ac.add((char) 1);
			ac.add((char) 3);
			ac.add((char) 5);
			ac.add((char) 50000);
			ac.add((char) 50001);

			// end together

			final ArrayContainer ac1 = new ArrayContainer();
			ac1.add((char) 1);
			ac1.add((char) 4);
			ac1.add((char) 5);
			ac1.add((char) 50000);
			ac1.add((char) 50001);

			final Container result = ac.or(ac1.getCharIterator());
			assertTrue(checkContent(result, new char[]{1, 3, 4, 5, (char) 50000, (char) 50001}));
		}

		@Test
		@DisplayName("OR of two sparse run containers produces an array container")
		void or6() {
			System.out.println("or6");
			final RunContainer rc1 = new RunContainer();
			for (int i = 0; i < 6144; i += 6) {
				rc1.iadd(i, i + 1);
			}

			final RunContainer rc2 = new RunContainer();

			for (int i = 3; i < 6144; i += 6) {
				rc2.iadd(i, i + 1);
			}

			final Container result = rc1.or(rc2);
			assertTrue(result.getCardinality() < ArrayContainer.DEFAULT_MAX_SIZE);
			assertTrue(result instanceof ArrayContainer);
		}
	}

	@Nested
	@DisplayName("XOR (symmetric difference)")
	class Xor {

		@Test
		@DisplayName("xorCardinality matches the cardinality of xor for every container pair")
		void testXorContainer() throws Exception {
			final Container rc1 = new RunContainer(new char[]{10, 12, 90, 10}, 2);
			final Container rc2 = new RunContainer(new char[]{1, 10, 40, 400, 900, 10}, 3);
			final Container bc1 = new BitmapContainer().add(10, 20);
			final Container bc2 = new BitmapContainer().add(21, 30);
			final Container ac1 = new ArrayContainer(4, new char[]{10, 12, 90, 104});
			final Container ac2 = new ArrayContainer(2, new char[]{1, 10, 40, 400, 900, 1910});
			for (final Set<Container> test : Sets.powerSet(ImmutableSet.of(rc1, rc2, bc1, bc2, ac1, ac2))) {
				final Iterator<Container> it = test.iterator();
				if (test.size() == 1) { // compare with self
					final Container x = it.next();
					assertEquals(
						x.xor(x).getCardinality(), x.xorCardinality(x), x.getContainerName() + ": " + x);
				} else if (test.size() == 2) {
					final Container x = it.next();
					final Container y = it.next();
					assertEquals(
						x.xor(y).getCardinality(),
						x.xorCardinality(y),
						x.getContainerName() + " " + x + " " + y.getContainerName() + " " + y
					);
					assertEquals(
						y.xor(x).getCardinality(),
						y.xorCardinality(x),
						y.getContainerName() + " " + y + " " + x.getContainerName() + " " + x
					);
				}
			}
		}

		@Test
		@DisplayName("XOR with an empty iterator returns the original elements")
		void xor1() {
			System.out.println("xor1");
			final ArrayContainer ac = new ArrayContainer();
			ac.add((char) 1);
			ac.add((char) 3);
			ac.add((char) 5);
			ac.add((char) 50000);
			ac.add((char) 50001);

			final ArrayContainer ac1 = new ArrayContainer(); // empty iterator
			final Container result = ac.xor(ac1.getCharIterator());
			assertTrue(checkContent(result, new char[]{1, 3, 5, (char) 50000, (char) 50001}));
		}

		@Test
		@DisplayName("XOR from an empty container yields the other side's elements")
		void xor2() {
			System.out.println("xor2");
			final ArrayContainer ac = new ArrayContainer();
			// empty array

			final ArrayContainer ac1 = new ArrayContainer();
			ac1.add((char) 1);
			ac1.add((char) 4);
			ac1.add((char) 5);
			ac1.add((char) 50000);
			ac1.add((char) 50002);
			ac1.add((char) 50003);
			ac1.add((char) 50004);

			final Container result = ac.xor(ac1.getCharIterator());
			assertTrue(
				checkContent(
					result, new char[]{1, 4, 5, (char) 50000, (char) 50002, (char) 50003, (char) 50004}));
		}

		@Test
		@DisplayName("XOR keeps the symmetric difference when the left side ends first")
		void xor3() {
			System.out.println("xor3");
			final ArrayContainer ac = new ArrayContainer();
			ac.add((char) 1);
			ac.add((char) 3);
			ac.add((char) 5);
			ac.add((char) 50000);
			ac.add((char) 50001);

			// array ends first

			final ArrayContainer ac1 = new ArrayContainer();
			ac1.add((char) 1);
			ac1.add((char) 4);
			ac1.add((char) 5);
			ac1.add((char) 50000);
			ac1.add((char) 50002);
			ac1.add((char) 50003);
			ac1.add((char) 50004);

			final Container result = ac.xor(ac1.getCharIterator());
			assertTrue(
				checkContent(
					result, new char[]{3, 4, (char) 50001, (char) 50002, (char) 50003, (char) 50004}));
		}

		@Test
		@DisplayName("XOR keeps the symmetric difference when the right side ends first")
		void xor4() {
			System.out.println("xor4");
			final ArrayContainer ac = new ArrayContainer();
			ac.add((char) 1);
			ac.add((char) 3);
			ac.add((char) 5);
			ac.add((char) 50000);
			ac.add((char) 50001);
			ac.add((char) 50011);

			// iterator ends first

			final ArrayContainer ac1 = new ArrayContainer();
			ac1.add((char) 1);
			ac1.add((char) 4);
			ac1.add((char) 5);
			ac1.add((char) 50000);
			ac1.add((char) 50002);
			ac1.add((char) 50003);
			ac1.add((char) 50004);

			final Container result = ac.xor(ac1.getCharIterator());
			assertTrue(
				checkContent(
					result,
					new char[]{
						3, 4, (char) 50001, (char) 50002, (char) 50003, (char) 50004, (char) 50011
					}
				));
		}

		@Test
		@DisplayName("XOR keeps the symmetric difference when both sides end together")
		void xor5() {
			System.out.println("xor5");
			final ArrayContainer ac = new ArrayContainer();
			ac.add((char) 1);
			ac.add((char) 3);
			ac.add((char) 5);
			ac.add((char) 50000);
			ac.add((char) 50001);

			// end together

			final ArrayContainer ac1 = new ArrayContainer();
			ac1.add((char) 1);
			ac1.add((char) 4);
			ac1.add((char) 5);
			ac1.add((char) 50000);
			ac1.add((char) 50001);

			final Container result = ac.xor(ac1.getCharIterator());
			assertTrue(checkContent(result, new char[]{3, 4}));
		}
	}

	@Nested
	@DisplayName("Container.rangeOfOnes factory")
	class RangeOfOnes {

		@Test
		@DisplayName("rangeOfOnes materializes a sparse range")
		void rangeOfOnesTest1() {
			final Container c = Container.rangeOfOnes(4, 11); // sparse
			// assertTrue(c instanceof ArrayContainer);
			assertEquals(10 - 4 + 1, c.getCardinality());
			assertTrue(checkContent(c, new char[]{4, 5, 6, 7, 8, 9, 10}));
		}

		@Test
		@DisplayName("rangeOfOnes reports the correct cardinality for a dense range")
		void rangeOfOnesTest2() {
			final Container c = Container.rangeOfOnes(1000, 35001); // dense
			// assertTrue(c instanceof BitmapContainer);
			assertEquals(35000 - 1000 + 1, c.getCardinality());
		}

		@Test
		@DisplayName("rangeOfOnes materializes every value of a dense range")
		void rangeOfOnesTest2A() {
			final Container c = Container.rangeOfOnes(1000, 35001); // dense
			final char[] s = new char[35000 - 1000 + 1];
			for (int i = 1000; i <= 35000; ++i) {
				s[i - 1000] = (char) i;
			}
			assertTrue(checkContent(c, s));
		}

		@Test
		@DisplayName("rangeOfOnes accepts a range up to the array container threshold")
		void rangeOfOnesTest3() {
			// bdry cases
			Container.rangeOfOnes(1, ArrayContainer.DEFAULT_MAX_SIZE);
		}

		@Test
		@DisplayName("rangeOfOnes accepts a range just past the array container threshold")
		void rangeOfOnesTest4() {
			Container.rangeOfOnes(1, ArrayContainer.DEFAULT_MAX_SIZE + 2);
		}
	}

	@Nested
	@DisplayName("runOptimize container-type selection")
	class RunOptimize {

		@Test
		@DisplayName("runOptimize converts a runny array container into a run container")
		void testRunOptimize1() {
			final ArrayContainer ac = new ArrayContainer();
			for (final char s : new char[]{1, 2, 3, 4, 5, 6, 7, 8, 9, (char) 50000, (char) 50001}) {
				ac.add(s);
			}
			final Container c = ac.runOptimize();
			assertTrue(c instanceof RunContainer);
			assertEquals(ac, c);
		}

		public void testRunOptimize1A() {
			ArrayContainer ac = new ArrayContainer();
			for (char s : new char[]{1, 2, 3, 4, 6, 8, 9, (char) 50000, (char) 50003}) {
				ac.add(s);
			}
			Container c = ac.runOptimize();
			assertTrue(c instanceof ArrayContainer);
			assertSame(ac, c);
		}

		@Test
		@DisplayName("runOptimize converts a dense bitmap container into a run container")
		void testRunOptimize2() {
			final BitmapContainer bc = new BitmapContainer();
			for (int i = 0; i < 40000; ++i) {
				bc.add((char) i);
			}
			final Container c = bc.runOptimize();
			assertTrue(c instanceof RunContainer);
			assertEquals(bc, c);
		}

		@Test
		@DisplayName("runOptimize leaves an un-runny bitmap container unchanged")
		void testRunOptimize2A() {
			final BitmapContainer bc = new BitmapContainer();
			for (int i = 0; i < 40000; i += 2) {
				bc.add((char) i);
			}
			final Container c = bc.runOptimize();
			assertTrue(c instanceof BitmapContainer);
			assertSame(c, bc);
		}

		@Test
		@DisplayName("runOptimize leaves an already-optimal run container unchanged")
		void testRunOptimize3() {
			final RunContainer rc = new RunContainer();
			for (final char s : new char[]{1, 2, 3, 4, 5, 6, 7, 8, 9, (char) 50000, (char) 50001}) {
				rc.add(s);
			}
			final Container c = rc.runOptimize();
			assertTrue(c instanceof RunContainer);
			assertSame(c, rc);
		}

		@Test
		@DisplayName("runOptimize converts a sparse run container into an array container")
		void testRunOptimize3A() {
			final RunContainer rc = new RunContainer();
			for (final char s : new char[]{1, 3, 5, 7, 9, 11, 17, 21, (char) 50000, (char) 50002}) {
				rc.add(s);
			}
			final Container c = rc.runOptimize();
			assertTrue(c instanceof ArrayContainer);
			assertEquals(c, rc);
		}

		@Test
		@DisplayName("runOptimize converts a dense run container into a bitmap container")
		void testRunOptimize3B() {
			final RunContainer rc = new RunContainer();
			for (char i = 100; i < 30000; i += 2) {
				rc.add(i);
			}
			final Container c = rc.runOptimize();
			assertTrue(c instanceof BitmapContainer);
			assertEquals(c, rc);
		}
	}

	@Nested
	@DisplayName("Container-type transitions across the size threshold")
	class ContainerTransition {

		@Test
		@DisplayName("Container promotes to a bitmap and demotes back to an array across the size threshold")
		void transitionTest() {
			Container c = new ArrayContainer();
			for (int i = 0; i < 4096; ++i) {
				c = c.add((char) i);
			}
			assertEquals(4096, c.getCardinality());
			assertTrue(c instanceof ArrayContainer);
			for (int i = 0; i < 4096; ++i) {
				c = c.add((char) i);
			}
			assertEquals(4096, c.getCardinality());
			assertTrue(c instanceof ArrayContainer);
			c = c.add((char) 4096);
			assertEquals(4097, c.getCardinality());
			assertTrue(c instanceof BitmapContainer);
			c = c.remove((char) 4096);
			assertEquals(4096, c.getCardinality());
			assertTrue(c instanceof ArrayContainer);
		}
	}

	@Nested
	@DisplayName("String representation")
	class StringRepresentation {

		@Test
		@DisplayName("All container types render the same toString for identical content")
		void testConsistentToString() {
			final ArrayContainer ac = new ArrayContainer();
			final BitmapContainer bc = new BitmapContainer();
			final RunContainer rc = new RunContainer();
			for (final char i : new char[]{0, 2, 17, Short.MAX_VALUE, (char) -3, (char) -1}) {
				ac.add(i);
				bc.add(i);
				rc.add(i);
			}
			final String expected = "{0,2,17,32767,65533,65535}";

			assertEquals(expected, ac.toString());
			assertEquals(expected, bc.toString());
			final String normalizedRCstr =
				rc.toString().replaceAll("\\d+\\]\\[", "").replace('[', '{').replaceFirst(",\\d+\\]", "}");
			assertEquals(expected, normalizedRCstr);
		}
	}

	@Nested
	@DisplayName("forAll value iteration and range materialization")
	class ForAll {

		@Test
		@DisplayName("forAll visits every value across container types and input shapes")
		void forAll() {
			testForAllMaterialization(new char[0]);
			testForAllMaterialization(new char[]{0});
			testForAllMaterialization(new char[]{1});
			testForAllMaterialization(new char[]{Character.MAX_VALUE});
			testForAllMaterialization(new char[]{0, 2, 5, 7});
			testForAllMaterialization(new char[]{49, 63, 65, 32768, 3280});
			testForAllMaterialization(new char[]{0, Character.MAX_VALUE});
			testForAllMaterialization(new char[]{Character.MAX_VALUE - 1, Character.MAX_VALUE});
			testForAllMaterialization(new char[]{Character.MAX_VALUE - 1});
			testForAllMaterialization(allValues());
		}

		@ParameterizedTest
		@ValueSource(ints = {0, 1, 50, 63, 64, 65, 32768, 3280, 65534, 65535})
		@DisplayName("forAllFrom visits every value from an inclusive start")
		void forAllFrom(final int start) {
			testForAllFromMaterialization((char) start, new char[0]);
			testForAllFromMaterialization((char) start, new char[]{0});
			testForAllFromMaterialization((char) start, new char[]{1});
			testForAllFromMaterialization((char) start, new char[]{0, 2, 5, 7});
			testForAllFromMaterialization((char) start, new char[]{49, 63, 65, 32768, 3280});
			testForAllFromMaterialization((char) start, new char[]{0, Character.MAX_VALUE});
			testForAllFromMaterialization(
				(char) start, new char[]{Character.MAX_VALUE - 1, Character.MAX_VALUE});
			testForAllFromMaterialization((char) start, new char[]{Character.MAX_VALUE - 1});
			testForAllFromMaterialization((char) start, allValues());
		}

		@ParameterizedTest
		@ValueSource(ints = {0, 1, 50, 63, 64, 65, 32768, 3280, 65534, 65535})
		@DisplayName("forAllUntil visits every value up to an exclusive end")
		void forAllUntil(final int end) {
			testForAllUntilMaterialization((char) end, new char[0]);
			testForAllUntilMaterialization((char) end, new char[]{0});
			testForAllUntilMaterialization((char) end, new char[]{1});
			testForAllUntilMaterialization((char) end, new char[]{0, 2, 5, 7});
			testForAllUntilMaterialization((char) end, new char[]{49, 63, 65, 32768, 3280});
			testForAllUntilMaterialization((char) end, new char[]{0, Character.MAX_VALUE});
			testForAllUntilMaterialization(
				(char) end, new char[]{Character.MAX_VALUE - 1, Character.MAX_VALUE});
			testForAllUntilMaterialization((char) end, new char[]{Character.MAX_VALUE - 1});
			testForAllUntilMaterialization((char) end, allValues());
		}

		@ParameterizedTest
		@MethodSource("provideArgsForAllInRange")
		@DisplayName("forAllInRange visits every value within a range and rejects inverted ranges")
		void forAllInRange(final int start, final int end) {
			testForAllInRangeMaterialization((char) start, (char) end, new char[0]);
			testForAllInRangeMaterialization((char) start, (char) end, new char[]{0});
			testForAllInRangeMaterialization((char) start, (char) end, new char[]{1});
			testForAllInRangeMaterialization((char) start, (char) end, new char[]{0, 2, 5, 7});
			testForAllInRangeMaterialization(
				(char) start, (char) end, new char[]{49, 63, 65, 32768, 3280});
			testForAllInRangeMaterialization((char) start, (char) end, new char[]{0, Character.MAX_VALUE});
			testForAllInRangeMaterialization(
				(char) start, (char) end, new char[]{Character.MAX_VALUE - 1, Character.MAX_VALUE});
			testForAllInRangeMaterialization(
				(char) start, (char) end, new char[]{Character.MAX_VALUE - 1});
			testForAllInRangeMaterialization((char) start, (char) end, allValues());
		}

		@Test
		@DisplayName("forAllInRange consumes an empty bitmap container over a wide range")
		void debugMe() {
			final char start = 65;
			final char end = 32768;
			final char[] data = new char[0];

			Container container = new BitmapContainer();

			final ValidationRangeConsumer.Value[] expected = new ValidationRangeConsumer.Value[end - start];
			Arrays.fill(expected, ABSENT);
			for (final char c : data) {
				container = container.add(c);
				final int relativePos = c - start;
				if (relativePos >= 0 && c < end) { // Otherwise it's out of range.
					expected[relativePos] = PRESENT;
				}
			}
			assertEquals(container.getCardinality(), data.length);
			final ValidationRangeConsumer consumer = ValidationRangeConsumer.validate(expected);
			container.forAllInRange(start, end, consumer);
			assertEquals(expected.length, consumer.getNumberOfValuesConsumed());
		}

		/**
		 * Instantiates the given container type via its no-argument constructor.
		 *
		 * @param ct container class to instantiate
		 * @return a fresh, empty container of the requested type
		 */
		@Nonnull
		private Container getContainerInstance(@Nonnull Class<?> ct) {
			try {
				return (Container) ct.getDeclaredConstructor().newInstance();
			} catch (final Exception e) {
				fail(e);
				throw new RuntimeException("unreachable code");
			}
		}

		/**
		 * Verifies that {@link Container#forAll} visits every position in the container's value range
		 * for each container type, marking present/absent values consistently with {@code data}.
		 *
		 * @param data values to add before iterating
		 */
		private void testForAllMaterialization(@Nonnull char[] data) {
			for (final Class<?> ct1 : CONTAINER_TYPES) {
				Container container = getContainerInstance(ct1);
				final ValidationRangeConsumer.Value[] expected =
					new ValidationRangeConsumer.Value[Character.MAX_VALUE + 1];
				Arrays.fill(expected, ABSENT);
				for (final char c : data) {
					container = container.add(c);
					expected[c] = PRESENT;
				}
				assertEquals(container.getCardinality(), data.length);
				final ValidationRangeConsumer consumer = ValidationRangeConsumer.validate(expected);
				container.forAll(0, consumer);
				assertEquals(expected.length, consumer.getNumberOfValuesConsumed());
			}
		}

		/**
		 * Produces an array containing every representable char value, used to exercise fully dense
		 * containers.
		 *
		 * @return an ascending array of all {@code char} values from {@code 0} to
		 * {@link Character#MAX_VALUE}
		 */
		@Nonnull
		private char[] allValues() {
			final char[] allValues = new char[Character.MAX_VALUE + 1];
			IntStream.rangeClosed(0, Character.MAX_VALUE).forEach(i -> allValues[i] = (char) i);
			return allValues;
		}

		/**
		 * Verifies that {@link Container#forAllFrom} visits every position from the inclusive
		 * {@code start} for each container type.
		 *
		 * @param start inclusive lower bound of the iteration
		 * @param data  values to add before iterating
		 */
		private void testForAllFromMaterialization(final char start, @Nonnull char[] data) {
			for (final Class<?> ct1 : CONTAINER_TYPES) {
				Container container = getContainerInstance(ct1);
				final ValidationRangeConsumer.Value[] expected =
					new ValidationRangeConsumer.Value[Character.MAX_VALUE + 1 - start];
				Arrays.fill(expected, ABSENT);
				for (final char c : data) {
					container = container.add(c);
					final int relativePos = c - start;
					if (relativePos >= 0) { // Otherwise it's out of range.
						expected[relativePos] = PRESENT;
					}
				}
				assertEquals(container.getCardinality(), data.length);
				final ValidationRangeConsumer consumer = ValidationRangeConsumer.validate(expected);
				container.forAllFrom(start, consumer);
				assertEquals(expected.length, consumer.getNumberOfValuesConsumed());
			}
		}

		/**
		 * Verifies that {@link Container#forAllUntil} visits every position up to the exclusive
		 * {@code end} for each container type.
		 *
		 * @param end  exclusive upper bound of the iteration
		 * @param data values to add before iterating
		 */
		private void testForAllUntilMaterialization(final char end, @Nonnull char[] data) {
			for (final Class<?> ct1 : CONTAINER_TYPES) {
				Container container = getContainerInstance(ct1);
				// End is an exclusive boundary, since there is `forAll` consume the entire container.
				final ValidationRangeConsumer.Value[] expected = new ValidationRangeConsumer.Value[end];
				Arrays.fill(expected, ABSENT);
				for (final char c : data) {
					container = container.add(c);
					if (c < end) { // Otherwise it's out of range.
						expected[c] = PRESENT;
					}
				}
				assertEquals(container.getCardinality(), data.length);
				final ValidationRangeConsumer consumer = ValidationRangeConsumer.validate(expected);
				container.forAllUntil(0, end, consumer);
				assertEquals(expected.length, consumer.getNumberOfValuesConsumed());
			}
		}

		/**
		 * Verifies that {@link Container#forAllInRange} visits every position within the
		 * {@code [start, end)} range for each container type, and that an inverted or empty range
		 * (where {@code start >= end}) is rejected with an {@link IllegalArgumentException}.
		 *
		 * @param start inclusive lower bound of the iteration
		 * @param end   exclusive upper bound of the iteration
		 * @param data  values to add before iterating
		 */
		private void testForAllInRangeMaterialization(final char start, final char end, @Nonnull char[] data) {
			for (final Class<?> ct1 : CONTAINER_TYPES) {
				if (start < end) {
					Container container = getContainerInstance(ct1);
					final ValidationRangeConsumer.Value[] expected = new ValidationRangeConsumer.Value[end - start];
					Arrays.fill(expected, ABSENT);
					for (final char c : data) {
						container = container.add(c);
						final int relativePos = c - start;
						if (relativePos >= 0 && c < end) { // Otherwise it's out of range.
							expected[relativePos] = PRESENT;
						}
					}
					assertEquals(container.getCardinality(), data.length);
					final ValidationRangeConsumer consumer = ValidationRangeConsumer.validate(expected);
					container.forAllInRange(start, end, consumer);
					assertEquals(expected.length, consumer.getNumberOfValuesConsumed());
				} else {
					final Container container = getContainerInstance(ct1);
					final ValidationRangeConsumer consumer = ValidationRangeConsumer.ofSize(0);
					assertThrows(
						IllegalArgumentException.class, () -> container.forAllInRange(start, end, consumer));
				}
			}
		}

		/**
		 * Supplies the cartesian product of the range endpoints where {@code start <= end}, feeding
		 * {@link #forAllInRange(int, int)}.
		 *
		 * @return a stream of {@code (start, end)} argument pairs
		 */
		@Nonnull
		private static Stream<Arguments> provideArgsForAllInRange() {
			final int[] baseArgs = IntStream.of(0, 1, 50, 63, 64, 65, 32768, 3280, 65534, 65535).toArray();
			final List<Arguments> cartesianProduct = new ArrayList<>();
			for (final int start : baseArgs) {
				for (final int end : baseArgs) {
					if (start <= end) {
						cartesianProduct.add(Arguments.of(start, end));
					}
				}
			}
			return cartesianProduct.stream();
		}
	}
}
