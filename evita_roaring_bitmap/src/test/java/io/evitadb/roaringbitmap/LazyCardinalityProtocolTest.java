package io.evitadb.roaringbitmap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the protocol a lazily-built container speaks, which the kernel rewiring of the dense container
 * operators could quietly break without failing a single existing assertion.
 *
 * A cardinality of `-1` is not "unknown, fill it in when convenient". It is a promise that
 * {@link BitmapContainer#repairAfterLazy()} has not run yet and is still entitled to demote the container.
 * {@link FastAggregation#workShyAnd} depends on exactly that: it seeds an all-ones
 * {@link BitmapContainer} with `-1`, intersects it with each input in place, and lets `repairAfterLazy`
 * decide the final container type. `repairAfterLazy` acts only while the cardinality is still negative, so a
 * fused kernel that helpfully writes a population count into `iand`'s lazy branch turns the repair into a
 * no-op — and a three-value result stays an 8 KiB {@link BitmapContainer}.
 *
 * That failure is close to invisible: the values are all correct, `getCardinality()` is correct, and
 * iteration is correct. What breaks is identity. `BitmapContainer.equals` compares against an
 * {@link ArrayContainer} by cardinality and content, but the two are different classes with different
 * memory footprints, and a bitmap that should have collapsed to three values keeps 8 KiB per chunk for the
 * rest of its life. The assertions below therefore check the container *type*, not only the values.
 */
@DisplayName("Lazily built containers keep their unknown cardinality until repairAfterLazy runs")
public class LazyCardinalityProtocolTest {
	/**
	 * Number of inputs. Above ten, {@link FastAggregation#and(PersistentRoaringBitmap...)} takes the work-shy
	 * path, which is the one that builds lazy containers.
	 */
	private static final int INPUT_COUNT = 11;
	/**
	 * The values every input shares, and therefore the whole intersection. Three of them, far below
	 * {@link ArrayContainer#DEFAULT_MAX_SIZE}, so a correct result cannot be a {@link BitmapContainer}.
	 */
	private static final int[] COMMON_VALUES = {1000, 2000, 3000};
	/**
	 * Size of the disjoint block each input carries on top of the common values. Large enough that every
	 * input's chunk is held as a {@link BitmapContainer}, which is what forces the bitmap-on-bitmap `iand`
	 * the kernels rewired.
	 */
	private static final int PRIVATE_BLOCK_SIZE = 5000;

	@Test
	@DisplayName("a work-shy AND whose intersection is three values produces an ArrayContainer")
	void shouldDemoteSparseWorkShyAndResult() {
		final PersistentRoaringBitmap[] inputs = buildInputs();
		final PersistentRoaringBitmap result = FastAggregation.and(inputs);

		assertEquals(COMMON_VALUES.length, result.getCardinality());
		assertEquals(expectedResult(), result);
		assertEquals(1, result.highLowContainer.size, "the intersection lives in a single chunk");
		assertInstanceOf(
			ArrayContainer.class,
			result.highLowContainer.getContainerAtIndex(0),
			"a three-value chunk must be demoted by repairAfterLazy, not left as an 8 KiB bitmap"
		);
	}

	@Test
	@DisplayName("every input is dense enough to exercise the bitmap-on-bitmap in-place intersection")
	void shouldBuildBitmapBackedInputs() {
		for (final PersistentRoaringBitmap input : buildInputs()) {
			assertInstanceOf(
				BitmapContainer.class,
				input.highLowContainer.getContainerAtIndex(0),
				"the fixture must present bitmap containers, otherwise it tests a different code path"
			);
		}
	}

	@Test
	@DisplayName("the cardinality-only work-shy AND agrees with the materialized one")
	void shouldCountWorkShyAndWithoutMaterializing() {
		final PersistentRoaringBitmap[] inputs = buildInputs();
		assertEquals(COMMON_VALUES.length, FastAggregation.andCardinality(inputs));
	}

	@Test
	@DisplayName("an in-place intersection on a lazy container leaves its cardinality unknown")
	void shouldLeaveLazyCardinalityUnknown() {
		final long[] words = new long[1024];
		java.util.Arrays.fill(words, ~0L);
		final BitmapContainer lazy = new BitmapContainer(words, -1);

		final BitmapContainer other = new BitmapContainer();
		for (int value = 0; value < 5000; value++) {
			other.add((char) value);
		}

		final Container intersected = lazy.iand(other);
		assertTrue(intersected instanceof BitmapContainer, "a lazy intersection never demotes on its own");
		assertEquals(-1, ((BitmapContainer) intersected).cardinality, "the lazy protocol must survive iand");
		assertEquals(5000, intersected.repairAfterLazy().getCardinality());
	}

	/**
	 * Builds the inputs: every one of them holds {@link #COMMON_VALUES} plus a block of
	 * {@link #PRIVATE_BLOCK_SIZE} consecutive values that no other input holds, so the intersection is
	 * exactly the common values and every input's chunk is dense.
	 *
	 * @return the inputs, all sharing chunk `0`
	 */
	@Nonnull
	private static PersistentRoaringBitmap[] buildInputs() {
		final PersistentRoaringBitmap[] inputs = new PersistentRoaringBitmap[INPUT_COUNT];
		for (int i = 0; i < INPUT_COUNT; i++) {
			final PersistentRoaringBitmap bitmap = new PersistentRoaringBitmap();
			for (int c = 0; c < COMMON_VALUES.length; c++) {
				bitmap.add(COMMON_VALUES[c]);
			}
			final int blockStart = 10000 + i * PRIVATE_BLOCK_SIZE;
			bitmap.add((long) blockStart, (long) (blockStart + PRIVATE_BLOCK_SIZE));
			inputs[i] = bitmap;
		}
		return inputs;
	}

	/**
	 * The bitmap the intersection has to equal, built directly from the common values.
	 *
	 * @return a bitmap holding exactly {@link #COMMON_VALUES}
	 */
	@Nonnull
	private static PersistentRoaringBitmap expectedResult() {
		final PersistentRoaringBitmap expected = new PersistentRoaringBitmap();
		for (int c = 0; c < COMMON_VALUES.length; c++) {
			expected.add(COMMON_VALUES[c]);
		}
		return expected;
	}

}
