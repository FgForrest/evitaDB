/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

package io.evitadb.index.component;

import io.evitadb.core.buffer.TrappedChanges;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.HistogramIndex;
import io.evitadb.index.map.TransactionalMap;

import javax.annotation.Nonnull;

/**
 * {@link IndexComponent} adapter for the per-histogram-name {@link HistogramIndex} map carried by
 * {@link io.evitadb.index.ReducedGroupEntityIndex} and {@link io.evitadb.index.ReferencedTypeEntityIndex}.
 * Each entry stores bucketed histogram data (filter indexes + cardinality tracking) for all locale
 * variants of a single histogram definition.
 *
 * The component:
 *
 * 1. forwards `getModifiedStorageParts` to every wrapped {@link HistogramIndex},
 * 2. asks every wrapped {@link HistogramIndex} to populate the shared {@link EntityIndexManifest} with
 *    its own filter + cardinality storage keys via {@link HistogramIndex#collectStorageKeys},
 * 3. forwards reset/remove-layer calls into every entry via the {@link TransactionalMap} machinery.
 */
public final class HistogramIndexMapComponent implements IndexComponent {

	/**
	 * Backing per-histogram-name map owned by the parent index. Held by reference because the parent
	 * index never swaps the map instance during its lifetime — only the contents change.
	 */
	@Nonnull private final TransactionalMap<String, HistogramIndex> histogramIndexes;
	/**
	 * The parent {@link EntityIndexKey} forwarded into {@link HistogramIndex#collectStorageKeys} so
	 * the histogram can compose its own storage keys.
	 */
	@Nonnull private final EntityIndexKey entityIndexKey;

	/**
	 * @param histogramIndexes the wrapped per-histogram-name map
	 * @param entityIndexKey   the parent index key used to compose storage keys
	 */
	public HistogramIndexMapComponent(
		@Nonnull TransactionalMap<String, HistogramIndex> histogramIndexes,
		@Nonnull EntityIndexKey entityIndexKey
	) {
		this.histogramIndexes = histogramIndexes;
		this.entityIndexKey = entityIndexKey;
	}

	@Override
	public void collectModifiedStorageParts(
		int entityIndexPrimaryKey,
		@Nonnull EntityIndexManifest manifest,
		@Nonnull TrappedChanges trappedChanges
	) {
		// emit dirty storage parts and announce live histogram keys — manifest population is
		// unconditional so a clean histogram still appears in the parent EntityIndexStoragePart
		for (final HistogramIndex histogramIndex : this.histogramIndexes.values()) {
			histogramIndex.getModifiedStorageParts(entityIndexPrimaryKey, trappedChanges);
			histogramIndex.collectStorageKeys(this.entityIndexKey, manifest.getHistogramKeys());
		}
	}

	@Override
	public void resetDirty() {
		// HistogramIndex has no own dirty flag — its sub-structures track their own dirtiness;
		// the parent EntityIndex.dirty bit is reset by the base loop
		for (final HistogramIndex histogramIndex : this.histogramIndexes.values()) {
			histogramIndex.resetDirty();
		}
	}

	@Override
	public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		// TransactionalMap#removeLayer drops its own diff layer AND propagates into every value
		// that is a TransactionalLayerProducer, so per-entry HistogramIndex layers are covered
		this.histogramIndexes.removeLayer(transactionalLayer);
	}

}
