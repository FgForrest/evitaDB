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

package io.evitadb.index.trigram;

import io.evitadb.api.APITestConstants;
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.proxy.mock.EmptyEntitySchemaAccessor;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.mutation.Mutation;
import io.evitadb.api.requestResponse.schema.CatalogEvolutionMode;
import io.evitadb.api.requestResponse.schema.EntityAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.FilterIndexCapability;
import io.evitadb.api.requestResponse.schema.builder.InternalEntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.attribute.AttributeFormula;
import io.evitadb.core.query.filter.translator.attribute.AttributeContainsTranslator;
import io.evitadb.core.query.filter.translator.attribute.AttributeEndsWithTranslator;
import io.evitadb.core.transaction.Transaction;
import io.evitadb.core.transaction.TransactionHandler;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.invertedIndex.InvertedIndex;
import io.evitadb.index.invertedIndex.InvertedIndex.MatchedBuckets;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.utils.NamingConvention;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiPredicate;

import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.INDEXING;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that {@link TrigramSubstringSearch} is INTERCHANGEABLE with the bucket scan it replaces - the same entity
 * primary keys, for `contains` and `ends with`, over ASCII, Unicode and collation-ordered corpora - and that every
 * situation in which it must decline is a fall back to that scan rather than a wrong or missing answer.
 *
 * The corpus is built directly on a {@link GlobalEntityIndex} rather than through a catalog, because what is under
 * test is the index-level path and nothing above it: that keeps the whole suite in-memory and lets the corpus be
 * shaped so the selectivity gate is deliberately met by some patterns and deliberately missed by others.
 * `AttributeSubstringIndexFunctionalTest` covers the same ground through the query engine.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(INDEXING)
@Tag(ATTRIBUTE)
@DisplayName("Trigram substring search")
class TrigramSubstringSearchTest {

	private static final String ENTITY_TYPE = "Product";
	private static final String ATTRIBUTE_TITLE = "title";
	private static final String ATTRIBUTE_LOCALIZED_TITLE = "localizedTitle";
	private static final Locale FRENCH = Locale.FRENCH;
	private static final Set<Locale> ALLOWED_LOCALES = Set.of(FRENCH);
	private static final int INDEX_PK = 1;

	/**
	 * Values planted with the `zebra` pattern. Deliberately above
	 * {@link io.evitadb.core.query.response.TransactionalDataRelatedStructure#EXCESSIVE_HIGH_CARDINALITY} (100), which
	 * is the bitmap count at which the folded formula stops keying on its individual buckets and falls back to the
	 * index-level token set - the only shape in which the trigram index's own identity is observable.
	 *
	 * It is also the widest candidate set any test here searches for, so it is what {@link #FILLER_VALUES} has to be
	 * sized against. This value must NOT be lowered to make that sizing cheaper - the cache-key assertions depend on
	 * it clearing the high-cardinality threshold, and the filler is the free side of the ratio.
	 */
	private static final int ZEBRA_VALUES = 120;

	/**
	 * Filler values, carrying none of the searched patterns. Their count alone is what puts the corpus past the gate,
	 * so a pattern that IS selective enough is actually accelerated instead of silently taking the scan and proving
	 * nothing.
	 *
	 * Past the whole gate, not merely past
	 * {@link TrigramSubstringSearch#MINIMAL_ACCELERATED_DISTINCT_VALUE_COUNT}: `zebra` is planted in
	 * {@link #ZEBRA_VALUES} values, so the corpus must also reach
	 * {@link TrigramSubstringSearch#accelerationThreshold} for that bound or the accelerated path declines and every
	 * parity case here compares the scan against itself. Derived rather than written down because
	 * {@link TrigramSubstringSearch#CANDIDATE_SELECTIVITY_DIVISOR} is a measured constant expected to be retuned;
	 * the floor of 700 keeps the corpus exactly what it was at the divisor this fixture was first written for.
	 */
	private static final int FILLER_VALUES = Math.max(
		700, (int) TrigramSubstringSearch.accelerationThreshold(ZEBRA_VALUES)
	);

	/**
	 * Values containing `omega` - three of which end with it. Deliberately BELOW the high-cardinality threshold, so
	 * the folded formula takes the other branch and keys on the individual buckets it verified.
	 */
	private static final int OMEGA_VALUES = 5;

	/**
	 * Precomposed (NFC) `café`: the final character is U+00E9 LATIN SMALL LETTER E WITH ACUTE. The form a user types.
	 */
	private static final String NFC_CAFE = "café";

	/**
	 * Decomposed (NFD) `café`: `e` followed by U+0301 COMBINING ACUTE ACCENT. The form the shared value tree stores,
	 * whatever it was given.
	 */
	private static final String NFD_CAFE = Normalizer.normalize(NFC_CAFE, Normalizer.Form.NFD);

	private static final CatalogSchema CATALOG_SCHEMA = CatalogSchema._internalBuild(
		APITestConstants.TEST_CATALOG, NamingConvention.generate(APITestConstants.TEST_CATALOG),
		null,
		EnumSet.allOf(CatalogEvolutionMode.class), EmptyEntitySchemaAccessor.INSTANCE
	);

