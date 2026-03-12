/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.spi.store.catalog.persistence.storageParts.entity;

import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.data.structure.Price;
import io.evitadb.api.requestResponse.data.structure.Price.PriceKey;
import io.evitadb.spi.store.catalog.shared.model.PriceWithInternalIds;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link PricesStoragePart} verifying construction validation, emptiness detection,
 * price inner record handling, price insertion/replacement, lookup, internal ID lookup, and versioning.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("PricesStoragePart behavioral tests")
class PricesStoragePartTest {

	private static final int ENTITY_PK = 1;
	private static final Currency USD = Currency.getInstance("USD");
	private static final Currency EUR = Currency.getInstance("EUR");

	/**
	 * Creates a {@link Price} with the given price ID, price list, and currency.
	 * Uses default values for amounts and no validity.
	 *
	 * @param priceId   the price identifier
	 * @param priceList the price list name
	 * @param currency  the currency
	 * @return new price instance
	 */
	@Nonnull
	private static Price createPrice(int priceId, @Nonnull String priceList, @Nonnull Currency currency) {
		return new Price(
			new PriceKey(priceId, priceList, currency),
			null,
			new BigDecimal("100.00"),
			new BigDecimal("21.00"),
			new BigDecimal("121.00"),
			null,
			true
		);
	}

	/**
	 * Creates a {@link Price} with specified amounts, allowing differentiation for replacement tests.
	 *
	 * @param priceId         the price identifier
	 * @param priceList       the price list name
	 * @param currency        the currency
	 * @param priceWithoutTax the price amount without tax
	 * @return new price instance
	 */
	@Nonnull
	private static Price createPriceWithAmount(
		int priceId,
		@Nonnull String priceList,
		@Nonnull Currency currency,
		@Nonnull BigDecimal priceWithoutTax
	) {
		return new Price(
			new PriceKey(priceId, priceList, currency),
			null,
			priceWithoutTax,
			new BigDecimal("21.00"),
			priceWithoutTax.multiply(new BigDecimal("1.21")),
			null,
			true
		);
	}

	/**
	 * Wraps a {@link Price} into a {@link PriceWithInternalIds} with the given internal price ID.
	 *
	 * @param price           the price to wrap
	 * @param internalPriceId the internal price ID
	 * @return wrapped price
	 */
	@Nonnull
	private static PriceWithInternalIds wrapPrice(@Nonnull Price price, int internalPriceId) {
		return new PriceWithInternalIds(price, internalPriceId);
	}

	@Nested
	@DisplayName("Construction validation")
	class ConstructionValidation {

		@Test
		@DisplayName("should reject UNKNOWN price inner record handling")
		void shouldRejectUnknownPriceInnerRecordHandling() {
			assertThrows(
				Exception.class,
				() -> new PricesStoragePart(
					ENTITY_PK, 1, PriceInnerRecordHandling.UNKNOWN,
					new PriceWithInternalIds[0], 128
				)
			);
		}

		@Test
		@DisplayName("should accept valid handling modes")
		void shouldAcceptValidHandlingModes() {
			final PricesStoragePart partNone = new PricesStoragePart(
				ENTITY_PK, 1, PriceInnerRecordHandling.NONE,
				new PriceWithInternalIds[0], 128
			);
			assertEquals(PriceInnerRecordHandling.NONE, partNone.getPriceInnerRecordHandling());

			final PricesStoragePart partLowestPrice = new PricesStoragePart(
				ENTITY_PK, 1, PriceInnerRecordHandling.LOWEST_PRICE,
				new PriceWithInternalIds[0], 128
			);
			assertEquals(PriceInnerRecordHandling.LOWEST_PRICE, partLowestPrice.getPriceInnerRecordHandling());

			final PricesStoragePart partSum = new PricesStoragePart(
				ENTITY_PK, 1, PriceInnerRecordHandling.SUM,
				new PriceWithInternalIds[0], 128
			);
			assertEquals(PriceInnerRecordHandling.SUM, partSum.getPriceInnerRecordHandling());
		}
	}

	@Nested
	@DisplayName("Emptiness detection")
	class IsEmpty {

		@Test
		@DisplayName("should be empty when no prices and handling is NONE")
		void shouldBeEmptyWhenNoPricesAndHandlingIsNone() {
			final PricesStoragePart part = new PricesStoragePart(ENTITY_PK);

			assertTrue(part.isEmpty());
		}

		@Test
		@DisplayName("should not be empty when handling is not NONE")
		void shouldNotBeEmptyWhenHandlingIsNotNone() {
			final PricesStoragePart part = new PricesStoragePart(
				ENTITY_PK, 1, PriceInnerRecordHandling.LOWEST_PRICE,
				new PriceWithInternalIds[0], 128
			);

			assertFalse(part.isEmpty());
		}

		@Test
		@DisplayName("should not be empty when non-dropped price exists")
		void shouldNotBeEmptyWhenNonDroppedPriceExists() {
			final Price price = createPrice(1, "basic", USD);
			final PriceWithInternalIds wrapped = wrapPrice(price, 100);
			final PricesStoragePart part = new PricesStoragePart(
				ENTITY_PK, 1, PriceInnerRecordHandling.NONE,
				new PriceWithInternalIds[]{wrapped}, 128
			);

			assertFalse(part.isEmpty());
		}

