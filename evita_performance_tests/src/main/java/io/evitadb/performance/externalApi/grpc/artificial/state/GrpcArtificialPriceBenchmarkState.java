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

package io.evitadb.performance.externalApi.grpc.artificial.state;

import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.schema.SealedEntitySchema;
import io.evitadb.performance.externalApi.grpc.artificial.GrpcArtificialFullDatabaseBenchmarkState;
import io.evitadb.performance.generators.RandomQueryGenerator;
import io.evitadb.performance.setup.EvitaCatalogReusableSetup;
import io.evitadb.test.Entities;
import lombok.Getter;

import java.util.ArrayList;
import java.util.List;

/**
 * Base state class for price-related benchmarks.
 * See benchmark description on the method.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public class GrpcArtificialPriceBenchmarkState extends GrpcArtificialFullDatabaseBenchmarkState
	implements RandomQueryGenerator, EvitaCatalogReusableSetup {

	/**
	 * Map contains set of all filterable attributes with statistics about them, that could be used to create random queries.
	 */
	@Getter private final GlobalPriceStatistics priceStatistics = new GlobalPriceStatistics();
	/**
	 * List contains set of all category ids available.
	 */
	@Getter private final List<Integer> categoryIds = new ArrayList<>();

	@Override
	protected SealedEntitySchema processSchema(SealedEntitySchema schema) {
		if (schema.getName().equals(Entities.PRODUCT)) {
			this.productSchema = schema;
		}
		return schema;
	}

	@Override
	protected void processEntity(SealedEntity entity) {
		if (entity.getType().equals(Entities.PRODUCT)) {
			updatePriceStatistics(entity, getRandom(), priceStatistics);
		} else if (entity.getType().equals(Entities.CATEGORY)) {
			categoryIds.add(entity.getPrimaryKey());
		}
	}

}
