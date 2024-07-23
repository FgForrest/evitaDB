/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

package io.evitadb.index.price;

import io.evitadb.index.price.model.entityPrices.EntityPrices;
import io.evitadb.index.price.model.priceRecord.PriceRecord;
import io.evitadb.index.price.model.priceRecord.PriceRecordContract;
import io.evitadb.index.price.model.priceRecord.PriceRecordInnerRecordSpecific;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * This test verifies contract of {@link EntityPrices}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
class EntityPricesTest {

	@Test
	void shouldAddPlainPricesAndVerifySorting() {
		final EntityPrices entityPrices = EntityPrices.create(createPrice(1, 123, 155));
		assertEquals(1, entityPrices.getLowestPriceRecords()[0].priceId());

		final EntityPrices entityPrices2 = EntityPrices.addPriceRecord(entityPrices, createPrice(2, 100, 200));
		assertEquals(1, entityPrices2.getLowestPriceRecords()[0].priceId());
	}

	@Test
	void shouldRemovePlainPricesAndVerifySorting() {
		final EntityPrices entityPrices = EntityPrices.create(createPrice(1, 123, 155));
		final EntityPrices entityPrices2 = EntityPrices.addPriceRecord(entityPrices, createPrice(2, 100, 200));

		assertEquals(1, entityPrices2.getLowestPriceRecords()[0].priceId());

		final EntityPrices entityPrices3 = EntityPrices.removePrice(entityPrices2, createPrice(2, 100, 200));
		assertEquals(1, entityPrices3.getLowestPriceRecords()[0].priceId());
	}

	@Test
	void shouldAddInnerRecordPricesAndVerifySorting() {
		final EntityPrices entityPrices = EntityPrices.create(createPrice(1, 1, 123, 155));
		final EntityPrices entityPrices2 = EntityPrices.addPriceRecord(entityPrices, createPrice(2, 1, 100, 200));
		final EntityPrices entityPrices3 = EntityPrices.addPriceRecord(entityPrices2, createPrice(3, 2, 800, 850));
		final EntityPrices entityPrices4 = EntityPrices.addPriceRecord(entityPrices3, createPrice(4, 2, 500, 590));

		assertArrayEquals(new int[] {1, 4}, Arrays.stream(entityPrices4.getLowestPriceRecords()).mapToInt(PriceRecordContract::priceId).toArray());
	}

	@Test
	void shouldRemoveInnerRecordPricesAndVerifySorting() {
		final EntityPrices entityPrices = EntityPrices.create(createPrice(1, 1, 123, 155));
		final EntityPrices entityPrices2 = EntityPrices.addPriceRecord(entityPrices, createPrice(2, 1, 100, 200));
		final EntityPrices entityPrices3 = EntityPrices.addPriceRecord(entityPrices2, createPrice(3, 2, 800, 850));
		final EntityPrices entityPrices4 = EntityPrices.addPriceRecord(entityPrices3, createPrice(4, 2, 500, 590));

		final EntityPrices entityPrices5 = EntityPrices.removePrice(entityPrices4, createPrice(1, 1, 123, 155));

		assertArrayEquals(new int[] {2, 4}, Arrays.stream(entityPrices5.getLowestPriceRecords()).mapToInt(PriceRecordContract::priceId).toArray());
	}

	@Test
	void shouldFindPriceRecordByInnerId() {
		final EntityPrices entityPrices = EntityPrices.create(createPrice(1, 1, 123, 155));
		final EntityPrices entityPrices2 = EntityPrices.addPriceRecord(entityPrices, createPrice(2, 1, 100, 200));
		final EntityPrices entityPrices3 = EntityPrices.addPriceRecord(entityPrices2, createPrice(3, 2, 800, 850));
		final EntityPrices entityPrices4 = EntityPrices.addPriceRecord(entityPrices3, createPrice(4, 2, 500, 590));

		assertTrue(entityPrices4.containsInnerRecord(1));
		assertTrue(entityPrices4.containsInnerRecord(2));
		assertFalse(entityPrices4.containsInnerRecord(3));
	}

	@Test
	void shouldFindPriceRecordById() {
		final PriceRecordContract price1 = createPrice(1, 1, 123, 155);
		final PriceRecordContract price2 = createPrice(2, 1, 100, 200);
		final PriceRecordContract price3 = createPrice(3, 2, 800, 850);
		final PriceRecordContract price4 = createPrice(4, 2, 500, 590);
		final PriceRecordContract price5 = createPrice(5, 1, 500, 590);
		final PriceRecordContract price6 = createPrice(6, 3, 500, 590);

		final EntityPrices entityPrices = EntityPrices.create(price1);
		final EntityPrices entityPrices2 = EntityPrices.addPriceRecord(entityPrices, price2);
		final EntityPrices entityPrices3 = EntityPrices.addPriceRecord(entityPrices2, price3);
		final EntityPrices entityPrices4 = EntityPrices.addPriceRecord(entityPrices3, price4);

		assertTrue(entityPrices4.containsAnyOf(new PriceRecordContract[]{price1, price5}));
		assertTrue(entityPrices4.containsAnyOf(new PriceRecordContract[]{price4, price5, price6}));
		assertFalse(entityPrices4.containsAnyOf(new PriceRecordContract[]{price5, price6}));
	}

	@Nonnull
	private PriceRecordContract createPrice(int priceId, int priceWithTax, int priceWithoutTax) {
		return new PriceRecord(priceId, priceId, 1, priceWithTax, priceWithoutTax);
	}

	@Nonnull
	private PriceRecordContract createPrice(int priceId, int innerRecordId, int priceWithTax, int priceWithoutTax) {
		return new PriceRecordInnerRecordSpecific(priceId, priceId, 1, innerRecordId, priceWithTax, priceWithoutTax);
	}

}
