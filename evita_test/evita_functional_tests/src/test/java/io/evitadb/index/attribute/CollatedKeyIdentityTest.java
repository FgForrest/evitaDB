/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/master/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */


package io.evitadb.index.attribute;

import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.index.invertedIndex.InvertedIndex;
import io.evitadb.index.invertedIndex.ValueToRecordBitmap;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.text.Collator;
import java.text.Normalizer;
import java.util.Comparator;
import java.util.Locale;
import java.util.function.Function;

import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.FILTER;
import static io.evitadb.test.TestTags.INDEXING;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the `compareTo` tie-break that makes a localized `String` value tree's key identity agree with `equals`.
 *
 * # What the tie-break is for
 *
 * A bare `Collator` at default strength equates strings that are not `equals` — a zero-width space, a joiner, a
 * directional mark all vanish from the comparison. A value tree ordered by one would therefore merge such
 * strings into a single bucket while every structure keyed by `equals` kept them apart, the
 * reference-attribute cardinality counter above all. {@link EqualsConsistentLocalizedStringComparator} breaks
 * that tie with {@link String#compareTo}, so bucket identity and `equals` agree again and the counter can
 * describe the tree it guards.
 *
 * The tie-break lives in that index-key comparator and NOT in
 * {@link io.evitadb.comparator.LocalizedStringComparator}, which is contractually the plain cached collator —
 * canonically equivalent NFC/NFD forms must compare equal there, and a custom-strength collator must keep its
 * deliberate equalities. `CanonicalEquivalence` pins that the index-key flavour honours the first of those
 * anyway, because the normalizer folds both forms to NFD before the comparator ever sees them.
 *
 * # It is NOT a re-ordering
 *
 * The tie-break is reached only where the collation returned zero, so no ordering decision the collation
 * actually makes changes — `Ordering` pins that on Czech, whose collation differs from codepoint order.
 *
 * # It IS a migration hazard, and that is worth keeping visible
 *
 * A catalog written by an engine WITHOUT the tie-break can hold a record filed under one spelling whose entity
 * value is the other — the collator merged them at index time and only one spelling reached the disk. Such a
 * record's removal names a bucket that no longer exists once the two spellings are distinct keys, and fails the
 * index premise. `MigrationHazard` builds exactly that stored state and loads it under the old comparator and
 * the current one in turn, so the difference is a test rather than a claim.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Localized String attributes — the collator tie-break that keeps bucket identity equal to equals")
@Tag(INDEXING)
@Tag(ATTRIBUTE)
@Tag(FILTER)
class CollatedKeyIdentityTest {

	/** A plain two-letter string, and the same string carrying a zero-width space a collator ignores entirely. */
	private static final String PLAIN = "ab";
	private static final String ZERO_WIDTH_SPACED = "a​b";
	private static final int RECORD = 1;
	private static final int OTHER_RECORD = 2;

	@Nested
	@DisplayName("The premise — a bare collator merges what equals keeps apart")
	class Premise {

		@Test
		@DisplayName("the bare collator equates the two spellings, which is why the tie-break exists")
		void shouldEquateTwoSpellingsWithoutTheTieBreak() {
			assertNotEquals(PLAIN, ZERO_WIDTH_SPACED, "the two values are distinct to every `equals` in the engine");
			assertEquals(
				0, Collator.getInstance(Locale.ENGLISH).compare(PLAIN, ZERO_WIDTH_SPACED),
				"a collator at default strength ignores the zero-width space entirely"
			);
		}

		@Test
		@DisplayName("the localized INDEX nevertheless keeps them in separate buckets")
		void shouldKeepTwoSpellingsApartInTheIndex() {
			final OwnerFilterIndex index = new OwnerFilterIndex(localizedKey(), String.class);
			index.addRecord(RECORD, PLAIN);
			index.addRecord(OTHER_RECORD, ZERO_WIDTH_SPACED);

			assertEquals(
				2, index.getInvertedIndex().getBucketCount(),
				"the tie-break makes bucket identity agree with equals - note this must be read off the BUCKET " +
					"count; `size()` sums per-bucket record counts and would report 2 either way"
			);
			assertTrue(
				index.getRecordsEqualTo(ZERO_WIDTH_SPACED).contains(OTHER_RECORD),
				"each record is reachable through its OWN spelling"
			);
			assertFalse(
				index.getRecordsEqualTo(PLAIN).contains(OTHER_RECORD),
				"and not through the other one, which is what lets an equals-keyed counter describe this tree"
			);
		}

		@Test
		@DisplayName("negative control — a NON-localized index keeps the two spellings apart today")
		void shouldKeepTheTwoSpellingsApartWithoutALocale() {
			final OwnerFilterIndex index = new OwnerFilterIndex(
				new AttributeIndexKey(null, "labels", null), String.class
			);
			index.addRecord(RECORD, PLAIN);
			index.addRecord(OTHER_RECORD, ZERO_WIDTH_SPACED);

			assertEquals(
				2, index.getInvertedIndex().getBucketCount(),
				"natural order is consistent with equals, so this flavour is unaffected"
			);
		}
	}

	@Nested
	@DisplayName("The migration hazard a tie-break would introduce")
	class MigrationHazard {

		@Test
		@DisplayName("a record filed under the other spelling survived removal BEFORE the tie-break")
		void shouldRemoveARecordFiledUnderTheOtherSpellingWithoutTheTieBreak() {
			// the stored shape a pre-tie-break engine writes: ONE bucket keyed `ab`, holding a record whose own
			// entity value is `a<ZWSP>b` - the collator merged them at index time and the spelling that created
			// the bucket is the one that got persisted
			final OwnerFilterIndex index = restore(storedSingleBucket(), collatingWithoutTieBreak());

			assertDoesNotThrow(
				() -> index.removeRecord(OTHER_RECORD, ZERO_WIDTH_SPACED),
				"the lookup was collated too, so it found the `ab` bucket while asking for `a<ZWSP>b`"
			);
		}

		@Test
		@DisplayName("the SAME stored state strands that record under the CURRENT comparator")
		void shouldStrandARecordFiledUnderTheOtherSpellingAfterATieBreak() {
			final OwnerFilterIndex index = restore(storedSingleBucket(), production());

			final EvitaInvalidUsageException ex = assertThrows(
				EvitaInvalidUsageException.class,
				() -> index.removeRecord(OTHER_RECORD, ZERO_WIDTH_SPACED),
				"`ab` and `a<ZWSP>b` are now different keys, so the bucket the record actually sits in is no " +
					"longer the bucket its own value names"
			);
			assertEquals("Sanity check - record not found!", ex.getMessage());
		}

		@Test
		@DisplayName("a legacy bucket answers a QUERY differently on the index path and the prefetch path")
		void shouldAnswerAQueryInconsistentlyOverALegacyBucket() {
			// this is the query-side half of the same hazard, and it is strictly worse than the write-side half:
			// the record is not merely un-removable, it is returned for a value it does not hold and withheld for
			// the value it does - and the two query paths disagree about it, so the answer depends on whether the
			// planner prefetched. Before the tie-break both paths matched `ab` (collation equality), so this
			// divergence is introduced BY the tie-break and exists only over data written before it
			final OwnerFilterIndex index = restore(storedSingleBucket(), production());
			final Comparator<Comparable> order = production();

			assertTrue(
				index.getRecordsEqualTo(PLAIN).contains(OTHER_RECORD),
				"index path: the surviving bucket still yields the record filed under the other spelling"
			);
			assertFalse(
				index.getRecordsEqualTo(ZERO_WIDTH_SPACED).contains(OTHER_RECORD),
				"index path: and its OWN value no longer finds it"
			);
			// AbstractAttributeComparisonTranslator normalizes both sides and compares with this very comparator,
			// so this is the verdict the prefetch path reaches for the record's real entity value
			assertNotEquals(
				0, order.compare(ZERO_WIDTH_SPACED, PLAIN),
				"prefetch path: rejects what the index path accepted - the two disagree over legacy data"
			);
		}

		@Test
		@DisplayName("data written by the CURRENT engine round-trips, so the hazard is migration-only")
		void shouldRoundTripBothSpellingsWrittenAfterATieBreak() {
			final OwnerFilterIndex index = empty(production());
			index.addRecord(RECORD, PLAIN);
			index.addRecord(OTHER_RECORD, ZERO_WIDTH_SPACED);

			assertEquals(2, index.getInvertedIndex().getBucketCount(), "the tie-break gives each spelling its own bucket");
			assertDoesNotThrow(() -> index.removeRecord(OTHER_RECORD, ZERO_WIDTH_SPACED));
			assertDoesNotThrow(() -> index.removeRecord(RECORD, PLAIN));
			assertTrue(index.isEmpty());
		}
	}

	@Nested
	@DisplayName("What a tie-break does NOT change")
	class Ordering {

		@Test
		@DisplayName("every ordering decision the collator actually makes is preserved")
		void shouldPreserveCollationOrder() {
			// Czech collation sorts `ch` after `h` and `č` after `c` - an order codepoint comparison gets wrong,
			// and the one a tie-break must not disturb
			final String[] czech = {"cukr", "čaj", "dům", "humr", "chléb", "irský"};
			final Comparator<Comparable> collating = collatingWithoutTieBreak(Locale.forLanguageTag("cs"));
			final Comparator<Comparable> tieBroken = production(Locale.forLanguageTag("cs"));

			for (int i = 0; i < czech.length; i++) {
				for (int j = 0; j < czech.length; j++) {
					final int collated = collating.compare(czech[i], czech[j]);
					if (collated != 0) {
						assertEquals(
							Integer.signum(collated), Integer.signum(tieBroken.compare(czech[i], czech[j])),
							"`" + czech[i] + "` vs `" + czech[j] + "` must keep its collated order"
						);
					}
				}
			}
		}

		@Test
		@DisplayName("the tie-break fires only where the collator declined to decide")
		void shouldOnlyDecideWhatTheCollatorLeftUndecided() {
			final Comparator<Comparable> collating = collatingWithoutTieBreak();
			final Comparator<Comparable> tieBroken = production();

			assertEquals(0, collating.compare(PLAIN, ZERO_WIDTH_SPACED), "premise: the collator declines this pair");
			assertNotEquals(
				0, tieBroken.compare(PLAIN, ZERO_WIDTH_SPACED),
				"and the tie-break is what turns it into two keys"
			);
			assertEquals(
				0, tieBroken.compare(PLAIN, new String(PLAIN.toCharArray())),
				"while two genuinely equal strings stay one key"
			);
		}
	}

	@Nested
	@DisplayName("Canonical equivalence still holds where it matters")
	class CanonicalEquivalence {

		@Test
		@DisplayName("NFC and NFD spellings of one word remain a single bucket")
		void shouldKeepCanonicallyEquivalentFormsInOneBucket() {
			// the tie-break separates strings by codepoint, which WOULD split these two - but it never sees them
			// apart, because the index normalizer folds every String key to NFD first. This is the invariant that
			// makes the tie-break safe here and unsafe in the shared comparator
			final String nfc = Normalizer.normalize("\u017Elu\u0165ou\u010Dk\u00FD k\u016F\u0148", Normalizer.Form.NFC);
			final String nfd = Normalizer.normalize("\u017Elu\u0165ou\u010Dk\u00FD k\u016F\u0148", Normalizer.Form.NFD);
			assertNotEquals(nfc, nfd, "premise: the two forms differ as Java strings");

			final OwnerFilterIndex index = new OwnerFilterIndex(localizedKey(), String.class);
			index.addRecord(RECORD, nfc);
			index.addRecord(OTHER_RECORD, nfd);

			assertEquals(1, index.getInvertedIndex().getBucketCount(), "canonical equivalence is preserved by the normalizer");
			assertTrue(index.getRecordsEqualTo(nfc).contains(OTHER_RECORD), "either form finds either record");
		}
	}

	/**
	 * The persisted shape a pre-tie-break engine writes for the two spellings: a single bucket keyed with the
	 * spelling that created it, holding both records. A fresh array per call — the buckets are mutable.
	 */
	@Nonnull
	private static ValueToRecordBitmap[] storedSingleBucket() {
		return new ValueToRecordBitmap[]{new ValueToRecordBitmap(PLAIN, RECORD, OTHER_RECORD)};
	}

	@Nonnull
	private static OwnerFilterIndex restore(
		@Nonnull ValueToRecordBitmap[] buckets, @Nonnull Comparator<Comparable> comparator
	) {
		final Function<Object, Serializable> normalizer = FilterIndex.getNormalizer(String.class, 0);
		return new OwnerFilterIndex(
			localizedKey(), String.class, 0,
			new InvertedIndex(String.class, buckets, normalizer, comparator, 0),
			null, comparator, normalizer
		);
	}

	@Nonnull
	private static OwnerFilterIndex empty(@Nonnull Comparator<Comparable> comparator) {
		final Function<Object, Serializable> normalizer = FilterIndex.getNormalizer(String.class, 0);
		return new OwnerFilterIndex(
			localizedKey(), String.class, 0,
			new InvertedIndex(String.class, normalizer, comparator, 0),
			null, comparator, normalizer
		);
	}

	/**
	 * The order a PRE-tie-break engine wrote its trees in: bare collation, nothing deciding the ties. Built from
	 * {@link Collator} directly rather than from {@link LocalizedStringComparator}, which no longer behaves this
	 * way — the point of these fixtures is to load state the current comparator did not write.
	 */
	@Nonnull
	private static Comparator<Comparable> collatingWithoutTieBreak() {
		return collatingWithoutTieBreak(Locale.ENGLISH);
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	@Nonnull
	private static Comparator<Comparable> collatingWithoutTieBreak(@Nonnull Locale locale) {
		final Collator collator = Collator.getInstance(locale);
		final Comparator<String> bare = collator::compare;
		return (Comparator) bare;
	}

	/** The comparator a localized String filter index is wired with in production. */
	@Nonnull
	private static Comparator<Comparable> production() {
		return production(Locale.ENGLISH);
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	@Nonnull
	private static Comparator<Comparable> production(@Nonnull Locale locale) {
		return (Comparator) new EqualsConsistentLocalizedStringComparator(locale);
	}

	@Nonnull
	private static AttributeIndexKey localizedKey() {
		return new AttributeIndexKey(null, "labels", Locale.ENGLISH);
	}

}
