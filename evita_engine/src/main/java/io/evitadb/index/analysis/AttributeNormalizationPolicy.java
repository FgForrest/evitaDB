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


package io.evitadb.index.analysis;

import io.evitadb.api.requestResponse.schema.analysis.AnalysisStep;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.utils.CollectionUtils;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Immutable collection-owned descriptions of the functions that built its String keys. Keys omit locale and scope.
 * Compound bindings retain element identity even when the declaration is removed or its element order changes.
 * Global attribute names are retained while their collection footprints survive, including orphaned indexes.
 *
 * @param rows scope/locale-free built-with non-empty step lists
 * @param compounds retained ordered element identities
 * @param globalAttributes adopted or retained global identities
 */
public record AttributeNormalizationPolicy(
	@Nonnull Map<AttributeIndexKey, List<AnalysisStep>> rows,
	@Nonnull Map<AttributeIndexKey, List<ElementBinding>> compounds,
	@Nonnull Set<String> globalAttributes
) {
	/** Empty metadata is the NFD-only policy of catalogs written before String normalization existed. */
	public static final AttributeNormalizationPolicy EMPTY =
		new AttributeNormalizationPolicy(Map.of(), Map.of(), Set.of());

	/**
	 * Makes the policy safe to share between committed owners and borrowed index resolvers.
	 *
	 * @param rows built-with non-empty step lists; empty lists are omitted
	 * @param compounds retained ordered element identities
	 * @param globalAttributes adopted or retained global identities
	 */
	public AttributeNormalizationPolicy {
		final Map<AttributeIndexKey, List<AnalysisStep>> rowCopy = CollectionUtils.createHashMap(rows.size());
		rows.forEach((key, steps) -> {
			if (!steps.isEmpty()) {
				rowCopy.put(key, List.copyOf(steps));
			}
		});
		rows = Map.copyOf(rowCopy);
		final Map<AttributeIndexKey, List<ElementBinding>> compoundCopy =
			CollectionUtils.createHashMap(compounds.size());
		compounds.forEach((key, elements) -> compoundCopy.put(key, List.copyOf(elements)));
		compounds = Map.copyOf(compoundCopy);
		globalAttributes = Set.copyOf(globalAttributes);
	}

	/**
	 * Persisted identity and folding locale of one compound element; executable functions are never persisted.
	 *
	 * @param attributeName retained element name
	 * @param type frozen plain value type
	 * @param localized whether String folding uses the compound locale
	 * @param global whether the catalog owns this element's row
	 */
	public record ElementBinding(
		@Nonnull String attributeName, @Nonnull Class<?> type, boolean localized, boolean global
	) {
	}
}
