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
import io.evitadb.api.requestResponse.schema.SealedEntitySchema;
import io.evitadb.performance.client.ClientDataFullDatabaseState;
import io.evitadb.performance.generators.RandomQueryGenerator;
import io.evitadb.utils.Assert;
import lombok.Getter;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Setup;

import javax.annotation.Nonnull;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Random;
import java.util.Set;

/**
 * Base state class for hierarchyStatisticsComputation tests on client data set.
 * See benchmark description on the method.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public abstract class ClientHierarchyStatisticsComputationState extends ClientDataFullDatabaseState
	implements RandomQueryGenerator {

	/**
	 * Senesi entity type of product.
	 */
	public static final String PRODUCT_ENTITY_TYPE = "Product";
	/**
	 * Pseudo-randomizer for picking random entities to fetch.
	 */
	private final Random random = new Random(SEED);
	/**
	 * Set contains all `entityTypes` that are hierarchical and are referenced from product entity.
	 */
	private final Set<String> referencedHierarchicalEntities = new LinkedHashSet<>();
	/**
	 * Set contains all `entityTypes` that are hierarchical.
	 */
	private final Set<String> hierarchicalEntities = new HashSet<>();
	/**
	 * Query prepared for the measured invocation.
	 */
	@Getter protected Query query;

	/**
	 * Prepares artificial product for the next operation that is measured in the benchmark.
	 */
	@Setup(Level.Invocation)
	public void prepareCall() {
		if (this.referencedHierarchicalEntities.isEmpty()) {
			this.productSchema.getReferences()
				.values()
				.forEach(it -> {
					if (it.isReferencedEntityTypeManaged() && this.hierarchicalEntities.contains(it.getReferencedEntityType())) {
						this.referencedHierarchicalEntities.add(it.getReferencedEntityType());
					}
				});
			Assert.isTrue(!this.referencedHierarchicalEntities.isEmpty(), "No referenced entity is hierarchical!");
		}
		this.query = generateRandomParentSummaryQuery(
			this.random, this.productSchema, this.referencedHierarchicalEntities
		);
	}

	@Override
	protected void processSchema(@Nonnull SealedEntitySchema schema) {
		if (schema.getName().equals(PRODUCT_ENTITY_TYPE)) {
			this.productSchema = schema;
		} else {
			if (schema.isWithHierarchy()) {
				this.hierarchicalEntities.add(schema.getName());
			}
		}
	}

}