	/**
	 * `title` is a plain capability-declaring `String`; `localizedTitle` is the same under a locale, which is what
	 * installs a collation comparator on its shared value tree and routes the scan it is compared against through
	 * the non-contiguous branch.
	 */
	private static final EntitySchemaContract SCHEMA = new InternalEntitySchemaBuilder(
		CATALOG_SCHEMA, EntitySchema._internalBuild(ENTITY_TYPE)
	)
		.withAttribute(ATTRIBUTE_TITLE, String.class, thatIs -> thatIs.filterable(FilterIndexCapability.SUBSTRING))
		.withAttribute(
			ATTRIBUTE_LOCALIZED_TITLE, String.class,
			thatIs -> thatIs.localized().filterable(FilterIndexCapability.SUBSTRING)
		)
		.toInstance();

	private static final BiPredicate<String, String> CONTAINS = AttributeContainsTranslator.createPredicate();
	private static final BiPredicate<String, String> ENDS_WITH = AttributeEndsWithTranslator.createPredicate();

	/**
	 * Builds the corpus every test in this class searches, in one deterministic order.
	 *
	 * Its shape is the fixture: the fillers make the corpus big enough to be worth accelerating and share no trigram
	 * with any searched pattern; `zebra` is planted widely enough to cross the high-cardinality bitmap threshold;
	 * `omega` distinguishes `contains` from `ends with`; `abcd` is planted alongside a value holding both of its
	 * trigrams in the WRONG order, which only exact verification can reject; and `café` is stored precomposed so the
	 * normalizer visibly rewrites it.
	 *
	 * @return the distinct attribute values, in insertion order
	 */
	@Nonnull
	private static List<String> corpus() {
		final List<String> values = new ArrayList<>(FILLER_VALUES + ZEBRA_VALUES + 10);
		for (int i = 0; i < FILLER_VALUES; i++) {
			values.add(String.format("item-%04d", i));
		}
		for (int i = 0; i < ZEBRA_VALUES; i++) {
			values.add(String.format("widget zebra %03d", i));
		}
		// ends with `omega`
		values.add("omega");
		values.add("tail omega");
		values.add("long tail omega");
		// contains `omega` but does not end with it
		values.add("omega leads here");
		values.add("an omega inside x");
		// `abcd`'s two trigrams, planted so that NEITHER posting is a subset of the other and the intersection really
		// drops an entry. The ORDER matters: value ids ascend with insertion, `abc` is the cheapest posting (equal
		// cardinality, lower packed key), and `abcz` is the entry that gets dropped from it - putting it FIRST means
		// the survivors move down a slot, which is what makes an in-place compaction of the index's own posting
		// observable at all (see `shouldNotWriteIntoTheIndexOwnPostings`). Planted last, the compaction would rewrite
		// each slot with the value it already held and the test would be decorative.
		values.add("abcz");
		values.add("zbcdz");
		// a true `abcd` match, and a decoy holding `abc` and `bcd` but never `abcd`
		values.add("xxabcdxx");
		values.add("bcd then abc");
		// the accented pair - one matches `café`, the other only its ASCII prefix
		values.add(NFC_CAFE + " noir");
		values.add("decaf latte");
		return values;
	}

	/**
	 * @param name the attribute name declared on {@link #SCHEMA}
	 * @return the assembled entity-level attribute schema
	 */
	@Nonnull
	private static EntityAttributeSchemaContract attribute(@Nonnull String name) {
		return SCHEMA.getAttribute(name).orElseThrow();
	}

	/**
	 * @param name   the attribute name
	 * @param locale the locale, or `null` for a language-agnostic attribute
	 * @return the key both the shared value tree and the trigram map are filed under
	 */
	@Nonnull
	private static AttributeIndexKey keyOf(@Nonnull String name, @Nullable Locale locale) {
		return new AttributeIndexKey(null, name, locale);
	}

	/**
	 * Builds a global index holding the whole corpus under one attribute.
	 *
	 * Every distinct value carries TWO records rather than one, which is not incidental: a single-record bucket is
	 * backed by a `SingleRecordBitmap` that owns no transactional identity at all, so a formula folded over such
	 * buckets gathers an EMPTY transactional id set and the cache-key assertions below would be asserted against
	 * nothing. Two records make every bucket a real `TransactionalBitmap`, which is also the shape a production
	 * corpus of any size has.
	 *
	 * @param attributeName the attribute to write to
	 * @param locale        the locale to write under, or `null`
	 * @return the populated index
	 */
	@Nonnull
	private static GlobalEntityIndex populatedIndex(@Nonnull String attributeName, @Nullable Locale locale) {
		final GlobalEntityIndex index = new GlobalEntityIndex(
			INDEX_PK, ENTITY_TYPE, new EntityIndexKey(EntityIndexType.GLOBAL, Scope.LIVE)
		);
		final List<String> values = corpus();
		for (int i = 0; i < values.size(); i++) {
			for (final int recordId : recordsOf(i)) {
				index.upsertAttribute(
					null, attribute(attributeName), ALLOWED_LOCALES, Scope.LIVE, locale, values.get(i), recordId
				);
			}
		}
		return index;
	}

	/**
	 * @param valueIndex the position of a value in {@link #corpus()}
	 * @return the record ids that value is written for
	 */
	@Nonnull
	private static int[] recordsOf(int valueIndex) {
		return new int[]{2 * valueIndex + 1, 2 * valueIndex + 2};
	}

	/**
	 * @param value the corpus value
	 * @return the record ids that value is written for
	 */
	@Nonnull
	private static int[] recordsOf(@Nonnull String value) {
		final int valueIndex = corpus().indexOf(value);
		assertTrue(valueIndex >= 0, "`" + value + "` is not part of the corpus");
		return recordsOf(valueIndex);
	}

