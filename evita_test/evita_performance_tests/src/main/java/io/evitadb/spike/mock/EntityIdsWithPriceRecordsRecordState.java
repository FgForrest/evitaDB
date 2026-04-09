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
 * JMH benchmark state providing three entity ID bitmaps with associated price records, combined
 * into an {@link OrFormula} tree via {@link MockEntityIdsFormula} leaves. Used by
 * price termination benchmarks that operate on entity-level IDs
 * ({@link io.evitadb.spike.FormulaCostMeasurement#plainPriceTermination},
 * {@link io.evitadb.spike.FormulaCostMeasurement#plainPriceTerminationWithPriceFilter}).
 *
 * Each of the three datasets generates {@link #PRICE_COUNT} price records distributed randomly
 * across {@link #ENTITY_COUNT} entities. Price records use {@link PriceRecordInnerRecordSpecific}
 * with inner record IDs offset beyond the entity ID range. The {@link OrFormula} is pre-computed
 * during setup so that benchmark iterations measure only the termination formula cost, not the
 * underlying bitmap union.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@State(Scope.Benchmark)
public class EntityIdsWithPriceRecordsRecordState {
	/** Number of distinct entities to distribute prices across. */
	private static final int ENTITY_COUNT = 10_000;
	/** Number of price records per dataset (3 datasets total = 300K records). */
	private static final int PRICE_COUNT = 100_000;
	private static final Random random = new Random(42);
	/** Monotonic price ID generator — shared across all instances within the same JVM fork. */
	private static int PRICE_ID_SEQ;

	@Getter private PriceRecordContract[] entitiesPriceRecordsA;
	@Getter private PriceRecordContract[] entitiesPriceRecordsB;
	@Getter private PriceRecordContract[] entitiesPriceRecordsC;
	@Getter private Bitmap entitiesA;
	@Getter private Bitmap entitiesB;
	@Getter private Bitmap entitiesC;
	@Getter private Formula formula;

	/**
	 * Generates three entity-price datasets, wraps each in a {@link MockEntityIdsFormula},
	 * combines them via {@link OrFormula}, and pre-computes the union bitmap.
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
			new MockEntityIdsFormula(this.entitiesC, new ResolvedFilteredPriceRecords(this.entitiesPriceRecordsC, SortingForm.NOT_SORTED))
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
