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

package io.evitadb.performance.generators;

import com.github.javafaker.Faker;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.query.Query;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.EntitySchemaEditor.EntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.SealedEntitySchema;
import io.evitadb.core.Evita;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.function.TriConsumer;
import io.evitadb.test.Entities;
import io.evitadb.test.generator.DataGenerator;
import io.evitadb.utils.StringUtils;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.entityFetchAllAnd;
import static io.evitadb.api.query.QueryConstraints.page;
import static io.evitadb.api.query.QueryConstraints.require;
import static java.util.Optional.of;

/**
 * Dataset generator of common data for performance tests.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public interface TestDatasetGenerator {

	default ReadyReadEvita generateReadTestDataset(@Nonnull DataGenerator dataGenerator,
	                                               int productCount,
	                                               @Nonnull Supplier<Boolean> shouldStartFromScratch,
	                                               @Nonnull Function<String, Boolean> isCatalogAvailable,
	                                               @Nonnull Function<String, Evita> createEvitaInstanceFromExistingData,
	                                               @Nonnull Function<String, Evita> createEmptyEvitaInstance,
	                                               @Nonnull String catalogName,
	                                               @Nonnull Map<Serializable, Integer> generatedEntities,
	                                               long seed,
	                                               @Nonnull BiFunction<String, Faker, Integer> randomEntityPicker,
	                                               @Nonnull Consumer<SealedEntity> entityProcessor,
	                                               @Nonnull Consumer<EntityReferenceContract> createdEntityReferenceProcessor,
	                                               @Nonnull TriConsumer<EvitaSessionContract, Map<Serializable, Integer>, EntityBuilder> entityCreator,
	                                               @Nonnull Function<SealedEntitySchema, SealedEntitySchema> schemaProcessor) {
		final Evita evita;
		final AtomicReference<SealedEntitySchema> productSchema = new AtomicReference<>();

		dataGenerator.clear();
		generatedEntities.clear();

		final long start = System.nanoTime();
		final StringBuilder loadInfo = new StringBuilder("Catalog: " + catalogName + "\n");
		if (shouldStartFromScratch.get() || !isCatalogAvailable.apply(catalogName)) {
			System.out.println("\n\nCreating database from scratch ...");
			// prepare database
			evita = createEmptyEvitaInstance.apply(catalogName);
			// create bunch or entities for referencing in products
			evita.updateCatalog(
				catalogName,
				session -> {
					dataGenerator.generateEntities(
							schemaProcessor.apply(dataGenerator.getSampleBrandSchema(session)),
							randomEntityPicker,
							seed
						)
						.limit(5)
						.forEach(it -> {
							entityProcessor.accept(it.toInstance());
							entityCreator.accept(session, generatedEntities, it);
						});

					dataGenerator.generateEntities(
							schemaProcessor.apply(dataGenerator.getSampleCategorySchema(session)),
							randomEntityPicker,
							seed
						)
						.limit(10)
						.forEach(it -> {
							entityProcessor.accept(it.toInstance());
							entityCreator.accept(session, generatedEntities, it);
						});

					dataGenerator.generateEntities(
							schemaProcessor.apply(dataGenerator.getSamplePriceListSchema(session)),
							randomEntityPicker,
							seed
						)
						.limit(4)
						.forEach(it -> {
							entityProcessor.accept(it.toInstance());
							entityCreator.accept(session, generatedEntities, it);
						});

					dataGenerator.generateEntities(
							schemaProcessor.apply(dataGenerator.getSampleStoreSchema(session)),
							randomEntityPicker,
							seed
						)
						.limit(12)
						.forEach(it -> {
							entityProcessor.accept(it.toInstance());
							entityCreator.accept(session, generatedEntities, it);
						});

					dataGenerator.generateEntities(
							schemaProcessor.apply(dataGenerator.getSampleParameterSchema(session)),
							randomEntityPicker,
							seed
						)
						.limit(200)
						.forEach(it -> {
							entityProcessor.accept(it.toInstance());
							entityCreator.accept(session, generatedEntities, it);
						});

					dataGenerator.generateEntities(
							schemaProcessor.apply(dataGenerator.getSampleParameterGroupSchema(session)),
							randomEntityPicker,
							seed
						)
						.limit(20)
						.forEach(it -> {
							entityProcessor.accept(it.toInstance());
							entityCreator.accept(session, generatedEntities, it);
						});

					productSchema.set(dataGenerator.getSampleProductSchema(
						session,
						(Consumer<EntitySchemaBuilder>) productSchemaBuilder -> productSchemaBuilder.withReferenceToEntity(
							Entities.PARAMETER,
							Entities.PARAMETER,
							Cardinality.ZERO_OR_MORE,
							whichIs -> whichIs.withGroupTypeRelatedToEntity(Entities.PARAMETER_GROUP).faceted()
						)
					));

					schemaProcessor.apply(productSchema.get());

					dataGenerator.generateEntities(
							productSchema.get(),
							randomEntityPicker,
							seed
						)
						.limit(productCount)
						.forEach(it -> {
							entityProcessor.accept(it.toInstance());
							final EntityReferenceContract createdEntity = session.upsertEntity(it);
							createdEntityReferenceProcessor.accept(createdEntity);
						});

					session.goLiveAndClose();
				}
			);
		} else {
			System.out.println("\n\nReusing existing database ...");
			final long startLoading = System.nanoTime();
			// prepare database
			evita = createEvitaInstanceFromExistingData.apply(catalogName);
			System.out.println("Database loaded in " + StringUtils.formatPreciseNano(System.nanoTime() - startLoading));
			final AtomicInteger totalEntityCount = new AtomicInteger();
			final AtomicInteger totalPriceCount = new AtomicInteger();
			final AtomicInteger totalAttributeCount = new AtomicInteger();
			final AtomicInteger totalAssociatedDataCount = new AtomicInteger();
			final AtomicInteger totalReferenceCount = new AtomicInteger();
			// read and process all existing entities
			evita.queryCatalog(
				catalogName,
				session -> {
					final long processingStart = System.nanoTime();
					final String[] entityTypes = session.getAllEntityTypes().stream().sorted().toArray(String[]::new);
					for (String entityType : entityTypes) {
						final long entityProcessingStart = System.nanoTime();
						final SealedEntitySchema entitySchema = session.getEntitySchema(entityType)
							.orElseThrow(() -> new GenericEvitaInternalError("Schema for entity `" + entityType + "` was not found!"));

						if (Entities.PRODUCT.equals(entitySchema.getName())) {
							productSchema.set(entitySchema);
						}

						schemaProcessor.apply(entitySchema);

						EvitaResponse<SealedEntity> response;
						int pageNumber = 1;
						final AtomicInteger entityCount = new AtomicInteger();
						final AtomicInteger priceCount = new AtomicInteger();
						final AtomicInteger attributeCount = new AtomicInteger();
						final AtomicInteger associatedDataCount = new AtomicInteger();
						final AtomicInteger referenceCount = new AtomicInteger();
						do {
							response = session.query(
								Query.query(
									collection(entityType),
									require(
										entityFetchAllAnd(
											page(pageNumber++, 1000)
										)
									)
								),
								SealedEntity.class
							);
							response
								.getRecordData()
								.forEach(it -> {
									entityProcessor.accept(it);
									createdEntityReferenceProcessor.accept(new EntityReference(it.getType(), it.getPrimaryKeyOrThrowException()));
									entityCount.incrementAndGet();
									priceCount.addAndGet(it.pricesAvailable() ? it.getPrices().size() : 0);
									attributeCount.addAndGet(it.attributesAvailable() ? it.getAttributeValues().size() : 0);
									associatedDataCount.addAndGet(it.associatedDataAvailable() ? it.getAssociatedDataValues().size() : 0);
									referenceCount.addAndGet(it.referencesAvailable() ? it.getReferences().size() : 0);
								});
							totalEntityCount.addAndGet(response.getRecordData().size());
						} while (response.getRecordPage().hasNext());

						totalPriceCount.addAndGet(priceCount.get());
						totalAttributeCount.addAndGet(attributeCount.get());
						totalAssociatedDataCount.addAndGet(associatedDataCount.get());
						totalReferenceCount.addAndGet(referenceCount.get());
						loadInfo.append("Entity `" + entityType + "` fully read and examined in " + StringUtils.formatPreciseNano(System.nanoTime() - entityProcessingStart) + "\n");
						of(entityCount.get()).filter(it -> it > 0).ifPresent(it -> loadInfo.append(        "\t - entity count          : " + it + "\n"));
						of(priceCount.get()).filter(it -> it > 0).ifPresent(it -> loadInfo.append(         "\t - price count           : " + it + "\n"));
						of(attributeCount.get()).filter(it -> it > 0).ifPresent(it -> loadInfo.append(     "\t - attribute count       : " + it + "\n"));
						of(associatedDataCount.get()).filter(it -> it > 0).ifPresent(it -> loadInfo.append("\t - associated data count : " + it + "\n"));
						of(referenceCount.get()).filter(it -> it > 0).ifPresent(it -> loadInfo.append("\t - reference count       : " + it + "\n"));
					}
					final double entitiesPerSec = (double) totalEntityCount.get() / (double) ((System.nanoTime() - processingStart) / 1_000_000_000);
					System.out.println("Entities (" + totalEntityCount.get() + ") processing speed " + entitiesPerSec + " recs/sec.");
					return null;
				}
			);
			loadInfo.append("\nSummary for " + totalEntityCount.get() + " entities:\n");
			of(totalPriceCount.get()).filter(it -> it > 0).ifPresent(it -> loadInfo.append(         "\t - price count           : " + it + "\n"));
			of(totalAttributeCount.get()).filter(it -> it > 0).ifPresent(it -> loadInfo.append(     "\t - attribute count       : " + it + "\n"));
			of(totalAssociatedDataCount.get()).filter(it -> it > 0).ifPresent(it -> loadInfo.append("\t - associated data count : " + it + "\n"));
			of(totalReferenceCount.get()).filter(it -> it > 0).ifPresent(it -> loadInfo.append("\t - reference count       : " + it + "\n"));
		}
		System.out.print("Database loaded in " + StringUtils.formatPreciseNano(System.nanoTime() - start) + "!\n" + loadInfo + "\nWarmup results: ");

		return new ReadyReadEvita(evita, productSchema.get());
	}

	record ReadyReadEvita(@Nonnull Evita evita, @Nonnull SealedEntitySchema productSchema) {}
}