	/**
	 * @param index         the populated index
	 * @param attributeName the attribute written to
	 * @param locale        the locale written under, or `null`
	 * @return the attribute's filter index, which is a view over the very tree the trigram postings are keyed by
	 */
	@Nonnull
	private static FilterIndex filterIndexOf(
		@Nonnull GlobalEntityIndex index, @Nonnull String attributeName, @Nullable Locale locale
	) {
		final FilterIndex filterIndex = index.getFilterIndex(keyOf(attributeName, locale));
		assertNotNull(filterIndex, "the corpus must have created the shared value tree");
		return filterIndex;
	}

	/**
	 * @param index         the populated index
	 * @param attributeName the attribute written to
	 * @param locale        the locale written under, or `null`
	 * @return the attribute's trigram index
	 */
	@Nonnull
	private static TrigramIndex trigramIndexOf(
		@Nonnull GlobalEntityIndex index, @Nonnull String attributeName, @Nullable Locale locale
	) {
		final TrigramIndex trigramIndex = index.getTrigramIndex(keyOf(attributeName, locale));
		assertNotNull(trigramIndex, "the corpus must have created the trigram index");
		return trigramIndex;
	}

	/**
	 * Runs the accelerated path, insisting it did NOT decline - a test that means to compare two paths must fail
	 * loudly rather than silently compare the scan with itself.
	 *
	 * @param index         the populated index
	 * @param attributeName the attribute to search
	 * @param locale        the locale to search under, or `null`
	 * @param pattern       the raw search pattern
	 * @param predicate     the exact predicate, `contains` or `ends with`
	 * @return the buckets the accelerated path matched
	 */
	@Nonnull
	private static MatchedBuckets matched(
		@Nonnull GlobalEntityIndex index,
		@Nonnull String attributeName,
		@Nullable Locale locale,
		@Nonnull String pattern,
		@Nonnull BiPredicate<String, String> predicate
	) {
		final MatchedBuckets buckets = TrigramSubstringSearch.match(
			trigramIndexOf(index, attributeName, locale),
			filterIndexOf(index, attributeName, locale).getInvertedIndex(),
			pattern, predicate
		);
		assertNotNull(
			buckets,
			"the trigram path declined `" + pattern + "` - this test compares it against the scan, so a decline "
				+ "would make the comparison vacuous"
		);
		return buckets;
	}

	/**
	 * Unions the matched buckets' record sets, which is what any assembly of them - eager or deferred - must
	 * ultimately produce. Comparing THIS against the scan is what makes the parity assertions independent of how the
	 * buckets are folded into a formula.
	 *
	 * @param index         the populated index
	 * @param attributeName the attribute to search
	 * @param locale        the locale to search under, or `null`
	 * @param pattern       the raw search pattern
	 * @param predicate     the exact predicate, `contains` or `ends with`
	 * @return the matching record ids, ascending
	 */
	@Nonnull
	private static int[] acceleratedKeys(
		@Nonnull GlobalEntityIndex index,
		@Nonnull String attributeName,
		@Nullable Locale locale,
		@Nonnull String pattern,
		@Nonnull BiPredicate<String, String> predicate
	) {
		final BaseBitmap union = new BaseBitmap();
		for (final Bitmap recordSet : matched(index, attributeName, locale, pattern, predicate).recordSets()) {
			union.addAll(recordSet);
		}
		return union.getArray();
	}

	/**
	 * Assembles the matched buckets into the EAGER formula the translator builds today. Used only by the cache-key
	 * assertions, which are about that assembly and nothing else.
	 *
	 * @param index         the populated index
	 * @param attributeName the attribute to search
	 * @param locale        the locale to search under, or `null`
	 * @param pattern       the raw search pattern
	 * @param predicate     the exact predicate, `contains` or `ends with`
	 * @return the eagerly assembled formula
	 */
	@Nonnull
	private static Formula accelerated(
		@Nonnull GlobalEntityIndex index,
		@Nonnull String attributeName,
		@Nullable Locale locale,
		@Nonnull String pattern,
		@Nonnull BiPredicate<String, String> predicate
	) {
		return filterIndexOf(index, attributeName, locale).getInvertedIndex().toFormula(
			matched(index, attributeName, locale, pattern, predicate),
			TrigramSubstringSearch.versionIdsOf(trigramIndexOf(index, attributeName, locale))
		);
	}

	/**
	 * Asserts the accelerated path and the scan return exactly the same primary keys.
	 *
	 * @param index         the populated index
	 * @param attributeName the attribute to search
	 * @param locale        the locale to search under, or `null`
	 * @param pattern       the raw search pattern
	 * @param endsWith      `true` compares `ends with`, `false` compares `contains`
	 */
	private static void assertParity(
		@Nonnull GlobalEntityIndex index,
		@Nonnull String attributeName,
		@Nullable Locale locale,
		@Nonnull String pattern,
		boolean endsWith
	) {
		final FilterIndex filterIndex = filterIndexOf(index, attributeName, locale);
		final int[] scanned = (endsWith
			? filterIndex.getRecordsWhoseValuesEndsWith(pattern)
			: filterIndex.getRecordsWhoseValuesContains(pattern)).compute().getArray();
		final int[] matchedKeys = acceleratedKeys(
			index, attributeName, locale, pattern, endsWith ? ENDS_WITH : CONTAINS
		);
		assertArrayEquals(
			scanned, matchedKeys,
			"the trigram path and the scan disagree on `" + pattern + "` (" + (endsWith ? "endsWith" : "contains")
				+ ") - scan produced " + scanned.length + " keys, trigram path " + matchedKeys.length
		);
	}

