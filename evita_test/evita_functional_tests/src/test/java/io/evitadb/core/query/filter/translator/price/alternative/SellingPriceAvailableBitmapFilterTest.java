/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

package io.evitadb.core.query.filter.translator.price.alternative;

import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.require.QueryPriceMode;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.InitialEntityBuilder;
import io.evitadb.api.requestResponse.schema.CatalogEvolutionMode;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchemaProvider;
import io.evitadb.core.query.algebra.identity.FormulaKey;
import io.evitadb.core.query.algebra.price.filteredPriceRecords.ResolvedFilteredPriceRecords;
import io.evitadb.core.query.algebra.price.predicate.PricePredicate;
import io.evitadb.core.query.algebra.price.predicate.PricePredicate.PriceContractPredicate;
import io.evitadb.core.query.filter.translator.TestQueryExecutionContext;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.price.model.priceRecord.PriceRecordContract;
import io.evitadb.test.Entities;
import io.evitadb.test.TestConstants;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.Collections;
import java.util.Currency;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;

import static io.evitadb.api.query.QueryConstraints.*;
import static java.util.Optional.of;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.FILTER;
import static io.evitadb.test.TestTags.PRICE;

/**
 * This test verifies behaviour of {@link SellingPriceAvailableBitmapFilter}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@Tag(ENGINE)
@Tag(FILTER)
@Tag(PRICE)
class SellingPriceAvailableBitmapFilterTest {
	private static final EntitySchema PRODUCT_SCHEMA = EntitySchema._internalBuild(Entities.PRODUCT);
	private static final CatalogSchema CATALOG_SCHEMA = CatalogSchema._internalBuild(
		TestConstants.TEST_CATALOG, Collections.emptyMap(), null, EnumSet.allOf(CatalogEvolutionMode.class),
		new EntitySchemaProvider() {
			@Nonnull
			@Override
			public Collection<EntitySchemaContract> getEntitySchemas() {
				return List.of(PRODUCT_SCHEMA);
			}

			@Nonnull
			@Override
			public Optional<EntitySchemaContract> getEntitySchema(@Nonnull String entityType) {
				return of(PRODUCT_SCHEMA);
			}
		}
	);
	private static final String PRICE_LIST_BASIC = "basic";
	private static final String PRICE_LIST_VIP = "vip";
	private static final String PRICE_LIST_REFERENCE = "reference";
	private static final Currency CZK = Currency.getInstance("CZK");
	private static final Currency EUR = Currency.getInstance("EUR");
	private static final DateTimeRange FAR_FUTURE = DateTimeRange.since(
		OffsetDateTime.now().plusYears(100)
	);
	private Map<Integer, SealedEntity> entities;

	@BeforeEach
	void setUp() {
		this.entities = Stream.of(
				new InitialEntityBuilder(Entities.PRODUCT, 1)
					.setPrice(1, PRICE_LIST_REFERENCE, CZK, new BigDecimal("80"), new BigDecimal("21"), new BigDecimal("101"), false)
					.setPrice(2, PRICE_LIST_BASIC, CZK, new BigDecimal("100"), new BigDecimal("21"), new BigDecimal("121"), true)
					.toInstance(),
				new InitialEntityBuilder(Entities.PRODUCT, 2)
					.setPrice(10, PRICE_LIST_REFERENCE, EUR, new BigDecimal("80"), new BigDecimal("21"), new BigDecimal("101"), false)
					.setPrice(11, PRICE_LIST_BASIC, EUR, new BigDecimal("200"), new BigDecimal("21"), new BigDecimal("241"), true)
					.toInstance(),
				new InitialEntityBuilder(Entities.PRODUCT, 3)
					.setPrice(20, PRICE_LIST_REFERENCE, CZK, new BigDecimal("80"), new BigDecimal("21"), new BigDecimal("101"), false)
					.setPrice(21, PRICE_LIST_VIP, CZK, new BigDecimal("100"), new BigDecimal("21"), new BigDecimal("121"), true)
					.toInstance(),
				new InitialEntityBuilder(Entities.PRODUCT, 4)
					.setPrice(30, PRICE_LIST_REFERENCE, CZK, new BigDecimal("80"), new BigDecimal("21"), new BigDecimal("101"), false)
					.setPrice(31, PRICE_LIST_BASIC, CZK, new BigDecimal("300"), new BigDecimal("21"), new BigDecimal("361"), true)
					.setPrice(32, PRICE_LIST_VIP, CZK, new BigDecimal("320"), new BigDecimal("21"), new BigDecimal("361"), true)
					.toInstance(),
				new InitialEntityBuilder(Entities.PRODUCT, 5)
					.setPrice(40, PRICE_LIST_REFERENCE, CZK, new BigDecimal("80"), new BigDecimal("21"), new BigDecimal("101"), FAR_FUTURE, false)
					.setPrice(41, PRICE_LIST_BASIC, CZK, new BigDecimal("300"), new BigDecimal("21"), new BigDecimal("361"), FAR_FUTURE, true)
					.setPrice(42, PRICE_LIST_VIP, CZK, new BigDecimal("320"), new BigDecimal("21"), new BigDecimal("361"), FAR_FUTURE, true)
					.toInstance()
			)
			.collect(
				Collectors.toMap(SealedEntity::getPrimaryKey, Function.identity())
			);
	}

	@Test
	void shouldFilterEntitiesByCurrencyAndPriceList() {
		final SellingPriceAvailableBitmapFilter filter = new SellingPriceAvailableBitmapFilter(null);
		final Bitmap result = filter.filter(
			new TestQueryExecutionContext(
				PRODUCT_SCHEMA,
				Query.query(
					collection(Entities.PRODUCT),
					filterBy(
						and(
							priceInCurrency(CZK),
							priceInPriceLists(PRICE_LIST_BASIC, PRICE_LIST_REFERENCE)
						)
					),
					require(
						filter.getEntityRequire()
					)
				),
				this.entities
			)
		);

		Assertions.assertArrayEquals(
			new int[]{1, 4, 5},
			result.getArray()
		);
	}

	@Test
	void shouldFilterEntitiesByCurrencyAndPriceListWithDifferentPriceLists() {
		final SellingPriceAvailableBitmapFilter filter = new SellingPriceAvailableBitmapFilter(PRICE_LIST_REFERENCE);
		final Bitmap result = filter.filter(
			new TestQueryExecutionContext(
				PRODUCT_SCHEMA,
				Query.query(
					collection(Entities.PRODUCT),
					filterBy(
						and(
							priceInCurrency(CZK),
							priceInPriceLists(PRICE_LIST_VIP)
						)
					),
					require(
						filter.getEntityRequire()
					)
				),
				this.entities
			)
		);

		Assertions.assertArrayEquals(
			new int[]{3, 4, 5},
			result.getArray()
		);
	}

	@Test
	void shouldFilterEntitiesByCurrencyAndPriceListAndPriceFilter() {
		final SellingPriceAvailableBitmapFilter filter = new SellingPriceAvailableBitmapFilter(
			new String[] {PRICE_LIST_REFERENCE},
			new PriceContractPredicate(
				new BigDecimal("90"), new BigDecimal("130"), QueryPriceMode.WITH_TAX, 0
			)
		);
		final Bitmap result = filter.filter(
			new TestQueryExecutionContext(
				PRODUCT_SCHEMA,
				Query.query(
					collection(Entities.PRODUCT),
					filterBy(
						and(
							priceInCurrency(CZK),
							priceInPriceLists(PRICE_LIST_VIP, PRICE_LIST_BASIC),
							priceBetween(new BigDecimal("90"), new BigDecimal("130"))
						)
					),
					require(
						filter.getEntityRequire()
					)
				),
				this.entities
			)
		);

		Assertions.assertArrayEquals(
			new int[]{1, 3},
			result.getArray()
		);
	}

	@Test
	void shouldFilterEntitiesByCurrencyAndPriceListAndPriceFilterBasicFirst() {
		final SellingPriceAvailableBitmapFilter filter = new SellingPriceAvailableBitmapFilter(
			new String[] {PRICE_LIST_REFERENCE},
			new PriceContractPredicate(
				new BigDecimal("90"), new BigDecimal("130"), QueryPriceMode.WITH_TAX, 0
			)
		);
		final Bitmap result = filter.filter(
			new TestQueryExecutionContext(
				PRODUCT_SCHEMA,
				Query.query(
					collection(Entities.PRODUCT),
					filterBy(
						and(
							priceInCurrency(CZK),
							priceInPriceLists(PRICE_LIST_BASIC, PRICE_LIST_VIP),
							priceBetween(new BigDecimal("90"), new BigDecimal("130"))
						)
					),
					require(
						filter.getEntityRequire()
					)
				),
				this.entities
			)
		);

		Assertions.assertArrayEquals(
			new int[]{1, 3},
			result.getArray()
		);
	}

	@Test
	void shouldFilterEntitiesByCurrencyAndPriceListAndValidity() {
		final SellingPriceAvailableBitmapFilter filter = new SellingPriceAvailableBitmapFilter(PRICE_LIST_REFERENCE);
		final Bitmap result = filter.filter(
			new TestQueryExecutionContext(
				PRODUCT_SCHEMA,
				Query.query(
					collection(Entities.PRODUCT),
					filterBy(
						and(
							priceInCurrency(CZK),
							priceInPriceLists(PRICE_LIST_BASIC, PRICE_LIST_VIP),
							priceValidIn(OffsetDateTime.now())
						)
					),
					require(
						filter.getEntityRequire()
					)
				),
				this.entities
			)
		);

		Assertions.assertArrayEquals(
			new int[]{1, 3, 4},
			result.getArray()
		);
	}

	@Test
	void shouldFilterEntitiesByCurrencyAndPriceListAndValidityInFarFuture() {
		final SellingPriceAvailableBitmapFilter filter = new SellingPriceAvailableBitmapFilter(PRICE_LIST_REFERENCE);
		final Bitmap result = filter.filter(
			new TestQueryExecutionContext(
				PRODUCT_SCHEMA,
				Query.query(
					collection(Entities.PRODUCT),
					filterBy(
						and(
							priceInCurrency(CZK),
							priceInPriceLists(PRICE_LIST_BASIC, PRICE_LIST_VIP),
							priceValidIn(OffsetDateTime.now().plusYears(101))
						)
					),
					require(
						filter.getEntityRequire()
					)
				),
				this.entities
			)
		);

		Assertions.assertArrayEquals(
			new int[]{1, 3, 4, 5},
			result.getArray()
		);
	}

	/**
	 * The same prefetched bodies select different entities under different price-for-sale contexts, so the detached
	 * key of the alternative must separate every context input the filter reads through the prefetched entities.
	 */
	@Nested
	@DisplayName("Detached alternative key")
	class KeyTest {

		@Test
		@DisplayName("Shares the key of alternatives built for the same price-for-sale context")
		void shouldShareKeyForSamePriceForSaleContext() {
			final OffsetDateTime moment = OffsetDateTime.now();
			final TestQueryExecutionContext first = context(
				SellingPriceAvailableBitmapFilterTest.this.entities, QueryPriceMode.WITH_TAX, moment, CZK,
				PRICE_LIST_BASIC, PRICE_LIST_REFERENCE
			);
			final TestQueryExecutionContext second = context(
				SellingPriceAvailableBitmapFilterTest.this.entities, QueryPriceMode.WITH_TAX, moment, CZK,
				PRICE_LIST_BASIC, PRICE_LIST_REFERENCE
			);

			Assertions.assertEquals(key(alternativeFor(first)), key(alternativeFor(second)));
		}

		@Test
		@DisplayName("Separates price-list priorities that select different entities")
		void shouldDistinguishKeysWhenPriceListsSelectDifferentEntities() {
			final TestQueryExecutionContext first = context(
				SellingPriceAvailableBitmapFilterTest.this.entities, QueryPriceMode.WITH_TAX, null, CZK,
				PRICE_LIST_BASIC, PRICE_LIST_REFERENCE
			);
			final TestQueryExecutionContext second = context(
				SellingPriceAvailableBitmapFilterTest.this.entities, QueryPriceMode.WITH_TAX, null, CZK,
				PRICE_LIST_VIP
			);

			assertDistinctKeysForDistinctSelections(first, new int[]{1, 4, 5}, second, new int[]{3, 4, 5});
		}

		@Test
		@DisplayName("Separates currencies that select different entities")
		void shouldDistinguishKeysWhenCurrencySelectsDifferentEntities() {
			final TestQueryExecutionContext first = context(
				SellingPriceAvailableBitmapFilterTest.this.entities, QueryPriceMode.WITH_TAX, null, CZK,
				PRICE_LIST_BASIC, PRICE_LIST_REFERENCE
			);
			final TestQueryExecutionContext second = context(
				SellingPriceAvailableBitmapFilterTest.this.entities, QueryPriceMode.WITH_TAX, null, EUR,
				PRICE_LIST_BASIC, PRICE_LIST_REFERENCE
			);

			assertDistinctKeysForDistinctSelections(first, new int[]{1, 4, 5}, second, new int[]{2});
		}

		@Test
		@DisplayName("Separates validity moments that select different entities")
		void shouldDistinguishKeysWhenValidityMomentSelectsDifferentEntities() {
			final OffsetDateTime now = OffsetDateTime.now();
			final TestQueryExecutionContext first = context(
				SellingPriceAvailableBitmapFilterTest.this.entities, QueryPriceMode.WITH_TAX, now, CZK,
				PRICE_LIST_BASIC, PRICE_LIST_VIP
			);
			final TestQueryExecutionContext second = context(
				SellingPriceAvailableBitmapFilterTest.this.entities, QueryPriceMode.WITH_TAX, now.plusYears(101), CZK,
				PRICE_LIST_BASIC, PRICE_LIST_VIP
			);

			assertDistinctKeysForDistinctSelections(first, new int[]{1, 3, 4}, second, new int[]{1, 3, 4, 5});
		}

		@Test
		@DisplayName("Separates query price modes that record different cumulated totals")
		void shouldDistinguishKeysWhenPriceModeChangesCumulatedPrice() {
			final Map<Integer, SealedEntity> sumEntity = Map.of(
				6,
				new InitialEntityBuilder(Entities.PRODUCT, 6)
					.setPriceInnerRecordHandling(PriceInnerRecordHandling.SUM)
					.setPrice(
						60, PRICE_LIST_BASIC, CZK, 1,
						new BigDecimal("100"), new BigDecimal("21"), new BigDecimal("121"), true
					)
					.setPrice(
						61, PRICE_LIST_BASIC, CZK, 2,
						new BigDecimal("50"), new BigDecimal("21"), new BigDecimal("60.5"), true
					)
					.toInstance()
			);
			final TestQueryExecutionContext withTax = context(
				sumEntity, QueryPriceMode.WITH_TAX, null, CZK, PRICE_LIST_BASIC
			);
			final TestQueryExecutionContext withoutTax = context(
				sumEntity, QueryPriceMode.WITHOUT_TAX, null, CZK, PRICE_LIST_BASIC
			);
			final SellingPriceAvailableBitmapFilter first = alternativeFor(withTax);
			final SellingPriceAvailableBitmapFilter second = alternativeFor(withoutTax);

			// both select the entity, but the cumulated selling price each one records carries a different total
			Assertions.assertArrayEquals(new int[]{6}, first.filter(withTax).getArray());
			Assertions.assertArrayEquals(new int[]{6}, second.filter(withoutTax).getArray());
			final PriceRecordContract withTaxRecord = singleRecord(first, withTax);
			final PriceRecordContract withoutTaxRecord = singleRecord(second, withoutTax);
			Assertions.assertEquals(18150, withTaxRecord.priceWithTax());
			Assertions.assertEquals(0, withTaxRecord.priceWithoutTax());
			Assertions.assertEquals(0, withoutTaxRecord.priceWithTax());
			Assertions.assertEquals(15000, withoutTaxRecord.priceWithoutTax());
			assertDistinctKeys(first, second);
		}
	}

	/**
	 * Builds the alternative the way the price translators build it for the query of the passed context.
	 */
	@Nonnull
	private static SellingPriceAvailableBitmapFilter alternativeFor(@Nonnull TestQueryExecutionContext context) {
		return SellingPriceAvailableBitmapFilter.forRequest(
			context.getEvitaRequest(), PricePredicate.ALL_CONTRACT_FILTER
		);
	}

	/**
	 * Builds the same leaf side key that a selection formula builds for its alternative.
	 */
	@Nonnull
	private static FormulaKey key(@Nonnull SellingPriceAvailableBitmapFilter filter) {
		return FormulaKey.of(filter.identity(), filter.getClassId(), FormulaKey.NO_CHILDREN, filter);
	}

	/**
	 * Asserts that the alternatives of the two contexts select the expected, different entities from the same
	 * prefetched bodies and therefore have different keys.
	 */
	private static void assertDistinctKeysForDistinctSelections(
		@Nonnull TestQueryExecutionContext firstContext,
		@Nonnull int[] firstSelection,
		@Nonnull TestQueryExecutionContext secondContext,
		@Nonnull int[] secondSelection
	) {
		final SellingPriceAvailableBitmapFilter first = alternativeFor(firstContext);
		final SellingPriceAvailableBitmapFilter second = alternativeFor(secondContext);

		Assertions.assertArrayEquals(firstSelection, first.filter(firstContext).getArray());
		Assertions.assertArrayEquals(secondSelection, second.filter(secondContext).getArray());
		assertDistinctKeys(first, second);
	}

	/**
	 * Asserts that two value keys differ; an opaque key would differ for any two instances and prove nothing.
	 */
	private static void assertDistinctKeys(
		@Nonnull SellingPriceAvailableBitmapFilter first,
		@Nonnull SellingPriceAvailableBitmapFilter second
	) {
		final FormulaKey firstKey = key(first);
		final FormulaKey secondKey = key(second);
		Assertions.assertFalse(firstKey.isOpaque());
		Assertions.assertFalse(secondKey.isOpaque());
		Assertions.assertNotEquals(firstKey, secondKey);
		Assertions.assertNotEquals(firstKey.getHash64(), secondKey.getHash64());
	}

	/**
	 * Returns the only selling price record the alternative collected while filtering.
	 */
	@Nonnull
	private static PriceRecordContract singleRecord(
		@Nonnull SellingPriceAvailableBitmapFilter filter,
		@Nonnull TestQueryExecutionContext context
	) {
		final PriceRecordContract[] records =
			((ResolvedFilteredPriceRecords) filter.getFilteredPriceRecords(context)).getPriceRecords();
		Assertions.assertEquals(1, records.length);
		return records[0];
	}

	/**
	 * Creates the execution context of a query filtering by currency, price lists and an optional validity moment,
	 * prefetching the entities with the prices that respect the filter.
	 */
	@Nonnull
	private static TestQueryExecutionContext context(
		@Nonnull Map<Integer, SealedEntity> entities,
		@Nonnull QueryPriceMode priceMode,
		@Nullable OffsetDateTime moment,
		@Nonnull Currency currency,
		@Nonnull String... priceLists
	) {
		final FilterConstraint[] constraints = moment == null ?
			new FilterConstraint[]{priceInCurrency(currency), priceInPriceLists(priceLists)} :
			new FilterConstraint[]{priceInCurrency(currency), priceInPriceLists(priceLists), priceValidIn(moment)};
		return new TestQueryExecutionContext(
			PRODUCT_SCHEMA,
			Query.query(
				collection(Entities.PRODUCT),
				filterBy(and(constraints)),
				require(entityFetch(priceContentRespectingFilter()), priceType(priceMode))
			),
			entities
		) {
			/** Reads the price mode of the request, as the planning context of a real query does. */
			@Nonnull
			@Override
			public QueryPriceMode getQueryPriceMode() {
				return getEvitaRequest().getQueryPriceMode();
			}
		};
	}

}
