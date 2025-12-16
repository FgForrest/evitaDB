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

import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.OrFormula;
import io.evitadb.core.query.algebra.price.filteredPriceRecords.FilteredPriceRecords.SortingForm;
import io.evitadb.core.query.algebra.price.filteredPriceRecords.ResolvedFilteredPriceRecords;
import io.evitadb.dataType.array.CompositeObjectArray;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.price.model.priceRecord.PriceRecordContract;
import io.evitadb.index.price.model.priceRecord.PriceRecordInnerRecordSpecific;
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
public class EntityIdsWithPriceRecordsRecordState {
	private static final int ENTITY_COUNT = 10_000;
	private static final int PRICE_COUNT = 100_000;
	private static final Random random = new Random(42);
	private static int PRICE_ID_SEQ;

	@Getter private PriceRecordContract[] entitiesPriceRecordsA;
	@Getter private PriceRecordContract[] entitiesPriceRecordsB;
	@Getter private PriceRecordContract[] entitiesPriceRecordsC;
	@Getter private Bitmap entitiesA;
	@Getter private Bitmap entitiesB;
	@Getter private Bitmap entitiesC;
	@Getter private Formula formula;

	/**
	 * This setup is called once for each `valueCount`.
	 */
	@Setup(Level.Trial)
	public void setUp() {
		final CompositeObjectArray<PriceRecordContract> priceRecordsA = new CompositeObjectArray<>(PriceRecordContract.class);
		this.entitiesA = generateBitmap(ENTITY_COUNT, PRICE_COUNT, priceRecordsA);
		this.entitiesPriceRecordsA = priceRecordsA.toArray();

		final CompositeObjectArray<PriceRecordContract> priceRecordsB = new CompositeObjectArray<>(PriceRecordContract.class);
		this.entitiesB = generateBitmap(ENTITY_COUNT, PRICE_COUNT, priceRecordsB);
		this.entitiesPriceRecordsB = priceRecordsB.toArray();

		final CompositeObjectArray<PriceRecordContract> priceRecordsC = new CompositeObjectArray<>(PriceRecordContract.class);
		this.entitiesC = generateBitmap(ENTITY_COUNT, PRICE_COUNT, priceRecordsC);
		this.entitiesPriceRecordsC = priceRecordsC.toArray();

		this.formula = new OrFormula(
			new MockEntityIdsFormula(this.entitiesA, new ResolvedFilteredPriceRecords(this.entitiesPriceRecordsA, SortingForm.NOT_SORTED)),
			new MockEntityIdsFormula(this.entitiesB, new ResolvedFilteredPriceRecords(this.entitiesPriceRecordsB, SortingForm.NOT_SORTED)),
			new MockEntityIdsFormula(this.entitiesB, new ResolvedFilteredPriceRecords(this.entitiesPriceRecordsC, SortingForm.NOT_SORTED))
		);
		this.formula.compute();
	}

	private Bitmap generateBitmap(int entityCount, int priceCount, CompositeObjectArray<PriceRecordContract> priceRecords) {
		final Bitmap bitmap = new BaseBitmap();
		for (int i = 0; i < priceCount; i++) {
			final int entityId = getRandomNumber(entityCount);
			bitmap.add(entityId);
			final int randomPrice = random.nextInt(5000);
			final int priceId = ++PRICE_ID_SEQ;
			final int innerRecordId = entityCount + random.nextInt(entityCount);
			priceRecords.add(
				new PriceRecordInnerRecordSpecific(
					priceId, priceId, entityId, innerRecordId, (int) (randomPrice * 1.21), randomPrice
				)
			);
		}

		return bitmap;
	}

	private int getRandomNumber(int entityCount) {
		return random.nextInt(entityCount);
	}

}