		@Test
		@DisplayName("should be empty when all prices are dropped")
		void shouldBeEmptyWhenAllPricesAreDropped() {
			final Price droppedPrice = new Price(
				1, new PriceKey(1, "basic", USD), null,
				new BigDecimal("100.00"), new BigDecimal("21.00"), new BigDecimal("121.00"),
				null, true, true
			);
			final PriceWithInternalIds wrapped = wrapPrice(droppedPrice, 100);
			final PricesStoragePart part = new PricesStoragePart(
				ENTITY_PK, 1, PriceInnerRecordHandling.NONE,
				new PriceWithInternalIds[]{wrapped}, 128
			);

			assertTrue(part.isEmpty());
		}
	}

	@Nested
	@DisplayName("Price inner record handling changes")
	class SetPriceInnerRecordHandling {

		@Test
		@DisplayName("should mark dirty when changing handling")
		void shouldMarkDirtyWhenChangingHandling() {
			final PricesStoragePart part = new PricesStoragePart(ENTITY_PK);
			assertFalse(part.isDirty());

			part.setPriceInnerRecordHandling(PriceInnerRecordHandling.LOWEST_PRICE);

			assertTrue(part.isDirty());
			assertEquals(PriceInnerRecordHandling.LOWEST_PRICE, part.getPriceInnerRecordHandling());
		}

		@Test
		@DisplayName("should not mark dirty when setting same handling")
		void shouldNotMarkDirtyWhenSettingSameHandling() {
			final PricesStoragePart part = new PricesStoragePart(ENTITY_PK);

			part.setPriceInnerRecordHandling(PriceInnerRecordHandling.NONE);

			assertFalse(part.isDirty());
		}
	}

	@Nested
	@DisplayName("Price insertion and replacement")
	class ReplaceOrAddPrice {

		@Test
		@DisplayName("should insert new price and mark dirty")
		void shouldInsertNewPriceAndMarkDirty() {
			final PricesStoragePart part = new PricesStoragePart(ENTITY_PK);
			final PriceKey priceKey = new PriceKey(1, "basic", USD);
			final Price price = createPrice(1, "basic", USD);

			part.replaceOrAddPrice(
				priceKey,
				existing -> price,
				pk -> 100
			);

			assertTrue(part.isDirty());
			assertEquals(1, part.getPrices().length);
			assertEquals(100, part.getPrices()[0].getInternalPriceId());
		}

		@Test
		@DisplayName("should insert multiple prices in sorted order")
		void shouldInsertMultiplePricesInSortedOrder() {
			final PricesStoragePart part = new PricesStoragePart(ENTITY_PK);
			// Insert price with id 3 first, then id 1 -- they should end up sorted by PriceIdFirst
			final PriceKey key3 = new PriceKey(3, "basic", USD);
			final PriceKey key1 = new PriceKey(1, "basic", USD);

			part.replaceOrAddPrice(key3, existing -> createPrice(3, "basic", USD), pk -> 103);
			part.replaceOrAddPrice(key1, existing -> createPrice(1, "basic", USD), pk -> 101);

			assertEquals(2, part.getPrices().length);
			// sorted by priceId first: 1, 3
			assertEquals(1, part.getPrices()[0].priceId());
			assertEquals(3, part.getPrices()[1].priceId());
		}

		@Test
		@DisplayName("should replace existing price when content differs")
		void shouldReplaceExistingPriceWhenContentDiffers() {
			final PricesStoragePart part = new PricesStoragePart(ENTITY_PK);
			final PriceKey priceKey = new PriceKey(1, "basic", USD);

			part.replaceOrAddPrice(
				priceKey,
				existing -> createPriceWithAmount(1, "basic", USD, new BigDecimal("100.00")),
				pk -> 100
			);
			// reset dirty flag
			assertNotNull(part.getPrices());

			// replace with different amount
			final PricesStoragePart freshPart = new PricesStoragePart(
				ENTITY_PK, 1, PriceInnerRecordHandling.NONE,
				part.getPrices(), 128
			);
			freshPart.replaceOrAddPrice(
				priceKey,
				existing -> createPriceWithAmount(1, "basic", USD, new BigDecimal("200.00")),
				pk -> 999
			);

			assertTrue(freshPart.isDirty());
			assertEquals(1, freshPart.getPrices().length);
		}

		@Test
		@DisplayName("should not mark dirty when replacing with identical price")
		void shouldNotMarkDirtyWhenReplacingWithIdenticalPrice() {
			final Price price = createPrice(1, "basic", USD);
			final PriceWithInternalIds wrapped = wrapPrice(price, 100);
			final PricesStoragePart part = new PricesStoragePart(
				ENTITY_PK, 1, PriceInnerRecordHandling.NONE,
				new PriceWithInternalIds[]{wrapped}, 128
			);
			assertFalse(part.isDirty());

			// replace with same price content
			part.replaceOrAddPrice(
				price.priceKey(),
				existing -> createPrice(1, "basic", USD),
				pk -> 999
			);

			assertFalse(part.isDirty());
		}

