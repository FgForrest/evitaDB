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

package io.evitadb.api.functional.fetch;

import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.core.Evita;
import io.evitadb.test.Entities;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.EvitaParameterResolver;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.Locale;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestConstants.FUNCTIONAL_TEST;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.generator.DataGenerator.ASSOCIATED_DATA_LABELS;
import static io.evitadb.test.generator.DataGenerator.ASSOCIATED_DATA_REFERENCED_FILES;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * This test verifies entity associated data fetching functionality including:
 *
 * - retrieval of entities with all associated data
 * - retrieval of entities with associated data in specific language
 * - retrieval of entities with associated data in multiple languages
 * - retrieval of entities with selected (named) associated data
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("Evita entity associated data fetch functionality")
@Tag(FUNCTIONAL_TEST)
@ExtendWith(EvitaParameterResolver.class)
@Slf4j
class EntityAssociatedDataFetchFunctionalTest extends AbstractEntityFetchingFunctionalTest {

	@DisplayName("Single entity with associated data only by primary key should be found")
	@Test
	@UseDataSet(HUNDRED_PRODUCTS)
	void shouldRetrieveSingleEntityWithAssociatedDataByPrimaryKey(Evita evita, List<SealedEntity> originalProducts) {
		final SealedEntity productWithAssociatedData = originalProducts.stream()
			.filter(it -> !it.getAssociatedDataValues().isEmpty())
			.findFirst()
			.orElseThrow();
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> productByPk = session.querySealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(productWithAssociatedData.getPrimaryKey())
						),
						require(
							entityFetch(
								associatedDataContentAll()
							)
						)
					)
				);
				assertEquals(1, productByPk.getRecordData().size());
				assertEquals(1, productByPk.getTotalRecordCount());

				assertProduct(
					productByPk.getRecordData().get(0), productWithAssociatedData.getPrimaryKey(), false, true, false,
					false
				);
				return null;
			}
		);
	}

	@DisplayName("Multiple entities with associated data only by their primary keys should be found")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldRetrieveMultipleEntitiesWithAssociatedDataByPrimaryKey(
		Evita evita, List<SealedEntity> originalProducts) {
		final Integer[] entitiesMatchingTheRequirements = getRequestedIdsByPredicate(
			originalProducts,
			it -> !it.getAssociatedDataValues(ASSOCIATED_DATA_REFERENCED_FILES).isEmpty()
		);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> productByPk = session.querySealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(entitiesMatchingTheRequirements)
						),
						require(
							entityFetch(
								associatedDataContentAll()
							),
							page(1, 4)
						)
					)
				);
				assertEquals(4, productByPk.getRecordData().size());
				assertEquals(entitiesMatchingTheRequirements.length, productByPk.getTotalRecordCount());

				assertProduct(
					productByPk.getRecordData().get(0), entitiesMatchingTheRequirements[0], false, true, false, false);
				assertProduct(
					productByPk.getRecordData().get(1), entitiesMatchingTheRequirements[1], false, true, false, false);
				assertProduct(
					productByPk.getRecordData().get(2), entitiesMatchingTheRequirements[2], false, true, false, false);
				assertProduct(
					productByPk.getRecordData().get(3), entitiesMatchingTheRequirements[3], false, true, false, false);
				return null;
			}
		);
	}

	@DisplayName("Multiple entities with associated data in passed language only by their primary keys should be found")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldRetrieveMultipleEntitiesWithAssociatedDataInLanguageDataByPrimaryKey(
		Evita evita, List<SealedEntity> originalProducts) {
		final Integer[] entitiesMatchingTheRequirements = getRequestedIdsByPredicate(
			originalProducts,
			it -> it.getAssociatedData(ASSOCIATED_DATA_LABELS, LOCALE_CZECH) != null &&
				it.getAssociatedData(ASSOCIATED_DATA_LABELS, Locale.ENGLISH) == null
		);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> productByPk = session.querySealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							and(
								entityPrimaryKeyInSet(entitiesMatchingTheRequirements),
								entityLocaleEquals(LOCALE_CZECH)
							)
						),
						require(
							entityFetch(
								associatedDataContentAll()
							)
						)
					)
				);

				for (SealedEntity product : productByPk.getRecordData()) {
					assertProductHasAssociatedDataInLocale(product, LOCALE_CZECH, ASSOCIATED_DATA_LABELS);
					assertProductHasNotAssociatedDataInLocale(product, Locale.ENGLISH, ASSOCIATED_DATA_LABELS);
				}
				return null;
			}
		);
	}

	@DisplayName("Multiple entities with associated data in multiple language only by their primary keys should be found")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldRetrieveMultipleEntitiesWithAssociatedDataInMultipleLanguageDataByPrimaryKey(
		Evita evita, List<SealedEntity> originalProducts) {
		final Integer[] entitiesMatchingTheRequirements = getRequestedIdsByPredicate(
			originalProducts,
			it -> it.getAssociatedData(ASSOCIATED_DATA_LABELS, LOCALE_CZECH) != null &&
				it.getAssociatedData(ASSOCIATED_DATA_LABELS, Locale.ENGLISH) != null
		);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> productByPk = session.querySealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							and(
								entityPrimaryKeyInSet(entitiesMatchingTheRequirements),
								entityLocaleEquals(LOCALE_CZECH)
							)
						),
						require(
							entityFetch(
								associatedDataContentAll(),
								dataInLocales(LOCALE_CZECH, Locale.ENGLISH)
							)
						)
					)
				);

				for (SealedEntity product : productByPk.getRecordData()) {
					assertProductHasAssociatedDataInLocale(product, LOCALE_CZECH, ASSOCIATED_DATA_LABELS);
					assertProductHasAssociatedDataInLocale(product, Locale.ENGLISH, ASSOCIATED_DATA_LABELS);
				}
				return null;
			}
		);
	}

	@DisplayName("Multiple entities with selected associated data only by their primary keys should be found")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldRetrieveMultipleEntitiesWithNamedAssociatedDataByPrimaryKey(
		Evita evita, List<SealedEntity> originalProducts) {
		final Integer[] entitiesMatchingTheRequirements = getRequestedIdsByPredicate(
			originalProducts,
			it -> it.getAssociatedData(ASSOCIATED_DATA_LABELS, LOCALE_CZECH) != null &&
				it.getAssociatedData(ASSOCIATED_DATA_LABELS, Locale.ENGLISH) != null &&
				!it.getAssociatedDataValues(ASSOCIATED_DATA_REFERENCED_FILES).isEmpty()
		);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> productByPk = session.querySealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(entitiesMatchingTheRequirements)
						),
						require(
							page(1, Integer.MAX_VALUE),
							entityFetch(
								associatedDataContent(ASSOCIATED_DATA_LABELS),
								dataInLocales(LOCALE_CZECH, Locale.ENGLISH)
							)
						)
					)
				);

				assertEquals(entitiesMatchingTheRequirements.length, productByPk.getRecordData().size());
				assertEquals(entitiesMatchingTheRequirements.length, productByPk.getTotalRecordCount());

				for (SealedEntity product : productByPk.getRecordData()) {
					assertProductHasAssociatedDataInLocale(product, LOCALE_CZECH, ASSOCIATED_DATA_LABELS);
					assertProductHasAssociatedDataInLocale(product, Locale.ENGLISH, ASSOCIATED_DATA_LABELS);
					assertProductHasNotAssociatedData(product, ASSOCIATED_DATA_REFERENCED_FILES);
				}
				return null;
			}
		);
	}

	@DisplayName("Multiple entities with selected associated data in passed language only by their primary keys should be found")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldRetrieveMultipleEntitiesWithNamedAssociatedDataInLanguageByPrimaryKey(
		Evita evita, List<SealedEntity> originalProducts) {
		final Integer[] entitiesMatchingTheRequirements = getRequestedIdsByPredicate(
			originalProducts,
			it -> it.getAssociatedData(ASSOCIATED_DATA_LABELS, LOCALE_CZECH) != null &&
				it.getAssociatedData(ASSOCIATED_DATA_LABELS, Locale.ENGLISH) == null &&
				!it.getAssociatedDataValues(ASSOCIATED_DATA_REFERENCED_FILES).isEmpty()
		);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> productByPk = session.querySealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							and(
								entityPrimaryKeyInSet(entitiesMatchingTheRequirements),
								entityLocaleEquals(LOCALE_CZECH)
							)
						),
						require(
							page(1, Integer.MAX_VALUE),
							entityFetch(
								associatedDataContent(ASSOCIATED_DATA_LABELS)
							)
						)
					)
				);

				assertEquals(entitiesMatchingTheRequirements.length, productByPk.getRecordData().size());
				assertEquals(entitiesMatchingTheRequirements.length, productByPk.getTotalRecordCount());

				for (SealedEntity product : productByPk.getRecordData()) {
					assertProductHasAssociatedDataInLocale(product, LOCALE_CZECH, ASSOCIATED_DATA_LABELS);
					assertProductHasNotAssociatedDataInLocale(product, Locale.ENGLISH, ASSOCIATED_DATA_LABELS);
					assertProductHasNotAssociatedData(product, ASSOCIATED_DATA_REFERENCED_FILES);
				}
				return null;
			}
		);
	}

}
