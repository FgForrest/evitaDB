/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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
import io.evitadb.api.query.Query;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.core.Evita;
import io.evitadb.test.Entities;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.DataCarrier;
import io.evitadb.test.extension.EvitaParameterResolver;
import io.evitadb.test.generator.DataGenerator;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import javax.annotation.Nonnull;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestConstants.FUNCTIONAL_TEST;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_EAN;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_NAME;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_QUANTITY;
import static io.evitadb.utils.AssertionUtils.assertSortedResultEquals;

/**
 * This test verifies segmented output and spacing rules for paginated results.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@DisplayName("Evita entity view port rules functionality")
@Tag(FUNCTIONAL_TEST)
@ExtendWith(EvitaParameterResolver.class)
@Slf4j
public class EntityViewPortRulesFunctionalTest {
	private static final String HUNDRED_PRODUCTS = "HundredProductsForViewPortTesting";
	private static final int SEED = 42;

	@DataSet(value = HUNDRED_PRODUCTS, readOnly = false, destroyAfterClass = true)
	DataCarrier setUp(Evita evita) {
		return evita.updateCatalog(TEST_CATALOG, session -> {
			final DataGenerator dataGenerator = new DataGenerator();
			final BiFunction<String, Faker, Integer> randomEntityPicker = (entityType, faker) -> {
				final int entityCount = session.getEntityCollectionSize(entityType);
				final int primaryKey = entityCount == 0 ? 0 : faker.random().nextInt(1, entityCount);
				return primaryKey == 0 ? null : primaryKey;
			};
			dataGenerator.generateEntities(
					dataGenerator.getSampleBrandSchema(session),
					randomEntityPicker,
					SEED
				)
				.limit(5)
				.forEach(session::upsertEntity);

			dataGenerator.generateEntities(
					dataGenerator.getSampleCategorySchema(session),
					randomEntityPicker,
					SEED
				)
				.limit(10)
				.forEach(session::upsertEntity);

			dataGenerator.generateEntities(
					dataGenerator.getSamplePriceListSchema(session),
					randomEntityPicker,
					SEED
				)
				.limit(4)
				.forEach(session::upsertEntity);

			dataGenerator.generateEntities(
					dataGenerator.getSampleStoreSchema(session),
					randomEntityPicker,
					SEED
				)
				.limit(12)
				.forEach(session::upsertEntity);

			final List<EntityReference> storedProducts = dataGenerator.generateEntities(
					dataGenerator.getSampleProductSchema(
						session,
						builder -> {
							builder
								.withAttribute(ATTRIBUTE_NAME, String.class, whichIs -> whichIs.localized(() -> false).filterable().sortable().nullable(() -> false))
								.withAttribute(ATTRIBUTE_EAN, String.class, whichIs -> whichIs.filterable().sortable().nullable(() -> false))
								.withAttribute(ATTRIBUTE_QUANTITY, BigDecimal.class, whichIs -> whichIs.filterable().sortable().nullable(() -> false));
						}
					),
					randomEntityPicker,
					SEED
				)
				.limit(100)
				.map(session::upsertEntity)
				.toList();

			return new DataCarrier(
				"originalProductEntities",
				storedProducts.stream()
					.map(it -> session.getEntity(it.getType(), it.getPrimaryKey(), entityFetchAllContent()).orElseThrow())
					.collect(Collectors.toList())
			);
		});
	}

	@DisplayName("Should return entities by matching locale")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnDifferentlySortedSegments(Evita evita, List<SealedEntity> originalProductEntities) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Set<Integer> nameDrainedPks = new HashSet<>();
				final Set<Integer> eanDrainedPks = new HashSet<>();
				final Set<Integer> quantityDrainedPks = new HashSet<>();

				assertSortedResultEquals(
					"First page must be sorted by name in descending order.",
					session.query(
						fabricateSegmentedQuery(1, 5),
						EntityReference.class
					)
						.getRecordData().stream().map(EntityReference::getPrimaryKey).collect(Collectors.toList()),
					originalProductEntities.stream()
						.sorted(
							(o1, o2) -> o2.getAttribute(ATTRIBUTE_NAME, String.class)
								.compareTo(o1.getAttribute(ATTRIBUTE_NAME, String.class))
						)
						.limit(5)
						.mapToInt(SealedEntity::getPrimaryKey)
						.peek(nameDrainedPks::add)
						.toArray()
				);
				assertSortedResultEquals(
					"Second page must be sorted by name in descending order.",
					session.query(
							fabricateSegmentedQuery(2, 5),
							EntityReference.class
						)
						.getRecordData().stream().map(EntityReference::getPrimaryKey).collect(Collectors.toList()),
					originalProductEntities.stream()
						.sorted(
							(o1, o2) -> o2.getAttribute(ATTRIBUTE_NAME, String.class)
								.compareTo(o1.getAttribute(ATTRIBUTE_NAME, String.class))
						)
						.skip(5)
						.limit(5)
						.mapToInt(SealedEntity::getPrimaryKey)
						.peek(nameDrainedPks::add)
						.toArray()
				);

				assertSortedResultEquals(
					"Third page must be sorted by EAN in descending order (excluding items on first two pages).",
					session.query(
							fabricateSegmentedQuery(3, 5),
							EntityReference.class
						)
						.getRecordData().stream().map(EntityReference::getPrimaryKey).collect(Collectors.toList()),
					originalProductEntities.stream()
						.filter(it -> !nameDrainedPks.contains(it.getPrimaryKey()))
						.sorted(
							(o1, o2) -> o2.getAttribute(ATTRIBUTE_EAN, String.class)
								.compareTo(o1.getAttribute(ATTRIBUTE_EAN, String.class))
						)
						.limit(5)
						.mapToInt(SealedEntity::getPrimaryKey)
						.peek(eanDrainedPks::add)
						.toArray()
				);

				final List<Integer> fourthPage = session.query(
						fabricateSegmentedQuery(4, 5),
						EntityReference.class
					)
					.getRecordData().stream().map(EntityReference::getPrimaryKey).collect(Collectors.toList());
				assertSortedResultEquals(
					"Fourth page contains 3 entities sorted according to EAN in descending order.",
					fourthPage.subList(0, 3),
					originalProductEntities.stream()
						.filter(it -> !nameDrainedPks.contains(it.getPrimaryKey()))
						.sorted(
							(o1, o2) -> o2.getAttribute(ATTRIBUTE_EAN, String.class)
								.compareTo(o1.getAttribute(ATTRIBUTE_EAN, String.class))
						)
						.skip(5)
						.limit(3)
						.mapToInt(SealedEntity::getPrimaryKey)
						.peek(eanDrainedPks::add)
						.toArray()
				);

				assertSortedResultEquals(
					"Fourth page ends with first 2 entities sorted according to quantity in ascending order.",
					fourthPage.subList(3, 5),
					originalProductEntities.stream()
						.filter(it -> !nameDrainedPks.contains(it.getPrimaryKey()) && !eanDrainedPks.contains(it.getPrimaryKey()))
						.sorted(
							Comparator.comparing(o -> o.getAttribute(ATTRIBUTE_QUANTITY, BigDecimal.class))
						)
						.limit(2)
						.mapToInt(SealedEntity::getPrimaryKey)
						.peek(quantityDrainedPks::add)
						.toArray()
				);

				final List<Integer> fifthPage = session.query(
						fabricateSegmentedQuery(5, 5),
						EntityReference.class
					)
					.getRecordData().stream().map(EntityReference::getPrimaryKey).collect(Collectors.toList());
				assertSortedResultEquals(
					"Fifth page must have only 4 entities be sorted by quantity in ascending order.",
					fifthPage.subList(0, 4),
					originalProductEntities.stream()
						.filter(it -> !nameDrainedPks.contains(it.getPrimaryKey()) && !eanDrainedPks.contains(it.getPrimaryKey()))
						.sorted(
							Comparator.comparing(o -> o.getAttribute(ATTRIBUTE_QUANTITY, BigDecimal.class))
						)
						.skip(2)
						.limit(4)
						.mapToInt(SealedEntity::getPrimaryKey)
						.peek(quantityDrainedPks::add)
						.toArray()
				);

				assertSortedResultEquals(
					"Fifth page must end with first entity sorted by PK in ascending order.",
					fifthPage.subList(4, 5),
					originalProductEntities.stream()
						.filter(it -> !nameDrainedPks.contains(it.getPrimaryKey()) && !eanDrainedPks.contains(it.getPrimaryKey()) && !quantityDrainedPks.contains(it.getPrimaryKey()))
						.sorted(Comparator.comparing(EntityContract::getPrimaryKey))
						.limit(1)
						.mapToInt(SealedEntity::getPrimaryKey)
						.toArray()
				);

				assertSortedResultEquals(
					"Sixth page must be sorted by PK in ascending order (but only from those entities that hasn't been already provided).",
					session.query(
							fabricateSegmentedQuery(6, 5),
							EntityReference.class
						)
						.getRecordData().stream().map(EntityReference::getPrimaryKey).collect(Collectors.toList()),
					originalProductEntities.stream()
						.filter(it -> !nameDrainedPks.contains(it.getPrimaryKey()) && !eanDrainedPks.contains(it.getPrimaryKey()) && !quantityDrainedPks.contains(it.getPrimaryKey()))
						.sorted(Comparator.comparing(EntityContract::getPrimaryKey))
						.skip(1)
						.limit(5)
						.mapToInt(SealedEntity::getPrimaryKey)
						.toArray()
				);

				assertSortedResultEquals(
					"Seventh page must be sorted by PK in ascending order (but only from those entities that hasn't been already provided).",
					session.query(
							fabricateSegmentedQuery(7, 5),
							EntityReference.class
						)
						.getRecordData().stream().map(EntityReference::getPrimaryKey).collect(Collectors.toList()),
					originalProductEntities.stream()
						.filter(it -> !nameDrainedPks.contains(it.getPrimaryKey()) && !eanDrainedPks.contains(it.getPrimaryKey()) && !quantityDrainedPks.contains(it.getPrimaryKey()))
						.sorted(Comparator.comparing(EntityContract::getPrimaryKey))
						.skip(6)
						.limit(5)
						.mapToInt(SealedEntity::getPrimaryKey)
						.toArray()
				);

				return null;
			}
		);
	}

	/**
	 * Constructs a segmented query for fetching products from the database.
	 * The query is filtered by the English locale and consists of three segments ordered by
	 * different attributes with specified limits on each segment.
	 * It also includes pagination and debug settings.
	 *
	 * @param pageNumber the page number for pagination.
	 * @param pageSize the number of items per page for pagination.
	 * @return a Query object with the specified configuration.
	 */
	@Nonnull
	private static Query fabricateSegmentedQuery(int pageNumber, int pageSize) {
		return query(
			collection(Entities.PRODUCT),
			orderBy(
				segments(
					segment(
						orderBy(
							attributeNatural(ATTRIBUTE_NAME, OrderDirection.DESC)
						),
						limit(10)
					),
					segment(
						orderBy(
							attributeNatural(ATTRIBUTE_EAN, OrderDirection.DESC)
						),
						limit(8)
					),
					segment(
						orderBy(
							attributeNatural(ATTRIBUTE_QUANTITY, OrderDirection.ASC)
						),
						limit(6)
					)
				)
			),
			require(
				page(pageNumber, pageSize)
				/* TODO JNO - enable again + add variant for prefetch */
				/*debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)*/
			)
		);
	}

}
