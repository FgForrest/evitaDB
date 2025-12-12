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

import io.evitadb.api.query.Query;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.schema.SealedEntitySchema;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.performance.senesi.SenesiBenchmark;
import io.evitadb.utils.StringUtils;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.TearDown;

import javax.annotation.Nonnull;
import java.util.LinkedList;
import java.util.concurrent.atomic.AtomicInteger;

import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.entityFetchAllAnd;
import static io.evitadb.api.query.QueryConstraints.page;
import static io.evitadb.api.query.QueryConstraints.require;
import static java.util.Optional.of;

/**
 * Base state class for {@link SenesiBenchmark} tests.
 * See benchmark description on the methods.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public abstract class ClientDataFullDatabaseState extends ClientDataState {

	/**
	 * Method is invoked before each benchmark.
	 * Method creates a bunch of brand, categories, price lists and stores that cen be referenced in products.
	 * Method also prepares 100.000 products in the database.
	 */
	@Setup(Level.Trial)
	public void setUp() {
		this.dataGenerator.clear();
		this.generatedEntities.clear();

		final String catalogName = getCatalogName();
		final StringBuilder loadInfo = new StringBuilder("Catalog: " + catalogName + "\n");

		System.out.println("\n\nReusing existing database ...");
		final long start = System.nanoTime();
		// prepare database
		this.evita = createEvitaInstanceFromExistingData(catalogName);
		System.out.println("Database loaded in " + StringUtils.formatPreciseNano(System.nanoTime() - start));
		final AtomicInteger totalEntityCount = new AtomicInteger();
		final AtomicInteger totalPriceCount = new AtomicInteger();
		final AtomicInteger totalAttributeCount = new AtomicInteger();
		final AtomicInteger totalAssociatedDataCount = new AtomicInteger();
		final AtomicInteger totalReferenceCount = new AtomicInteger();
		// read and process all existing entities
		this.evita.queryCatalog(
			getCatalogName(),
			session -> {
				final long processingStart = System.nanoTime();
				final String[] entityTypes = session.getAllEntityTypes().stream().sorted().toArray(String[]::new);
				for (String entityType : entityTypes) {
					final long entityProcessingStart = System.nanoTime();
					final SealedEntitySchema entitySchema = session.getEntitySchema(entityType)
						.orElseThrow(() -> new GenericEvitaInternalError("Schema for entity `" + entityType + "` was not found!"));
					processSchema(entitySchema);

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
								processCreatedEntityReference(new EntityReference(it.getType(), it.getPrimaryKeyOrThrowException()));
								processEntity(it);
								entityCount.incrementAndGet();
								priceCount.addAndGet(it.getPrices().size());
								attributeCount.addAndGet(it.getAttributeValues().size());
								associatedDataCount.addAndGet(it.getAssociatedDataValues().size());
								referenceCount.addAndGet(it.getReferences().size());
							});
						totalEntityCount.addAndGet(response.getRecordData().size());
					} while (response.getRecordPage().hasNext());

					totalPriceCount.addAndGet(priceCount.get());
					totalAttributeCount.addAndGet(attributeCount.get());
					totalAssociatedDataCount.addAndGet(associatedDataCount.get());
					totalReferenceCount.addAndGet(referenceCount.get());
					loadInfo.append("Entity `").append(entityType).append("` fully read and examined in ").append(StringUtils.formatPreciseNano(System.nanoTime() - entityProcessingStart)).append("\n");
					of(entityCount.get()).filter(it -> it > 0).ifPresent(it -> loadInfo.append("\t - entity count          : ").append(it).append("\n"));
					of(priceCount.get()).filter(it -> it > 0).ifPresent(it -> loadInfo.append("\t - price count           : ").append(it).append("\n"));
					of(attributeCount.get()).filter(it -> it > 0).ifPresent(it -> loadInfo.append("\t - attribute count       : ").append(it).append("\n"));
					of(associatedDataCount.get()).filter(it -> it > 0).ifPresent(it -> loadInfo.append("\t - associated data count : ").append(it).append("\n"));
					of(referenceCount.get()).filter(it -> it > 0).ifPresent(it -> loadInfo.append("\t - reference count       : ").append(it).append("\n"));
				}
				final double entitiesPerSec = (double) totalEntityCount.get() / (double) ((System.nanoTime() - processingStart) / 1_000_000_000);
				System.out.println("Entities (" + totalEntityCount.get() + ") processing speed " + entitiesPerSec + " recs/sec.");
				return null;
			}
		);
		loadInfo.append("\nSummary for ").append(totalEntityCount.get()).append(" entities:\n");
		of(totalPriceCount.get()).filter(it -> it > 0).ifPresent(it -> loadInfo.append("\t - price count           : ").append(it).append("\n"));
		of(totalAttributeCount.get()).filter(it -> it > 0).ifPresent(it -> loadInfo.append("\t - attribute count       : ").append(it).append("\n"));
		of(totalAssociatedDataCount.get()).filter(it -> it > 0).ifPresent(it -> loadInfo.append("\t - associated data count : ").append(it).append("\n"));
		of(totalReferenceCount.get()).filter(it -> it > 0).ifPresent(it -> loadInfo.append("\t - reference count       : ").append(it).append("\n"));

		System.out.print("Database loaded in " + StringUtils.formatPreciseNano(System.nanoTime() - start) + "!\n" + loadInfo + "\nWarmup results: ");
	}

	/**
	 * Closes Evita database.
	 */
	@TearDown(Level.Trial)
	public void closeEvita() {
		this.evita.close();
	}

	/**
	 * Method is called when before benchmark iteration is started, prepares session.
	 */
	@Setup(Level.Iteration)
	public void openSession() {
		this.session = this.evita.createReadOnlySession(getCatalogName());
	}

	/**
	 * Method is called when benchmark iteration is finished, closes session.
	 */
	@TearDown(Level.Iteration)
	public void closeSession() {
		this.session.close();
	}

	/**
	 * Descendants may store reference to the schema if they want.
	 */
	protected void processSchema(@Nonnull SealedEntitySchema schema) {
		// do nothing by default
	}

	/**
	 * Descendants may examine created entity if they want.
	 */
	protected void processEntity(@Nonnull SealedEntity entity) {
		// do nothing by default
	}

	/**
	 * Descendants may examine created entity if they want.
	 */
	protected void processCreatedEntityReference(EntityReference entityReference) {
		this.generatedEntities.computeIfAbsent(
			entityReference.getType(), serializable -> new LinkedList<>()
		).add(entityReference.getPrimaryKey());
	}

}
