/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

package io.evitadb.core.catalog;

import io.evitadb.api.statistics.DurabilityStatistics;
import io.evitadb.spi.store.catalog.persistence.DurabilitySnapshot;

import javax.annotation.Nonnull;

/**
 * Maps the storage layer's {@link DurabilitySnapshot} onto the API's {@link DurabilityStatistics}.
 *
 * The mapping is one-to-one and carries no arithmetic of its own, deliberately: the checkpoint path is the only thing
 * that knows what a checkpoint cost, and re-deriving any of it here would produce a second notion of *how far behind
 * the device this catalog is* that drifts the first time the checkpoint trigger is touched. Same rule that keeps the
 * compaction predicate in {@link FragmentationProjection}'s storage-side counterpart rather than in the engine.
 *
 * The class exists rather than an inline constructor call for the reason {@link StorageSizeProjection} does: an
 * eight-argument positional constructor written out at the call site is where a field silently swaps places with its
 * neighbour.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
final class DurabilityProjection {

	/**
	 * This class is a collection of static projection helpers and must never be instantiated.
	 */
	private DurabilityProjection() {
	}

	/**
	 * Projects the storage layer's durability snapshot onto the API component.
	 *
	 * @param snapshot the measured snapshot
	 * @return the {@link io.evitadb.api.statistics.CatalogStatisticsComponent#DURABILITY} component
	 */
	@Nonnull
	static DurabilityStatistics toDurabilityStatistics(@Nonnull DurabilitySnapshot snapshot) {
		return new DurabilityStatistics(
			snapshot.checkpointIntervalMillis(),
			snapshot.lastCadenceMillis(),
			snapshot.lastFenceDepthMillis(),
			snapshot.lastFilesForced(),
			snapshot.lastForceDurationMillis(),
			snapshot.checkpointsCompleted(),
			snapshot.lastCheckpointAt(),
			snapshot.countingSince()
		);
	}

}
