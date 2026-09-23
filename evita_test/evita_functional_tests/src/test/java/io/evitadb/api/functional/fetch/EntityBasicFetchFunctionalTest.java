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

import io.evitadb.api.SessionTraits.SessionFlags;
import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.require.ManagedReferencesBehaviour;
import io.evitadb.api.query.require.ReferenceContent;
import io.evitadb.api.requestResponse.EntityFetchAwareDecorator;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.BinaryEntity;
import io.evitadb.api.requestResponse.data.structure.predicate.ReferenceDecodeCoverage;
import io.evitadb.spi.store.catalog.persistence.ReferenceDecodeCoverageContext;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.core.Evita;
import io.evitadb.test.Entities;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.EvitaParameterResolver;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.QUERY;
import static io.evitadb.test.TestTags.REFERENCE;

/**
 * This test verifies basic entity fetching functionality including:
 *
 * - existence checks by primary key
 * - retrieval of single and multiple entities by primary key
 * - boolean query combinations (NOT, OR, AND)
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("Evita basic entity fetch functionality")
@ExtendWith(EvitaParameterResolver.class)
@Slf4j
@Tag(CONTRACT)
@Tag(QUERY)
class EntityBasicFetchFunctionalTest extends AbstractEntityFetchingFunctionalTest {

	@DisplayName("Should check existence of the entity")
	@Test
	void shouldReturnOnlyPrimaryKey(@UseDataSet(HUNDRED_PRODUCTS) Evita evita) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReferenceContract> productByPk = session.queryEntityReference(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(2)
						)
					)
				);
				assertEquals(1, productByPk.getRecordData().size());
				assertEquals(1, productByPk.getTotalRecordCount());
				assertEquals(new EntityReference(Entities.PRODUCT, 2), productByPk.getRecordData().get(0));
				return null;
			}
		);
	}

	@DisplayName("Should not return missing entity")
	@Test
	void shouldNotReturnMissingEntity(@UseDataSet(HUNDRED_PRODUCTS) Evita evita) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Optional<EntityReferenceContract> productByPk = session.queryOneEntityReference(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(-100)
						)
					)
				);
				assertTrue(productByPk.isEmpty());
				return null;
			}
		);
	}

	@DisplayName("Should check existence of multiple entities")
	@Test
	void shouldReturnOnlyPrimaryKeys(@UseDataSet(HUNDRED_PRODUCTS) Evita evita) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReferenceContract> productByPk = session.queryEntityReference(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(2, 4, 9, 10, 18, 16)
						),
						require(
							page(1, 4)
						)
					)
				);
				assertEquals(4, productByPk.getRecordData().size());
				assertEquals(6, productByPk.getTotalRecordCount());
				assertEquals(new EntityReference(Entities.PRODUCT, 2), productByPk.getRecordData().get(0));
				assertEquals(new EntityReference(Entities.PRODUCT, 4), productByPk.getRecordData().get(1));
				assertEquals(new EntityReference(Entities.PRODUCT, 9), productByPk.getRecordData().get(2));
				assertEquals(new EntityReference(Entities.PRODUCT, 10), productByPk.getRecordData().get(3));
				return null;
			}
		);
	}

	@DisplayName("Single entity by primary key should be found")
	@Test
	void shouldRetrieveSingleEntityByPrimaryKey(@UseDataSet(HUNDRED_PRODUCTS) Evita evita) {
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
							entityFetch()
						)
					)
				);
				assertEquals(1, productByPk.getRecordData().size());
				assertEquals(1, productByPk.getTotalRecordCount());

				assertProduct(productByPk.getRecordData().get(0), 2, false, false, false, false);
				return null;
			}
		);
	}

	@DisplayName("Single entity in binary form by primary key should be found")
	@Test
	void shouldRetrieveSingleBinaryEntityByPrimaryKey(@UseDataSet(HUNDRED_PRODUCTS) Evita evita) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<BinaryEntity> productByPk = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(2)
						),
						require(
							entityFetchAll()
						)
					),
					BinaryEntity.class
				);
				assertEquals(1, productByPk.getRecordData().size());
				assertEquals(1, productByPk.getTotalRecordCount());

				final BinaryEntity binaryEntity = productByPk.getRecordData().get(0);
				assertEquals(Entities.PRODUCT, binaryEntity.getType());
				assertNotNull(binaryEntity.getEntityStoragePart());
				assertNotNull(binaryEntity.getAttributeStorageParts());
				assertTrue(binaryEntity.getAttributeStorageParts().length > 0);
				assertNotNull(binaryEntity.getAssociatedDataStorageParts());
				assertTrue(binaryEntity.getAssociatedDataStorageParts().length > 0);
				assertNotNull(binaryEntity.getPriceStoragePart());
				assertNotNull(binaryEntity.getReferenceStoragePart());
				return null;
			},
			SessionFlags.BINARY
		);
	}

	@DisplayName("Binary entity references are never narrowed by a coverage bound on the thread")
	@Test
	void shouldNeverNarrowBinaryEntityReferences(@UseDataSet(HUNDRED_PRODUCTS) Evita evita) {
		// The references container of a binary entity is re-serialized verbatim and handed to the client, so it
		// has to carry every reference the entity has. The read must therefore BIND an unrestricted coverage
		// rather than assume the thread holds none - a coverage left behind by a read further up the stack would
		// otherwise reach it. Both consumers refuse a narrowed part, so the symptom is a failed request rather
		// than a short entity, but the container must simply come back whole.
		final byte[] unrestricted = ReferenceDecodeCoverageContext.executeWithCoverage(
			null, () -> fetchBinaryReferenceStoragePart(evita)
		);
		final byte[] underBoundCoverage = ReferenceDecodeCoverageContext.executeWithCoverage(
			ReferenceDecodeCoverage.of(Set.of(), Map.of(Entities.CATEGORY, new int[]{Integer.MAX_VALUE})),
			() -> fetchBinaryReferenceStoragePart(evita)
		);

		assertNotNull(unrestricted);
		assertTrue(unrestricted.length > 0);
		// byte for byte the same container, whatever the thread happened to be carrying
		assertArrayEquals(unrestricted, underBoundCoverage);
	}

	/**
	 * Fetches one product in binary form and hands back the serialized references container, so that the same read
	 * can be performed under two different thread bound coverages and the results compared.
	 *
	 * @param evita the test instance to query
	 * @return the serialized references storage part of the fetched entity
	 */
	@Nullable
	private static byte[] fetchBinaryReferenceStoragePart(@Nonnull Evita evita) {
		return evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				// a referenceContent carrying an entityFetch is what makes `getReferenceEntityFetch()` non-empty,
				// which is the branch that DECODES the container instead of handing back its stored bytes - the
				// raw-bytes branch cannot be narrowed at all and would make this test vacuous
				final EvitaResponse<BinaryEntity> productByPk = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(2)
						),
						require(
							entityFetch(
								referenceContent(Entities.CATEGORY, entityFetch(attributeContentAll()))
							)
						)
					),
					BinaryEntity.class
				);
				assertEquals(1, productByPk.getRecordData().size());
				return productByPk.getRecordData().get(0).getReferenceStoragePart();
			},
			SessionFlags.BINARY
		);
	}

	/**
	 * Pins that reading an entity body in binary form is counted, both by the entity that read it and by the
	 * response that returned it.
	 *
	 * A binary fetch reads the body through a code path of its own - one that neither routes through the query's
	 * record de-duplicating scope nor hands the bytes to the collector assembling the entity's statistic. The read
	 * happened all the same, and an entity asking for nothing but its body is the shape where that read is the only
	 * one there is: whatever both numbers report here, they report about it alone.
	 */
	@DisplayName("Reading an entity body in binary form should be counted in the IO statistics")
	@Test
	void shouldCountTheBinaryEntityBodyRead(@UseDataSet(HUNDRED_PRODUCTS) Evita evita) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<BinaryEntity> productByPk = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(2)
						),
						require(
							entityFetch()
						)
					),
					BinaryEntity.class
				);
				assertEquals(1, productByPk.getRecordData().size());

				final BinaryEntity binaryEntity = productByPk.getRecordData().get(0);
				assertNotNull(binaryEntity.getEntityStoragePart());

				final EntityFetchAwareDecorator fetchAware = assertInstanceOf(
					EntityFetchAwareDecorator.class, binaryEntity
				);
				assertTrue(
					fetchAware.getIoFetchCount() > 0,
					"The body this entity is made of was read and has to be counted."
				);
				assertTrue(
					fetchAware.getIoFetchedBytes() > 0,
					"The body this entity is made of occupied Bytes that have to be counted."
				);
				assertTrue(
					productByPk.getIoFetchCount() > 0,
					"The response has to report the read its only entity performed."
				);
				return null;
			},
			SessionFlags.BINARY
		);
	}

	@DisplayName("Multiple entities by their primary keys should be found")
	@Test
	void shouldRetrieveMultipleEntitiesByPrimaryKey(@UseDataSet(HUNDRED_PRODUCTS) Evita evita) {
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
							entityFetch(),
							page(1, 4)
						)
					)
				);
				assertEquals(4, productByPk.getRecordData().size());
				assertEquals(6, productByPk.getTotalRecordCount());

				assertProduct(productByPk.getRecordData().get(0), 2, false, false, false, false);
				assertProduct(productByPk.getRecordData().get(1), 4, false, false, false, false);
				assertProduct(productByPk.getRecordData().get(2), 9, false, false, false, false);
				assertProduct(productByPk.getRecordData().get(3), 10, false, false, false, false);
				return null;
			}
		);
	}

	@DisplayName("Multiple entities by negative query against defined set should be found")
	@Test
	void shouldRetrieveMultipleEntitiesByNotAgainstDefinedSetQuery(@UseDataSet(HUNDRED_PRODUCTS) Evita evita) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> productByPk = session.querySealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							and(
								entityPrimaryKeyInSet(2, 4, 9, 10, 18, 16),
								not(entityPrimaryKeyInSet(9, 10))
							)
						),
						require(
							entityFetch(),
							page(1, 4)
						)
					)
				);
				assertEquals(4, productByPk.getRecordData().size());
				assertEquals(4, productByPk.getTotalRecordCount());

				assertProduct(productByPk.getRecordData().get(0), 2, false, false, false, false);
				assertProduct(productByPk.getRecordData().get(1), 4, false, false, false, false);
				assertProduct(productByPk.getRecordData().get(2), 16, false, false, false, false);
				assertProduct(productByPk.getRecordData().get(3), 18, false, false, false, false);
				return null;
			}
		);
	}

	@DisplayName("Multiple entities by negative query against entire superset should be found")
	@Test
	void shouldRetrieveMultipleEntitiesByNotAgainstSupersetQuery(@UseDataSet(HUNDRED_PRODUCTS) Evita evita) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> productByPk = session.querySealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							not(entityPrimaryKeyInSet(2, 4, 9, 10, 18, 16))
						),
						require(
							entityFetch(),
							page(1, 4)
						)
					)
				);
				assertEquals(4, productByPk.getRecordData().size());
				assertEquals(94, productByPk.getTotalRecordCount());

				assertProduct(productByPk.getRecordData().get(0), 1, false, false, false, false);
				assertProduct(productByPk.getRecordData().get(1), 3, false, false, false, false);
				assertProduct(productByPk.getRecordData().get(2), 5, false, false, false, false);
				assertProduct(productByPk.getRecordData().get(3), 6, false, false, false, false);
				return null;
			}
		);
	}

	@DisplayName("Multiple entities by complex boolean query should be found")
	@Test
	void shouldRetrieveMultipleEntitiesByComplexBooleanQuery(@UseDataSet(HUNDRED_PRODUCTS) Evita evita) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> productByPk = session.querySealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							or(
								entityPrimaryKeyInSet(2, 4, 9, 10, 18, 16),
								and(
									not(entityPrimaryKeyInSet(7, 32, 55)),
									entityPrimaryKeyInSet(7, 14, 32, 33)
								)
							)
						),
						require(
							entityFetch(),
							page(2, 4)
						)
					)
				);
				assertEquals(4, productByPk.getRecordData().size());
				assertEquals(8, productByPk.getTotalRecordCount());

				assertProduct(productByPk.getRecordData().get(0), 14, false, false, false, false);
				assertProduct(productByPk.getRecordData().get(1), 16, false, false, false, false);
				assertProduct(productByPk.getRecordData().get(2), 18, false, false, false, false);
				assertProduct(productByPk.getRecordData().get(3), 33, false, false, false, false);
				return null;
			}
		);
	}


	/**
	 * Covers the single decision standing between a reference read that materialized only some of an entity's
	 * references and a caller that later asks for more of them - the enrichment gate.
	 *
	 * The gate compares the coverage the previous read was performed under with the coverage the new requirement
	 * needs, and its two failure modes pull in opposite directions: a gate that never re-reads hands the caller
	 * a silently short reference set, and a gate that always re-reads is correct but deletes the whole saving.
	 * Only the first is asserted here, and deliberately so - the second has no observable at all. A redundant
	 * re-read is of a record the entity already holds, and the per-entity I/O statistic unions the enrichment's
	 * read records with the ones already billed precisely so that the same record is never counted twice, so no
	 * counter moves; the decorator identity short circuit upstream of the gate answers a different question.
	 * The claim that a coverage which changed nothing produces no new predicate - and therefore nothing for the
	 * gate to re-read for - is pinned where it is observable, on the predicate itself.
	 */
	@Nested
	@DisplayName("Enrichment after a narrowed reference read")
	@Tag(REFERENCE)
	class EnrichmentAfterNarrowedReferenceReadTest {

		@DisplayName("Enrichment asking for a referenced key the first read skipped goes back to the storage")
		@Test
		void shouldGoBackToStorageWhenEnrichmentAsksForAKeyTheFirstReadSkipped(@UseDataSet(HUNDRED_PRODUCTS) Evita evita) {
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final int[] fixture = findProductWithTwoCategories(session);
					final int productPk = fixture[0];
					final int firstCategoryPk = fixture[1];
					final int secondCategoryPk = fixture[2];

					final SealedEntity narrowed = fetchProductBoundToCategories(session, productPk, firstCategoryPk);
					assertEquals(
						Set.of(firstCategoryPk), categoryKeysOf(narrowed),
						"The first read was bound to a single referenced key and must have materialized only it."
					);

					final SealedEntity enriched = session.enrichEntity(
						narrowed,
						referenceContent(
							Entities.CATEGORY,
							filterBy(entityPrimaryKeyInSet(secondCategoryPk)),
							entityFetch(attributeContentAll())
						)
					);

					assertTrue(
						categoryKeysOf(enriched).contains(secondCategoryPk),
						"The key the first read skipped has to be read from the storage, not answered from the " +
							"narrowed reference set already in hand."
					);
					return null;
				}
			);
		}

		@DisplayName("Enrichment wanting a key-narrowed reference name whole widens it")
		@Test
		void shouldWidenAKeyNarrowedNameWhenEnrichmentWantsItWhole(@UseDataSet(HUNDRED_PRODUCTS) Evita evita) {
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final int[] fixture = findProductWithTwoCategories(session);
					final int productPk = fixture[0];
					final int firstCategoryPk = fixture[1];

					final Set<Integer> allStoredCategories = categoryKeysOf(
						session.queryOneSealedEntity(
							query(
								collection(Entities.PRODUCT),
								filterBy(entityPrimaryKeyInSet(productPk)),
								require(entityFetch(referenceContent(Entities.CATEGORY)))
							)
						).orElseThrow()
					);

					final SealedEntity narrowed = fetchProductBoundToCategories(session, productPk, firstCategoryPk);
					final SealedEntity enriched = session.enrichEntity(
						narrowed,
						referenceContent(Entities.CATEGORY)
					);

					// a coverage bounded by key can never satisfy a requirement that names no keys at all, so the
					// whole reference name has to be read again
					assertEquals(
						allStoredCategories, categoryKeysOf(enriched),
						"An enrichment naming no keys wants the reference name whole and must receive all of it."
					);
					return null;
				}
			);
		}

		/**
		 * Fetches one product with its `CATEGORY` references bound to an explicit key set, which is the shape that
		 * makes the storage read materialize only those keys.
		 *
		 * @param session        session to query through
		 * @param productPk      primary key of the product to fetch
		 * @param categoryPks    referenced category primary keys the read is bound to
		 * @return the fetched product
		 */
		@Nonnull
		private SealedEntity fetchProductBoundToCategories(
			@Nonnull EvitaSessionContract session,
			int productPk,
			@Nonnull int... categoryPks
		) {
			return session.queryOneSealedEntity(
				query(
					collection(Entities.PRODUCT),
					filterBy(entityPrimaryKeyInSet(productPk)),
					require(
						entityFetch(
							referenceContent(
								Entities.CATEGORY,
								filterBy(entityPrimaryKeyInSet(categoryPks)),
								entityFetch(attributeContentAll())
							)
						)
					)
				)
			).orElseThrow();
		}

		/**
		 * Finds a product storing at least two `CATEGORY` references, which is the minimum a narrowing can be
		 * observed on - with a single reference, a bound read and an unbound one return the same thing and every
		 * assertion in this class would hold for the wrong reason.
		 *
		 * @param session session to query through
		 * @return three element array of the product primary key and two of its referenced category primary keys
		 */
		@Nonnull
		private int[] findProductWithTwoCategories(@Nonnull EvitaSessionContract session) {
			return session.querySealedEntity(
					query(
						collection(Entities.PRODUCT),
						require(
							entityFetch(referenceContent(Entities.CATEGORY)),
							page(1, Integer.MAX_VALUE)
						)
					)
				)
				.getRecordData()
				.stream()
				.map(product -> {
					final int[] categoryPks = product.getReferences(Entities.CATEGORY)
						.stream()
						.mapToInt(ReferenceContract::getReferencedPrimaryKey)
						.distinct()
						.sorted()
						.toArray();
					return categoryPks.length < 2 ?
						null :
						new int[]{product.getPrimaryKeyOrThrowException(), categoryPks[0], categoryPks[1]};
				})
				.filter(Objects::nonNull)
				.findFirst()
				.orElseThrow(
					() -> new IllegalStateException(
						"The data set carries no product with two categories - the narrowing cannot be observed."
					)
				);
		}

		/**
		 * Collects the referenced category primary keys an entity carries.
		 *
		 * @param entity entity to read the references off
		 * @return the referenced category primary keys
		 */
		@Nonnull
		private Set<Integer> categoryKeysOf(@Nonnull SealedEntity entity) {
			final Collection<ReferenceContract> references = entity.getReferences(Entities.CATEGORY);
			return references.stream()
				.map(ReferenceContract::getReferencedPrimaryKey)
				.collect(Collectors.toSet());
		}
	}

	/**
	 * Covers the unnamed reference view of a name that several named requirements asked for differently.
	 *
	 * Each named requirement decorates the stored references it matched with its own attribute predicate and its
	 * own deeply fetched bodies, so two requirements matching one and the same stored reference produce two
	 * decorators over one delegate that show different things. The view can show one arrangement, and the request
	 * therefore derives the implicit requirement behind it as the **union** of the named ones.
	 *
	 * Two properties are asserted together, and neither is sufficient alone. The view must not depend on what the
	 * requirements are called - requirements are walked in {@code ReferenceContentKey} order, so a rule that keeps
	 * whichever decorator arrives first is decided by the alias names. And the view must carry everything the
	 * requirements fetched between them, which is checked against what each of them fetches ON ITS OWN: a view
	 * that showed nothing at all would satisfy the first property perfectly.
	 */
	@Nested
	@DisplayName("Unnamed view of a name several requirements asked for differently")
	@Tag(REFERENCE)
	class UnnamedViewOfAMultiplyRequestedReferenceTest {

		@DisplayName("Carries what every requirement fetched, whatever the requirements are called")
		@Test
		void shouldUnionWhatEveryNamedRequirementFetched(@UseDataSet(HUNDRED_PRODUCTS) Evita evita) {
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final int productPk = findProductWithAtLeastOneCategory(session);

					// what each requirement fetches alone - the two halves the union has to contain
					final List<String> bodiesAlone = unnamedCategoryView(
						session, productPk, categoryRequirement("solo", entityFetch(attributeContentAll()))
					);
					final List<String> attributesAlone = unnamedCategoryView(
						session, productPk, categoryRequirement("solo", attributeContentAll())
					);
					assertNotEquals(
						bodiesAlone, attributesAlone,
						"The two requirements must fetch observably different things, or this test cannot tell a " +
							"union from either half of it."
					);

					final List<String> aaaFetchesBodies = unnamedCategoryView(
						session, productPk,
						categoryRequirement("aaa", entityFetch(attributeContentAll())),
						categoryRequirement("zzz", attributeContentAll())
					);
					final List<String> aaaFetchesAttributes = unnamedCategoryView(
						session, productPk,
						categoryRequirement("aaa", attributeContentAll()),
						categoryRequirement("zzz", entityFetch(attributeContentAll()))
					);

					assertEquals(
						aaaFetchesBodies, aaaFetchesAttributes,
						"Renaming the requirements must not change the unnamed view. When it does, one " +
							"requirement's decorator is kept and the other's silently discarded."
					);
					assertEquals(
						unionOf(bodiesAlone, attributesAlone), aaaFetchesBodies,
						"The unnamed view has to carry everything the requirements fetched between them - the " +
							"referenced bodies one asked for AND the reference attributes the other did."
					);
					return null;
				}
			);
		}

		/**
		 * Merges two renderings of one reference set into what a view carrying both would look like.
		 *
		 * @param left  one rendering
		 * @param right the other rendering of the same references
		 * @return the rendering a view containing both would produce
		 */
		@Nonnull
		private List<String> unionOf(@Nonnull List<String> left, @Nonnull List<String> right) {
			final List<String> merged = new ArrayList<>(left.size());
			for (int i = 0; i < left.size(); i++) {
				final String[] mine = left.get(i).split(" ");
				final String[] theirs = right.get(i).split(" ");
				final boolean body = mine[1].endsWith("true") || theirs[1].endsWith("true");
				final String attributes = mine[2].endsWith("<unfetched>") ? theirs[2] : mine[2];
				merged.add(mine[0] + " body=" + body + " " + attributes);
			}
			return merged;
		}

		/**
		 * Builds one named `CATEGORY` requirement, the shape a GraphQL field alias produces.
		 *
		 * @param instanceName instance name the requirement carries
		 * @param content      what it asks to be fetched
		 * @return the named requirement
		 */
		@Nonnull
		private ReferenceContent categoryRequirement(
			@Nonnull String instanceName,
			@Nonnull RequireConstraint content
		) {
			return new ReferenceContent(
				instanceName, ManagedReferencesBehaviour.ANY, new String[]{Entities.CATEGORY},
				new RequireConstraint[]{content}, new Constraint<?>[0]
			);
		}

		/**
		 * Reads the unnamed `CATEGORY` view of one product fetched under the passed requirements, rendered so that
		 * a failure says WHAT differed rather than merely that something did.
		 *
		 * @param session      session to query through
		 * @param productPk    primary key of the product to fetch
		 * @param requirements named requirements to fetch it under
		 * @return one line per reference, ordered by referenced primary key
		 */
		@Nonnull
		private List<String> unnamedCategoryView(
			@Nonnull EvitaSessionContract session,
			int productPk,
			@Nonnull ReferenceContent... requirements
		) {
			return session.queryOneSealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(entityPrimaryKeyInSet(productPk)),
						require(entityFetch(requirements))
					)
				)
				.orElseThrow()
				.getReferences(Entities.CATEGORY)
				.stream()
				.sorted(Comparator.comparingInt(ReferenceContract::getReferencedPrimaryKey))
				.map(
					reference -> reference.getReferencedPrimaryKey() +
						" body=" + reference.getReferencedEntity().isPresent() +
						" attributes=" + (
							reference.attributesAvailable() ?
								reference.getAttributeNames().stream().sorted().toList().toString() :
								"<unfetched>"
						)
				)
				.toList();
		}

		/**
		 * Finds a product storing at least one `CATEGORY` reference - the minimum for two requirements to meet
		 * over one shared stored reference, which is the situation being pinned.
		 *
		 * @param session session to query through
		 * @return primary key of the product
		 */
		private int findProductWithAtLeastOneCategory(@Nonnull EvitaSessionContract session) {
			return session.querySealedEntity(
					query(
						collection(Entities.PRODUCT),
						require(entityFetch(referenceContent(Entities.CATEGORY)), page(1, Integer.MAX_VALUE))
					)
				)
				.getRecordData()
				.stream()
				.filter(product -> !product.getReferences(Entities.CATEGORY).isEmpty())
				.mapToInt(SealedEntity::getPrimaryKeyOrThrowException)
				.findFirst()
				.orElseThrow(
					() -> new IllegalStateException("The data set carries no product with a category reference.")
				);
		}

	}

}
