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
import io.evitadb.api.requestResponse.data.EntityContract;
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
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_NAME;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_URL;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * This test verifies entity attribute fetching functionality including:
 *
 * - retrieval of entities with all attributes
 * - retrieval of entities with attributes in specific language
 * - retrieval of entities with attributes in multiple languages
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("Evita entity attribute fetch functionality")
@Tag(FUNCTIONAL_TEST)
@ExtendWith(EvitaParameterResolver.class)
@Slf4j
class EntityAttributeFetchFunctionalTest extends AbstractEntityFetchingFunctionalTest {

	@DisplayName("Single entity with attributes only by primary key should be found")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldRetrieveSingleEntityWithAttributesByPrimaryKey(Evita evita) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> productByPk = session.querySealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(2)
						),
						require(
							entityFetch(
								attributeContentAll()
							)
						)
					)
				);
				assertEquals(1, productByPk.getRecordData().size());
				assertEquals(1, productByPk.getTotalRecordCount());

				assertProduct(productByPk.getRecordData().get(0), 2, true, false, false, false);
				return null;
			}
		);
	}

	@DisplayName("Multiple entities with attributes only by their primary keys should be found")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldRetrieveMultipleEntitiesWithAttributesByPrimaryKey(Evita evita) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> productByPk = session.querySealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(2, 4, 9, 10, 18, 16)
						),
						require(
							entityFetch(
								attributeContentAll()
							),
							page(1, 4)
						)
					)
				);
				assertEquals(4, productByPk.getRecordData().size());
				assertEquals(6, productByPk.getTotalRecordCount());

				assertProduct(productByPk.getRecordData().get(0), 2, true, false, false, false);
				assertProduct(productByPk.getRecordData().get(1), 4, true, false, false, false);
				assertProduct(productByPk.getRecordData().get(2), 9, true, false, false, false);
				assertProduct(productByPk.getRecordData().get(3), 10, true, false, false, false);
				return null;
			}
		);
	}

	@DisplayName("Single entity with attributes in passed language only by primary key should be found")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldRetrieveSingleEntityWithAttributesInLanguageByPrimaryKey(
		Evita evita, List<SealedEntity> originalProducts) {
		final Integer[] entitiesMatchingTheRequirements = getRequestedIdsByPredicate(
			originalProducts,
			it -> it.getAttribute(ATTRIBUTE_NAME, LOCALE_CZECH) != null && it.getAttribute(
				ATTRIBUTE_URL, LOCALE_CZECH) != null &&
				it.getAttribute(ATTRIBUTE_NAME, Locale.ENGLISH) == null && it.getAttribute(
				ATTRIBUTE_URL, Locale.ENGLISH) == null
		);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> productByPk = session.querySealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							and(
								entityPrimaryKeyInSet(entitiesMatchingTheRequirements[0]),
								entityLocaleEquals(LOCALE_CZECH)
							)
						),
						require(
							entityFetch(
								attributeContentAll()
							)
						)
					)
				);
				assertEquals(1, productByPk.getRecordData().size());
				assertEquals(1, productByPk.getTotalRecordCount());

				final SealedEntity product = productByPk.getRecordData().get(0);
				assertProductHasAttributesInLocale(product, LOCALE_CZECH, ATTRIBUTE_NAME, ATTRIBUTE_URL);
				assertProductHasNotAttributesInLocale(product, Locale.ENGLISH, ATTRIBUTE_NAME, ATTRIBUTE_URL);
				return null;
			}
		);
	}

	@DisplayName("Single entity with attributes in multiple languages only by primary key should be found")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldRetrieveSingleEntityWithAttributesInMultipleLanguagesByPrimaryKey(
		Evita evita, List<SealedEntity> originalProducts) {
		final Integer[] entitiesMatchingTheRequirements = getRequestedIdsByPredicate(
			originalProducts,
			it -> it.getAttribute(ATTRIBUTE_NAME, LOCALE_CZECH) != null && it.getAttribute(
				ATTRIBUTE_URL, LOCALE_CZECH) != null &&
				it.getAttribute(ATTRIBUTE_NAME, Locale.ENGLISH) != null && it.getAttribute(
				ATTRIBUTE_URL, Locale.ENGLISH) != null
		);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> productByPk = session.querySealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							and(
								entityPrimaryKeyInSet(entitiesMatchingTheRequirements[0]),
								entityLocaleEquals(LOCALE_CZECH)
							)
						),
						require(
							entityFetch(
								attributeContentAll(),
								dataInLocales(LOCALE_CZECH, Locale.ENGLISH)
							)
						)
					)
				);
				assertEquals(1, productByPk.getRecordData().size());
				assertEquals(1, productByPk.getTotalRecordCount());

				final SealedEntity product = productByPk.getRecordData().get(0);
				assertProductHasAttributesInLocale(product, LOCALE_CZECH, ATTRIBUTE_NAME, ATTRIBUTE_URL);
				assertProductHasAttributesInLocale(product, Locale.ENGLISH, ATTRIBUTE_NAME, ATTRIBUTE_URL);
				return null;
			}
		);
	}

	@DisplayName("Multiple entities with attributes in passed language only by their primary keys should be found")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldRetrieveMultipleEntitiesWithAttributesInLanguageByPrimaryKey(
		Evita evita, List<SealedEntity> originalProducts) {
		final Integer[] pks = originalProducts
			.stream()
			.filter(
				it ->
					(it.getAttribute(ATTRIBUTE_NAME, LOCALE_CZECH) != null && it.getAttribute(
						ATTRIBUTE_URL, LOCALE_CZECH) != null) &&
						!(it.getAttribute(ATTRIBUTE_NAME, Locale.ENGLISH) != null && it.getAttribute(
							ATTRIBUTE_URL, Locale.ENGLISH) != null)
			)
			.map(EntityContract::getPrimaryKey)
			.toArray(Integer[]::new);
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> productByPk = session.querySealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							and(
								entityPrimaryKeyInSet(pks),
								entityLocaleEquals(LOCALE_CZECH)
							)
						),
						require(
							entityFetch(
								attributeContentAll()
							)
						)
					)
				);

				for (SealedEntity product : productByPk.getRecordData()) {
					assertProductHasAttributesInLocale(product, LOCALE_CZECH, ATTRIBUTE_NAME, ATTRIBUTE_URL);
					assertProductHasNotAttributesInLocale(product, Locale.ENGLISH, ATTRIBUTE_NAME, ATTRIBUTE_URL);
				}
				return null;
			}
		);
	}

}