	/**
	 * Runs `insideTransaction` with a real transaction bound to the calling thread, rolling it back afterwards - the
	 * same fixture `ValueIdTest` uses, for the same reason: what is asserted is what a reader sees WHILE the
	 * transaction is open.
	 *
	 * @param insideTransaction the body to run with a transaction on the thread
	 */
	private static void executeInsideTransaction(@Nonnull Runnable insideTransaction) {
		Transaction.executeInTransactionIfProvided(
			new Transaction(
				UUID.randomUUID(),
				new TransactionHandler() {
					@Override
					public void registerMutation(@Nonnull Mutation mutation) {
						// no mutation recording is needed for a structure-level test
					}

					@Override
					public void commit(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
						// unused - the transaction is always rolled back
					}

					@Override
					public void rollback(
						@Nonnull TransactionalLayerMaintainer transactionalLayer, @Nullable Throwable cause
					) {
						// the writes made inside are discarded; only what was observed inside matters
					}
				},
				false
			),
			() -> {
				final Transaction transaction = Transaction.getTransaction().orElseThrow();
				try {
					insideTransaction.run();
				} finally {
					// closed in a finally so a failing assertion cannot leave a transaction bound to the thread and
					// poison every test that runs after it in the same fork
					transaction.setRollbackOnly();
					transaction.close();
				}
			}
		);
	}

	@Nested
	@DisplayName("the accelerated path answers exactly what the scan would")
	class Parity {

		@Test
		@DisplayName("contains agrees with the scan across the whole corpus")
		void shouldAgreeWithTheScanOnContains() {
			final GlobalEntityIndex index = populatedIndex(ATTRIBUTE_TITLE, null);
			for (final String pattern : new String[]{"zebra", "omega", "abcd", "widget zebra 007", "tail"}) {
				assertParity(index, ATTRIBUTE_TITLE, null, pattern, false);
			}
		}

		@Test
		@DisplayName("endsWith agrees with the scan across the whole corpus")
		void shouldAgreeWithTheScanOnEndsWith() {
			final GlobalEntityIndex index = populatedIndex(ATTRIBUTE_TITLE, null);
			for (final String pattern : new String[]{"omega", "zebra", "abcdxx", "here"}) {
				assertParity(index, ATTRIBUTE_TITLE, null, pattern, true);
			}
		}

		@Test
		@DisplayName("a candidate holding every trigram in the wrong order is rejected by verification")
		void shouldRejectAFalseCandidate() {
			// `bcd then abc` posts against both `abc` and `bcd`, so it survives the intersection and can only be
			// removed by the exact predicate - if verification were skipped this would return two keys, not one
			final GlobalEntityIndex index = populatedIndex(ATTRIBUTE_TITLE, null);
			final TrigramIndex trigramIndex = trigramIndexOf(index, ATTRIBUTE_TITLE, null);
			assertEquals(
				2, trigramIndex.resolveCandidateValueIds(TrigramCodec.extractUniqueTrigrams("abcd")).length,
				"the intersection must nominate the decoy as well, or this test proves nothing about verification"
			);
			assertArrayEquals(
				recordsOf("xxabcdxx"),
				acceleratedKeys(index, ATTRIBUTE_TITLE, null, "abcd", CONTAINS)
			);
		}

		@Test
		@DisplayName("the intersection leaves the index's own postings untouched")
		void shouldNotWriteIntoTheIndexOwnPostings() {
			// the cheapest posting of `abcd` is the index's OWN array, shared by reference with every index version
			// that has not rewritten it, and the intersection really drops one of its entries - so an intersection
			// that compacted in place would corrupt the posting rather than merely produce a wrong answer once
			final GlobalEntityIndex index = populatedIndex(ATTRIBUTE_TITLE, null);
			final TrigramIndex trigramIndex = trigramIndexOf(index, ATTRIBUTE_TITLE, null);
			final long[] trigrams = TrigramCodec.extractUniqueTrigrams("abcd");
			final int[][] postingsBefore = new int[trigrams.length][];
			for (int i = 0; i < trigrams.length; i++) {
				postingsBefore[i] = trigramIndex.getValueIdsOf(trigrams[i]).getArray();
				assertEquals(3, postingsBefore[i].length, "both postings must be wider than their intersection");
			}

			final int[] first = trigramIndex.resolveCandidateValueIds(trigrams);
			assertEquals(2, first.length, "the intersection must really drop an entry, or nothing is being compacted");

			for (int i = 0; i < trigrams.length; i++) {
				assertArrayEquals(
					postingsBefore[i], trigramIndex.getValueIdsOf(trigrams[i]).getArray(),
					"posting of `" + TrigramCodec.toDisplayString(trigrams[i]) + "` was written into"
				);
			}
			assertArrayEquals(first, trigramIndex.resolveCandidateValueIds(trigrams));
		}

