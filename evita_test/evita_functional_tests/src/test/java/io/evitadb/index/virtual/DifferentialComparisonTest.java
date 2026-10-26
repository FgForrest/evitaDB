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

package io.evitadb.index.virtual;

import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.schema.OrderBehaviour;
import io.evitadb.dataType.BigDecimalNumberRange;
import io.evitadb.dataType.ComparableCurrency;
import io.evitadb.dataType.ComparableLocale;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.dataType.Predecessor;
import io.evitadb.index.attribute.ChainIndex;
import io.evitadb.index.attribute.OwnerSortIndex;
import io.evitadb.index.attribute.SortIndex;
import io.evitadb.index.attribute.SortIndex.ComparableArray;
import io.evitadb.index.attribute.SortIndex.ComparatorSource;
import io.evitadb.index.virtual.SortIndexWalk.Block;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Currency;
import java.util.List;
import java.util.Locale;

import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.TEST_HARNESS;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Proves that the index-level descriptions of {@link CatalogDescriber} can fail where the renderings they replaced
 * could not. Each test builds two indexes that differ in exactly one respect and asserts, first, that the old rendering
 * finds them equal - the premise that makes the test worth having - and then that the comparison tells them apart.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Differential index comparisons")
@Tag(TEST_HARNESS)
@Tag(INDEXING)
class DifferentialComparisonTest {
	private static final AttributeIndexKey KEY = new AttributeIndexKey(null, "a", null);

	/**
	 * Creates the range `[3.4,4.5]` kept at the given decimal places.
	 *
	 * @param decimalPlaces the decimal places
	 * @return the range
	 */
	@Nonnull
	private static BigDecimalNumberRange rangeAt(int decimalPlaces) {
		return BigDecimalNumberRange.between(new BigDecimal("3.4"), new BigDecimal("4.5"), decimalPlaces);
	}

	@Nested
	@DisplayName("Canonical value encoding")
	class CanonicalValueTest {

		@Test
		@DisplayName("two ranges that print alike but keep different decimal places encode differently")
		void shouldTellApartRangesThatPrintAlike() {
			final BigDecimalNumberRange atOnePlace = rangeAt(1);
			final BigDecimalNumberRange atTwoPlaces = rangeAt(2);

			assertEquals(atOnePlace.toString(), atTwoPlaces.toString(), "the premise: the ranges print alike");
			assertNotEquals(CanonicalValue.encode(atOnePlace), CanonicalValue.encode(atTwoPlaces));
			assertEquals(CanonicalValue.encode(atOnePlace), CanonicalValue.encode(rangeAt(1)));
		}

		@Test
		@DisplayName("the same two ranges as a compound element encode differently")
		void shouldTellApartCompoundsWhoseRangesPrintAlike() {
			final ComparableArray atOnePlace = new ComparableArray(new Serializable[]{rangeAt(1), 1});
			final ComparableArray atTwoPlaces = new ComparableArray(new Serializable[]{rangeAt(2), 1});

			assertEquals(atOnePlace.toString(), atTwoPlaces.toString(), "the premise: the compounds print alike");
			assertNotEquals(CanonicalValue.encode(atOnePlace), CanonicalValue.encode(atTwoPlaces));
		}

