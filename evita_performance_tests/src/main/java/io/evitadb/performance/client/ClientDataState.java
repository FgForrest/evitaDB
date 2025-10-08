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

package io.evitadb.performance.client;

import com.github.javafaker.Faker;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.schema.SealedEntitySchema;
import io.evitadb.core.Evita;
import io.evitadb.performance.setup.CatalogSetup;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.test.TestConstants;
import io.evitadb.test.generator.DataGenerator;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Base state class for all client database based benchmarks.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public abstract class ClientDataState implements CatalogSetup, TestConstants, EvitaTestSupport {
	/**
	 * Fixed seed allows to replay the same randomized data multiple times.
	 */
	public static final long SEED = 40;
	/**
	 * Instance of the data generator that is used for randomizing artificial test data.
	 */
	protected final DataGenerator dataGenerator = new DataGenerator();
	/**
	 * Index of created entities that allows to retrieve referenced entities when creating product.
	 */
	protected final Map<Serializable, List<Integer>> generatedEntities = new HashMap<>();
	/**
	 * Function allowing to pseudo randomly pick referenced entity for the product.
	 */
	protected final BiFunction<String, Faker, Integer> randomEntityPicker = (entityType, faker) -> {
		final List<Integer> entityIndex = this.generatedEntities.computeIfAbsent(entityType, serializable -> new LinkedList<>());
		final int entityCount = entityIndex.size();
		if (entityCount == 0) {
			return null;
		} else {
			final int index = faker.random().nextInt(0, entityCount - 1);
			return entityIndex.get(index);
		}
	};
	/**
	 * Created randomized product schema.
	 */
	protected SealedEntitySchema productSchema;
	/**
	 * Iterator that produces products from Senesi catalog.
	 */
	protected Iterator<EntityBuilder> productIterator;
	/**
	 * Open read-write Evita instance.
	 */
	@Getter protected Evita evita;
	/**
	 * Open read-write session that can be used for upserting data.
	 */
	@Getter protected EvitaSessionContract session;
	/**
	 * Prepared product with randomized content ready to be upserted to DB.
	 */
	@Getter protected EntityBuilder product;

	/**
	 * Returns name of the test catalog.
	 */
	protected abstract String getCatalogName();

	/**
	 * Creates new entity and inserts it into the index.
	 */
	protected EntityReference createEntity(@Nonnull EvitaSessionContract session, @Nonnull EntityBuilder it) {
		return session.upsertEntity(it);
	}

}