		@Test
		@DisplayName("a precomposed pattern matches a value the tree stores decomposed")
		void shouldMatchAcrossUnicodeNormalizationForms() {
			final GlobalEntityIndex index = populatedIndex(ATTRIBUTE_TITLE, null);
			final InvertedIndex tree = filterIndexOf(index, ATTRIBUTE_TITLE, null).getInvertedIndex();
			// the tree really did rewrite the value into NFD, or the two probe forms below would be the same probe
			// twice and this test would say nothing about normalization at all
			assertEquals(
				NFD_CAFE + " noir",
				tree.getValueById(tree.getValueId(NFC_CAFE + " noir")),
				"the shared value tree must store the decomposed form"
			);
			for (final String pattern : new String[]{NFC_CAFE, NFD_CAFE}) {
				assertParity(index, ATTRIBUTE_TITLE, null, pattern, false);
				assertArrayEquals(
					recordsOf(NFC_CAFE + " noir"),
					acceleratedKeys(index, ATTRIBUTE_TITLE, null, pattern, CONTAINS),
					"`" + pattern + "` must find the accented value in either normalization form"
				);
			}
		}

		@Test
		@DisplayName("a collation-ordered localized attribute agrees with its scan too")
		void shouldAgreeWithTheScanUnderACollationComparator() {
			// a localized String attribute installs a LocalizedStringComparator, under which the tree's key order is
			// no longer codepoint order - the reverse lookup resolves through the directory rather than through that
			// order, so the two paths must still coincide
			final GlobalEntityIndex index = populatedIndex(ATTRIBUTE_LOCALIZED_TITLE, FRENCH);
			for (final String pattern : new String[]{"zebra", "omega", NFC_CAFE}) {
				assertParity(index, ATTRIBUTE_LOCALIZED_TITLE, FRENCH, pattern, false);
			}
			assertParity(index, ATTRIBUTE_LOCALIZED_TITLE, FRENCH, "omega", true);
		}

		@Test
		@DisplayName("a pattern no value contains resolves to nothing, without an intersection")
		void shouldResolveAnAbsentPatternToNothing() {
			final GlobalEntityIndex index = populatedIndex(ATTRIBUTE_TITLE, null);
			final MatchedBuckets buckets = TrigramSubstringSearch.match(
				trigramIndexOf(index, ATTRIBUTE_TITLE, null),
				filterIndexOf(index, ATTRIBUTE_TITLE, null).getInvertedIndex(),
				"xyzzy", CONTAINS
			);
			assertNotNull(buckets, "an absent pattern is an ANSWER, not a decline");
			assertTrue(buckets.isEmpty());
			assertEquals(
				0,
				filterIndexOf(index, ATTRIBUTE_TITLE, null)
					.getRecordsWhoseValuesContains("xyzzy").compute().size()
			);
		}
	}

	@Nested
	@DisplayName("every reason to decline hands the query back to the scan")
	class Fallbacks {

		@Test
		@DisplayName("a pattern under three code points declines")
		void shouldDeclineAPatternShorterThanATrigram() {
			// CALIBRATION: without this guard the pattern yields NO trigrams, `minimumCardinalityOf` answers 0, and
			// the empty-posting short circuit below it would return EmptyFormula - i.e. `contains("it")` would match
			// nothing at all instead of the 700 filler values the scan finds. The assertion on the scan's own answer
			// is what makes that counterfactual visible here.
			final GlobalEntityIndex index = populatedIndex(ATTRIBUTE_TITLE, null);
			assertNull(
				TrigramSubstringSearch.match(
					trigramIndexOf(index, ATTRIBUTE_TITLE, null),
					filterIndexOf(index, ATTRIBUTE_TITLE, null).getInvertedIndex(),
					"it", CONTAINS
				)
			);
			assertEquals(
				2 * FILLER_VALUES,
				filterIndexOf(index, ATTRIBUTE_TITLE, null)
					.getRecordsWhoseValuesContains("it").compute().size(),
				"the scan this declines to must have a non-empty answer, or the guard would be indistinguishable "
					+ "from the empty-posting short circuit"
			);
		}

		@Test
		@DisplayName("an empty pattern declines rather than nominating every value")
		void shouldDeclineAnEmptyPattern() {
			final GlobalEntityIndex index = populatedIndex(ATTRIBUTE_TITLE, null);
			assertNull(
				TrigramSubstringSearch.match(
					trigramIndexOf(index, ATTRIBUTE_TITLE, null),
					filterIndexOf(index, ATTRIBUTE_TITLE, null).getInvertedIndex(),
					"", CONTAINS
				)
			);
		}

		@Test
		@DisplayName("an open transaction declines instead of under-reporting")
		void shouldDeclineWhileATransactionIsOpen() {
			final GlobalEntityIndex index = populatedIndex(ATTRIBUTE_TITLE, null);
			final TrigramIndex trigramIndex = trigramIndexOf(index, ATTRIBUTE_TITLE, null);
			final InvertedIndex tree = filterIndexOf(index, ATTRIBUTE_TITLE, null).getInvertedIndex();
			// force the directory into existence outside the transaction, so what the body below meets is a built one
			assertFalse(matched(index, ATTRIBUTE_TITLE, null, "omega", CONTAINS).isEmpty());

			executeInsideTransaction(() -> {
				assertNull(TrigramSubstringSearch.match(trigramIndex, tree, "omega", CONTAINS));
				// CALIBRATION: this is exactly what the pre-flight above avoids. Remove that check and the verification
				// it guards raises this error on an ordinary query rather than falling back
				assertThrows(
					GenericEvitaInternalError.class,
					() -> tree.getRecordsOfValueIdsMatching(
						trigramIndex.resolveCandidateValueIds(TrigramCodec.extractUniqueTrigrams("omega")),
						1, value -> true
					)
				);
			});

			assertFalse(
				matched(index, ATTRIBUTE_TITLE, null, "omega", CONTAINS).isEmpty(),
				"the decline must be scoped to the open transaction"
			);
		}

