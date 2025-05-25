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

package io.evitadb.performance.artificial;

import com.github.javafaker.Faker;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.schema.SealedEntitySchema;
import io.evitadb.core.Evita;
import io.evitadb.performance.setup.CatalogSetup;
import io.evitadb.test.TestConstants;
import io.evitadb.test.generator.DataGenerator;
import lombok.Getter;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static java.util.Optional.ofNullable;

/**
 * Base state class for all artifical based benchmarks.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@State(Scope.Benchmark)
public abstract class AbstractArtificialBenchmarkState<S> implements TestConstants, CatalogSetup {
	/**
	 * Fixed seed allows to replay the same randomized data multiple times.
	 */
	public static final long SEED = 42;
	/**
	 * Instance of the data generator that is used for randomizing artificial test data.
	 */
	protected final DataGenerator dataGenerator = new DataGenerator.Builder()
		.withPriceInnerRecordHandlingGenerator(
			faker -> {
				final int selectedOption = faker.random().nextInt(10);
				if (selectedOption < 6) {
					// 60% of basic products
					return PriceInnerRecordHandling.NONE;
				} else if (selectedOption < 9) {
					// 30% of variant products
					return PriceInnerRecordHandling.LOWEST_PRICE;
				} else {
					// 10% of set products
					return PriceInnerRecordHandling.SUM;
				}
			}
		)
		.build();

	/**
	 * Index of created entities that allows to retrieve referenced entities when creating product.
	 */
	protected final Map<Serializable, Integer> generatedEntities = new HashMap<>();
	/**
	 * Function allowing to pseudo randomly pick referenced entity for the product.
	 */
	protected final BiFunction<String, Faker, Integer> randomEntityPicker = (entityType, faker) -> {
		final Integer entityCount = this.generatedEntities.computeIfAbsent(entityType, serializable -> 0);
		final int primaryKey = entityCount == 0 ? 0 : faker.random().nextInt(1, entityCount);
		return primaryKey == 0 ? null : primaryKey;
	};
	/**
	 * Open read-write session that can be used for upserting data.
	 */
	protected final ThreadLocal<S> session = new ThreadLocal<>();
	/**
	 * Pseudo-randomizer for picking random entities to fetch.
	 */
	@Getter private final Random random = new Random(SEED);
	/**
	 * Created randomized product schema.
	 */
	@Getter
	protected SealedEntitySchema productSchema;
	/**
	 * Iterator that infinitely produces new artificial products.
	 */
	@Getter protected Iterator<EntityBuilder> productIterator;
	/**
	 * Open Evita instance.
	 */
	@Getter protected Evita evita;

	/**
	 * Returns an existing session unique for the thread or creates new one.
	 */
	public abstract S getSession();

	/**
	 * Returns an existing session unique for the thread or creates new one.
	 */
	public abstract S getSession(Supplier<S> creatorFct);

	/**
	 * Returns name of the test catalog.
	 */
	protected String getCatalogName() {
		return TEST_CATALOG;
	}

	/**
	 * Creates new product stream for the iteration.
	 */
	protected Stream<EntityBuilder> getProductStream() {
		return this.dataGenerator.generateEntities(
			this.productSchema,
			this.randomEntityPicker,
			SEED
		);
	}

	/**
	 * Creates new entity and inserts it into the index.
	 */
	protected void createEntity(@Nonnull EvitaSessionContract session, @Nonnull Map<Serializable, Integer> generatedEntities, @Nonnull EntityBuilder it) {
		final EntityReferenceContract<?> insertedEntity = session.upsertEntity(it);
		generatedEntities.compute(
			insertedEntity.getType(),
			(serializable, existing) -> ofNullable(existing).orElse(0) + 1
		);
	}

}