		@Test
		@DisplayName("ranges equal by their encoded bounds encode alike when indexed, whatever endpoints they keep")
		void shouldEncodeEqualRangesAlikeWhenIndexed() {
			final OffsetDateTime from = OffsetDateTime.of(2026, 10, 2, 14, 0, 0, 0, ZoneOffset.ofHours(2));
			final DateTimeRange inPrague = DateTimeRange.between(from, from.plusDays(1));
			final ZoneOffset tokyo = ZoneOffset.ofHours(9);
			final DateTimeRange inTokyo = DateTimeRange.between(
				from.withOffsetSameInstant(tokyo), from.plusDays(1).withOffsetSameInstant(tokyo)
			);
			assertEquals(inPrague, inTokyo, "the premise: the index finds the ranges equal");
			assertNotEquals(CanonicalValue.encode(inPrague), CanonicalValue.encode(inTokyo));
			assertEquals(CanonicalValue.encodeIndexed(inPrague), CanonicalValue.encodeIndexed(inTokyo));

			final BigDecimalNumberRange roundedDown = BigDecimalNumberRange.between(
				new BigDecimal("3.44"), new BigDecimal("4.5"), 1
			);
			final BigDecimalNumberRange roundedUp = BigDecimalNumberRange.between(
				new BigDecimal("3.36"), new BigDecimal("4.53"), 1
			);
			assertEquals(roundedDown, roundedUp, "the premise: the index finds the ranges equal");
			assertNotEquals(CanonicalValue.encode(roundedDown), CanonicalValue.encode(roundedUp));
			assertEquals(CanonicalValue.encodeIndexed(roundedDown), CanonicalValue.encodeIndexed(roundedUp));
			// a range keeping its natural scale is encoded at the scale it is compared at, explicit or not
			assertEquals(CanonicalValue.encodeIndexed(roundedDown), CanonicalValue.encodeIndexed(
				BigDecimalNumberRange.between(new BigDecimal("3.4"), new BigDecimal("4.5"))
			));
			// and so is a compound element
			assertEquals(
				CanonicalValue.encodeIndexed(new ComparableArray(new Serializable[]{roundedDown, inPrague})),
				CanonicalValue.encodeIndexed(new ComparableArray(new Serializable[]{roundedUp, inTokyo}))
			);
		}

		@Test
		@DisplayName("ranges at different scales encode differently when indexed, even when the index finds them equal")
		void shouldTellApartRangeScalesWhenIndexed() {
			assertNotEquals(CanonicalValue.encodeIndexed(rangeAt(1)), CanonicalValue.encodeIndexed(rangeAt(2)));
			// the same encoded bounds at another scale - a value the index cannot tell apart, but a different one
			final BigDecimalNumberRange tenTimesSmaller = BigDecimalNumberRange.between(
				new BigDecimal("0.34"), new BigDecimal("0.45"), 2
			);
			assertEquals(rangeAt(1), tenTimesSmaller, "the premise: the index finds the ranges equal");
			assertNotEquals(CanonicalValue.encodeIndexed(rangeAt(1)), CanonicalValue.encodeIndexed(tenTimesSmaller));
			assertNotEquals(
				CanonicalValue.encodeIndexed(new ComparableArray(new Serializable[]{rangeAt(1), 1})),
				CanonicalValue.encodeIndexed(new ComparableArray(new Serializable[]{rangeAt(2), 1}))
			);
		}

		@Test
		@DisplayName("decimals of one value and different scales encode differently")
		void shouldTellApartDecimalScales() {
			assertNotEquals(
				CanonicalValue.encode(new BigDecimal("1.0")), CanonicalValue.encode(new BigDecimal("1.00"))
			);
			assertEquals(
				CanonicalValue.encode(new BigDecimal("1.00")), CanonicalValue.encode(new BigDecimal("1.00"))
			);
		}

		@Test
		@DisplayName("one instant at two offsets encodes differently as a date time and alike as an instant")
		void shouldKeepTheOffsetOfADateTime() {
			final OffsetDateTime inPrague = OffsetDateTime.of(2026, 10, 2, 14, 0, 0, 0, ZoneOffset.ofHours(2));
			final OffsetDateTime inLondon = inPrague.withOffsetSameInstant(ZoneOffset.ofHours(1));

			assertNotEquals(CanonicalValue.encode(inPrague), CanonicalValue.encode(inLondon));
			assertEquals(CanonicalValue.encode(inPrague.toInstant()), CanonicalValue.encode(inLondon.toInstant()));
		}

		@Test
		@DisplayName("a value and its comparable wrapper, and two types of one number, encode differently")
		void shouldKeepTheClass() {
			assertNotEquals(
				CanonicalValue.encode(Locale.ENGLISH), CanonicalValue.encode(new ComparableLocale(Locale.ENGLISH))
			);
			assertNotEquals(
				CanonicalValue.encode(Currency.getInstance("EUR")),
				CanonicalValue.encode(new ComparableCurrency(Currency.getInstance("EUR")))
			);
			assertNotEquals(CanonicalValue.encode(5), CanonicalValue.encode(5L));
			assertNotEquals(CanonicalValue.encode("5"), CanonicalValue.encode(5));
		}