		@Test
		@DisplayName("a pattern covering too much of the corpus declines")
		void shouldDeclineAnUnselectivePattern() {
			// `item` is carried by every filler value, so its cheapest posting covers all but a handful of the corpus
			// - far past whatever share CANDIDATE_SELECTIVITY_DIVISOR admits, at any value it is ever retuned to. The
			// scan visits each of those values once, while the trigram path would visit them once AND pay a directory
			// probe and a bucket descent on top
			final GlobalEntityIndex index = populatedIndex(ATTRIBUTE_TITLE, null);
			assertNull(
				TrigramSubstringSearch.match(
					trigramIndexOf(index, ATTRIBUTE_TITLE, null),
					filterIndexOf(index, ATTRIBUTE_TITLE, null).getInvertedIndex(),
					"item", CONTAINS
				)
			);
		}

		@Test
		@DisplayName("a corpus below the floor declines however selective the pattern is")
		void shouldDeclineOnATinyCorpus() {
			final GlobalEntityIndex index = new GlobalEntityIndex(
				INDEX_PK, ENTITY_TYPE, new EntityIndexKey(EntityIndexType.GLOBAL, Scope.LIVE)
			);
			index.upsertAttribute(
				null, attribute(ATTRIBUTE_TITLE), ALLOWED_LOCALES, Scope.LIVE, null, "solitary zebra", 1);
			assertNull(
				TrigramSubstringSearch.match(
					trigramIndexOf(index, ATTRIBUTE_TITLE, null),
					filterIndexOf(index, ATTRIBUTE_TITLE, null).getInvertedIndex(),
					"zebra", CONTAINS
				),
				"a one-leaf tree is scanned as a contiguous array; nothing can beat that"
			);
		}
	}

	@Nested
	@DisplayName("the A/B threshold is a decision of its own")
	class SelectivityGate {

		@Test
		@DisplayName("a corpus below the floor is never accelerated")
		void shouldRefuseBelowTheFloor() {
			assertFalse(
				TrigramSubstringSearch.isWorthAccelerating(
					0, TrigramSubstringSearch.MINIMAL_ACCELERATED_DISTINCT_VALUE_COUNT - 1
				)
			);
			assertTrue(
				TrigramSubstringSearch.isWorthAccelerating(
					1, TrigramSubstringSearch.MINIMAL_ACCELERATED_DISTINCT_VALUE_COUNT
				)
			);
		}

		@Test
		@DisplayName("the candidate bound must stay within its share of the corpus")
		void shouldRefuseAnUnselectiveCandidateBound() {
			final int corpusSize = 4_000;
			final int share = corpusSize / TrigramSubstringSearch.CANDIDATE_SELECTIVITY_DIVISOR;
			assertTrue(TrigramSubstringSearch.isWorthAccelerating(share, corpusSize));
			assertFalse(TrigramSubstringSearch.isWorthAccelerating(share + 1, corpusSize));
		}

		@Test
		@DisplayName("this suite's own corpus still clears the gate it is calibrated against")
		void shouldKeepTheCorpusAboveTheGate() {
			// named and asserted separately so that a retune of CANDIDATE_SELECTIVITY_DIVISOR reddens THIS case first,
			// saying the fixture needs resizing - rather than reddening a dozen Parity and CacheKey cases, which reads
			// as "the retune broke the accelerator" to whoever sees it next
			final int corpusSize = corpus().size();
			assertTrue(
				TrigramSubstringSearch.isWorthAccelerating(ZEBRA_VALUES, corpusSize),
				"`zebra` is planted in " + ZEBRA_VALUES + " of " + corpusSize + " values, which no longer clears the "
					+ "gate - FILLER_VALUES derives from the threshold precisely so this cannot happen, so if it has "
					+ "the derivation is wrong rather than the corpus"
			);
		}

		@Test
		@DisplayName("the single-comparison threshold decides exactly what the two-part formula did")
		void shouldAgreeWithTheTwoPartFormulaItReplaces() {
			// `isWorthAccelerating` is now one comparison against `accelerationThreshold`, so that a caller summing a
			// fan-out can stop the moment its running total reaches that target instead of walking every index. The
			// fold relies on `c <= n / d` and `c * d <= n` being the same predicate under floor division - true, but
			// exactly the kind of identity that is wrong at one boundary and right everywhere else, so it is swept
			// rather than spot-checked. Deliberately including the corner where n is NOT a multiple of the divisor.
			for (int distinctValueCount = 0; distinctValueCount <= 2_048; distinctValueCount++) {
				for (int candidateUpperBound = 0; candidateUpperBound <= 600; candidateUpperBound += 7) {
					final boolean asWrittenBefore =
						distinctValueCount >= TrigramSubstringSearch.MINIMAL_ACCELERATED_DISTINCT_VALUE_COUNT
							&& candidateUpperBound
							<= distinctValueCount / TrigramSubstringSearch.CANDIDATE_SELECTIVITY_DIVISOR;
					assertEquals(
						asWrittenBefore,
						TrigramSubstringSearch.isWorthAccelerating(candidateUpperBound, distinctValueCount),
						"the two forms of the gate disagree at candidateUpperBound=" + candidateUpperBound
							+ ", distinctValueCount=" + distinctValueCount
					);
				}
			}
		}

