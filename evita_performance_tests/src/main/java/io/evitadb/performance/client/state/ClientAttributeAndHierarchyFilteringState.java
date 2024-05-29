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

package io.evitadb.performance.client.state;

import io.evitadb.api.query.Query;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.schema.SealedEntitySchema;
import io.evitadb.performance.client.ClientDataFullDatabaseState;
import io.evitadb.performance.generators.RandomQueryGenerator;
import lombok.Getter;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Setup;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Base state class for attributeAndHierarchyFiltering tests on client data set.
 * See benchmark description on the method.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public abstract class ClientAttributeAndHierarchyFilteringState extends ClientDataFullDatabaseState
	implements RandomQueryGenerator {

	/**
	 * Senesi entity type of product.
	 */
	public static final String PRODUCT_ENTITY_TYPE = "Product";
	/**
	 * Senesi entity type of category.
	 */
	public static final String CATEGORY_ENTITY_TYPE = "Category";
	/**
	 * Pseudo-randomizer for picking random entities to fetch.
	 */
	private final Random random = new Random(SEED);
	/**
	 * This set contains names of all sortable attributes of the entity
	 */
	private final Set<String> sortableAttributes = new HashSet<>();
	/**
	 * Map contains set of all filterable attributes with statistics about them, that could be used to create random queries.
	 */
	private final Map<String, AttributeStatistics> filterableAttributes = new HashMap<>();
	/**
	 * List contains set of all category ids available.
	 */
	private final List<Integer> categoryIds = new ArrayList<>();
	/**
	 * Query prepared for the measured invocation.
	 */
	@Getter protected Query query;

	/**
	 * Prepares artificial product for the next operation that is measured in the benchmark.
	 */
	@Setup(Level.Invocation)
	public void prepareCall() {
		this.query = generateRandomHierarchyQuery(
			generateRandomAttributeQuery(random, productSchema, filterableAttributes, sortableAttributes),
			random, categoryIds, CATEGORY_ENTITY_TYPE
		);
	}

	@Override
	protected void processSchema(@Nonnull SealedEntitySchema schema) {
		if (schema.getName().equals(PRODUCT_ENTITY_TYPE)) {
			this.productSchema = schema;
			schema.getAttributes()
				.values()
				.forEach(it -> {
					if (it.isSortable()) {
						this.sortableAttributes.add(it.getName());
					}
					if (!it.getName().startsWith("validity::") && (it.isFilterable() || it.isUnique())) {
						this.filterableAttributes.put(it.getName(), new AttributeStatistics(it));
					}
				});
		}
	}

	@Override
	protected void processEntity(@Nonnull SealedEntity entity) {
		if (entity.getType().equals(PRODUCT_ENTITY_TYPE)) {
			updateAttributeStatistics(entity, random, filterableAttributes);
		} else if (entity.getType().equals(CATEGORY_ENTITY_TYPE)) {
			categoryIds.add(entity.getPrimaryKey());
		}
	}

}
