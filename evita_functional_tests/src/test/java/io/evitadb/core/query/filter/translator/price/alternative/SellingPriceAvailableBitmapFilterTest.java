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

import io.evitadb.api.query.Query;
import io.evitadb.api.query.require.QueryPriceMode;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.InitialEntityBuilder;
import io.evitadb.api.requestResponse.schema.CatalogEvolutionMode;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchemaProvider;
import io.evitadb.core.query.algebra.price.predicate.PricePredicate.PriceContractPredicate;
import io.evitadb.core.query.filter.translator.TestQueryExecutionContext;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.test.Entities;
import io.evitadb.test.TestConstants;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
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

import static io.evitadb.api.query.QueryConstraints.*;
import static java.util.Optional.of;

/**
 * This test verifies behaviour of {@link SellingPriceAvailableBitmapFilter}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
class SellingPriceAvailableBitmapFilterTest {
	private static final EntitySchema PRODUCT_SCHEMA = EntitySchema._internalBuild(Entities.PRODUCT);
	private static final CatalogSchema CATALOG_SCHEMA = CatalogSchema._internalBuild(
		TestConstants.TEST_CATALOG, Collections.emptyMap(), EnumSet.allOf(CatalogEvolutionMode.class),
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

}
