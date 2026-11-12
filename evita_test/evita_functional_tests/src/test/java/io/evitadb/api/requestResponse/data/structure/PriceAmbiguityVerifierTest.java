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

package io.evitadb.api.requestResponse.data.structure;

import io.evitadb.api.exception.AmbiguousPriceException;
import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.data.structure.Price.PriceKey;
import io.evitadb.dataType.DateTimeRange;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Currency;
import java.util.List;
import java.util.Random;

import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.PRICE;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link PriceAmbiguityVerifier}, the check of the rule that an entity has at most one valid price at any
 * moment per group of prices competing for its price for sale, which both entity builders and the server apply to the
 * end state of an entity: that it decides every price set as the pairwise rule does, and that its cost stays far below
 * comparing every pair when thousands of prices share one group.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Verification that the prices of an entity are not ambiguous")
@Tag(CONTRACT)
@Tag(PRICE)
class PriceAmbiguityVerifierTest {
	private static final Currency CZK = Currency.getInstance("CZK");
	private static final String BASIC = "basic";
	private static final String VIP = "vip";
	private static final OffsetDateTime EPOCH = OffsetDateTime.of(2020, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);

	/**
	 * Creates an indexed price in CZK selling for one.
	 *
	 * @param priceId       id of the price
	 * @param priceList     price list of the price
	 * @param innerRecordId inner record of the price, `null` for none
	 * @param validity      validity of the price, `null` for a price valid at every moment
	 * @return the price
	 */
	@Nonnull
	private static Price price(
		int priceId,
		@Nonnull String priceList,
		@Nullable Integer innerRecordId,
		@Nullable DateTimeRange validity
	) {
		return new Price(
			new PriceKey(priceId, priceList, CZK), innerRecordId,
			BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, validity, true
		);
	}

	/**
	 * Returns the validity spanning the passed hours after {@link #EPOCH}, both bounds included.
	 *
	 * @param fromHour first hour of the validity
	 * @param toHour   last hour of the validity
	 * @return the validity
	 */
	@Nonnull
	private static DateTimeRange hours(int fromHour, int toHour) {
		return DateTimeRange.between(EPOCH.plusHours(fromHour), EPOCH.plusHours(toHour));
	}

