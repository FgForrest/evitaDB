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

package io.evitadb.api.functional.indexing;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.core.Evita;
import io.evitadb.test.Entities;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.test.EvitaTestSupport.TestPaths;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.math.BigDecimal;
import java.util.Currency;
import org.junit.jupiter.api.Tag;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static org.junit.jupiter.api.Assertions.*;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.PRICE;

/**
 * Tests for price indexing operations in evitaDB, verifying price sellability changes
 * and price inner record handling modifications.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("Price indexing operations")
@Tag(CONTRACT)
@Tag(INDEXING)
@Tag(PRICE)
class PriceIndexingTest implements EvitaTestSupport, IndexingTestSupport {

	private TestPaths paths;
	private Evita evita;

	private static int countProductsWithPriceListCurrencyCombination(
		@Nonnull EvitaSessionContract session, @Nonnull String priceList, @Nonnull Currency currency
	) {
		return session.query(
				query(
					collection(Entities.PRODUCT),
					filterBy(
						and(
							priceInPriceLists(priceList),
							priceInCurrency(currency)
						)
					)
				),
				EntityReferenceContract.class
			)
			.getTotalRecordCount();
	}

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("PriceIndexingTest");
		this.evita = new Evita(
			getEvitaConfiguration()
		);
		this.evita.defineCatalog(TEST_CATALOG);
	}

	@AfterEach
	void tearDown() {
		this.evita.close();
		cleanupTestPaths(this.paths);
	}

	@Test
	@DisplayName("Should change price sellability by toggling indexed flag")
	void shouldChangePriceSellability() {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session
					.defineEntitySchema(Entities.PRODUCT)
					.withPrice()
					.updateVia(session);

				final EntityBuilder product = session.createNewEntity(Entities.PRODUCT, 1)
					.setPriceInnerRecordHandling(PriceInnerRecordHandling.NONE)
					.setPrice(1, PRICE_LIST_BASIC, CURRENCY_CZK, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, true)
					.setPrice(
						2, PRICE_LIST_BASIC, CURRENCY_EUR, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, false);

				session.upsertEntity(product);

				assertEquals(1, countProductsWithPriceListCurrencyCombination(session, PRICE_LIST_BASIC, CURRENCY_CZK));
				assertEquals(0, countProductsWithPriceListCurrencyCombination(session, PRICE_LIST_BASIC, CURRENCY_EUR));

				session.getEntity(Entities.PRODUCT, 1, entityFetchAllContent())
					.orElseThrow()
					.openForWrite()
					.setPrice(1, PRICE_LIST_BASIC, CURRENCY_CZK, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, false)
					.setPrice(2, PRICE_LIST_BASIC, CURRENCY_EUR, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, true)
					.upsertVia(session);

				assertEquals(0, countProductsWithPriceListCurrencyCombination(session, PRICE_LIST_BASIC, CURRENCY_CZK));
				assertEquals(1, countProductsWithPriceListCurrencyCombination(session, PRICE_LIST_BASIC, CURRENCY_EUR));
			}
		);
	}

	@Test
	@DisplayName("Should change inner record handling and remove price")
	void shouldChangePriceInnerRecordHandlingAndRemovePrice() {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(Entities.PRODUCT)
					.withPrice()
					.updateVia(session);

				final EntityBuilder product = session.createNewEntity(Entities.PRODUCT, 1)
					.setPriceInnerRecordHandling(PriceInnerRecordHandling.NONE)
					.setPrice(1, PRICE_LIST_BASIC, CURRENCY_CZK, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, true)
					.setPrice(2, PRICE_LIST_BASIC, CURRENCY_EUR, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, true)
					.setPrice(3, PRICE_LIST_VIP, CURRENCY_CZK, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, true)
					.setPrice(4, PRICE_LIST_VIP, CURRENCY_EUR, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, true);

				session.upsertEntity(product);

				session.getEntity(Entities.PRODUCT, 1, entityFetchAllContent())
					.orElseThrow()
					.openForWrite()
					.setPriceInnerRecordHandling(PriceInnerRecordHandling.LOWEST_PRICE)
					.upsertVia(session);

				session.getEntity(Entities.PRODUCT, 1, entityFetchAllContent())
					.orElseThrow()
					.openForWrite()
					.removePrice(1, PRICE_LIST_BASIC, CURRENCY_CZK)
					.removePrice(3, PRICE_LIST_VIP, CURRENCY_CZK)
					.upsertVia(session);

				final SealedEntity loadedEntity = session.getEntity(Entities.PRODUCT, 1, entityFetchAllContent())
					.orElseThrow();

				assertEquals(
					2,
					loadedEntity
						.getPrices()
						.size()
				);
			}
		);
	}

	@Nonnull
	private EvitaConfiguration getEvitaConfiguration() {
		return newTestEvitaConfigurationBuilder(this.paths)
			.server(
				ServerOptions.builder()
					.closeSessionsAfterSecondsOfInactivity(-1)
					.build()
			)
			.build();
	}
}
