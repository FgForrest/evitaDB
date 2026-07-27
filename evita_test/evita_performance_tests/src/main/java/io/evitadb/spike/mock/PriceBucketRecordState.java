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
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.price.FilteredPriceRecordAccessor;
import io.evitadb.core.query.algebra.price.filteredPriceRecords.FilteredPriceRecords.SortingForm;
import io.evitadb.core.query.algebra.price.filteredPriceRecords.ResolvedFilteredPriceRecords;
import io.evitadb.dataType.array.CompositeObjectArray;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.price.model.priceRecord.PriceRecord;
import lombok.Getter;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * JMH benchmark state providing two disjoint entity-price datasets for the price histogram
 * benchmark ({@link io.evitadb.spike.FormulaCostMeasurement#priceHistogramComputer}).
 *
 * Dataset A contains 70% of the total ({@link #PRICE_COUNT} × 0.7 = 70K prices over ~7K entities)
 * and dataset B contains the remaining 30% (30K prices over ~3K entities), with non-overlapping
 * entity ID ranges. Each dataset provides both a {@link ConstantFormula} wrapping the entity ID
 * bitmap and a {@link FilteredPriceRecordAccessor} backed by {@link MockEntityIdsFormula} for
 * price record access.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@State(Scope.Benchmark)
public class PriceBucketRecordState {
	/** Total number of distinct entities across both datasets. */
	private static final int ENTITY_COUNT = 10_000;
	/** Total number of price records across both datasets. */
	private static final int PRICE_COUNT = 100_000;
	private static final Random random = new Random(42);
	/** Monotonic price ID generator — shared across all instances within the same JVM fork. */
	private static int PRICE_ID_SEQ;
	@Getter private Bitmap entityIdsA;
	@Getter private Bitmap entityIdsB;
	@Getter private List<FilteredPriceRecordAccessor> filteredPriceRecordAccessors;
	@Getter private Formula formulaA;
	@Getter private Formula formulaB;

	/**
	 * Generates two non-overlapping entity-price datasets (70/30 split), creates filtered
	 * price record accessors, and wraps entity IDs in {@link ConstantFormula} instances.
	 */
	@Setup(Level.Trial)
	public void setUp() {
		final CompositeObjectArray<PriceRecord> priceRecordsA = new CompositeObjectArray<>(PriceRecord.class);
		final CompositeObjectArray<PriceRecord> priceRecordsB = new CompositeObjectArray<>(PriceRecord.class);
		this.entityIdsA = generateBitmap((int) (ENTITY_COUNT * 0.7), (int) (PRICE_COUNT * 0.7), 1, priceRecordsA);
		this.entityIdsB = generateBitmap((int) (ENTITY_COUNT * 0.3), (int) (PRICE_COUNT * 0.3), this.entityIdsA.getLast(), priceRecordsB);

		this.filteredPriceRecordAccessors = Arrays.asList(
			new MockEntityIdsFormula(this.entityIdsA, new ResolvedFilteredPriceRecords(priceRecordsA.toArray(), SortingForm.NOT_SORTED)),
			new MockEntityIdsFormula(this.entityIdsB, new ResolvedFilteredPriceRecords(priceRecordsB.toArray(), SortingForm.NOT_SORTED))
		);
		this.formulaA = new ConstantFormula(this.entityIdsA);
		this.formulaB = new ConstantFormula(this.entityIdsB);
	}

	private Bitmap generateBitmap(int entityCount, int priceCount, int startValue, CompositeObjectArray<PriceRecord> priceRecords) {
		final Bitmap bitmap = new BaseBitmap();
		int recId = startValue;
		for (int i = 0; i < priceCount; i++) {
			recId += random.nextInt(5) + 1;
			bitmap.add(recId);
			final int randomPrice = random.nextInt(5000);
			priceRecords.add(
				new PriceRecord(
					++PRICE_ID_SEQ, recId, entityCount + random.nextInt(entityCount), (int) (randomPrice * 1.21), randomPrice
				)
			);
		}

		return bitmap;
	}

}
