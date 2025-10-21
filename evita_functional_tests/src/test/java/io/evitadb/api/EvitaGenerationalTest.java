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

package io.evitadb.api;

import com.github.javafaker.Faker;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.SealedEntitySchema;
import io.evitadb.core.Evita;
import io.evitadb.test.Entities;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.test.builder.CopyExistingEntityBuilder;
import io.evitadb.test.duration.TimeArgumentProvider;
import io.evitadb.test.duration.TimeArgumentProvider.GenerationalTestInput;
import io.evitadb.test.duration.TimeBoundedTestSupport;
import io.evitadb.test.generator.DataGenerator;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;
import lombok.extern.apachecommons.CommonsLog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.io.Serializable;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Stream;

import static io.evitadb.api.query.QueryConstraints.entityFetchAllContent;
import static java.util.Optional.ofNullable;
import static org.apache.commons.io.FileUtils.byteCountToDisplaySize;
import static org.apache.commons.io.FileUtils.sizeOfDirectory;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * This test contains various integration tests for {@link Evita}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@CommonsLog
class EvitaGenerationalTest implements EvitaTestSupport, TimeBoundedTestSupport {
	public static final String ATTRIBUTE_CODE = "code";
	/**
	 * Seed for data generation.
	 */
	private static final long SEED = 10;
	/**
	 * Count of the product that will exist in the database BEFORE the test starts.
	 */
	private static final int INITIAL_COUNT_OF_PRODUCTS = 1000;
	public static final String DIRECTORY_EVITA_GENERATIONAL_TEST = "evitaGenerationalTest";
	public static final String DIRECTORY_EVITA_GENERATIONAL_TEST_EXPORT = "evitaGenerationalTest_export";
	/**
	 * Instance of the data generator that is used for randomizing artificial test data.
	 */
	protected final DataGenerator dataGenerator = new DataGenerator();
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
	 * Created randomized product schema.
	 */
	protected SealedEntitySchema productSchema;
	/**
	 * Iterator that infinitely produces new artificial products.
	 */
	protected Iterator<EntityBuilder> productIterator;
	/**
	 * Evita instance.
	 */
	private Evita evita;
	/**
	 * Functions allows to pseudo randomly modify existing product contents.
	 */
	private Function<SealedEntity, EntityBuilder> modificationFunction;

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
		final EntityReferenceContract insertedEntity = session.upsertEntity(it);
		generatedEntities.compute(
			insertedEntity.getType(),
			(serializable, existing) -> ofNullable(existing).orElse(0) + 1
		);
	}

	@BeforeEach
	void setUp() throws IOException {
		cleanTestSubDirectory(DIRECTORY_EVITA_GENERATIONAL_TEST);
		cleanTestSubDirectory(DIRECTORY_EVITA_GENERATIONAL_TEST_EXPORT);
		this.dataGenerator.clear();
		this.generatedEntities.clear();
		final String catalogName = "testCatalog";
		// prepare database
		this.evita = new Evita(
			getEvitaConfiguration()
		);
		this.evita.defineCatalog(TEST_CATALOG);
		// create bunch or entities for referencing in products
		this.evita.updateCatalog(
			catalogName,
			session -> {
				session.getCatalogSchema()
					.openForWrite()
					.withAttribute(ATTRIBUTE_CODE, String.class, thatIs -> thatIs.uniqueGlobally())
					.updateVia(session);

				this.dataGenerator.generateEntities(
						this.dataGenerator.getSampleBrandSchema(session),
						this.randomEntityPicker,
						SEED
					)
					.limit(5)
					.forEach(it -> createEntity(session, this.generatedEntities, it));

				this.dataGenerator.generateEntities(
						this.dataGenerator.getSampleCategorySchema(session),
						this.randomEntityPicker,
						SEED
					)
					.limit(10)
					.forEach(it -> createEntity(session, this.generatedEntities, it));

				this.dataGenerator.generateEntities(
						this.dataGenerator.getSamplePriceListSchema(session),
						this.randomEntityPicker,
						SEED
					)
					.limit(4)
					.forEach(it -> createEntity(session, this.generatedEntities, it));

				this.dataGenerator.generateEntities(
						this.dataGenerator.getSampleStoreSchema(session),
						this.randomEntityPicker,
						SEED
					)
					.limit(12)
					.forEach(it -> createEntity(session, this.generatedEntities, it));

				this.dataGenerator.generateEntities(
						this.dataGenerator.getSampleParameterGroupSchema(session),
						this.randomEntityPicker,
						SEED
					)
					.limit(20)
					.forEach(it -> createEntity(session, this.generatedEntities, it));

				this.dataGenerator.generateEntities(
						this.dataGenerator.getSampleParameterSchema(session),
						this.randomEntityPicker,
						SEED
					)
					.limit(200)
					.forEach(it -> createEntity(session, this.generatedEntities, it));

				this.productSchema = this.dataGenerator.getSampleProductSchema(
					session,
					entitySchemaBuilder -> {
						entitySchemaBuilder
							.withoutGeneratedPrimaryKey()
							.withGlobalAttribute(ATTRIBUTE_CODE)
							.withReferenceToEntity(
								Entities.PARAMETER,
								Entities.PARAMETER,
								Cardinality.ZERO_OR_MORE,
								thatIs -> thatIs.faceted().withGroupTypeRelatedToEntity(Entities.PARAMETER_GROUP)
							);
					}
				);
				this.dataGenerator.generateEntities(
						this.productSchema,
						this.randomEntityPicker,
						SEED
					)
					.limit(INITIAL_COUNT_OF_PRODUCTS)
					.forEach(session::upsertEntity);

				session.goLiveAndClose();
			}
		);
		// create product iterator
		this.productIterator = getProductStream().iterator();
	}

	@AfterEach
	void tearDown() throws IOException {
		this.evita.close();
		cleanTestSubDirectory(DIRECTORY_EVITA_GENERATIONAL_TEST);
		cleanTestSubDirectory(DIRECTORY_EVITA_GENERATIONAL_TEST_EXPORT);
	}

	@Test
	void loadTest() {
		this.evita.close();

		this.evita = new Evita(
			getEvitaConfiguration()
		);

		assertNotNull(this.evita);
	}

	@ParameterizedTest(name = "Evita should survive generational randomized test upserting the values in transaction")
	@Tag(LONG_RUNNING_TEST)
	@ArgumentsSource(TimeArgumentProvider.class)
	void generationalTransactionalModificationProofTest(GenerationalTestInput input) {
		final int maximumAmountOfRemovedEntities = 50;
		final Map<Integer, SealedEntity> removedEntities = CollectionUtils.createHashMap(maximumAmountOfRemovedEntities);

		final TestState finalState = runFor(
			input,
			100,
			new TestState(0, 0),
			(random, testState) -> {
				int generation = testState.generation();
				int updateCounter = testState.updateCounter();
				// create product modificator
				if (this.modificationFunction == null) {
					this.modificationFunction = this.dataGenerator.createModificationFunction(this.randomEntityPicker, random);
				}

				String operation = null;
				try (final EvitaSessionContract session = this.evita.createReadWriteSession(TEST_CATALOG)) {
					final int iterations = random.nextInt(500);
					for (int i = 0; i < iterations; i++) {
						int primaryKey;
						do {
							primaryKey = random.nextInt(INITIAL_COUNT_OF_PRODUCTS) + 1;
						} while (removedEntities.containsKey(primaryKey));

						if (random.nextInt(10) == 0 && removedEntities.size() < maximumAmountOfRemovedEntities) {
							int productId = primaryKey;
							removedEntities.put(
								primaryKey,
								session.getEntity(
									Entities.PRODUCT,
									primaryKey,
									entityFetchAllContent()
								)
									.orElseThrow(
										() -> new IllegalStateException("Product with primary key " + productId + " was not found.")
									)
							);
							operation = "removal of " + primaryKey;
							session.deleteEntity(Entities.PRODUCT, primaryKey);
						} else if (random.nextInt(10) == 0 && removedEntities.size() > 10) {
							final SealedEntity entityToRestore = pickRandom(random, removedEntities.values());
							removedEntities.remove(entityToRestore.getPrimaryKey());
							operation = "restoring of " + entityToRestore.getPrimaryKey();
							session.upsertEntity(
								new CopyExistingEntityBuilder(entityToRestore)
							);
							assertNotNull(
								session.getEntity(
									Entities.PRODUCT,
									entityToRestore.getPrimaryKey(),
									entityFetchAllContent()
								)
							);
						} else {
							operation = "modification of " + primaryKey;
							final SealedEntity existingEntity = session.getEntity(
								Entities.PRODUCT,
								primaryKey,
								entityFetchAllContent()
							).orElseThrow();
							session.upsertEntity(
								this.modificationFunction.apply(existingEntity)
							);
						}
						updateCounter++;
					}
				} catch (Exception ex) {
					fail("Failed to execute " + operation + ": " + ex.getMessage(), ex);
				}

				generation++;

				if (generation % 3 == 0) {
					// reload EVITA entirely
					this.evita.close();
					System.out.println("Survived " + generation + " generations, size on disk is " + byteCountToDisplaySize(sizeOfDirectory(getTestDirectory().toFile())));
					this.evita = new Evita(
						getEvitaConfiguration()
					);
				}

				return new TestState(
					generation, updateCounter
				);
			}
		);
		System.out.println(
			"Finished " + finalState.generation() + " generations (" + finalState.updateCounter() + " updates), size on disk is " +
				byteCountToDisplaySize(sizeOfDirectory(getTestDirectory().toFile()))
		);
	}

	@Nonnull
	private EvitaConfiguration getEvitaConfiguration() {
		return EvitaConfiguration.builder()
			.storage(
				StorageOptions.builder()
					.storageDirectory(getTestDirectory().resolve(DIRECTORY_EVITA_GENERATIONAL_TEST))
					.exportDirectory(getTestDirectory().resolve(DIRECTORY_EVITA_GENERATIONAL_TEST_EXPORT))
					.build()
			)
			.build();
	}

	/**
	 * Returns random element from the set.
	 */
	@Nonnull
	private static <T> T pickRandom(@Nonnull Random random, @Nonnull Collection<T> theSet) {
		Assert.isTrue(!theSet.isEmpty(), "There are no values to choose from!");
		final int index = theSet.size() == 1 ? 0 : random.nextInt(theSet.size() - 1) + 1;
		final Iterator<T> it = theSet.iterator();
		for (int i = 0; i < index; i++) {
			it.next();
		}
		return it.next();
	}

	/**
	 * Generational test state.
	 *
	 * @param generation    total count of generations that were correctly created
	 * @param updateCounter simple counter for measuring total product count updated in the database.
	 */
	private record TestState(
		int generation,
		int updateCounter
	) {
	}
}