		/**
		 * NOTE, so nobody mistakes this for protection it does not give: this case derives the corpus it expects
		 * from `accelerationThreshold` itself, so it pins the RELATIONSHIP between that method and
		 * `isWorthAccelerating` and not the value either produces. It survives any self-consistent corruption of
		 * the threshold - measured: adding `+ 1` to the product inside `accelerationThreshold` leaves this case
		 * green, and leaves the pre-existing `shouldRefuseAnUnselectiveCandidateBound` green too, because 4000 is
		 * not divisible by the divisor and the slack absorbs it. `shouldAgreeWithTheTwoPartFormulaItReplaces` is
		 * the only guard on the arithmetic, because it compares against a formula written out independently.
		 */
		@Test
		@DisplayName("the threshold is the smallest corpus the bound is accepted against")
		void shouldReportTheSmallestAcceptedCorpus() {
			final int candidateUpperBound = 500;
			final long threshold = TrigramSubstringSearch.accelerationThreshold(candidateUpperBound);
			assertTrue(threshold <= Integer.MAX_VALUE, "the sweep below needs the threshold to fit an int");
			assertTrue(
				TrigramSubstringSearch.isWorthAccelerating(candidateUpperBound, (int) threshold),
				"the threshold itself must be accepted"
			);
			assertFalse(
				TrigramSubstringSearch.isWorthAccelerating(candidateUpperBound, (int) threshold - 1),
				"one distinct value short of the threshold must be refused"
			);
		}
	}

	/**
	 * Pins the EAGER assembly of the matched buckets, and only that: every assertion here goes through
	 * {@link InvertedIndex#toFormula}, whose cache identity is content-addressed precisely because the selection has
	 * already happened. A deferred assembly would hash the question instead of the answer, and these assertions would
	 * have to be restated rather than merely re-pointed. Everything the substring path does BEFORE the fold is pinned
	 * by the sibling classes, which never mention a formula.
	 */
	@Nested
	@DisplayName("the eager assembly keys on everything its answer depends on")
	class CacheKey {

		@Test
		@DisplayName("the accelerated formula and the scan's share one cache entry")
		void shouldHashIdenticallyToTheScan() {
			// A far stronger statement than "different results hash differently": the two paths agree on the answer
			// AND land on the same cache entry, so accelerating a query cannot fragment the formula cache. It holds
			// because both fold the very same bucket record sets, and `AbstractBitmapCacheableFormula` sorts the
			// bitmap ids before hashing - so the candidate order the trigram path produces (ascending VALUE id) and
			// the bucket order the scan produces (ascending KEY) are indistinguishable to the hash.
			final GlobalEntityIndex index = populatedIndex(ATTRIBUTE_TITLE, null);
			final FilterIndex filterIndex = filterIndexOf(index, ATTRIBUTE_TITLE, null);
			for (final String pattern : new String[]{"zebra", "omega", "abcd", NFC_CAFE}) {
				assertEquals(
					filterIndex.getRecordsWhoseValuesContains(pattern).getHash(),
					accelerated(index, ATTRIBUTE_TITLE, null, pattern, CONTAINS).getHash(),
					"contains `" + pattern + "` must hash the same on both paths"
				);
				assertEquals(
					filterIndex.getRecordsWhoseValuesEndsWith(pattern).getHash(),
					accelerated(index, ATTRIBUTE_TITLE, null, pattern, ENDS_WITH).getHash(),
					"endsWith `" + pattern + "` must hash the same on both paths"
				);
			}
		}

		@Test
		@DisplayName("only the high-cardinality answer diverges from the scan in its staleness ids")
		void shouldDivergeFromTheScanOnlyAboveTheCardinalityCap() {
			// the two axes come apart exactly where `AbstractBitmapCacheableFormula` stops keying on individual
			// bitmaps: below the cap both paths gather the same per-bucket ids, above it the trigram path's extra
			// token appears - which is the whole point of carrying it
			final GlobalEntityIndex index = populatedIndex(ATTRIBUTE_TITLE, null);
			final FilterIndex filterIndex = filterIndexOf(index, ATTRIBUTE_TITLE, null);
			assertArrayEquals(
				filterIndex.getRecordsWhoseValuesContains("omega").gatherTransactionalIds(),
				accelerated(index, ATTRIBUTE_TITLE, null, "omega", CONTAINS).gatherTransactionalIds(),
				"below the cap the two paths must gather identical transactional ids"
			);
			final long[] scanned = filterIndex.getRecordsWhoseValuesContains("zebra").gatherTransactionalIds();
			final long[] acceleratedIds =
				accelerated(index, ATTRIBUTE_TITLE, null, "zebra", CONTAINS).gatherTransactionalIds();
			assertEquals(
				scanned.length + 1, acceleratedIds.length,
				"above the cap the accelerated answer adds exactly the trigram index id"
			);
			assertTrue(
				Arrays.stream(acceleratedIds)
					.anyMatch(id -> id == trigramIndexOf(index, ATTRIBUTE_TITLE, null).getId())
			);
		}

