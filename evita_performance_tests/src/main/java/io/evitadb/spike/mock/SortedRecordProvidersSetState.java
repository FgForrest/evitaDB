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
 *   https://github.com/FgForrest/evitaDB/blob/master/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.spike.mock;

import io.evitadb.core.query.sort.SortedRecordsSupplierFactory.SortedRecordsProvider;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.spike.FormulaCostMeasurement;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.CollectionUtils;
import lombok.Getter;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

import java.util.HashSet;
import java.util.Random;

/**
 * State object for {@link FormulaCostMeasurement#mergedSortedRecordsSupplier(SortedRecordProvidersSetState, Blackhole)}
 * benchmark.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@State(Scope.Benchmark)
public class SortedRecordProvidersSetState {
	private static final int ENTITY_COUNT = 100_000;
	private static final int PROVIDER_COUNT = 10;
	private static final Random random = new Random(42);
	@Getter private SortedRecordsProvider[] providers;

	/**
	 * This setup is called once for each `valueCount`.
	 */
	@Setup(Level.Trial)
	public void setUp() {
		providers = new SortedRecordsProvider[PROVIDER_COUNT];

		for(int i = 0; i < PROVIDER_COUNT; i++) {
			int[] sortedRecordIds = new int[ENTITY_COUNT / PROVIDER_COUNT];
			final HashSet<Object> presentIds = CollectionUtils.createHashSet(sortedRecordIds.length);
			int peak = 0;
			do {
				final int entityId = random.nextInt(ENTITY_COUNT);
				if (!presentIds.contains(entityId)) {
					sortedRecordIds[peak++] = entityId;
					presentIds.add(entityId);
				}
			} while (peak < sortedRecordIds.length - 1);

			providers[i] = new MockSortedRecordsSupplier(sortedRecordIds);
		}
	}

	/**
	 * Mock implementation of {@link SortedRecordsProvider} for testing purposes.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
	 */
	public class MockSortedRecordsSupplier implements SortedRecordsProvider {
		@Getter private final RoaringBitmapBackedBitmap allRecords;
		@Getter private final int[] sortedRecordIds;
		@Getter private final int[] recordPositions;

		public MockSortedRecordsSupplier(int... sortedRecordIds) {
			this.sortedRecordIds = sortedRecordIds;
			this.allRecords = new BaseBitmap(sortedRecordIds);
			this.recordPositions = new int[sortedRecordIds.length];
			for (int i = 0; i < sortedRecordIds.length; i++) {
				this.recordPositions[i] = i;
			}
			ArrayUtils.sortSecondAlongFirstArray(this.sortedRecordIds, this.recordPositions);
		}

		@Override
		public int getRecordCount() {
			return sortedRecordIds.length;
		}

	}

}
