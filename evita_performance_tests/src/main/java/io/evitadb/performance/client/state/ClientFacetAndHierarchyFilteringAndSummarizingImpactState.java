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

package io.evitadb.performance.client.state;

import io.evitadb.api.query.Query;
import io.evitadb.api.query.require.FacetStatisticsDepth;
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
 * Base state class for facetAndHierarchyFilteringAndSummarizingImpact tests on client data set.
 * See benchmark description on the method.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public abstract class ClientFacetAndHierarchyFilteringAndSummarizingImpactState extends ClientDataFullDatabaseState
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
	 * Map contains set of all filterable attributes with statistics about them, that could be used to create random queries.
	 */
	private final Map<String, Set<Integer>> facetedReferences = new HashMap<>();
	/**
	 * Map contains relation betwen facet and its group for all faceted entity types.
	 */
	private final Map<String, Map<Integer, Integer>> facetGroupsIndex = new HashMap<>();
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
			generateRandomFacetSummaryQuery(
				generateRandomFacetQuery(this.random, this.productSchema, this.facetedReferences),
				this.random, this.productSchema, FacetStatisticsDepth.IMPACT, this.facetGroupsIndex
			),
			this.random, this.categoryIds, CATEGORY_ENTITY_TYPE
		);
	}

	@Override
	protected void processSchema(@Nonnull SealedEntitySchema schema) {
		if (schema.getName().equals(PRODUCT_ENTITY_TYPE)) {
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
	}

	@Override
	protected void processEntity(@Nonnull SealedEntity entity) {
		if (entity.getType().equals(PRODUCT_ENTITY_TYPE)) {
			updateFacetStatistics(entity, this.facetedReferences, this.facetGroupsIndex);
		} else if (entity.getType().equals(CATEGORY_ENTITY_TYPE)) {
			this.categoryIds.add(entity.getPrimaryKey());
		}
	}

}
