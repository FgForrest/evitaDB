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

package io.evitadb.spike.mock;

import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.OrFormula;
import io.evitadb.core.query.algebra.price.filteredPriceRecords.FilteredPriceRecords.SortingForm;
import io.evitadb.core.query.algebra.price.filteredPriceRecords.ResolvedFilteredPriceRecords;
import io.evitadb.dataType.array.CompositeObjectArray;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.price.model.priceRecord.PriceRecordInnerRecordSpecific;
import lombok.Getter;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import java.util.Arrays;
import java.util.Comparator;
import java.util.Random;

/**
 * No extra information provided - see (selfexplanatory) method signatures.
 * I have the best intention to write more detailed documentation but if you see this, there was not enough time or will to do so.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@State(Scope.Benchmark)
public class InnerRecordIdsWithPriceRecordsRecordState {
	private static final int PRICE_COUNT = 100_000;
	private static final Random random = new Random(42);
	private static int PRICE_ID_SEQ;

	@Getter private PriceRecordInnerRecordSpecific[] entitiesPriceRecordsA;
	@Getter private PriceRecordInnerRecordSpecific[] entitiesPriceRecordsB;
	@Getter private PriceRecordInnerRecordSpecific[] entitiesPriceRecordsC;
	@Getter private Bitmap entitiesA;
	@Getter private Bitmap entitiesB;
	@Getter private Bitmap entitiesC;
	@Getter private Formula formula;

	/**
	 * This setup is called once for each `valueCount`.
	 */
	@Setup(Level.Trial)
	public void setUp() {
		final CompositeObjectArray<PriceRecordInnerRecordSpecific> priceRecordsA = new CompositeObjectArray<>(PriceRecordInnerRecordSpecific.class);
		entitiesA = generateBitmap(PRICE_COUNT, priceRecordsA);
		entitiesPriceRecordsA = priceRecordsA.toArray();
		Arrays.sort(entitiesPriceRecordsA, Comparator.comparingInt(PriceRecordInnerRecordSpecific::entityPrimaryKey));

		final CompositeObjectArray<PriceRecordInnerRecordSpecific> priceRecordsB = new CompositeObjectArray<>(PriceRecordInnerRecordSpecific.class);
		entitiesB = generateBitmap(PRICE_COUNT, priceRecordsB);
		entitiesPriceRecordsB = priceRecordsB.toArray();
		Arrays.sort(entitiesPriceRecordsB, Comparator.comparingInt(PriceRecordInnerRecordSpecific::entityPrimaryKey));

		final CompositeObjectArray<PriceRecordInnerRecordSpecific> priceRecordsC = new CompositeObjectArray<>(PriceRecordInnerRecordSpecific.class);
		entitiesC = generateBitmap(PRICE_COUNT, priceRecordsC);
		entitiesPriceRecordsC = priceRecordsC.toArray();
		Arrays.sort(entitiesPriceRecordsC, Comparator.comparingInt(PriceRecordInnerRecordSpecific::entityPrimaryKey));

		formula = new OrFormula(
			new MockInnerRecordIdsFormula(entitiesA, new ResolvedFilteredPriceRecords(entitiesPriceRecordsA, SortingForm.ENTITY_PK)),
			new MockInnerRecordIdsFormula(entitiesB, new ResolvedFilteredPriceRecords(entitiesPriceRecordsB, SortingForm.ENTITY_PK)),
			new MockInnerRecordIdsFormula(entitiesB, new ResolvedFilteredPriceRecords(entitiesPriceRecordsC, SortingForm.ENTITY_PK))
		);
		formula.compute();
	}

	private Bitmap generateBitmap(int priceCount, CompositeObjectArray<PriceRecordInnerRecordSpecific> priceRecords) {
		final Bitmap bitmap = new BaseBitmap();
		int entityId = 1;
		int counter = 0;
		while (counter < priceCount) {
			entityId += random.nextInt(512);
			int innerRecordId = 1;
			for (int i = 0; i < random.nextInt(10); i++) {
				final int randomPrice = random.nextInt(5000);
				final int priceId = ++PRICE_ID_SEQ;
				innerRecordId += random.nextInt(2);
				final PriceRecordInnerRecordSpecific priceRecord = new PriceRecordInnerRecordSpecific(
					priceId, priceId, entityId, innerRecordId, (int) (randomPrice * 1.21), randomPrice
				);
				bitmap.add(priceRecord.entityPrimaryKey());
				priceRecords.add(priceRecord);
				counter++;
				if (counter == priceCount) {
					break;
				}
			}
		}

		return bitmap;
	}

}
