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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * State class for attribute-related benchmarks.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public class RestArtificialAttributeBenchmarkState extends RestArtificialFullDatabaseBenchmarkState
	implements RandomQueryGenerator, EvitaCatalogReusableSetup {

	/**
	 * This set contains names of all sortable attributes of the entity
	 */
	@Getter private final Set<String> sortableAttributes = new HashSet<>();
	/**
	 * Map contains set of all filterable attributes with statistics about them, that could be used to create random queries.
	 */
	@Getter private final Map<String, AttributeStatistics> filterableAttributes = new HashMap<>();
	/**
	 * Set contains names of all filterable attributes that has type extending {@link Number}.
	 */
	@Getter private final Set<String> numericFilterableAttributes = new HashSet<>();
	/**
	 * List contains set of all category ids available.
	 */
	@Getter private final List<Integer> categoryIds = new ArrayList<>();

	@Override
	protected SealedEntitySchema processSchema(SealedEntitySchema schema) {
		if (schema.getName().equals(Entities.PRODUCT)) {
			this.productSchema = schema;
			schema.getAttributes()
				.values()
				.forEach(it -> {
					if (it.isSortable()) {
						this.sortableAttributes.add(it.getName());
					}
					if ((it.isFilterable() || it.isUnique()) && it.isNullable()) {
						this.filterableAttributes.put(it.getName(), new AttributeStatistics(it));
					}
					if (it.isFilterable() && Number.class.isAssignableFrom(it.getType())) {
						this.numericFilterableAttributes.add(it.getName());
					}
				});
		}
		return schema;
	}

	@Override
	protected void processEntity(SealedEntity entity) {
		if (entity.getType().equals(Entities.PRODUCT)) {
			updateAttributeStatistics(entity, getRandom(), this.filterableAttributes);
		} else if (entity.getType().equals(Entities.CATEGORY)) {
			this.categoryIds.add(entity.getPrimaryKey());
		}
	}

}