		@Test
		@DisplayName("a high-cardinality match keys on the trigram index's own identity")
		void shouldFoldInTheTrigramIndexIdentity() {
			// above EXCESSIVE_HIGH_CARDINALITY bitmaps the folded formula stops keying on its individual buckets and
			// falls back to the token set handed to it - which is where the trigram index's identity has to be, since
			// no leaf token can express a change to the postings that decided WHICH buckets were verified
			final GlobalEntityIndex index = populatedIndex(ATTRIBUTE_TITLE, null);
			final TrigramIndex trigramIndex = trigramIndexOf(index, ATTRIBUTE_TITLE, null);
			final Formula formula = accelerated(index, ATTRIBUTE_TITLE, null, "zebra", CONTAINS);
			assertEquals(2 * ZEBRA_VALUES, formula.compute().size());
			assertTrue(
				Arrays.stream(formula.gatherTransactionalIds()).anyMatch(id -> id == trigramIndex.getId()),
				"the trigram index id must be among " + Arrays.toString(formula.gatherTransactionalIds())
			);
		}

		@Test
		@DisplayName("a low-cardinality match keys on the buckets it actually read")
		void shouldFoldInTheVerifiedLeaves() {
			final GlobalEntityIndex index = populatedIndex(ATTRIBUTE_TITLE, null);
			final Formula formula = accelerated(index, ATTRIBUTE_TITLE, null, "omega", CONTAINS);
			assertEquals(2 * OMEGA_VALUES, formula.compute().size());
			assertEquals(
				OMEGA_VALUES, formula.gatherTransactionalIds().length,
				"below the high-cardinality threshold the formula keys on the record set of each verified bucket"
			);
		}

		@Test
		@DisplayName("contains and endsWith over the same term do not share a key when they differ")
		void shouldNotCollideAcrossConstraintKinds() {
			final GlobalEntityIndex index = populatedIndex(ATTRIBUTE_TITLE, null);
			final Formula contains = accelerated(index, ATTRIBUTE_TITLE, null, "omega", CONTAINS);
			final Formula endsWith = accelerated(index, ATTRIBUTE_TITLE, null, "omega", ENDS_WITH);
			assertNotEquals(contains.compute().size(), endsWith.compute().size());
			assertNotEquals(contains.getHash(), endsWith.getHash());
		}

		@Test
		@DisplayName("different search terms do not share a key")
		void shouldNotCollideAcrossSearchTerms() {
			final GlobalEntityIndex index = populatedIndex(ATTRIBUTE_TITLE, null);
			assertNotEquals(
				accelerated(index, ATTRIBUTE_TITLE, null, "omega", CONTAINS).getHash(),
				accelerated(index, ATTRIBUTE_TITLE, null, "zebra", CONTAINS).getHash()
			);
		}

		@Test
		@DisplayName("two indexes holding identical values do not share a key")
		void shouldNotCollideAcrossIndexes() {
			// the two trees hold exactly the same strings, so nothing but the identity of the record sets they were
			// folded over can tell the two formulas apart - which is precisely what a cache key over index state
			// has to do, since the two indexes can be written to independently
			final GlobalEntityIndex first = populatedIndex(ATTRIBUTE_TITLE, null);
			final GlobalEntityIndex second = populatedIndex(ATTRIBUTE_TITLE, null);
			assertNotEquals(
				accelerated(first, ATTRIBUTE_TITLE, null, "omega", CONTAINS).getHash(),
				accelerated(second, ATTRIBUTE_TITLE, null, "omega", CONTAINS).getHash()
			);
		}

		@Test
		@DisplayName("the same result under two attribute names does not share a key")
		void shouldNotCollideAcrossAttributes() {
			// the attribute discriminator is the enclosing AttributeFormula's, exactly as it is for every other
			// attribute filter - the trigram path produces the same shape of inner formula the scan does, so it
			// inherits that discrimination rather than restating it
			final GlobalEntityIndex index = populatedIndex(ATTRIBUTE_TITLE, null);
			final Formula inner = accelerated(index, ATTRIBUTE_TITLE, null, "omega", CONTAINS);
			assertNotEquals(
				new AttributeFormula(false, new AttributeKey(ATTRIBUTE_TITLE), inner).getHash(),
				new AttributeFormula(false, new AttributeKey(ATTRIBUTE_LOCALIZED_TITLE), inner).getHash()
			);
		}

		@Test
		@DisplayName("a write that adds a match changes the gathered transactional ids")
		void shouldInvalidateWhenTheDataChanges() {
			final GlobalEntityIndex index = populatedIndex(ATTRIBUTE_TITLE, null);
			final Formula before = accelerated(index, ATTRIBUTE_TITLE, null, "omega", CONTAINS);
			final long[] idsBefore = before.gatherTransactionalIds();
			final long hashBefore = before.getHash();

			// two records again, so the new bucket is a TransactionalBitmap with an identity of its own rather than a
			// SingleRecordBitmap, which owns none and would leave the gathered id set unchanged
			index.upsertAttribute(
				null, attribute(ATTRIBUTE_TITLE), ALLOWED_LOCALES, Scope.LIVE, null, "one more omega", 100_001);
			index.upsertAttribute(
				null, attribute(ATTRIBUTE_TITLE), ALLOWED_LOCALES, Scope.LIVE, null, "one more omega", 100_002);

			final Formula after = accelerated(index, ATTRIBUTE_TITLE, null, "omega", CONTAINS);
			assertEquals(before.compute().size() + 2, after.compute().size());
			assertNotEquals(hashBefore, after.getHash());
			assertFalse(Arrays.equals(idsBefore, after.gatherTransactionalIds()));
		}
	}

}
