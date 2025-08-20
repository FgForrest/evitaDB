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

package io.evitadb.spike.mock;

import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.price.model.priceRecord.PriceRecord;
import lombok.Getter;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import java.util.Random;

/**
 * No extra information provided - see (selfexplanatory) method signatures.
 * I have the best intention to write more detailed documentation but if you see this, there was not enough time or will to do so.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@State(Scope.Benchmark)
public class EntityIdsState {
	private static final int ENTITY_COUNT = 10_000;
	private static final int PRICE_COUNT = 100_000;
	private static final Random random = new Random(42);
	private static int PRICE_ID_SEQ;

	@Getter private final MockPriceListAndCurrencyPriceIndex priceIndex = new MockPriceListAndCurrencyPriceIndex(ENTITY_COUNT);
	@Getter private Bitmap entityIds;

	/**
	 * This setup is called once for each `valueCount`.
	 */
	@Setup(Level.Trial)
	public void setUp() {
		this.entityIds = generateBitmap(ENTITY_COUNT, PRICE_COUNT);
	}

	private Bitmap generateBitmap(int entityCount, int priceCount) {
		final Bitmap bitmap = new BaseBitmap();
		final int priceToEntitySize = priceCount / entityCount;
		for (int i = 0; i < priceCount; i++) {
			final int entityId = getRandomNumber(entityCount);
			bitmap.add(entityId);
			final int randomPrice = random.nextInt(5000);
			final PriceRecord price = new PriceRecord(
				++PRICE_ID_SEQ, entityId, entityCount + random.nextInt(entityCount), (int) (randomPrice * 1.21), randomPrice
			);
			this.priceIndex.recordPrice(price);
			if (getRandomNumber(priceToEntitySize) == 0) {
				bitmap.add(price.entityPrimaryKey());
			}
		}

		return bitmap;
	}

	private int getRandomNumber(int entityCount) {
		return random.nextInt(entityCount);
	}

}
