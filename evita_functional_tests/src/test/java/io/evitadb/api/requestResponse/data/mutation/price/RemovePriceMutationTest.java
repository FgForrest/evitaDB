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

package io.evitadb.api.requestResponse.data.mutation.price;

import io.evitadb.api.exception.InvalidMutationException;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * This test verifies contract of {@link RemovePriceMutation} mutation.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class RemovePriceMutationTest extends AbstractMutationTest {
	public static final Currency CZK = Currency.getInstance("CZK");

	@Test
	void shouldRemovePrice() {
		final PriceKey priceKey = new PriceKey(1, "basic", CZK);
		final RemovePriceMutation mutation = new RemovePriceMutation(priceKey);
		final OffsetDateTime theDay = OffsetDateTime.now();
		final PriceContract removedPrice = mutation.mutateLocal(
			this.productSchema,
			new Price(
				1, priceKey, 2, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.TEN,
				DateTimeRange.since(theDay),
				true, false
			)
		);

		assertEquals(2L, removedPrice.version());
		assertEquals(1, removedPrice.priceId());
		assertEquals("basic", removedPrice.priceList());
		assertEquals(CZK, removedPrice.currency());
		assertEquals(2, removedPrice.innerRecordId());
		assertEquals(BigDecimal.ONE, removedPrice.priceWithoutTax());
		assertEquals(BigDecimal.ZERO, removedPrice.taxRate());
		assertEquals(BigDecimal.TEN, removedPrice.priceWithTax());
		assertEquals(DateTimeRange.since(theDay), removedPrice.validity());
		assertTrue(removedPrice.indexed());
		assertTrue(removedPrice.dropped());
	}

	@Test
	void shouldFailToRemoveNonexistingPrice() {
		final RemovePriceMutation mutation = new RemovePriceMutation(new PriceKey(1, "basic", CZK));
		assertThrows(InvalidMutationException.class, () -> mutation.mutateLocal(this.productSchema, null));
	}

	@Test
	void shouldFailToRemoveNonexistingPriceWhenAcceptingDroppedObject() {
		final PriceKey priceKey = new PriceKey(1, "basic", CZK);
		final RemovePriceMutation mutation = new RemovePriceMutation(priceKey);
		assertThrows(
			InvalidMutationException.class,
			() -> mutation.mutateLocal(
				this.productSchema,
				new Price(
					1, priceKey, 2, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.TEN,
					null, true, true
				)
			)
		);
	}

}