	/**
	 * Tells whether the pairwise rule finds an ambiguous pair: two existing prices of one group, at least one of them
	 * touched, valid at a common moment. This is the definition the verifier must agree with.
	 *
	 * @param prices   all prices of the entity
	 * @param touched  the touched prices
	 * @param handling inner record handling of the entity
	 * @return `true` when a pair is ambiguous
	 */
	private static boolean pairwiseRuleFindsAmbiguousPair(
		@Nonnull List<PriceContract> prices,
		@Nonnull List<PriceContract> touched,
		@Nonnull PriceInnerRecordHandling handling
	) {
		for (final PriceContract first : prices) {
			for (final PriceContract second : prices) {
				if (first != second && first.exists() && second.exists() &&
					(touched.contains(first) || touched.contains(second)) &&
					first.priceList().equals(second.priceList()) &&
					first.currency().equals(second.currency()) &&
					(handling == PriceInnerRecordHandling.NONE ||
						innerRecordKey(first) == innerRecordKey(second)) &&
					(first.validity() == null || second.validity() == null ||
						first.validity().overlaps(second.validity()))) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Returns the inner record id of the price, `0` for a price without one.
	 *
	 * @param price the price
	 * @return the inner record id or `0`
	 */
	private static int innerRecordKey(@Nonnull PriceContract price) {
		final Integer innerRecordId = price.innerRecordId();
		return innerRecordId == null ? 0 : innerRecordId;
	}

	/**
	 * Runs the verifier and tells whether it found an ambiguous pair.
	 *
	 * @param prices   all prices of the entity
	 * @param touched  the touched prices
	 * @param handling inner record handling of the entity
	 * @return `true` when the verifier threw {@link AmbiguousPriceException}
	 */
	private static boolean verifierFindsAmbiguousPair(
		@Nonnull List<PriceContract> prices,
		@Nonnull List<PriceContract> touched,
		@Nonnull PriceInnerRecordHandling handling
	) {
		final PriceContract[] touchedArray = touched.toArray(new PriceContract[0]);
		try {
			PriceAmbiguityVerifier.verifyPricesNotAmbiguous(
				prices.toArray(new PriceContract[0]), prices.size(), touchedArray, touchedArray.length, handling
			);
			return false;
		} catch (AmbiguousPriceException ex) {
			return true;
		}
	}

	@Nested
	@DisplayName("Decisions")
	class DecisionsTest {

		@Test
		@DisplayName("should reject a touched price valid at the last moment of another price of its group")
		void shouldRejectTouchedPriceValidAtLastMomentOfAnotherPriceOfItsGroup() {
			final Price stored = price(1, BASIC, 1, hours(0, 10));
			final Price touched = price(2, BASIC, 1, hours(10, 20));

			final AmbiguousPriceException exception = assertThrows(
				AmbiguousPriceException.class,
				() -> PriceAmbiguityVerifier.verifyPricesNotAmbiguous(
					new PriceContract[]{stored, touched}, 2, new PriceContract[]{touched}, 1,
					PriceInnerRecordHandling.LOWEST_PRICE
				)
			);
			assertSame(stored, exception.getExistingPrice());
			assertSame(touched, exception.getAmbiguousPrice());
		}

		@Test
		@DisplayName("should keep an untouched ambiguous pair legal next to a touched price valid at other moments")
		void shouldKeepUntouchedAmbiguousPairLegalNextToTouchedPriceValidAtOtherMoments() {
			final Price storedFirst = price(1, BASIC, 1, null);
			final Price storedSecond = price(2, BASIC, 1, hours(0, 10));
			final Price touched = price(3, BASIC, 2, hours(0, 10));

			assertDoesNotThrow(
				() -> PriceAmbiguityVerifier.verifyPricesNotAmbiguous(
					new PriceContract[]{storedFirst, storedSecond, touched}, 3, new PriceContract[]{touched}, 1,
					PriceInnerRecordHandling.LOWEST_PRICE
				)
			);
		}

		@Test
		@DisplayName("should accept a touched price named twice among the touched prices")
		void shouldAcceptTouchedPriceNamedTwiceAmongTouchedPrices() {
			final Price stored = price(1, BASIC, 1, hours(0, 10));
			final Price touched = price(2, BASIC, 1, hours(11, 20));

			assertDoesNotThrow(
				() -> PriceAmbiguityVerifier.verifyPricesNotAmbiguous(
					new PriceContract[]{stored, touched}, 2, new PriceContract[]{touched, touched}, 2,
					PriceInnerRecordHandling.LOWEST_PRICE
				)
			);
		}

		@Test
		@DisplayName("should group prices of different inner records only without inner record handling")
		void shouldGroupPricesOfDifferentInnerRecordsOnlyWithoutInnerRecordHandling() {
			final Price stored = price(1, BASIC, 1, null);
			final Price touched = price(2, BASIC, 2, hours(0, 10));

			assertThrows(
				AmbiguousPriceException.class,
				() -> PriceAmbiguityVerifier.verifyPricesNotAmbiguous(
					new PriceContract[]{stored, touched}, 2, new PriceContract[]{touched}, 1,
					PriceInnerRecordHandling.NONE
				)
			);
			assertDoesNotThrow(
				() -> PriceAmbiguityVerifier.verifyPricesNotAmbiguous(
					new PriceContract[]{stored, touched}, 2, new PriceContract[]{touched}, 1,
					PriceInnerRecordHandling.SUM
				)
			);
		}

		/**
		 * Small random price sets - two price lists, prices without inner record and of two inner records, validities
		 * without bounds, open on one side or closed, many of them sharing a bound, some prices removed and an
		 * arbitrary subset touched, some touched prices named twice - must be decided exactly as the pairwise rule
		 * decides them.
		 */
		@Test
		@DisplayName("should decide every price set as the pairwise rule does")
		void shouldDecideEveryPriceSetAsPairwiseRuleDoes() {
			final Random random = new Random(42);
			final String[] priceLists = {BASIC, VIP};
			final Integer[] innerRecordIds = {null, 1, 2};
			final PriceInnerRecordHandling[] handlings = {
				PriceInnerRecordHandling.NONE, PriceInnerRecordHandling.LOWEST_PRICE, PriceInnerRecordHandling.SUM
			};
			int ambiguousSets = 0;
			for (int set = 0; set < 20_000; set++) {
				final int priceCount = 1 + random.nextInt(7);
				final List<PriceContract> prices = new ArrayList<>(priceCount);
				final List<PriceContract> touched = new ArrayList<>(priceCount + 2);
				for (int priceId = 1; priceId <= priceCount; priceId++) {
					final DateTimeRange validity;
					final int from = random.nextInt(6);
					final int to = from + random.nextInt(4);
					switch (random.nextInt(5)) {
						case 0 -> validity = null;
						case 1 -> validity = DateTimeRange.since(EPOCH.plusHours(from));
						case 2 -> validity = DateTimeRange.until(EPOCH.plusHours(to));
						default -> validity = hours(from, to);
					}
					final Price price = new Price(
						1, new PriceKey(priceId, priceLists[random.nextInt(priceLists.length)], CZK),
						innerRecordIds[random.nextInt(innerRecordIds.length)],
						BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, validity, true, random.nextInt(8) == 0
					);
					prices.add(price);
					if (price.exists() && random.nextBoolean()) {
						touched.add(price);
						if (random.nextInt(4) == 0) {
							touched.add(price);
						}
					}
				}
				Collections.shuffle(touched, random);
				final PriceInnerRecordHandling handling = handlings[random.nextInt(handlings.length)];
				final boolean expected = pairwiseRuleFindsAmbiguousPair(prices, touched, handling);
				if (expected) {
					ambiguousSets++;
				}
				assertEquals(
					expected,
					verifierFindsAmbiguousPair(prices, touched, handling),
					"Prices " + prices + ", touched " + touched + ", handling " + handling
				);
			}
			// both verdicts are frequent, so the agreement is not a vacuous one
			assertTrue(ambiguousSets > 2_000 && ambiguousSets < 18_000, "Ambiguous sets: " + ambiguousSets);
		}

	}

	/**
	 * The verifier reads the validity of a price for every comparison it makes, so counting the reads counts its work
	 * without measuring time. Thousands of prices of one price list, currency and inner record that are valid at
	 * pairwise different moments are legal, and comparing every touched price with every other price of its group
	 * would read the validities millions of times.
	 */
	@Nested
	@DisplayName("Cost")
	class CostTest {
		private static final int PRICE_COUNT = 5_000;
		/**
		 * Generous bound of validity reads per price: the work of sorting the touched prices and of one binary search
		 * per price is a few dozen reads per price for {@link #PRICE_COUNT} prices, comparing every pair is thousands.
		 */
		private static final int READS_PER_PRICE_BOUND = 100;

		/**
		 * Creates {@link #PRICE_COUNT} prices of one group whose validities follow one another without a common
		 * moment, in a shuffled order.
		 *
		 * @param validityReads counter of the validity reads of the created prices
		 * @return the prices
		 */
		@Nonnull
		private static List<PriceContract> disjointPricesOfOneGroup(@Nonnull long[] validityReads) {
			final List<PriceContract> prices = new ArrayList<>(PRICE_COUNT);
			for (int i = 0; i < PRICE_COUNT; i++) {
				prices.add(new CountingPrice(price(i + 1, BASIC, 1, hours(2 * i, 2 * i + 1)), validityReads));
			}
			Collections.shuffle(prices, new Random(42));
			return prices;
		}

		@Test
		@DisplayName("should read each validity a bounded number of times when all prices of one group are touched")
		void shouldReadEachValidityBoundedNumberOfTimesWhenAllPricesOfOneGroupAreTouched() {
			final long[] validityReads = new long[1];
			final List<PriceContract> prices = disjointPricesOfOneGroup(validityReads);
			final PriceContract[] touched = prices.toArray(new PriceContract[0]);

			assertDoesNotThrow(
				() -> PriceAmbiguityVerifier.verifyPricesNotAmbiguous(
					prices.toArray(new PriceContract[0]), prices.size(), touched, touched.length,
					PriceInnerRecordHandling.LOWEST_PRICE
				)
			);
			assertTrue(
				validityReads[0] < (long) READS_PER_PRICE_BOUND * PRICE_COUNT,
				"Validity read " + validityReads[0] + " times for " + PRICE_COUNT + " prices."
			);
		}

		@Test
		@DisplayName("should read each validity a bounded number of times when half the prices of a group are touched")
		void shouldReadEachValidityBoundedNumberOfTimesWhenEveryOtherPriceOfOneGroupIsTouched() {
			final long[] validityReads = new long[1];
			final List<PriceContract> prices = disjointPricesOfOneGroup(validityReads);
			final PriceContract[] touched = new PriceContract[PRICE_COUNT / 2];
			for (int i = 0; i < touched.length; i++) {
				touched[i] = prices.get(2 * i);
			}

			assertDoesNotThrow(
				() -> PriceAmbiguityVerifier.verifyPricesNotAmbiguous(
					prices.toArray(new PriceContract[0]), prices.size(), touched, touched.length,
					PriceInnerRecordHandling.LOWEST_PRICE
				)
			);
			assertTrue(
				validityReads[0] < (long) READS_PER_PRICE_BOUND * PRICE_COUNT,
				"Validity read " + validityReads[0] + " times for " + PRICE_COUNT + " prices."
			);
		}

		@Test
		@DisplayName("should reject the one price of thousands valid at a moment of another price of its group")
		void shouldRejectOnePriceOfThousandsValidAtMomentOfAnotherPriceOfItsGroup() {
			final long[] validityReads = new long[1];
			final List<PriceContract> prices = disjointPricesOfOneGroup(validityReads);
			final Price overlapping = price(PRICE_COUNT + 1, BASIC, 1, hours(2 * PRICE_COUNT - 1, 2 * PRICE_COUNT));
			prices.add(overlapping);

			final AmbiguousPriceException exception = assertThrows(
				AmbiguousPriceException.class,
				() -> PriceAmbiguityVerifier.verifyPricesNotAmbiguous(
					prices.toArray(new PriceContract[0]), prices.size(), new PriceContract[]{overlapping}, 1,
					PriceInnerRecordHandling.LOWEST_PRICE
				)
			);
			assertEquals(PRICE_COUNT, exception.getExistingPrice().priceId());
		}

	}

	/**
	 * Price that counts the reads of its validity and delegates everything else to a real price.
	 *
	 * @param delegate      the real price
	 * @param validityReads single-slot counter of the validity reads
	 */
	private record CountingPrice(
		@Nonnull PriceContract delegate,
		@Nonnull long[] validityReads
	) implements PriceContract {

		@Nullable
		@Override
		public DateTimeRange validity() {
			this.validityReads[0]++;
			return this.delegate.validity();
		}

		@Nonnull
		@Override
		public PriceKey priceKey() {
			return this.delegate.priceKey();
		}

		@Override
		public int priceId() {
			return this.delegate.priceId();
		}

		@Nonnull
		@Override
		public String priceList() {
			return this.delegate.priceList();
		}

		@Nonnull
		@Override
		public Currency currency() {
			return this.delegate.currency();
		}

		@Nullable
		@Override
		public Integer innerRecordId() {
			return this.delegate.innerRecordId();
		}

		@Override
		public boolean relatesTo(@Nonnull PriceContract anotherPrice) {
			return this.delegate.relatesTo(anotherPrice);
		}

		@Nonnull
		@Override
		public BigDecimal priceWithoutTax() {
			return this.delegate.priceWithoutTax();
		}

		@Nonnull
		@Override
		public BigDecimal taxRate() {
			return this.delegate.taxRate();
		}

		@Nonnull
		@Override
		public BigDecimal priceWithTax() {
			return this.delegate.priceWithTax();
		}

		@Override
		public boolean indexed() {
			return this.delegate.indexed();
		}

		@Override
		public int estimateSize() {
			return this.delegate.estimateSize();
		}

		@Override
		public boolean dropped() {
			return this.delegate.dropped();
		}

		@Override
		public int version() {
			return this.delegate.version();
		}

	}

}