		@Test
		@DisplayName("should use existing internal price ID when replacing a known price")
		void shouldUseExistingInternalPriceIdWhenReplacingKnownPrice() {
			final Price originalPrice = createPrice(1, "basic", USD);
			final PriceWithInternalIds wrapped = wrapPrice(originalPrice, 42);
			final PricesStoragePart part = new PricesStoragePart(
				ENTITY_PK, 1, PriceInnerRecordHandling.NONE,
				new PriceWithInternalIds[]{wrapped}, 128
			);

			// replace with different amount but the existing internal id should be preserved
			part.replaceOrAddPrice(
				originalPrice.priceKey(),
				existing -> createPriceWithAmount(1, "basic", USD, new BigDecimal("999.00")),
				pk -> 777
			);

			assertEquals(42, part.getPrices()[0].getInternalPriceId());
		}

		@Test
		@DisplayName("should call resolver for new internal price ID when existing ID is -1")
		void shouldCallResolverForNewInternalPriceIdWhenExistingIdIsMinusOne() {
			final Price originalPrice = createPrice(1, "basic", USD);
			// -1 indicates an old price that was not indexed and lacks an internal id
			final PriceWithInternalIds wrapped = wrapPrice(originalPrice, -1);
			final PricesStoragePart part = new PricesStoragePart(
				ENTITY_PK, 1, PriceInnerRecordHandling.NONE,
				new PriceWithInternalIds[]{wrapped}, 128
			);

			// replace with a different price -- should trigger resolver because existing id is -1
			part.replaceOrAddPrice(
				originalPrice.priceKey(),
				existing -> createPriceWithAmount(1, "basic", USD, new BigDecimal("500.00")),
				pk -> 55
			);

			assertEquals(55, part.getPrices()[0].getInternalPriceId());
		}
	}

	@Nested
	@DisplayName("Price lookup")
	class GetPriceByKey {

		@Test
		@DisplayName("should return price when key exists")
		void shouldReturnPriceWhenKeyExists() {
			final Price price = createPrice(1, "basic", USD);
			final PriceWithInternalIds wrapped = wrapPrice(price, 100);
			final PricesStoragePart part = new PricesStoragePart(
				ENTITY_PK, 1, PriceInnerRecordHandling.NONE,
				new PriceWithInternalIds[]{wrapped}, 128
			);

			final PriceWithInternalIds found = part.getPriceByKey(new PriceKey(1, "basic", USD));

			assertNotNull(found);
			assertEquals(100, found.getInternalPriceId());
			assertEquals(1, found.priceId());
		}

		@Test
		@DisplayName("should return null when key does not exist")
		void shouldReturnNullWhenKeyDoesNotExist() {
			final PricesStoragePart part = new PricesStoragePart(ENTITY_PK);

			final PriceWithInternalIds found = part.getPriceByKey(new PriceKey(999, "nonexistent", EUR));

			assertNull(found);
		}
	}

	@Nested
	@DisplayName("Internal ID lookup")
	class FindExistingInternalIds {

		@Test
		@DisplayName("should return internal ID when price key matches")
		void shouldReturnInternalIdWhenPriceKeyMatches() {
			final Price price = createPrice(1, "basic", USD);
			final PriceWithInternalIds wrapped = wrapPrice(price, 42);
			final PricesStoragePart part = new PricesStoragePart(
				ENTITY_PK, 1, PriceInnerRecordHandling.NONE,
				new PriceWithInternalIds[]{wrapped}, 128
			);

			final OptionalInt result = part.findExistingInternalIds(new PriceKey(1, "basic", USD));

			assertTrue(result.isPresent());
			assertEquals(42, result.getAsInt());
		}

		@Test
		@DisplayName("should return empty when price key not found")
		void shouldReturnEmptyWhenPriceKeyNotFound() {
			final PricesStoragePart part = new PricesStoragePart(ENTITY_PK);

			final OptionalInt result = part.findExistingInternalIds(new PriceKey(1, "basic", USD));

			assertFalse(result.isPresent());
		}
	}

	@Nested
	@DisplayName("Version tracking")
	class Versioning {

		@Test
		@DisplayName("should return incremented version when dirty")
		void shouldReturnIncrementedVersionWhenDirty() {
			final PricesStoragePart part = new PricesStoragePart(
				ENTITY_PK, 3, PriceInnerRecordHandling.NONE,
				new PriceWithInternalIds[0], 128
			);

			part.setPriceInnerRecordHandling(PriceInnerRecordHandling.SUM);
			assertTrue(part.isDirty());

			assertEquals(4, part.getVersion());
		}

		@Test
		@DisplayName("should return original version when clean")
		void shouldReturnOriginalVersionWhenClean() {
			final PricesStoragePart part = new PricesStoragePart(
				ENTITY_PK, 3, PriceInnerRecordHandling.NONE,
				new PriceWithInternalIds[0], 128
			);

			assertFalse(part.isDirty());
			assertEquals(3, part.getVersion());
		}
	}

}
