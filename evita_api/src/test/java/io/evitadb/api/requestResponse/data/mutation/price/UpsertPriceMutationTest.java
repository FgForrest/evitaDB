/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.api.requestResponse.data.mutation.price;

import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.api.requestResponse.data.mutation.AbstractMutationTest;
import io.evitadb.api.requestResponse.data.structure.Price;
import io.evitadb.api.requestResponse.data.structure.Price.PriceKey;
import io.evitadb.dataType.DateTimeRange;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Currency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * This test verifies contract of {@link UpsertPriceMutation} mutation.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class UpsertPriceMutationTest extends AbstractMutationTest {
	public static final Currency CZK = Currency.getInstance("CZK");
	public static final Currency EUR = Currency.getInstance("EUR");

	@Test
	void shouldCreateNewPrice() {
		final PriceKey priceKey = new PriceKey(1, "basic", CZK);
		final OffsetDateTime theDay = OffsetDateTime.now().plusMinutes(5);
		final UpsertPriceMutation mutation = new UpsertPriceMutation(
			priceKey,
			new Price(
				1, priceKey, 2, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.TEN,
				DateTimeRange.since(theDay),
				true, false
			)
		);
		final PriceContract createdPrice = mutation.mutateLocal(productSchema, null);
		assertEquals(1L, createdPrice.getVersion());
		assertEquals(1, createdPrice.getPriceId());
		assertEquals("basic", createdPrice.getPriceList());
		assertEquals(CZK, createdPrice.getCurrency());
		assertEquals(2, createdPrice.getInnerRecordId());
		assertEquals(BigDecimal.ONE, createdPrice.getPriceWithoutTax());
		assertEquals(BigDecimal.ZERO, createdPrice.getTaxRate());
		assertEquals(BigDecimal.TEN, createdPrice.getPriceWithTax());
		assertEquals(DateTimeRange.since(theDay), createdPrice.getValidity());
		assertTrue(createdPrice.isSellable());
		assertFalse(createdPrice.isDropped());
	}

	@Test
	void shouldCreateNewPriceByFullParameterConstructor() {
		final OffsetDateTime theDay = OffsetDateTime.now().plusMinutes(5);
		final UpsertPriceMutation mutation = new UpsertPriceMutation(
			new PriceKey(1, "basic", CZK),
			2, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.TEN,
			DateTimeRange.since(theDay),
			true
		);
		final PriceContract createdPrice = mutation.mutateLocal(productSchema, null);
		assertEquals(1L, createdPrice.getVersion());
		assertEquals(1, createdPrice.getPriceId());
		assertEquals("basic", createdPrice.getPriceList());
		assertEquals(CZK, createdPrice.getCurrency());
		assertEquals(2, createdPrice.getInnerRecordId());
		assertEquals(BigDecimal.ONE, createdPrice.getPriceWithoutTax());
		assertEquals(BigDecimal.ZERO, createdPrice.getTaxRate());
		assertEquals(BigDecimal.TEN, createdPrice.getPriceWithTax());
		assertEquals(DateTimeRange.since(theDay), createdPrice.getValidity());
		assertTrue(createdPrice.isSellable());
		assertFalse(createdPrice.isDropped());
	}

	@Test
	void shouldUpdatePrice() {
		final OffsetDateTime theDay = OffsetDateTime.now().plusMinutes(5);
		final OffsetDateTime theAnotherDay = OffsetDateTime.now().plusMinutes(45);
		final PriceKey priceKey = new PriceKey(1, "basic", CZK);
		final UpsertPriceMutation mutation = new UpsertPriceMutation(
			priceKey,
			new Price(
				1, priceKey, 5, BigDecimal.TEN, BigDecimal.ONE, new BigDecimal("100.00"),
				DateTimeRange.since(theAnotherDay),
				false, false
			)
		);
		final PriceContract updatedPrice = mutation.mutateLocal(
			productSchema,
			new Price(
				1, priceKey, 2, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.TEN,
				DateTimeRange.since(theDay),
				true, false
			)
		);
		assertEquals(2L, updatedPrice.getVersion());
		assertEquals(1, updatedPrice.getPriceId());
		assertEquals("basic", updatedPrice.getPriceList());
		assertEquals(CZK, updatedPrice.getCurrency());
		assertEquals(5, updatedPrice.getInnerRecordId());
		assertEquals(BigDecimal.TEN, updatedPrice.getPriceWithoutTax());
		assertEquals(BigDecimal.ONE, updatedPrice.getTaxRate());
		assertEquals(new BigDecimal("100.00"), updatedPrice.getPriceWithTax());
		assertEquals(DateTimeRange.since(theAnotherDay), updatedPrice.getValidity());
		assertFalse(updatedPrice.isSellable());
		assertFalse(updatedPrice.isDropped());
	}

	@Test
	void shouldReturnSameSkipToken() {
		assertEquals(
			new UpsertPriceMutation(new PriceKey(1, "basic", CZK), 45, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, null, true).getSkipToken(catalogSchema, productSchema),
			new UpsertPriceMutation(new PriceKey(2, "reference", CZK), 46, BigDecimal.TEN, BigDecimal.ONE, BigDecimal.TEN, null, false).getSkipToken(catalogSchema, productSchema)
		);
	}

	@Test
	void shouldReturnDifferentSkipToken() {
		assertNotEquals(
			new UpsertPriceMutation(new PriceKey(1, "basic", CZK), 45, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, null, true).getSkipToken(catalogSchema, productSchema),
			new UpsertPriceMutation(new PriceKey(2, "reference", EUR), 46, BigDecimal.TEN, BigDecimal.ONE, BigDecimal.TEN, null, false).getSkipToken(catalogSchema, productSchema)
		);
	}

}