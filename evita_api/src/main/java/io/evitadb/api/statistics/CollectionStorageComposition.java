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

package io.evitadb.api.statistics;

import javax.annotation.Nonnull;
import java.util.Arrays;

/**
 * The {@link CatalogStatisticsComponent#STORAGE_COMPOSITION} component of one entity collection - where this
 * collection's bytes actually go, broken down by storage-part type.
 *
 * See {@link StorageCompositionStatistics} for why the breakdown is measured in bytes rather than record counts, and
 * why no per-type maximum is reported.
 *
 * @param parts one entry per storage-part type present in this collection's data store
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public record CollectionStorageComposition(
	@Nonnull StoragePartUsage[] parts
) {

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		return Arrays.equals(this.parts, ((CollectionStorageComposition) o).parts);
	}

	@Override
	public int hashCode() {
		return Arrays.hashCode(this.parts);
	}

	@Nonnull
	@Override
	public String toString() {
		return "CollectionStorageComposition{parts=" + Arrays.toString(this.parts) + '}';
	}

}