		@Test
		@DisplayName("a locale differing only in its variant encodes differently")
		void shouldKeepTheVariantOfALocale() {
			final Locale plain = new Locale.Builder().setLanguage("en").setRegion("US").build();
			final Locale withVariant = new Locale.Builder()
				.setLanguage("en")
				.setRegion("US")
				.setVariant("posix")
				.build();

			assertNotEquals(
				CanonicalValue.encode(new ComparableLocale(plain)),
				CanonicalValue.encode(new ComparableLocale(withVariant))
			);
		}

		@Test
		@DisplayName("a compound keeps the position of every element, nulls included")
		void shouldKeepElementPositions() {
			assertNotEquals(
				CanonicalValue.encode(new ComparableArray(new Serializable[]{null, "a"})),
				CanonicalValue.encode(new ComparableArray(new Serializable[]{"a", null}))
			);
			assertNotEquals(
				CanonicalValue.encode(new ComparableArray(new Serializable[]{"a"})),
				CanonicalValue.encode(new ComparableArray(new Serializable[]{"a", null}))
			);
		}

		@Test
		@DisplayName("a class the encoding does not know is refused, not rendered")
		void shouldRefuseAnUnknownClass() {
			assertThrows(IllegalArgumentException.class, () -> CanonicalValue.encode(1.5d));
			assertThrows(
				IllegalArgumentException.class,
				() -> CanonicalValue.encode(new ComparableArray(new Serializable[]{"a", 1.5f}))
			);
		}
	}

	@Nested
	@DisplayName("Sort index walk")
	class SortIndexWalkTest {

		@Test
		@DisplayName("one record whose range prints alike but keeps other decimal places is found")
		void shouldFindARangeThatPrintsAlike() {
			final SortIndex atOnePlace = new OwnerSortIndex(
				BigDecimalNumberRange.class,
				null,
				KEY,
				0,
				SortIndex.createTypeLevelNormalizerFor(BigDecimalNumberRange.class, 0)
			);
			atOnePlace.addRecord(rangeAt(1), 1);
			final SortIndex atTwoPlaces = new OwnerSortIndex(
				BigDecimalNumberRange.class,
				null,
				KEY,
				0,
				SortIndex.createTypeLevelNormalizerFor(BigDecimalNumberRange.class, 0)
			);
			atTwoPlaces.addRecord(rangeAt(2), 1);

			assertEquals(
				oldRendering(atOnePlace), oldRendering(atTwoPlaces), "the premise: the old rendering is blind"
			);
			assertNotEquals(
				CatalogDescriber.describeSortIndex(atOnePlace), CatalogDescriber.describeSortIndex(atTwoPlaces)
			);
		}

		@Test
		@DisplayName("one record whose compound holds such a range is found")
		void shouldFindACompoundRangeThatPrintsAlike() {
			final SortIndex atOnePlace = compoundOfRangeAndInteger();
			atOnePlace.addRecord(new Serializable[]{rangeAt(1), 1}, 1);
			final SortIndex atTwoPlaces = compoundOfRangeAndInteger();
			atTwoPlaces.addRecord(new Serializable[]{rangeAt(2), 1}, 1);

			assertEquals(
				oldRendering(atOnePlace), oldRendering(atTwoPlaces), "the premise: the old rendering is blind"
			);
			assertNotEquals(
				CatalogDescriber.describeSortIndex(atOnePlace), CatalogDescriber.describeSortIndex(atTwoPlaces)
			);
		}

