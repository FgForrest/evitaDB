/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

package io.evitadb.api.functional.reference;

import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.ReferenceContract;
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

import java.util.Collection;
import java.util.List;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestConstants.FUNCTIONAL_TEST;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_CODE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Functional tests for the `entityHaving` filtering constraint within `referenceHaving`.
 * Verifies that filtering references by attributes on the referenced entity works correctly.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Entity having reference filtering")
@Tag(FUNCTIONAL_TEST)
@ExtendWith(EvitaParameterResolver.class)
@Slf4j
public class EntityHavingReferenceFilterFunctionalTest extends AbstractReferenceFilterFunctionalTest {

	@DisplayName("Should filter products to those having a store with a specific code")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldFilterProductsByStoreEntityHavingAttributeEquals(
		Evita evita,
		List<SealedEntity> originalProducts,
		List<SealedEntity> originalStores
	) {
		final SealedEntity targetStore = originalStores.get(0);
		final String targetCode = targetStore.getAttribute(ATTRIBUTE_CODE, String.class);
		final int targetPk = targetStore.getPrimaryKey();

		// compute expected products — those that reference the target store
		final Integer[] expectedPks = getRequestedIdsByPredicate(
			originalProducts,
			product -> product.getReferences(Entities.STORE)
				.stream()
				.anyMatch(ref -> ref.getReferencedPrimaryKey() == targetPk)
		);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> result = session.querySealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							referenceHaving(
								Entities.STORE,
								entityHaving(attributeEquals(ATTRIBUTE_CODE, targetCode))
							)
						),
						require(
							entityFetch(
								referenceContentWithAttributes(Entities.STORE, entityFetch(attributeContent()))
							),
							page(1, Integer.MAX_VALUE)
						)
					)
				);

				assertFalse(result.getRecordData().isEmpty(), "Should find products referencing the target store");
				assertEquals(expectedPks.length, result.getRecordData().size());

				for (SealedEntity product : result.getRecordData()) {
					final Collection<ReferenceContract> storeRefs = product.getReferences(Entities.STORE);
					assertFalse(storeRefs.isEmpty());
					// at least one reference must point to the target store
					assertTrue(
						storeRefs.stream().anyMatch(ref -> ref.getReferencedPrimaryKey() == targetPk),
						"Product " + product.getPrimaryKey() + " should have a reference to store " + targetPk
					);
				}
				return null;
			}
		);
	}

	@DisplayName("Should filter products to those having a parameter with a specific code")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldFilterProductsByParameterEntityHavingAttributeEquals(
		Evita evita,
		List<SealedEntity> originalProducts,
		List<SealedEntity> originalParameters
	) {
		final SealedEntity targetParameter = originalParameters.get(0);
		final String targetCode = targetParameter.getAttribute(ATTRIBUTE_CODE, String.class);
		final int targetPk = targetParameter.getPrimaryKey();

		final Integer[] expectedPks = getRequestedIdsByPredicate(
			originalProducts,
			product -> product.getReferences(Entities.PARAMETER)
				.stream()
				.anyMatch(ref -> ref.getReferencedPrimaryKey() == targetPk)
		);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> result = session.querySealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							referenceHaving(
								Entities.PARAMETER,
								entityHaving(attributeEquals(ATTRIBUTE_CODE, targetCode))
							)
						),
						require(
							entityFetch(
								referenceContentWithAttributes(Entities.PARAMETER, entityFetch(attributeContent()))
							),
							page(1, Integer.MAX_VALUE)
						)
					)
				);

				assertFalse(result.getRecordData().isEmpty(), "Should find products referencing the target parameter");
				assertEquals(expectedPks.length, result.getRecordData().size());
				return null;
			}
		);
	}

	@DisplayName("Should filter products by entity having with multiple PKs using entityPrimaryKeyInSet")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldFilterProductsByEntityHavingPrimaryKeyInSet(
		Evita evita,
		List<SealedEntity> originalProducts,
		List<SealedEntity> originalStores
	) {
		final int pk1 = originalStores.get(0).getPrimaryKey();
		final int pk2 = originalStores.size() > 1 ? originalStores.get(1).getPrimaryKey() : pk1;

		final Integer[] expectedPks = getRequestedIdsByPredicate(
			originalProducts,
			product -> product.getReferences(Entities.STORE)
				.stream()
				.anyMatch(ref -> ref.getReferencedPrimaryKey() == pk1 || ref.getReferencedPrimaryKey() == pk2)
		);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> result = session.querySealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							referenceHaving(
								Entities.STORE,
								entityHaving(entityPrimaryKeyInSet(pk1, pk2))
							)
						),
						require(
							entityFetch(referenceContentWithAttributes(Entities.STORE)),
							page(1, Integer.MAX_VALUE)
						)
					)
				);

				assertEquals(expectedPks.length, result.getRecordData().size());
				return null;
			}
		);
	}

	@DisplayName("Should return no products when entity having matches no entities")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnNoProductsWhenEntityHavingMatchesNothing(Evita evita) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> result = session.querySealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							referenceHaving(
								Entities.STORE,
								entityHaving(entityPrimaryKeyInSet(Integer.MAX_VALUE))
							)
						),
						require(
							entityFetch(),
							page(1, Integer.MAX_VALUE)
						)
					)
				);

				assertTrue(result.getRecordData().isEmpty(), "No products should match a non-existent store PK");
				return null;
			}
		);
	}

}
