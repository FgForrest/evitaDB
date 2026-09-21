package io.evitadb.roaringbitmap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the container hash codes to the value set they describe, across all three encodings.
 *
 * Every container is a set of 16-bit values with exactly one canonical 1,024-word form, and
 * {@link ContainerHash} defines the hash over that form. {@link CanonicalForm} checks each encoding against a
 * reference that materializes the words the slow way, and {@link AcrossEncodings} checks the three against
 * each other — which is the {@link Object#hashCode()} contract, because `runOptimize()` re-encodes a chunk
 * without changing the set and {@link Container#equals(Object)} compares sets.
 *
 * **Those two are not enough on their own, and {@link IndependentOracle} is why.** Both take their expected
 * value from {@link ContainerHash#ofWords}, which shares `fold`, `mixWord` and the weight table with the three
 * implementations under test — so a change to the weights from `31^i` to `33^i`, or to the mixer's multiplier,
 * would move expectation and actual together and leave them green. They prove that each encoding *emits* the
 * right words; they cannot prove what is *done* with them. {@link IndependentOracle} closes that by writing
 * the documented formula out with its constants inline, and by pinning literal values.
 *
 * {@link UpstreamDefects} is the regression half. It reproduces the three upstream defects this
 * implementation replaces, each as a case that upstream's code gets wrong and this one gets right:
 *
 * 1. the base-32 recurrence (`hash += 31 * hash + entry`) annihilating every entry before the last seven;
 * 2. {@link RunContainer} hashing its run pairs while {@link ArrayContainer} hashed values, so one set had
 *    two hashes;
 * 3. `Arrays.hashCode(long[])` folding `(int) (e ^ (e >>> 32))`, which is `0` for `-1L` as it is for `0L`,
 *    hiding every saturated word from {@link BitmapContainer}.
 *
 * {@link KnownEqualsAsymmetry} records what was deliberately **not** changed, so that nobody reads the fixed
 * hash as evidence that container equality is sound, and {@link SeedCarriesNoMeaning} does the same for
 * {@link ContainerHash#seed()} — it is a constant offset, not a marker anything may be inferred from.
 */
@DisplayName("Container hash codes describe the value set, not the encoding")
public class ContainerHashCodeTest {
	/**
	 * Builds the canonical word form of a value set by the most obvious means available, so that the
	 * reference shares no code with the containers it checks.
	 */
	@Nonnull
	private static long[] canonicalWords(@Nonnull final int... values) {
		final long[] words = new long[BitmapContainer.MAX_CAPACITY / 64];
		for (final int value : values) {
			words[value >>> 6] |= 1L << value;
		}
		return words;
	}

	/**
	 * Fills a container with the given values, honouring the {@link Container#add(char)} contract — a
	 * container promotes to a denser encoding by *returning* the replacement, so the result of every call has
	 * to be kept. Reusing the receiver instead silently builds a truncated container, which is the trap this
	 * helper exists to close.
	 */
	@Nonnull
	private static Container fill(@Nonnull final Container empty, @Nonnull final int... values) {
		Container container = empty;
		for (final int value : values) {
			container = container.add((char) value);
		}
		return container;
	}

	/**
	 * @return the same value set grown from each of the three empty encodings, in the order array, run,
	 * bitmap — noting that a container promotes as it fills, so above
	 * {@link ArrayContainer#DEFAULT_MAX_SIZE} values the first slot is no longer an {@link ArrayContainer}.
	 * That is the point: whatever each one ends up as, the hash must be the same.
	 */
	@Nonnull
	private static List<Container> allEncodings(@Nonnull final int... values) {
		return List.of(
			fill(new ArrayContainer(), values),
			fill(new RunContainer(), values),
			fill(new BitmapContainer(), values)
		);
	}

	/**
	 * @return `count` distinct values drawn from `[0, 65536)`, ascending
	 */
	@Nonnull
	private static int[] randomValues(@Nonnull final Random random, final int count) {
		final boolean[] taken = new boolean[BitmapContainer.MAX_CAPACITY];
		int remaining = count;
		while (remaining > 0) {
			final int candidate = random.nextInt(BitmapContainer.MAX_CAPACITY);
			if (!taken[candidate]) {
				taken[candidate] = true;
				remaining--;
			}
		}
		final int[] values = new int[count];
		int at = 0;
		for (int value = 0; value < taken.length; value++) {
			if (taken[value]) {
				values[at++] = value;
			}
		}
		return values;
	}

	@Nonnull
	private static int[] range(final int fromInclusive, final int toExclusive) {
		final int[] values = new int[toExclusive - fromInclusive];
		for (int i = 0; i < values.length; i++) {
			values[i] = fromInclusive + i;
		}
		return values;
	}

	@Nested
	@DisplayName("every encoding reports the canonical word form")
	class CanonicalForm {

		@Test
		@DisplayName("all three agree with a reference that materializes the words")
		void shouldMatchTheMaterializedReference() {
			final Random random = new Random(42);
			for (int round = 0; round < 200; round++) {
				// cardinalities either side of the array/bitmap promotion threshold
				final int cardinality = round < 100 ? 1 + random.nextInt(4096) : 4097 + random.nextInt(8000);
				final int[] values = randomValues(random, cardinality);
				final int expected = ContainerHash.ofWords(canonicalWords(values));
				for (final Container container : allEncodings(values)) {
					assertEquals(
						expected, container.hashCode(),
						() -> container.getClass().getSimpleName() + " disagreed with the materialized words at "
							+ cardinality + " values"
					);
				}
			}
		}

		@Test
		@DisplayName("an empty container hashes to the seed")
		void shouldHashAnEmptyContainerToTheSeed() {
			for (final Container container : allEncodings()) {
				assertEquals(ContainerHash.seed(), container.hashCode());
			}
			assertEquals(ContainerHash.seed(), ContainerHash.ofWords(canonicalWords()));
		}

		@Test
		@DisplayName("a saturated chunk is not an empty one")
		void shouldSeparateASaturatedChunkFromAnEmptyOne() {
			final int[] everything = range(0, BitmapContainer.MAX_CAPACITY);
			for (final Container container : allEncodings(everything)) {
				assertNotEquals(ContainerHash.seed(), container.hashCode());
				assertEquals(ContainerHash.ofWords(canonicalWords(everything)), container.hashCode());
			}
		}

		@Test
		@DisplayName("only the live region is read, never the slack of the backing array")
		void shouldIgnoreTheBackingArraysSlack() {
			Container grown = fill(new ArrayContainer(), range(0, 300));
			for (int value = 299; value >= 40; value--) {
				grown = grown.remove((char) value);
			}
			assertInstanceOf(ArrayContainer.class, grown);
			final Container fresh = fill(new ArrayContainer(), range(0, 40));
			assertTrue(
				((ArrayContainer) grown).content.length > grown.getCardinality(),
				"the test needs a container with slack"
			);
			assertEquals(fresh.hashCode(), grown.hashCode());
		}

		@Test
		@DisplayName("a run container never reads past its live runs either")
		void shouldIgnoreTheRunArraysSlack() {
			// `valueslength` grows geometrically and `iremove` never shrinks it, so a container that held a
			// hundred runs and now holds one still carries capacity for a hundred. The array is private, so
			// the slack cannot be asserted directly - what is asserted is the consequence: the shortened
			// container has to hash exactly like one built at that size from nothing.
			Container grown = new RunContainer();
			for (int run = 0; run < 100; run++) {
				grown = grown.iadd(run * 600, run * 600 + 200);
			}
			assertEquals(100, ((RunContainer) grown).numberOfRuns(), "the test needs a container to shrink");
			for (int run = 99; run >= 1; run--) {
				grown = grown.iremove(run * 600, run * 600 + 200);
			}
			assertInstanceOf(RunContainer.class, grown);
			assertEquals(1, ((RunContainer) grown).numberOfRuns(), "the test needs a shrunken container");

			final Container fresh = new RunContainer().iadd(0, 200);
			assertEquals(fresh.hashCode(), grown.hashCode());
			assertEquals(ContainerHash.ofWords(canonicalWords(range(0, 200))), grown.hashCode());
		}

		@Test
		@DisplayName("a bitmap container with an unrepaired lazy cardinality still hashes its words")
		void shouldHashALazilyUnionedBitmapContainer() {
			Container left = new BitmapContainer();
			Container right = new BitmapContainer();
			for (int value = 0; value < 9000; value++) {
				left = left.add((char) (value * 7 % BitmapContainer.MAX_CAPACITY));
				right = right.add((char) (value * 11 % BitmapContainer.MAX_CAPACITY));
			}
			final Container lazy = left.clone().lazyIOR(right);
			// the lazy union defers the population count, so the container reports no cardinality at all -
			// the hash describes the words and must not consult it, nor short-circuit on a zero it would read
			assertEquals(-1, ((BitmapContainer) lazy).cardinality, "the test needs an unrepaired lazy union");
			final int expected = ContainerHash.ofWords(((BitmapContainer) lazy).bitmap.clone());
			assertEquals(expected, lazy.hashCode());
			assertEquals(expected, lazy.repairAfterLazy().hashCode(), "repairing changed the hash");
		}

		@Test
		@DisplayName("mixWord maps the empty word to zero, which is what lets a sparse container skip it")
		void shouldMapTheEmptyWordToZero() {
			assertEquals(0, ContainerHash.mixWord(0L));
			assertNotEquals(0, ContainerHash.mixWord(-1L));
			assertNotEquals(0, ContainerHash.mixWord(1L));
		}
	}

	@Nested
	@DisplayName("the three encodings of one set hash alike")
	class AcrossEncodings {

		@Test
		@DisplayName("random sets hash the same however they are stored")
		void shouldAgreeOnRandomSets() {
			final Random random = new Random(7);
			for (int round = 0; round < 300; round++) {
				final int[] values = randomValues(random, 1 + random.nextInt(9000));
				final List<Container> encodings = allEncodings(values);
				final int reference = encodings.get(0).hashCode();
				for (final Container container : encodings) {
					assertEquals(reference, container.hashCode());
				}
			}
		}

		@Test
		@DisplayName("run-shaped sets hash the same however they are stored")
		void shouldAgreeOnRunShapedSets() {
			final Random random = new Random(11);
			for (int round = 0; round < 200; round++) {
				final List<Integer> values = new ArrayList<>();
				int at = random.nextInt(64);
				while (at < BitmapContainer.MAX_CAPACITY - 200) {
					final int runLength = 1 + random.nextInt(150);
					for (int i = 0; i < runLength && at + i < BitmapContainer.MAX_CAPACITY; i++) {
						values.add(at + i);
					}
					at += runLength + 1 + random.nextInt(200);
				}
				final int[] asArray = values.stream().mapToInt(Integer::intValue).toArray();
				final List<Container> encodings = allEncodings(asArray);
				final int expected = ContainerHash.ofWords(canonicalWords(asArray));
				for (final Container container : encodings) {
					assertEquals(expected, container.hashCode());
				}
			}
		}

		@Test
		@DisplayName("two runs sharing one word are folded once, not twice")
		void shouldFoldASharedWordOnce() {
			// both runs live inside word 0: bits 0..9 and bits 20..30
			final List<Integer> values = new ArrayList<>();
			for (int value = 0; value <= 9; value++) {
				values.add(value);
			}
			for (int value = 20; value <= 30; value++) {
				values.add(value);
			}
			final int[] asArray = values.stream().mapToInt(Integer::intValue).toArray();
			final Container run = fill(new RunContainer(), asArray);
			assertInstanceOf(RunContainer.class, run, "the test needs a run container");
			assertEquals(2, ((RunContainer) run).numberOfRuns(), "the test needs two runs inside one word");
			assertEquals(ContainerHash.ofWords(canonicalWords(asArray)), run.hashCode());
		}

		@Test
		@DisplayName("a run spanning a word boundary is folded with both partial masks")
		void shouldFoldARunAcrossAWordBoundary() {
			for (final int[] bounds : new int[][]{{60, 70}, {63, 64}, {0, 63}, {0, 64}, {64, 127}, {1000, 1600}}) {
				final int[] values = range(bounds[0], bounds[1] + 1);
				final Container run = fill(new RunContainer(), values);
				assertEquals(
					ContainerHash.ofWords(canonicalWords(values)), run.hashCode(),
					() -> "run " + bounds[0] + ".." + bounds[1] + " hashed wrong"
				);
			}
		}

		@Test
		@DisplayName("equal bitmaps hash alike after runOptimize re-encodes one of them")
		void shouldSurviveRunOptimize() {
			for (final int size : new int[]{40, 20_000, BitmapContainer.MAX_CAPACITY}) {
				final PersistentRoaringBitmap plain = new PersistentRoaringBitmap();
				final PersistentRoaringBitmap optimized = new PersistentRoaringBitmap();
				for (int value = 0; value < size; value++) {
					plain.add(value);
					optimized.add(value);
				}
				optimized.runOptimize();
				assertEquals(plain, optimized);
				assertEquals(
					plain.hashCode(), optimized.hashCode(),
					() -> "runOptimize changed the hash of a " + size + "-value bitmap"
				);
			}
		}

		@Test
		@DisplayName("equal containers hash alike, over many random pairs")
		void shouldHonourTheHashCodeContract() {
			final Random random = new Random(1234);
			for (int round = 0; round < 500; round++) {
				final int[] values = randomValues(random, 1 + random.nextInt(6000));
				final List<Container> encodings = allEncodings(values);
				for (final Container left : encodings) {
					for (final Container right : encodings) {
						if (left.equals(right)) {
							assertEquals(
								left.hashCode(), right.hashCode(),
								() -> left.getClass().getSimpleName() + " equals "
									+ right.getClass().getSimpleName() + " but hashes apart"
							);
						}
					}
				}
			}
		}
	}

	@Nested
	@DisplayName("the upstream defects this replaces")
	class UpstreamDefects {

		@Test
		@DisplayName("containers differing only before their last seven values hash apart")
		void shouldSeeBeyondTheLastSevenValues() {
			// upstream's `hash += 31 * hash + value` is base 32, so 2^(5*7) == 0 annihilates everything
			// before the last seven values and these two collide
			final int[] leftValues = new int[200];
			final int[] rightValues = new int[200];
			for (int value = 0; value < 200; value++) {
				leftValues[value] = value * 3;
				rightValues[value] = value == 0 ? 1 : value * 3;
			}
			final Container left = fill(new ArrayContainer(), leftValues);
			final Container right = fill(new ArrayContainer(), rightValues);
			assertInstanceOf(ArrayContainer.class, left, "the defect is ArrayContainer.hashCode's");
			assertInstanceOf(ArrayContainer.class, right, "the defect is ArrayContainer.hashCode's");
			assertNotEquals(left, right);
			assertNotEquals(left.hashCode(), right.hashCode());
		}

		@Test
		@DisplayName("run containers differing only before their last seven run entries hash apart")
		void shouldSeeBeyondTheLastSevenRunEntries() {
			final List<Integer> shared = new ArrayList<>();
			for (int run = 0; run < 40; run++) {
				final int start = run * 100;
				for (int i = 0; i < 10; i++) {
					shared.add(start + i);
				}
			}
			final int[] leftValues = shared.stream().mapToInt(Integer::intValue).toArray();
			// lengthen only the very first run, which upstream's tail loop cannot see
			shared.add(10);
			final int[] rightValues = shared.stream().mapToInt(Integer::intValue).sorted().toArray();
			final Container left = fill(new RunContainer(), leftValues);
			final Container right = fill(new RunContainer(), rightValues);
			assertInstanceOf(RunContainer.class, left, "the defect is RunContainer.hashCode's");
			assertInstanceOf(RunContainer.class, right, "the defect is RunContainer.hashCode's");
			assertNotEquals(left, right);
			assertNotEquals(left.hashCode(), right.hashCode());
		}

		@Test
		@DisplayName("an array container and a run container over the same values hash alike")
		void shouldAgreeAcrossArrayAndRunEncodings() {
			final int[] values = range(100, 140);
			final Container array = fill(new ArrayContainer(), values);
			final Container run = fill(new RunContainer(), values);
			assertInstanceOf(ArrayContainer.class, array);
			assertInstanceOf(RunContainer.class, run);
			assertTrue(run.equals(array), "the two encodings must compare equal for the hash to be required to agree");
			assertEquals(array.hashCode(), run.hashCode());
		}

		@Test
		@DisplayName("disjoint dense chunks whose every word is saturated hash apart")
		void shouldSeeSaturatedWords() {
			// Arrays.hashCode(long[]) folds (int) (e ^ (e >>> 32)), which is 0 for -1L as it is for 0L,
			// so upstream hashed these two - and every other all-ones chunk - identically
			final int[] lower = range(0, 4160);
			final int[] upper = range(4160, 8320);
			final Container left = fill(new BitmapContainer(), lower);
			final Container right = fill(new BitmapContainer(), upper);
			assertInstanceOf(BitmapContainer.class, left, "the defect is BitmapContainer.hashCode's");
			assertInstanceOf(BitmapContainer.class, right, "the defect is BitmapContainer.hashCode's");
			assertNotEquals(left, right);
			assertNotEquals(left.hashCode(), right.hashCode());
			assertEquals(ContainerHash.ofWords(canonicalWords(lower)), left.hashCode());
			assertEquals(ContainerHash.ofWords(canonicalWords(upper)), right.hashCode());
		}

		@Test
		@DisplayName("a saturated word is not the same as an absent one")
		void shouldSeparateASaturatedWordFromAnAbsentOne() {
			assertNotEquals(ContainerHash.mixWord(-1L), ContainerHash.mixWord(0L));
		}
	}

	@Nested
	@DisplayName("what was deliberately left alone")
	class KnownEqualsAsymmetry {

		@Test
		@DisplayName("array and bitmap containers still refuse to compare equal to each other")
		void shouldRecordThatEqualsIsStillNotTransitive() {
			// upstream dispatches equals by type: RunContainer answers against any Container, while
			// ArrayContainer and BitmapContainer know only their own type and RunContainer. The fix to
			// hashCode does not touch this, and no public API path reaches it - the encoding of a chunk is
			// decided by its cardinality, so an array and a bitmap never hold the same set.
			final int[] values = range(100, 140);
			final List<Container> encodings = allEncodings(values);
			final Container array = encodings.get(0);
			final Container run = encodings.get(1);
			final Container bitmap = encodings.get(2);

			assertTrue(array.equals(run));
			assertTrue(run.equals(bitmap));
			assertFalse(array.equals(bitmap), "if this starts passing, equals was fixed and this test should go");

			// the hash is required to agree only where equals says true - but it agrees everywhere anyway,
			// because it describes the set rather than the encoding
			assertEquals(array.hashCode(), run.hashCode());
			assertEquals(run.hashCode(), bitmap.hashCode());
			assertEquals(array.hashCode(), bitmap.hashCode());
		}
	}

	@Nested
	@DisplayName("the seed carries no meaning")
	class SeedCarriesNoMeaning {
		/**
		 * The multiplier inside {@link ContainerHash#mixWord(long)}, mirrored here because the witnesses below
		 * are derived from it. If the mixer ever changes, these two tests fail and the witnesses have to be
		 * re-derived from the new constant — which is the point: the claim they refute is about the shape of
		 * the function, not about these particular words.
		 */
		private static final long MIX_MULTIPLIER = 0xFF51AFD7ED558CCDL;

		/**
		 * @return the multiplicative inverse of {@link #MIX_MULTIPLIER} modulo `2^64`, by Newton iteration —
		 * the multiplier is odd, so it is invertible and the iteration doubles the number of correct bits
		 * each round
		 */
		private static long mixMultiplierInverse() {
			long inverse = 1L;
			for (int round = 0; round < 6; round++) {
				inverse = inverse * (2L - MIX_MULTIPLIER * inverse);
			}
			return inverse;
		}

		/**
		 * Builds a word whose {@link ContainerHash#mixWord(long)} is exactly `target`, by inverting the
		 * finalizer: the xorshift folds bits 33..63 down onto bits 0..31, so choosing those top bits freely
		 * fixes what the bottom ones must be, and the multiply is undone with {@link #mixMultiplierInverse()}.
		 *
		 * @param target the value the returned word must mix to
		 * @param topBits any 31-bit value, which selects one witness out of the many that exist
		 * @return a word `w` with `mixWord(w) == target`
		 */
		private static long wordMixingTo(final int target, final long topBits) {
			final long mixed = (topBits << 33) | (1L << 32) | ((target ^ (int) topBits) & 0xFFFFFFFFL);
			return mixed * mixMultiplierInverse();
		}

		/**
		 * @return a container holding exactly the values whose bits are set in `word`, all of which fall in
		 * word 0 of the chunk, so the container's hash is `seed() + mixWord(word)`
		 */
		@Nonnull
		private static Container containerOccupyingOnlyWordZero(final long word) {
			Container container = new ArrayContainer();
			for (int bit = 0; bit < 64; bit++) {
				if ((word & (1L << bit)) != 0L) {
					container = container.add((char) bit);
				}
			}
			return container;
		}

		@Test
		@DisplayName("a non-empty container can hash to the seed, so the seed is not an emptiness marker")
		void shouldNotTreatTheSeedAsAnEmptinessMarker() {
			final long word = wordMixingTo(0, 12345L);
			assertEquals(0, ContainerHash.mixWord(word), "the witness has to mix to zero");
			final Container container = containerOccupyingOnlyWordZero(word);
			assertNotEquals(0, container.getCardinality(), "the witness has to be non-empty");
			assertEquals(
				ContainerHash.seed(), container.hashCode(),
				"a container of " + container.getCardinality() + " values hashes to the seed - `hash == seed()`"
					+ " must never be read as `isEmpty()`"
			);
		}

		@Test
		@DisplayName("a non-empty container can hash to zero, so the seed rules nothing out either")
		void shouldNotTreatZeroAsUnreachable() {
			final long word = wordMixingTo(-ContainerHash.seed(), 12345L);
			final Container container = containerOccupyingOnlyWordZero(word);
			assertNotEquals(0, container.getCardinality(), "the witness has to be non-empty");
			assertEquals(
				0, container.hashCode(),
				"a word contributing -seed() exists, so the seed does not keep any container off zero"
			);
		}

		@Test
		@DisplayName("what the seed does guarantee is only that the empty container is not zero")
		void shouldKeepTheEmptyContainerOffZero() {
			for (final Container container : allEncodings()) {
				assertNotEquals(0, container.hashCode());
				assertEquals(ContainerHash.seed(), container.hashCode());
			}
		}
	}

	@Nested
	@DisplayName("the weights")
	class Weights {

		@Test
		@DisplayName("no word position is annihilated, at any index")
		void shouldKeepEveryWordPositionAlive() {
			// the property upstream's base-32 recurrence lost: with an odd multiplier no power is ever zero,
			// so a word cannot be made invisible by sitting far enough from the end
			final int wordCount = BitmapContainer.MAX_CAPACITY / 64;
			for (int wordIndex = 0; wordIndex < wordCount; wordIndex++) {
				final long[] words = new long[wordCount];
				words[wordIndex] = 1L;
				assertNotEquals(
					ContainerHash.seed(), ContainerHash.ofWords(words),
					"word " + wordIndex + " contributed nothing"
				);
			}
		}

		@Test
		@DisplayName("moving a set bit between word positions changes the hash")
		void shouldDistinguishWordPositions() {
			final int wordCount = BitmapContainer.MAX_CAPACITY / 64;
			final int[] seen = new int[wordCount];
			for (int wordIndex = 0; wordIndex < wordCount; wordIndex++) {
				final long[] words = new long[wordCount];
				words[wordIndex] = -1L;
				seen[wordIndex] = ContainerHash.ofWords(words);
			}
			final int[] sorted = Arrays.copyOf(seen, seen.length);
			Arrays.sort(sorted);
			for (int i = 1; i < sorted.length; i++) {
				assertNotEquals(sorted[i - 1], sorted[i], "two word positions produced the same hash");
			}
		}
	}

	@Nested
	@DisplayName("the formula itself, not merely the words fed to it")
	class IndependentOracle {

		/**
		 * The documented formula, written out with its constants inline: `SEED + Σ mix(word) * 31^i` over the
		 * non-empty words, with MurmurHash3's finalizer spelled out.
		 *
		 * It deliberately calls nothing in {@link ContainerHash}. That duplication is the entire point — it is
		 * a second site that has to be edited in step, so a change to the production weights or mixer alone
		 * fails here instead of silently redefining what the tests expect.
		 */
		private int independentHash(@Nonnull final long[] words) {
			int hash = 0x9E3779B9;
			int weight = 1;
			for (int i = 0; i < words.length; i++) {
				if (words[i] != 0L) {
					long mixed = words[i] * 0xFF51AFD7ED558CCDL;
					mixed ^= mixed >>> 33;
					hash += ((int) mixed) * weight;
				}
				weight *= 31;
			}
			return hash;
		}

		@Test
		@DisplayName("all three encodings match the formula spelled out independently")
		void shouldMatchAFormulaWrittenOutWithItsConstants() {
			final Random random = new Random(99);
			for (int round = 0; round < 200; round++) {
				final int[] values = randomValues(random, 1 + random.nextInt(9000));
				final int expected = independentHash(canonicalWords(values));
				for (final Container container : allEncodings(values)) {
					assertEquals(
						expected, container.hashCode(),
						() -> container.getClass().getSimpleName() + " disagreed with the inline formula"
					);
				}
			}
		}

		@Test
		@DisplayName("the helper agrees with the formula it documents")
		void shouldPinContainerHashItselfToTheFormula() {
			final Random random = new Random(100);
			for (int round = 0; round < 200; round++) {
				final long[] words = canonicalWords(randomValues(random, 1 + random.nextInt(9000)));
				assertEquals(independentHash(words), ContainerHash.ofWords(words));
			}
		}

		@Test
		@DisplayName("known answers: fixed inputs keep their exact hash")
		void shouldMatchTheGoldenValues() {
			// literal values, so that an unintended change to the weights, the mixer or the seed fails here
			// with a number rather than passing because both sides moved together
			assertGolden(-1640531527);
			assertGolden(825545951, 0);
			assertGolden(-1627211615, 0, 1, 2);
			assertGolden(-1427799213, 63, 64);
			assertGolden(1580693945, 65535);
			assertGolden(-248195873, 0, 65535);
			assertGolden(2078398510, range(100, 140));
			assertGolden(-137390663, range(0, 4096));
			assertGolden(1052326329, range(0, BitmapContainer.MAX_CAPACITY));
		}

		/**
		 * Asserts the literal hash for a value set, through every encoding and through both oracles.
		 */
		private void assertGolden(final int expected, @Nonnull final int... values) {
			assertEquals(expected, independentHash(canonicalWords(values)), "the inline formula moved");
			assertEquals(expected, ContainerHash.ofWords(canonicalWords(values)), "ContainerHash.ofWords moved");
			for (final Container container : allEncodings(values)) {
				assertEquals(
					expected, container.hashCode(),
					() -> container.getClass().getSimpleName() + " moved off its known answer"
				);
			}
		}
	}
}
