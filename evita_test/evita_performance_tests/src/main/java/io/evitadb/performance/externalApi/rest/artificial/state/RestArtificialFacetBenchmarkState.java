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

package io.evitadb.performance.externalApi.rest.artificial.state;

import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.schema.SealedEntitySchema;
import io.evitadb.performance.externalApi.rest.artificial.RestArtificialFullDatabaseBenchmarkState;
import io.evitadb.performance.generators.RandomQueryGenerator;
import io.evitadb.performance.setup.EvitaCatalogReusableSetup;
import io.evitadb.test.Entities;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * State class for facet-related benchmarks.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public class RestArtificialFacetBenchmarkState extends RestArtificialFullDatabaseBenchmarkState
	implements RandomQueryGenerator, EvitaCatalogReusableSetup {

	/**
	 * Map contains set of all faceted referenced entities, that could be used to create random queries.
	 */
	@Getter private final Map<String, Set<Integer>> facetedReferences = new LinkedHashMap<>();
	/**
	 * Map contains relation between facet and its group for all faceted entity types.
	 */
	@Getter private final Map<String, Map<Integer, Integer>> facetGroupsIndex = new LinkedHashMap<>();
	/**
	 * List contains set of all category ids available.
	 */
	@Getter private final List<Integer> categoryIds = new ArrayList<>();

	@Override
	protected SealedEntitySchema processSchema(@Nonnull SealedEntitySchema schema) {
		if (schema.getName().equals(Entities.PRODUCT)) {
			this.productSchema = schema;
			schema.getReferences()
				.values()
				.forEach(it -> {
					if (it.isFaceted()) {
						this.facetedReferences.put(it.getReferencedEntityType(), new HashSet<>());
						this.facetGroupsIndex.put(it.getReferencedEntityType(), new HashMap<>());
					}
				});
		}
		return schema;
	}

	@Override
	protected void processEntity(@Nonnull SealedEntity entity) {
		if (entity.getType().equals(Entities.PRODUCT)) {
			updateFacetStatistics(entity, this.facetedReferences, this.facetGroupsIndex);
		} else if (entity.getType().equals(Entities.CATEGORY)) {
			this.categoryIds.add(entity.getPrimaryKey());
		}
	}

}
