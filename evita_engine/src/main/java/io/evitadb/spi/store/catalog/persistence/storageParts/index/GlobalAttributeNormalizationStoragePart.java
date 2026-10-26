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


package io.evitadb.spi.store.catalog.persistence.storageParts.index;

import io.evitadb.api.requestResponse.schema.analysis.AnalysisStep;
import io.evitadb.spi.store.catalog.persistence.storageParts.KeyCompressor;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import io.evitadb.utils.CollectionUtils;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.util.List;
import java.util.Map;

/**
 * Carries authoritative built-with rows shared by catalog unique indexes and adopting collections.
 * Only nonempty chains are retained; an absent row means type-level NFD-only normalization. The singleton travels
 * through ordinary trapped changes and contains descriptions rather than executable index functions.
 *
 * @param rows global attribute names mapped to their built-with step lists
 */
public record GlobalAttributeNormalizationStoragePart(
	@Nonnull Map<String, List<AnalysisStep>> rows
) implements StoragePart {
	@Serial private static final long serialVersionUID = 1L;

	/**
	 * Freezes real policies only: an empty list and an absent row both mean NFD-only.
	 *
	 * @param rows built-with global step descriptions
	 */
	public GlobalAttributeNormalizationStoragePart {
		final Map<String, List<AnalysisStep>> copy = CollectionUtils.createHashMap(rows.size());
		rows.forEach((name, steps) -> {
			if (!steps.isEmpty()) {
				copy.put(name, List.copyOf(steps));
			}
		});
		rows = Map.copyOf(copy);
	}

	@Override
	@Nonnull
	public Long getStoragePartPK() {
		return 1L;
	}

	@Override
	public long computeUniquePartIdAndSet(@Nonnull KeyCompressor keyCompressor) {
		return 1L;
	}
}
