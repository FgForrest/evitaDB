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

package io.evitadb.api.functional.price;

import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.core.Evita;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.test.extension.EvitaParameterResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Currency;

import static io.evitadb.api.query.QueryConstraints.priceContentAll;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.PRICE;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies end to end that an upsert of a price is stored when its validity names the same instants as the stored
 * validity at another zone offset, although the two validities compare equal by their encoded bounds.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Upsert of a price validity naming the same instants at another zone offset")
@ExtendWith(EvitaParameterResolver.class)
@Tag(CONTRACT)
@Tag(PRICE)
class PriceValidityUpsertFunctionalTest {
	private static final String PRODUCT = "product";
	private static final int PK = 1;
	private static final Currency CZK = Currency.getInstance("CZK");

	@Test
	@DisplayName("should replace the validity of a price")
	void shouldReplaceValidityOfPrice(Evita evita) {
		final LocalDateTime noon = LocalDateTime.of(2024, 1, 1, 12, 0);
		final DateTimeRange storedValidity = DateTimeRange.between(
			noon.atOffset(ZoneOffset.ofHours(2)), noon.plusHours(1).atOffset(ZoneOffset.ofHours(2))
		);
		final DateTimeRange newValidity = DateTimeRange.between(
			noon.minusHours(1).atOffset(ZoneOffset.ofHours(1)), noon.atOffset(ZoneOffset.ofHours(1))
		);
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(PRODUCT).withPrice().updateVia(session);
				session.createNewEntity(PRODUCT, PK)
					.setPrice(
						1, "basic", CZK, BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN, storedValidity, true
					)
					.upsertVia(session);
			}
		);
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.getEntity(PRODUCT, PK, priceContentAll()).orElseThrow()
					.openForWrite()
					.setPrice(
						1, "basic", CZK, BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN, newValidity, true
					)
					.upsertVia(session);
			}
		);

		final SealedEntity product = evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.getEntity(PRODUCT, PK, priceContentAll()).orElseThrow();
			}
		);
		assertEquals(
			newValidity.toString(),
			String.valueOf(product.getPrice(1, "basic", CZK).orElseThrow().validity())
		);
	}
}