		@Test
		@DisplayName("the same records over the same values split into blocks of 1 and 2 versus 2 and 1 are found")
		void shouldFindDifferentMultiplicities() {
			final SortIndex oneThenTwo = new OwnerSortIndex(
				Integer.class,
				null,
				KEY,
				0,
				SortIndex.createTypeLevelNormalizerFor(Integer.class, 0)
			);
			oneThenTwo.addRecord(10, 1);
			oneThenTwo.addRecord(20, 2);
			oneThenTwo.addRecord(20, 3);
			final SortIndex twoThenOne = new OwnerSortIndex(
				Integer.class,
				null,
				KEY,
				0,
				SortIndex.createTypeLevelNormalizerFor(Integer.class, 0)
			);
			twoThenOne.addRecord(10, 1);
			twoThenOne.addRecord(10, 2);
			twoThenOne.addRecord(20, 3);

			assertEquals(oldRendering(oneThenTwo), oldRendering(twoThenOne), "the premise: the old rendering is blind");
			assertNotEquals(
				CatalogDescriber.describeSortIndex(oneThenTwo), CatalogDescriber.describeSortIndex(twoThenOne)
			);
			assertEquals(
				List.of(new Block(10, 0, 1), new Block(20, 1, 2)), SortIndexWalk.of(oneThenTwo).blocks()
			);
			assertEquals(
				List.of(new Block(10, 0, 2), new Block(20, 2, 1)), SortIndexWalk.of(twoThenOne).blocks()
			);
		}

		@Test
		@DisplayName("locales, currencies and instants are walked in their stored, normalized form")
		void shouldWalkWrappedValues() {
			final SortIndex locales = new OwnerSortIndex(
				Locale.class,
				null,
				KEY,
				0,
				SortIndex.createTypeLevelNormalizerFor(Locale.class, 0)
			);
			locales.addRecord(Locale.GERMAN, 2);
			locales.addRecord(Locale.ENGLISH, 1);
			locales.addRecord(Locale.GERMAN, 3);
			final SortIndexWalk localeWalk = SortIndexWalk.of(locales);
			// ordered by language tag: `de` before `en`
			assertArrayEquals(new int[]{2, 3, 1}, localeWalk.records());
			assertEquals(
				List.of(
					new Block(new ComparableLocale(Locale.GERMAN), 0, 2),
					new Block(new ComparableLocale(Locale.ENGLISH), 2, 1)
				),
				localeWalk.blocks()
			);

			final SortIndex currencies = new OwnerSortIndex(
				Currency.class,
				null,
				KEY,
				0,
				SortIndex.createTypeLevelNormalizerFor(Currency.class, 0)
			);
			currencies.addRecord(Currency.getInstance("EUR"), 1);
			currencies.addRecord(Currency.getInstance("CZK"), 2);
			final SortIndexWalk currencyWalk = SortIndexWalk.of(currencies);
			assertArrayEquals(new int[]{2, 1}, currencyWalk.records());
			assertInstanceOf(ComparableCurrency.class, currencyWalk.values()[0]);

			final SortIndex instants = new OwnerSortIndex(
				OffsetDateTime.class,
				null,
				KEY,
				0,
				SortIndex.createTypeLevelNormalizerFor(OffsetDateTime.class, 0)
			);
			final OffsetDateTime moment = OffsetDateTime.of(2026, 10, 2, 14, 0, 0, 0, ZoneOffset.ofHours(2));
			instants.addRecord(moment, 2);
			instants.addRecord(moment.withOffsetSameInstant(ZoneOffset.UTC), 1);
			final SortIndexWalk instantWalk = SortIndexWalk.of(instants);
			assertEquals(List.of(new Block(moment.toInstant(), 0, 2)), instantWalk.blocks());
			assertInstanceOf(Instant.class, instantWalk.values()[1]);
		}

