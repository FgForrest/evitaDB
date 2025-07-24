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

package io.evitadb.externalApi.grpc.testUtils;

import com.github.javafaker.Faker;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.EvolutionMode;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaEditor;
import io.evitadb.core.Evita;
import io.evitadb.dataType.IntegerNumberRange;
import io.evitadb.test.Entities;
import io.evitadb.test.generator.DataGenerator;

import javax.annotation.Nonnull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

import static io.evitadb.api.query.QueryConstraints.entityFetchAllContent;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_CODE;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_PRIORITY;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_QUANTITY;

/**
 * Class used in tests to grant access to commonly used constants and to generate and get testing data set of {@link SealedEntity}.
 *
 * @author Tomáš Pozler, 2022
 */
@SuppressWarnings("unused")
public class TestDataProvider {
	public static final String ATTRIBUTE_SIZE = "size";
	public static final String ATTRIBUTE_CREATED = "created";
	public static final String ATTRIBUTE_MANUFACTURED = "manufactured";
	public static final String ATTRIBUTE_COMBINED_PRIORITY = "combinedPriority";
	public static final String ATTRIBUTE_TARGET_MARKET = "targetMarket";
	public static final String ATTRIBUTE_LOCATED_AT = "locatedAt";
	public static final String ATTRIBUTE_MARKET_SHARE = "marketShare";
	public static final String ATTRIBUTE_FOUNDED = "founded";
	public static final String ATTRIBUTE_CAPACITY = "capacity";
	private static final int SEED = 40;
	public static final String ATTRIBUTE_ENABLED = "enabled";
	public static final String ATTRIBUTE_INHERITANCE = "inheritance";
	public static final String ATTRIBUTE_OPTICS = "optics";
	private final DataGenerator dataGenerator = new DataGenerator();

	/**
	 * Generates test data for the given {@link Evita} instance.
	 *
	 * @param evita instance
	 * @return list of {@link SealedEntity} instances inserted into Evita
	 */
	public List<SealedEntity> generateEntities(@Nonnull Evita evita, int productCount) {
		return evita.updateCatalog(TEST_CATALOG, session -> {
			System.out.println("Generating dataset...");
			final BiFunction<String, Faker, Integer> randomEntityPicker = (entityType, faker) -> {
				final int entityCount = session.getEntityCollectionSize(entityType);
				final int primaryKey = entityCount == 0 ? 0 : faker.random().nextInt(1, entityCount);
				return primaryKey == 0 ? null : primaryKey;
			};

			session.getCatalogSchema()
				.openForWrite()
				.withAttribute(ATTRIBUTE_ENABLED, boolean.class, thatIs -> thatIs.withDefaultValue(true).withDescription("Sets visibility of the entity."))
				.withAttribute(ATTRIBUTE_INHERITANCE, boolean[].class, thatIs -> thatIs.withDefaultValue(new boolean[] {true, false, true}))
				.withAttribute(ATTRIBUTE_OPTICS, byte[].class, thatIs -> thatIs.withDefaultValue(new byte[] {1, 5, 12}))
				.updateVia(session);

			this.dataGenerator.generateEntities(
					this.dataGenerator.getSampleBrandSchema(session),
					randomEntityPicker,
					SEED
				)
				.limit(5)
				.forEach(session::upsertEntity);

			this.dataGenerator.generateEntities(
					this.dataGenerator.getSampleCategorySchema(session),
					randomEntityPicker,
					SEED
				)
				.limit(10)
				.forEach(session::upsertEntity);

			this.dataGenerator.generateEntities(
					this.dataGenerator.getSamplePriceListSchema(session),
					randomEntityPicker,
					SEED
				)
				.limit(4)
				.forEach(session::upsertEntity);

			this.dataGenerator.generateEntities(
					this.dataGenerator.getSampleStoreSchema(session),
					randomEntityPicker,
					SEED
				)
				.limit(12)
				.forEach(session::upsertEntity);

			this.dataGenerator.generateEntities(
					this.dataGenerator.getSampleParameterGroupSchema(session),
					randomEntityPicker,
					SEED
				)
				.limit(15)
				.forEach(session::upsertEntity);

			this.dataGenerator.generateEntities(
					this.dataGenerator.getSampleParameterSchema(session),
					randomEntityPicker,
					SEED
				)
				.limit(Math.min(Math.max(10, productCount / 5), 100))
				.forEach(session::upsertEntity);

			final List<EntityReference> storedProducts = this.dataGenerator.generateEntities(
					this.dataGenerator.getSampleProductSchema(
						session,
						schemaBuilder -> {
							schemaBuilder
								.verifySchemaButAllow(EvolutionMode.ADDING_REFERENCES)
								.withAttribute(
									ATTRIBUTE_CODE, String.class,
									whichIs -> whichIs.unique().sortable().nullable()
								)
								.withAttribute(
									ATTRIBUTE_QUANTITY, BigDecimal.class,
									whichIs -> whichIs.filterable().sortable().nullable().indexDecimalPlaces(2)
								)
								.withAttribute(
									ATTRIBUTE_PRIORITY, Long.class,
									whichIs -> whichIs.sortable().filterable().nullable()
								)
								.withAttribute(
									ATTRIBUTE_SIZE, IntegerNumberRange[].class, whichIs -> whichIs.filterable().nullable()
								)
								.withAttribute(
									ATTRIBUTE_CREATED, OffsetDateTime.class,
									whichIs -> whichIs.filterable().sortable().nullable()
								)
								.withAttribute(
									ATTRIBUTE_MANUFACTURED, LocalDate.class,
									whichIs -> whichIs.filterable().sortable().nullable()
								)
								.withAttribute(
									ATTRIBUTE_TARGET_MARKET, String.class,
									whichIs -> whichIs.filterable().nullable()
								)
								.withReferenceToEntity(
									Entities.CATEGORY,
									Entities.CATEGORY,
									Cardinality.ZERO_OR_MORE,
									whichIs ->
										/* we can specify special attributes on relation */
										whichIs.indexedForFilteringAndPartitioning()
											.withAttribute(
												"categoryPriority", Long.class,
												thatIs -> thatIs.sortable(() -> false).nullable()
											)
								)
								/* for indexed facets we can compute "counts" */
								.withReferenceToEntity(
									Entities.BRAND,
									Entities.BRAND,
									Cardinality.ZERO_OR_ONE,
									ReferenceSchemaEditor::faceted
								)
								/* facets may be also represented be entities unknown to Evita */
								.withReferenceTo(
									Entities.STORE,
									"externalStore",
									Cardinality.ZERO_OR_MORE,
									ReferenceSchemaEditor::faceted
								)
								.withReferenceToEntity(
									Entities.PARAMETER,
									Entities.PARAMETER,
									Cardinality.ZERO_OR_MORE,
									whichIs ->
										// we can specify special attributes on relation
										whichIs
											.withGroupTypeRelatedToEntity(Entities.PARAMETER_GROUP).faceted()
								);
						}
						/* we can also use references to entities that are not yet known to Evita */
					),
					randomEntityPicker,
					SEED
				)
				.limit(productCount)
				.map(session::upsertEntity)
				.toList();

			return storedProducts.stream()
				.map(it -> session.getEntity(it.getType(), it.getPrimaryKey(), entityFetchAllContent()).orElseThrow())
				.collect(Collectors.toList());
		});
	}
}
