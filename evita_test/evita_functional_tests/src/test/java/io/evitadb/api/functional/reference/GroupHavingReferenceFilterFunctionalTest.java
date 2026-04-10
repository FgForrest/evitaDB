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

import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.ReferenceContract.GroupEntityReference;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.core.Evita;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.test.Entities;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.EvitaParameterResolver;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import javax.annotation.Nonnull;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestConstants.FUNCTIONAL_TEST;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_CODE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Functional tests for the `groupHaving` filtering constraint within `referenceHaving`.
 * Verifies that filtering products by attributes or primary keys of the group entity on
 * their references works correctly. This requires
 * {@link io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents#REFERENCED_GROUP_ENTITY}
 * to be enabled on the reference schema.
 *
 * Tests use the BRAND reference (group type = STORE) since it has `REFERENCED_GROUP_ENTITY`
 * indexing enabled and no sortable reference attributes that would conflict with group indexing.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Group having reference filtering")
@Tag(FUNCTIONAL_TEST)
@ExtendWith(EvitaParameterResolver.class)
@Slf4j
public class GroupHavingReferenceFilterFunctionalTest extends AbstractReferenceFilterFunctionalTest {

	/**
	 * Extracts the set of distinct group PKs for a given reference type from the original products.
	 */
	@Nonnull
	private static Set<Integer> extractGroupPks(
		@Nonnull List<SealedEntity> originalProducts,
		@SuppressWarnings("SameParameterValue") @Nonnull String referenceName
	) {
		return originalProducts.stream()
			.flatMap(p -> p.getReferences(referenceName).stream())
			.map(ref -> ref.getGroup().orElse(null))
			.filter(Objects::nonNull)
			.map(GroupEntityReference::getPrimaryKey)
			.collect(Collectors.toSet());
	}

	@DisplayName("Should filter products by brand group (store) primary key")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldFilterProductsByBrandGroupPrimaryKey(
		Evita evita,
		List<SealedEntity> originalProducts
	) {
		final Set<Integer> allGroupPks = extractGroupPks(originalProducts, Entities.BRAND);
		Assumptions.assumeFalse(allGroupPks.isEmpty(), "Test data should have brand groups (stores)");

		final int targetGroupPk = allGroupPks.iterator().next();

		// compute expected products — those that have a BRAND reference with the target group (store)
		final Integer[] expectedPks = getRequestedIdsByPredicate(
			originalProducts,
			product -> product.getReferences(Entities.BRAND)
				.stream()
				.anyMatch(
					ref -> ref.getGroup()
						.map(g -> g.getPrimaryKey() == targetGroupPk)
						.orElse(false)
				)
		);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> result = session.querySealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							referenceHaving(
								Entities.BRAND,
								groupHaving(entityPrimaryKeyInSet(targetGroupPk))
							)
						),
						require(
							entityFetch(
								referenceContentWithAttributes(
									Entities.BRAND,
									entityFetch(attributeContent()),
									entityGroupFetch(attributeContent())
								)
							),
							page(1, Integer.MAX_VALUE)
						)
					)
				);

				assertFalse(result.getRecordData().isEmpty(), "Should find products with the target brand group");
				assertEquals(expectedPks.length, result.getRecordData().size());

				// verify each product has a BRAND reference with the target group PK
				for (SealedEntity product : result.getRecordData()) {
					final Collection<ReferenceContract> brandRefs = product.getReferences(Entities.BRAND);
					assertTrue(
						brandRefs.stream().anyMatch(
							ref -> ref.getGroup()
								.map(g -> g.getPrimaryKey() == targetGroupPk)
								.orElse(false)
						),
						"Product " + product.getPrimaryKey() + " should have a brand reference " +
							"with group PK " + targetGroupPk
					);
				}
				return null;
			}
		);
	}

	@DisplayName("Should filter products by brand group (store) entity attribute code")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldFilterProductsByBrandGroupAttributeEquals(
		Evita evita,
		List<SealedEntity> originalProducts,
		List<SealedEntity> originalStores
	) {
		final Set<Integer> allGroupPks = extractGroupPks(originalProducts, Entities.BRAND);
		Assumptions.assumeFalse(allGroupPks.isEmpty(), "Test data should have brand groups (stores)");

		final int targetGroupPk = allGroupPks.iterator().next();

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				// read the target store (group entity) to get its code
				final SealedEntity targetStore = session.getEntity(
					Entities.STORE, targetGroupPk, entityFetchAllContent()
				).orElseThrow();
				final String targetCode = targetStore.getAttribute(ATTRIBUTE_CODE, String.class);
				assertNotNull(targetCode, "Store (brand group) should have a code attribute");

				// compute expected products
				final Integer[] expectedPks = getRequestedIdsByPredicate(
					originalProducts,
					product -> product.getReferences(Entities.BRAND)
						.stream()
						.anyMatch(
							ref -> ref.getGroup()
								.map(g -> g.getPrimaryKey() == targetGroupPk)
								.orElse(false)
						)
				);

				final EvitaResponse<SealedEntity> result = session.querySealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							referenceHaving(
								Entities.BRAND,
								groupHaving(attributeEquals(ATTRIBUTE_CODE, targetCode))
							)
						),
						require(
							entityFetch(
								referenceContentWithAttributes(
									Entities.BRAND,
									entityFetch(attributeContent()),
									entityGroupFetch(attributeContent())
								)
							),
							page(1, Integer.MAX_VALUE)
						)
					)
				);

				assertFalse(result.getRecordData().isEmpty(), "Should find products by group attribute code");
				assertEquals(expectedPks.length, result.getRecordData().size());
				return null;
			}
		);
	}

	@DisplayName("Should filter products by multiple brand group (store) PKs")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldFilterProductsByMultipleBrandGroupPks(
		Evita evita,
		List<SealedEntity> originalProducts
	) {
		final Set<Integer> allGroupPks = extractGroupPks(originalProducts, Entities.BRAND);
		Assumptions.assumeTrue(allGroupPks.size() >= 2, "Test data should have at least 2 brand groups");

		final Integer[] targetGroupPks = allGroupPks.stream().limit(2).toArray(Integer[]::new);
		final int gPk1 = targetGroupPks[0];
		final int gPk2 = targetGroupPks[1];

		final Integer[] expectedPks = getRequestedIdsByPredicate(
			originalProducts,
			product -> product.getReferences(Entities.BRAND)
				.stream()
				.anyMatch(
					ref -> ref.getGroup()
						.map(g -> g.getPrimaryKey() == gPk1 || g.getPrimaryKey() == gPk2)
						.orElse(false)
				)
		);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> result = session.querySealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							referenceHaving(
								Entities.BRAND,
								groupHaving(entityPrimaryKeyInSet(gPk1, gPk2))
							)
						),
						require(
							entityFetch(referenceContentWithAttributes(Entities.BRAND)),
							page(1, Integer.MAX_VALUE)
						)
					)
				);

				assertEquals(expectedPks.length, result.getRecordData().size());
				return null;
			}
		);
	}

	@DisplayName("Should return no products when group having matches no group")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnNoProductsWhenGroupHavingMatchesNothing(Evita evita) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> result = session.querySealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							referenceHaving(
								Entities.BRAND,
								groupHaving(entityPrimaryKeyInSet(Integer.MAX_VALUE))
							)
						),
						require(
							entityFetch(),
							page(1, Integer.MAX_VALUE)
						)
					)
				);

				assertTrue(result.getRecordData().isEmpty(), "No products should match a non-existent group PK");
				return null;
			}
		);
	}

	@DisplayName("Should throw when groupHaving targets reference without group type")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldThrowWhenGroupHavingTargetsReferenceWithoutGroupType(Evita evita) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaInvalidUsageException exception = assertThrows(
					EvitaInvalidUsageException.class,
					() -> session.querySealedEntity(
						query(
							collection(Entities.PRODUCT),
							filterBy(
								referenceHaving(
									Entities.PRICE_LIST,
									groupHaving(
										entityPrimaryKeyInSet(1)
									)
								)
							),
							require(entityFetch())
						)
					)
				);

				assertTrue(
					exception.getMessage().contains(
						"does not reference any group type"
					),
					"Exception should mention missing group type, " +
						"but was: " + exception.getMessage()
				);
				return null;
			}
		);
	}

	@DisplayName("Should throw when groupHaving targets unmanaged group entity type")
	@Test
	void shouldThrowWhenGroupHavingTargetsUnmanagedGroupType(@TempDir Path tempDir) {
		final String unmanagedGroupRef = "unmanagedGroupRef";
		final String externalGroup = "ExternalGroupEntity";
		try (
			final Evita tempEvita = new Evita(
				EvitaConfiguration.builder()
					.storage(StorageOptions.builder().storageDirectory(tempDir).build())
					.build()
			)
		) {
			tempEvita.defineCatalog(TEST_CATALOG);
			tempEvita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.defineEntitySchema(Entities.PRODUCT)
						.withReferenceTo(
							unmanagedGroupRef,
							"ExternalEntity",
							Cardinality.ZERO_OR_MORE,
							whichIs -> whichIs
								.indexedForFiltering()
								.withGroupType(externalGroup)
						)
						.updateVia(session);
					session.upsertEntity(
						session.createNewEntity(Entities.PRODUCT, 1)
							.setReference(
								unmanagedGroupRef, 1,
								whichIs -> whichIs.setGroup(externalGroup, 1)
							)
					);
				}
			);

			tempEvita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final EvitaInvalidUsageException exception = assertThrows(
						EvitaInvalidUsageException.class,
						() -> session.querySealedEntity(
							query(
								collection(Entities.PRODUCT),
								filterBy(
									referenceHaving(
										unmanagedGroupRef,
										groupHaving(
											entityPrimaryKeyInSet(1)
										)
									)
								),
								require(entityFetch())
							)
						)
					);

					assertTrue(
						exception.getMessage().contains(
							"not managed by evitaDB"
						),
						"Exception should mention unmanaged group, " +
							"but was: " + exception.getMessage()
					);
					return null;
				}
			);
		}
	}

}