		@Test
		@DisplayName("a compound with descending, nulls-first, wrapped and missing elements is walked whole")
		void shouldWalkACompound() {
			final SortIndex compound = new OwnerSortIndex(new ComparatorSource[]{
					new ComparatorSource(String.class, OrderDirection.ASC, OrderBehaviour.NULLS_FIRST),
					new ComparatorSource(Integer.class, OrderDirection.DESC, OrderBehaviour.NULLS_LAST),
					new ComparatorSource(Currency.class, OrderDirection.ASC, OrderBehaviour.NULLS_LAST)
				}, null, KEY, SortIndex.createTypeLevelNormalizerFor(new ComparatorSource[]{
					new ComparatorSource(String.class, OrderDirection.ASC, OrderBehaviour.NULLS_FIRST),
					new ComparatorSource(Integer.class, OrderDirection.DESC, OrderBehaviour.NULLS_LAST),
					new ComparatorSource(Currency.class, OrderDirection.ASC, OrderBehaviour.NULLS_LAST)
				}, 0));
			compound.addRecord(new Serializable[]{"B", 1, Currency.getInstance("EUR")}, 1);
			compound.addRecord(new Serializable[]{null, 3, null}, 2);
			compound.addRecord(new Serializable[]{"B", 9, null}, 3);
			compound.addRecord(new Serializable[]{"B", 1, Currency.getInstance("EUR")}, 4);

			final SortIndexWalk walk = SortIndexWalk.of(compound);
			// nulls first, then the descending integer element orders 9 before 1
			assertArrayEquals(new int[]{2, 3, 1, 4}, walk.records());
			assertEquals(
				List.of(
					new Block(new ComparableArray(new Serializable[]{null, 3, null}), 0, 1),
					new Block(new ComparableArray(new Serializable[]{"B", 9, null}), 1, 1),
					new Block(
						new ComparableArray(
							new Serializable[]{"B", 1, new ComparableCurrency(Currency.getInstance("EUR"))}
						),
						2, 2
					)
				),
				walk.blocks()
			);
		}

		@Test
		@DisplayName("an empty index is walked into nothing")
		void shouldWalkAnEmptyIndex() {
			final SortIndexWalk walk = SortIndexWalk.of(new OwnerSortIndex(
				Integer.class,
				null,
				KEY,
				0,
				SortIndex.createTypeLevelNormalizerFor(Integer.class, 0)
			));

			assertEquals(0, walk.records().length);
			assertEquals(List.of(), walk.blocks());
		}

		/**
		 * Renders a sort index the way the index descriptions did before they walked sort indexes position by
		 * position: the records in order, then the distinct values by their `toString`.
		 *
		 * @param sortIndex the sort index
		 * @return the old rendering
		 */
		@Nonnull
		private static String oldRendering(@Nonnull SortIndex sortIndex) {
			return Arrays.toString(sortIndex.getSortedRecords()) + " by " +
				Arrays.stream(sortIndex.getSortedRecordValues()).map(String::valueOf).toList();
		}

		/**
		 * Creates an empty compound sort index of a range element and an integer element.
		 *
		 * @return the sort index
		 */
		@Nonnull
		private static SortIndex compoundOfRangeAndInteger() {
			return new OwnerSortIndex(new ComparatorSource[]{
					new ComparatorSource(BigDecimalNumberRange.class, OrderDirection.ASC, OrderBehaviour.NULLS_LAST),
					new ComparatorSource(Integer.class, OrderDirection.ASC, OrderBehaviour.NULLS_LAST)
				}, null, KEY, SortIndex.createTypeLevelNormalizerFor(new ComparatorSource[]{
					new ComparatorSource(BigDecimalNumberRange.class, OrderDirection.ASC, OrderBehaviour.NULLS_LAST),
					new ComparatorSource(Integer.class, OrderDirection.ASC, OrderBehaviour.NULLS_LAST)
				}, 0));
		}
	}

	@Nested
	@DisplayName("Chain predecessors")
	class ChainPredecessorTest {

		@Test
		@DisplayName("a predecessor that changes while the materialized order does not is found")
		void shouldFindAPredecessorTheOrderDoesNotShow() {
			final ChainIndex linked = new ChainIndex(KEY);
			linked.upsertPredecessor(Predecessor.HEAD, 1);
			linked.upsertPredecessor(new Predecessor(1), 2);
			linked.upsertPredecessor(new Predecessor(2), 3);
			final ChainIndex dangling = new ChainIndex(KEY);
			dangling.upsertPredecessor(Predecessor.HEAD, 1);
			dangling.upsertPredecessor(new Predecessor(1), 2);
			dangling.upsertPredecessor(new Predecessor(99), 3);

			assertArrayEquals(
				linked.getUnorderedLookup().getArray(), dangling.getUnorderedLookup().getArray(),
				"the premise: the materialized order is the same"
			);
			assertNotEquals(
				CatalogDescriber.describeChainPredecessors(linked),
				CatalogDescriber.describeChainPredecessors(dangling)
			);
		}
	}
}
