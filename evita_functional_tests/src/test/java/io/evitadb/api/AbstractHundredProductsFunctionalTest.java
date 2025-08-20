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
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.core.Evita;
import io.evitadb.test.Entities;
import io.evitadb.test.extension.DataCarrier;
import io.evitadb.test.generator.DataGenerator;
import io.evitadb.test.generator.DataGenerator.Labels;
import io.evitadb.test.generator.DataGenerator.ReferencedFileSet;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;

import static io.evitadb.api.query.QueryConstraints.entityFetchAllContent;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.extension.DataCarrier.tuple;
import static io.evitadb.test.generator.DataGenerator.ASSOCIATED_DATA_LABELS;
import static io.evitadb.test.generator.DataGenerator.ASSOCIATED_DATA_REFERENCED_FILES;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_CATEGORY_PRIORITY;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_PRIORITY;

/**
 * Abstract parent that setups shared schema and testing data set for multiple functional tests.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class AbstractHundredProductsFunctionalTest {
	public static final String ATTRIBUTE_CATEGORY_LABEL = "label";
	public static final String ATTRIBUTE_CATEGORY_SHADOW = "shadow";
	public static final String ATTRIBUTE_MARKETS = "markets";
	public static final String ATTRIBUTE_ENUM = "enum";
	public static final String ATTRIBUTE_OPTIONAL_AVAILABILITY = "optionalAvailability";
	public static final String ASSOCIATED_DATA_MARKETS = "markets";
	private static final int SEED = 40;
	protected final DataGenerator dataGenerator = new DataGenerator.Builder()
		.registerValueGenerator(
			Entities.PRODUCT, ATTRIBUTE_ENUM,
			faker -> TestEnum.values()[faker.random().nextInt(TestEnum.values().length)].name()
		).build();

	@Nonnull
	protected BiFunction<String, Faker, Integer> getRandomEntityPicker(EvitaSessionContract session) {
		return (entityType, faker) -> {
			final int entityCount = session.getEntityCollectionSize(entityType);
			final int primaryKey = entityCount == 0 ? 0 : faker.random().nextInt(1, entityCount);
			return primaryKey == 0 ? null : primaryKey;
		};
	}

	protected DataCarrier setUp(Evita evita) {
		return evita.updateCatalog(TEST_CATALOG, session -> {
			final BiFunction<String, Faker, Integer> randomEntityPicker = getRandomEntityPicker(session);

			final List<EntityReference> storedPriceLists = this.dataGenerator.generateEntities(
					this.dataGenerator.getSamplePriceListSchema(session),
					randomEntityPicker,
					SEED
				)
				.limit(20)
				.map(session::upsertEntity)
				.toList();

			final List<EntityReference> storedCategories = this.dataGenerator.generateEntities(
					this.dataGenerator.getSampleCategorySchema(
						session,
						builder -> {
							builder
								.withReferenceToEntity(Entities.PRICE_LIST, Entities.PRICE_LIST, Cardinality.ZERO_OR_ONE)
								/* here we define set of associated data, that can be stored along with entity */
								.withAssociatedData(ASSOCIATED_DATA_REFERENCED_FILES, ReferencedFileSet.class)
								.withAssociatedData(ASSOCIATED_DATA_LABELS, Labels.class)
								.updateVia(session);
							return builder.toInstance();
						}
					),
					randomEntityPicker,
					SEED
				)
				.limit(20)
				.map(session::upsertEntity)
				.toList();

			final List<EntityReference> storedStores = this.dataGenerator.generateEntities(
					this.dataGenerator.getSampleStoreSchema(
						session,
						builder -> {
							builder
								/* here we define set of associated data, that can be stored along with entity */
								.withAssociatedData(ASSOCIATED_DATA_REFERENCED_FILES, ReferencedFileSet.class)
								.withAssociatedData(ASSOCIATED_DATA_LABELS, Labels.class)
								.updateVia(session);
							return builder.toInstance();
						}
					),
					randomEntityPicker,
					SEED
				)
				.limit(12)
				.map(session::upsertEntity)
				.toList();

			final List<EntityReference> storedBrands = this.dataGenerator.generateEntities(
					this.dataGenerator.getSampleBrandSchema(
						session,
						builder -> {
							builder.withReferenceToEntity(
								Entities.STORE, Entities.STORE, Cardinality.EXACTLY_ONE,
								thatIs -> thatIs.indexedForFilteringAndPartitioning().withGroupTypeRelatedToEntity(Entities.CATEGORY)
							).updateVia(session);
							return builder.toInstance();
						}
					),
					randomEntityPicker,
					SEED
				)
				.limit(5)
				.map(session::upsertEntity)
				.toList();

			this.dataGenerator.generateEntities(
					this.dataGenerator.getSampleParameterGroupSchema(session),
					randomEntityPicker,
					SEED
				)
				.limit(10)
				.forEach(session::upsertEntity);

			final List<EntityReference> storedParameters = this.dataGenerator.generateEntities(
					this.dataGenerator.getSampleParameterSchema(
						session,
						builder -> {
							builder.withAttribute(
								ATTRIBUTE_PRIORITY, Long.class, thatIs -> thatIs.filterable()
							).updateVia(session);
							return builder.toInstance();
						}
					),
					randomEntityPicker,
					SEED
				)
				.limit(100)
				.map(session::upsertEntity)
				.toList();

			final List<EntityReference> storedProducts = this.dataGenerator.generateEntities(
					this.dataGenerator.getSampleProductSchema(
						session,
						builder -> {
							builder
								.withAttribute(ATTRIBUTE_OPTIONAL_AVAILABILITY, Boolean.class, whichIs -> whichIs.filterable().nullable())
								.withAttribute(ATTRIBUTE_MARKETS, String[].class)
								.withAttribute(ATTRIBUTE_ENUM, String.class, whichIs -> whichIs.filterable().sortable())
								.withAssociatedData(ASSOCIATED_DATA_MARKETS, String[].class)
								.withReferenceToEntity(
									Entities.CATEGORY,
									Entities.CATEGORY,
									Cardinality.ZERO_OR_MORE,
									whichIs ->
										whichIs.indexedForFilteringAndPartitioning()
											.withAttribute(ATTRIBUTE_CATEGORY_PRIORITY, Long.class, thatIs -> thatIs.sortable().nullable())
											.withAttribute(ATTRIBUTE_CATEGORY_LABEL, String.class, thatIs -> thatIs.localized())
											.withAttribute(ATTRIBUTE_CATEGORY_SHADOW, Boolean.class)
								)
								.withReferenceToEntity(
									Entities.PARAMETER, Entities.PARAMETER, Cardinality.ONE_OR_MORE,
									whichIs -> whichIs.indexedForFilteringAndPartitioning().faceted()
										.withGroupTypeRelatedToEntity(Entities.PARAMETER_GROUP)
										.withAttribute(ATTRIBUTE_CATEGORY_PRIORITY, Long.class, thatIs -> thatIs.sortable().filterable())
								)
								.withReferenceToEntity(Entities.PRICE_LIST, Entities.PRICE_LIST, Cardinality.ZERO_OR_MORE)
								.withReferenceToEntity(
									Entities.BRAND,
									Entities.BRAND,
									Cardinality.ZERO_OR_ONE,
									whichIs -> whichIs
										.indexedForFilteringAndPartitioning()
										.faceted()
										.withGroupTypeRelatedToEntity(Entities.STORE)
								)
								.withReferenceToEntity(Entities.PRODUCT, Entities.PRODUCT, Cardinality.ZERO_OR_MORE)
								.updateVia(session);
							return builder.toInstance();
						}
					),
					randomEntityPicker,
					SEED
				)
				.limit(100)
				.map(session::upsertEntity)
				.toList();

			final Map<Integer, SealedEntity> categories = storedCategories.stream()
				.map(it -> session.getEntity(it.getType(), it.getPrimaryKey(), entityFetchAllContent()).orElseThrow())
				.collect(
					Collectors.toMap(
						EntityContract::getPrimaryKey,
						Function.identity()
					)
				);

			final List<SealedEntity> products = storedProducts.stream()
				.map(it -> session.getEntity(it.getType(), it.getPrimaryKey(), entityFetchAllContent()).orElseThrow())
				.toList();

			final List<SealedEntity> brands = storedBrands.stream()
				.map(it -> session.getEntity(it.getType(), it.getPrimaryKey(), entityFetchAllContent()).orElseThrow())
				.toList();

			final List<SealedEntity> parameters = storedParameters.stream()
				.map(it -> session.getEntity(it.getType(), it.getPrimaryKey(), entityFetchAllContent()).orElseThrow())
				.toList();

			final List<SealedEntity> stores = storedStores.stream()
				.map(it -> session.getEntity(it.getType(), it.getPrimaryKey(), entityFetchAllContent()).orElseThrow())
				.toList();

			return new DataCarrier(
				tuple("originalProducts", products),
				tuple("originalBrands", brands),
				tuple("originalParameters", parameters),
				tuple("originalStores", stores),
				tuple("originalCategories", categories),
				tuple("originalPriceLists", storedPriceLists),
				tuple("categoryHierarchy", this.dataGenerator.getHierarchy(Entities.CATEGORY))
			);
		});
	}

	public enum TestEnum {

		ONE, TWO, THREE

	}

}
