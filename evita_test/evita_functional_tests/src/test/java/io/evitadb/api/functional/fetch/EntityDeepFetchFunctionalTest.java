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
import io.evitadb.api.exception.ContextMissingException;
import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.order.OrderBy;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.query.require.ManagedReferencesBehaviour;
import io.evitadb.api.query.require.ReferenceContent;
import io.evitadb.api.requestResponse.EvitaRequest.ReferenceContentKey;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.BinaryEntity;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.comparator.LocalizedStringComparator;
import io.evitadb.core.Evita;
import io.evitadb.core.query.response.ServerEntityDecorator;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.test.Entities;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.DataCarrier;
import io.evitadb.test.extension.EvitaParameterResolver;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.annotation.Nonnull;

import java.text.Collator;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_CATEGORY_PRIORITY;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_CODE;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_NAME;
import static io.evitadb.test.generator.DataGenerator.CZECH_LOCALE;
import static java.util.Optional.ofNullable;
import static org.junit.jupiter.api.Assertions.*;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.QUERY;

/**
 * This test verifies deep fetching of referenced entity bodies, including eager and lazy deep
 * fetching, filtered and ordered reference fetching, reference attribute filtering/ordering,
 * handling of missing references, and binary entity deep fetching.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("Evita entity deep fetch functionality")
@ExtendWith(EvitaParameterResolver.class)
@Slf4j
@Tag(CONTRACT)
@Tag(QUERY)
class EntityDeepFetchFunctionalTest extends AbstractEntityFetchingFunctionalTest {
	private static final String UNGROUPED_REFERENCES = "ungroupedReferences";

	@DisplayName("References can be eagerly deeply fetched")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldEagerlyDeepFetchReferenceEntityBodies(
		Evita evita, List<SealedEntity> originalProducts, List<SealedEntity> originalBrands) {
		final Set<Integer> brandsWithGroupedCategory = originalBrands.stream()
			.filter(it -> it.getReferences(Entities.STORE).stream().anyMatch(ref -> ref.getGroup().isPresent()))
			.map(EntityContract::getPrimaryKey)
			.collect(Collectors.toSet());

		final Integer[] entitiesMatchingTheRequirements = getRequestedIdsByPredicate(
			originalProducts,
			it -> !it.getReferences(Entities.BRAND).isEmpty() &&
				it.getReferences(Entities.BRAND)
					.stream()
					.anyMatch(brand -> brandsWithGroupedCategory.contains(brand.getReferencedPrimaryKey())) &&
				!it.getReferences(Entities.STORE).isEmpty()
		);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> productByPk = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(entitiesMatchingTheRequirements[0])
						),
						require(
							entityFetch(
								referenceContent(
									Entities.BRAND,
									entityFetch(
										attributeContent(),
										referenceContent(
											Entities.STORE,
											entityFetch(attributeContent()),
											entityGroupFetch(attributeContent(), associatedDataContentAll())
										)
									),
									entityGroupFetch(attributeContent())
								),
								referenceContent(
									Entities.STORE,
									entityFetch(attributeContent(), associatedDataContentAll())
								)
							)
						)
					),
					SealedEntity.class
				);

				assertEquals(1, productByPk.getRecordData().size());
				assertEquals(1, productByPk.getTotalRecordCount());

				final SealedEntity product = productByPk.getRecordData().getFirst();
				assertFalse(product.getReferences().isEmpty());

				final Optional<SealedEntity> referencedStore = product.getReference(
						Entities.STORE, REFERENCED_ID_EXTRACTOR.apply(product, Entities.STORE)[0])
					.orElseThrow()
					.getReferencedEntity();

				assertTrue(referencedStore.isPresent());
				assertFalse(referencedStore.get().getAttributeValues().isEmpty());
				assertFalse(referencedStore.get().getAssociatedDataValues().isEmpty());

				final ReferenceContract referencedBrand = product.getReferences(Entities.BRAND)
					.stream()
					.filter(it -> brandsWithGroupedCategory.contains(it.getReferencedPrimaryKey()))
					.findFirst()
					.orElseThrow();

				final Optional<SealedEntity> brand = referencedBrand.getReferencedEntity();
				assertTrue(brand.isPresent());
				assertFalse(brand.get().getAttributeValues().isEmpty());
				assertFalse(brand.get().associatedDataAvailable());
				assertThrows(ContextMissingException.class, () -> brand.get().getAssociatedDataValues());

				final ReferenceContract referenceToBrandStore = brand.get()
					.getReferences(Entities.STORE)
					.stream()
					.filter(it -> it.getGroup().isPresent())
					.findFirst()
					.orElseThrow();

				final Optional<SealedEntity> referencedBrandStore = referenceToBrandStore.getReferencedEntity();
				final Optional<SealedEntity> referencedBrandStoreCategory = referenceToBrandStore.getGroupEntity();

				assertTrue(referencedBrandStore.isPresent());
				assertFalse(referencedBrandStore.get().getAttributeValues().isEmpty());
				assertFalse(referencedBrandStore.get().associatedDataAvailable());
				assertThrows(ContextMissingException.class, () -> referencedBrandStore.get().getAssociatedDataValues());

				assertTrue(referencedBrandStoreCategory.isPresent());
				assertFalse(referencedBrandStoreCategory.get().getAttributeValues().isEmpty());
				assertFalse(referencedBrandStoreCategory.get().getAssociatedDataValues().isEmpty());

				return null;
			}
		);
	}

	@DisplayName("References without index (non-filterable) can be eagerly deeply fetched")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldEagerlyDeepFetchNonFilterableReferenceEntityBodies(Evita evita, List<SealedEntity> originalProducts) {
		final Integer[] entitiesMatchingTheRequirements = getRequestedIdsByPredicate(
			originalProducts,
			it -> !it.getReferences(Entities.PRICE_LIST).isEmpty()
		);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> productByPk = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(entitiesMatchingTheRequirements[0])
						),
						require(
							entityFetch(
								referenceContent(
									Entities.PRICE_LIST,
									entityFetch(entityFetchAllContent())
								)
							)
						)
					),
					SealedEntity.class
				);

				assertEquals(1, productByPk.getRecordData().size());
				assertEquals(1, productByPk.getTotalRecordCount());

				final SealedEntity product = productByPk.getRecordData().getFirst();
				assertFalse(product.getReferences().isEmpty());

				final Collection<ReferenceContract> priceLists = product.getReferences(Entities.PRICE_LIST);
				assertFalse(priceLists.isEmpty());

				boolean atLeastOnePriceListBodyFound = false;
				for (ReferenceContract priceList : priceLists) {
					final Optional<SealedEntity> referencedEntity = priceList.getReferencedEntity();
					atLeastOnePriceListBodyFound = atLeastOnePriceListBodyFound || referencedEntity.isPresent();
					referencedEntity.ifPresent(
						sealedEntity -> assertFalse(sealedEntity.getAttributeValues().isEmpty()));
				}
				assertTrue(atLeastOnePriceListBodyFound, "At least one price list body should have been found");

				return null;
			}
		);
	}

	@DisplayName("References can be eagerly deeply fetched in gradual manner")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldLazilyDeepFetchReferenceEntityBodies(
		Evita evita, List<SealedEntity> originalProducts, List<SealedEntity> originalBrands) {
		final Set<Integer> brandsWithGroupedCategory = originalBrands.stream()
			.filter(it -> it.getReferences(Entities.STORE).stream().anyMatch(ref -> ref.getGroup().isPresent()))
			.map(EntityContract::getPrimaryKey)
			.collect(Collectors.toSet());

		final Integer[] entitiesMatchingTheRequirements = getRequestedIdsByPredicate(
			originalProducts,
			it -> !it.getReferences(Entities.BRAND).isEmpty() &&
				it.getReferences(Entities.BRAND)
					.stream()
					.anyMatch(brand -> brandsWithGroupedCategory.contains(brand.getReferencedPrimaryKey())) &&
				!it.getReferences(Entities.STORE).isEmpty()
		);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> productByPk = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(entitiesMatchingTheRequirements[0])
						),
						require(
							entityFetch(
								referenceContent(
									Entities.BRAND,
									entityFetch(
										attributeContent()
									)
								)
							)
						)
					),
					SealedEntity.class
				);

				assertEquals(1, productByPk.getRecordData().size());
				assertEquals(1, productByPk.getTotalRecordCount());

				final SealedEntity product = productByPk.getRecordData().getFirst();
				assertFalse(product.getReferences().isEmpty());

				final ReferenceContract referencedBrand = product.getReferences(Entities.BRAND)
					.stream()
					.filter(it -> brandsWithGroupedCategory.contains(it.getReferencedPrimaryKey()))
					.findFirst()
					.orElseThrow();

				final Optional<SealedEntity> brand = referencedBrand.getReferencedEntity();
				assertTrue(brand.isPresent());
				assertFalse(brand.get().getAttributeValues().isEmpty());
				assertFalse(brand.get().associatedDataAvailable());
				assertThrows(ContextMissingException.class, () -> brand.get().getAssociatedDataValues());

				// lazy fetch
				final SealedEntity enrichedProduct = session.enrichEntity(
					product,
					referenceContent(
						Entities.BRAND,
						entityFetch(
							attributeContent(),
							referenceContent(
								Entities.STORE,
								entityFetch(attributeContent()),
								entityGroupFetch(attributeContent(), associatedDataContentAll())
							)
						),
						entityGroupFetch(attributeContent())
					),
					referenceContent(
						Entities.STORE,
						entityFetch(attributeContent(), associatedDataContentAll())
					)
				);

				final Optional<SealedEntity> referencedStore = enrichedProduct.getReference(
						Entities.STORE, REFERENCED_ID_EXTRACTOR.apply(
							enrichedProduct, Entities.STORE)[0]
					)
					.orElseThrow()
					.getReferencedEntity();

				assertTrue(referencedStore.isPresent());
				assertFalse(referencedStore.get().getAttributeValues().isEmpty());
				assertFalse(referencedStore.get().getAssociatedDataValues().isEmpty());

				final ReferenceContract referencedBrandAgain = enrichedProduct.getReferences(Entities.BRAND)
					.stream()
					.filter(it -> brandsWithGroupedCategory.contains(it.getReferencedPrimaryKey()))
					.findFirst()
					.orElseThrow();

				final Optional<SealedEntity> brandAgain = referencedBrandAgain.getReferencedEntity();
				assertTrue(brandAgain.isPresent());
				assertFalse(brandAgain.get().getAttributeValues().isEmpty());
				assertFalse(brandAgain.get().associatedDataAvailable());
				assertThrows(ContextMissingException.class, () -> brandAgain.get().getAssociatedDataValues());

				final ReferenceContract referenceToBrandStoreAgain = brandAgain.get()
					.getReferences(Entities.STORE)
					.stream()
					.filter(it -> it.getGroup().isPresent())
					.findFirst()
					.orElseThrow();

				final Optional<SealedEntity> referencedBrandStore = referenceToBrandStoreAgain.getReferencedEntity();
				final Optional<SealedEntity> referencedBrandStoreCategory = referenceToBrandStoreAgain.getGroupEntity();

				assertTrue(referencedBrandStore.isPresent());
				assertFalse(referencedBrandStore.get().getAttributeValues().isEmpty());
				assertFalse(referencedBrandStore.get().associatedDataAvailable());
				assertThrows(ContextMissingException.class, () -> referencedBrandStore.get().getAssociatedDataValues());

				assertTrue(referencedBrandStoreCategory.isPresent());
				assertFalse(referencedBrandStoreCategory.get().getAttributeValues().isEmpty());
				assertFalse(referencedBrandStoreCategory.get().getAssociatedDataValues().isEmpty());

				return null;
			}
		);
	}

	@DisplayName("References can be eagerly deeply fetched, filtered and ordered")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldEagerlyDeepFetchReferenceEntityBodiesFilteredAndOrdered(
		Evita evita, List<SealedEntity> originalProducts) {
		final Map<Integer, Set<Integer>> productsWithLotsOfStores = originalProducts.stream()
			.filter(it -> it.getReferences(Entities.STORE).size() > 4 && it.getLocales().contains(CZECH_LOCALE))
			.collect(
				Collectors.toMap(
					EntityContract::getPrimaryKey,
					it -> it.getReferences(Entities.STORE)
						.stream()
						.map(ref -> ref.getReferenceKey().primaryKey())
						.collect(Collectors.toSet())
				)
			);

		final AtomicBoolean atLeastFirst = new AtomicBoolean();
		final Random rnd = new Random(5);
		final Integer[] randomStores = productsWithLotsOfStores
			.values()
			.stream()
			.flatMap(Collection::stream)
			.filter(it -> atLeastFirst.compareAndSet(false, true) || rnd.nextInt(10) == 0)
			.distinct()
			.sorted()
			.toArray(Integer[]::new);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> productByPk = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							and(
								entityPrimaryKeyInSet(productsWithLotsOfStores.keySet().toArray(Integer[]::new)),
								entityLocaleEquals(CZECH_LOCALE)
							)
						),
						require(
							page(1, Integer.MAX_VALUE),
							entityFetch(
								referenceContent(
									Entities.STORE,
									filterBy(entityPrimaryKeyInSet(randomStores)),
									orderBy(
										entityProperty(
											attributeNatural(ATTRIBUTE_NAME, OrderDirection.DESC)
										)
									),
									entityFetch(attributeContent(), associatedDataContentAll())
								)
							)
						)
					),
					SealedEntity.class
				);

				assertEquals(productsWithLotsOfStores.size(), productByPk.getRecordData().size());
				assertEquals(productsWithLotsOfStores.size(), productByPk.getTotalRecordCount());

				final LocalizedStringComparator czechComparator = new LocalizedStringComparator(
					Collator.getInstance(CZECH_LOCALE));
				for (final SealedEntity product : productByPk.getRecordData()) {
					final Collection<ReferenceContract> references = product.getReferences(Entities.STORE);

					// references should be properly filtered
					final Set<Integer> storeIds = productsWithLotsOfStores.get(product.getPrimaryKey());
					final Set<Integer> filteredStoreIds = storeIds.stream()
						.filter(it -> Arrays.asList(randomStores).contains(it))
						.collect(Collectors.toSet());
					assertEquals(
						filteredStoreIds.size(),
						references.size(),
						"Product `" + product.getPrimaryKey() + "` has references to stores that are not " +
							"in the filtered set: `" +
							product.getReferences(Entities.STORE)
								.stream()
								.map(ReferenceContract::getReferencedPrimaryKey)
								.collect(Collectors.toSet()) +
							"` vs. expected `" + filteredStoreIds
					);

					// references should be ordered by name
					final String[] receivedOrderedNames = references.stream()
						.map(it -> it.getReferencedEntity().orElseThrow())
						.map(it -> it.getAttribute(ATTRIBUTE_NAME, String.class))
						.toArray(String[]::new);
					final String[] expectedNames = Arrays.stream(receivedOrderedNames)
						.sorted((o1, o2) -> czechComparator.compare(o1, o2) * -1)
						.toArray(String[]::new);
					assertArrayEquals(
						expectedNames,
						receivedOrderedNames
					);

					for (ReferenceContract reference : references) {
						assertTrue(filteredStoreIds.contains(reference.getReferencedPrimaryKey()));
						final Optional<SealedEntity> storeEntity = reference.getReferencedEntity();
						assertTrue(storeEntity.isPresent());
						assertFalse(storeEntity.get().getAttributeValues().isEmpty());
						assertFalse(storeEntity.get().getAssociatedDataValues().isEmpty());
					}
				}

				return null;
			}
		);
	}

	@DisplayName("References can be eagerly deeply fetched, filtered and ordered primarily by group and secondarily by entity attribute")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldEagerlyDeepFetchReferenceEntityBodiesFilteredAndOrderedByGroupPropertyPrimarilyAndEntityPropertySecondarily(
		Evita evita,
		List<SealedEntity> originalProducts
	) {
		final Map<Integer, Set<Integer>> productsWithLotsOfParameters = originalProducts.stream()
			.filter(it -> it.getReferences(Entities.PARAMETER).size() > 4 && it.getLocales().contains(CZECH_LOCALE))
			.collect(
				Collectors.toMap(
					EntityContract::getPrimaryKey,
					it -> it.getReferences(Entities.PARAMETER)
						.stream()
						.map(ref -> ref.getReferenceKey().primaryKey())
						.collect(Collectors.toSet())
				)
			);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> productByPk = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							and(
								entityPrimaryKeyInSet(productsWithLotsOfParameters.keySet().toArray(Integer[]::new)),
								entityLocaleEquals(CZECH_LOCALE)
							)
						),
						require(
							page(1, Integer.MAX_VALUE),
							entityFetch(
								referenceContent(
									Entities.PARAMETER,
									orderBy(
										entityGroupProperty(
											attributeNatural(ATTRIBUTE_NAME, OrderDirection.DESC)
										),
										entityProperty(
											attributeNatural(ATTRIBUTE_NAME, OrderDirection.ASC)
										)
									),
									entityFetch(attributeContent()),
									entityGroupFetch(attributeContent())
								)
							)
						)
					),
					SealedEntity.class
				);

				assertEquals(productsWithLotsOfParameters.size(), productByPk.getRecordData().size());
				assertEquals(productsWithLotsOfParameters.size(), productByPk.getTotalRecordCount());

				final LocalizedStringComparator czechComparator = new LocalizedStringComparator(
					Collator.getInstance(CZECH_LOCALE));
				for (final SealedEntity product : productByPk.getRecordData()) {
					final Collection<ReferenceContract> references = product.getReferences(Entities.PARAMETER);

					// references should be ordered by name
					final String[][] receivedOrderedParameterComposedNames = references.stream()
						.map(it -> {
							final String parameterTypeName = it.getGroupEntity().orElseThrow().getAttribute(
								ATTRIBUTE_NAME, String.class);
							final String parameterName = it.getReferencedEntity().orElseThrow().getAttribute(
								ATTRIBUTE_NAME, String.class);
							return new String[]{parameterTypeName, parameterName};
						})
						.toArray(String[][]::new);
					final String[][] expectedResult = Arrays.stream(receivedOrderedParameterComposedNames)
						.sorted((o1, o2) -> {
							final int typeResult = czechComparator.compare(o1[0], o2[0]) * -1;
							if (typeResult == 0) {
								return czechComparator.compare(o1[1], o2[1]);
							} else {
								return typeResult;
							}
						})
						.toArray(String[][]::new);
					assertEquals(expectedResult.length, receivedOrderedParameterComposedNames.length);
					for (int i = 0; i < expectedResult.length; i++) {
						final String[] expected = expectedResult[i];
						final String[] actual = receivedOrderedParameterComposedNames[i];
						assertArrayEquals(expected, actual);
					}
				}

				return null;
			}
		);
	}

	@DisplayName("References ordered by a group property carry group bodies without referenced entity bodies")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldOrderReferencesByGroupPropertyAndFetchGroupBodiesWithoutReferencedEntityBodies(
		Evita evita,
		List<SealedEntity> originalProducts
	) {
		final Map<Integer, Integer> parameterCountByProduct = getCzechProductsWithLotsOfParameters(originalProducts);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> products = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							and(
								entityPrimaryKeyInSet(parameterCountByProduct.keySet().toArray(Integer[]::new)),
								entityLocaleEquals(CZECH_LOCALE)
							)
						),
						require(
							page(1, Integer.MAX_VALUE),
							entityFetch(
								referenceContent(
									Entities.PARAMETER,
									orderBy(
										entityGroupProperty(
											attributeNatural(ATTRIBUTE_NAME, OrderDirection.DESC)
										)
									),
									entityGroupFetch(attributeContent())
								)
							)
						)
					),
					SealedEntity.class
				);

				assertEquals(parameterCountByProduct.size(), products.getRecordData().size());
				for (final SealedEntity product : products.getRecordData()) {
					final Collection<ReferenceContract> references = product.getReferences(Entities.PARAMETER);
					assertEquals(parameterCountByProduct.get(product.getPrimaryKey()), references.size());

					final String[] receivedGroupNames = references.stream()
						.map(
							it -> it.getGroupEntity()
								.orElseThrow(
									() -> new AssertionError(
										"Product `" + product.getPrimaryKey() + "` lacks the body of the group of " +
											"the parameter `" + it.getReferencedPrimaryKey() + "` although " +
											"`entityGroupFetch` was requested."
									)
								)
								.getAttribute(ATTRIBUTE_NAME, String.class)
						)
						.toArray(String[]::new);
					assertGroupNamesInDescendingOrder(product, references, receivedGroupNames);
				}

				return null;
			}
		);
	}

	@DisplayName("References can be ordered by a group property without fetching any referenced body")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldOrderReferencesByGroupPropertyWithoutFetchingAnyReferencedBody(
		Evita evita,
		List<SealedEntity> originalProducts
	) {
		final Map<Integer, Integer> parameterCountByProduct = getCzechProductsWithLotsOfParameters(originalProducts);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Map<Integer, String> groupNames = session.queryListOfSealedEntities(
						query(
							collection(Entities.PARAMETER_GROUP),
							filterBy(entityLocaleEquals(CZECH_LOCALE)),
							require(
								page(1, Integer.MAX_VALUE),
								entityFetch(attributeContent(ATTRIBUTE_NAME))
							)
						)
					)
					.stream()
					.collect(
						Collectors.toMap(
							EntityContract::getPrimaryKey,
							it -> it.getAttribute(ATTRIBUTE_NAME, String.class)
						)
					);

				final EvitaResponse<SealedEntity> products = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							and(
								entityPrimaryKeyInSet(parameterCountByProduct.keySet().toArray(Integer[]::new)),
								entityLocaleEquals(CZECH_LOCALE)
							)
						),
						require(
							page(1, Integer.MAX_VALUE),
							entityFetch(
								referenceContent(
									Entities.PARAMETER,
									orderBy(
										entityGroupProperty(
											attributeNatural(ATTRIBUTE_NAME, OrderDirection.DESC)
										)
									)
								)
							)
						)
					),
					SealedEntity.class
				);

				assertEquals(parameterCountByProduct.size(), products.getRecordData().size());
				for (final SealedEntity product : products.getRecordData()) {
					final Collection<ReferenceContract> references = product.getReferences(Entities.PARAMETER);
					assertEquals(parameterCountByProduct.get(product.getPrimaryKey()), references.size());

					final String[] receivedGroupNames = references.stream()
						.map(it -> groupNames.get(it.getGroup().orElseThrow().getPrimaryKey()))
						.toArray(String[]::new);
					assertGroupNamesInDescendingOrder(product, references, receivedGroupNames);
				}

				return null;
			}
		);
	}

	@DisplayName("References can be ordered by an entity property without fetching referenced entity bodies")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldOrderReferencesByEntityPropertyWithoutFetchingReferencedEntityBodies(
		Evita evita,
		List<SealedEntity> originalProducts,
		List<SealedEntity> originalParameters
	) {
		final Map<Integer, Integer> parameterCountByProduct = getCzechProductsWithLotsOfParameters(originalProducts);
		final Map<Integer, String> parameterNames = originalParameters.stream()
			.collect(
				Collectors.toMap(
					EntityContract::getPrimaryKey,
					it -> it.getAttribute(ATTRIBUTE_NAME, CZECH_LOCALE, String.class)
				)
			);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> products = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							and(
								entityPrimaryKeyInSet(parameterCountByProduct.keySet().toArray(Integer[]::new)),
								entityLocaleEquals(CZECH_LOCALE)
							)
						),
						require(
							page(1, Integer.MAX_VALUE),
							entityFetch(
								referenceContent(
									Entities.PARAMETER,
									orderBy(
										entityProperty(
											attributeNatural(ATTRIBUTE_NAME, OrderDirection.DESC)
										)
									)
								)
							)
						)
					),
					SealedEntity.class
				);

				assertEquals(parameterCountByProduct.size(), products.getRecordData().size());
				final LocalizedStringComparator czechComparator = new LocalizedStringComparator(
					Collator.getInstance(CZECH_LOCALE));
				for (final SealedEntity product : products.getRecordData()) {
					final Collection<ReferenceContract> references = product.getReferences(Entities.PARAMETER);
					assertEquals(parameterCountByProduct.get(product.getPrimaryKey()), references.size());

					final String[] receivedNames = references.stream()
						.map(it -> parameterNames.get(it.getReferencedPrimaryKey()))
						.toArray(String[]::new);
					final String[] expectedNames = Arrays.stream(receivedNames)
						.sorted((o1, o2) -> czechComparator.compare(o1, o2) * -1)
						.toArray(String[]::new);
					assertArrayEquals(
						expectedNames,
						receivedNames,
						"Parameters of product `" + product.getPrimaryKey() + "` are not ordered by their name: " +
							references.stream().map(ReferenceContract::getReferencedPrimaryKey).toList()
					);
				}

				return null;
			}
		);
	}

	/**
	 * Sets up a small catalog in which some parameter references of a product carry no group, so that the position
	 * of the references without a group can be asserted exactly:
	 *
	 * - parameter groups `1` (`Alpha`), `2` (`Beta`) and `3`, which has no name,
	 * - parameters `1` to `6` whose names do not follow their primary keys,
	 * - product `1` referencing parameters `1` and `4` without a group, `3` in group `1` and `2`, `5` in group `2`,
	 * - product `2` referencing the same plus parameter `6` in group `99`, which does not exist,
	 * - product `5` referencing parameter `1` in group `3`, `3` in group `1` and `4` without a group,
	 * - products `3` and `4` referencing parameters `2` and `3` in swapped groups `1` and `2`,
	 * - product `6` referencing parameter `1` in group `2` - which product `1` references without a group - and `4`
	 *   without a group.
	 *
	 * @param evita the evitaDB instance the catalog is created in
	 * @return an empty data carrier - the fixture is addressed by primary key
	 */
	@DataSet(UNGROUPED_REFERENCES)
	DataCarrier setUpUngroupedReferences(Evita evita) {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(Entities.PARAMETER_GROUP)
					.withoutGeneratedPrimaryKey()
					.withAttribute(ATTRIBUTE_NAME, String.class, thatIs -> thatIs.sortable().nullable())
					.updateAndFetchVia(session);
				session.defineEntitySchema(Entities.PARAMETER)
					.withoutGeneratedPrimaryKey()
					.withAttribute(ATTRIBUTE_NAME, String.class, AttributeSchemaEditor::sortable)
					.updateAndFetchVia(session);
				session.defineEntitySchema(Entities.PRODUCT)
					.withoutGeneratedPrimaryKey()
					.withReferenceToEntity(
						Entities.PARAMETER,
						Entities.PARAMETER,
						Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs.withGroupTypeRelatedToEntity(Entities.PARAMETER_GROUP).indexedForFiltering()
					)
					.updateAndFetchVia(session);

				session.createNewEntity(Entities.PARAMETER_GROUP, 1)
					.setAttribute(ATTRIBUTE_NAME, "Alpha")
					.upsertVia(session);
				session.createNewEntity(Entities.PARAMETER_GROUP, 2)
					.setAttribute(ATTRIBUTE_NAME, "Beta")
					.upsertVia(session);
				session.createNewEntity(Entities.PARAMETER_GROUP, 3)
					.upsertVia(session);

				final String[] parameterNames = {"Aa", "Ab", "Mx", "Zz", "Zy", "Mm"};
				for (int i = 0; i < parameterNames.length; i++) {
					session.createNewEntity(Entities.PARAMETER, i + 1)
						.setAttribute(ATTRIBUTE_NAME, parameterNames[i])
						.upsertVia(session);
				}

				session.createNewEntity(Entities.PRODUCT, 1)
					.setReference(Entities.PARAMETER, 1)
					.setReference(Entities.PARAMETER, 2, whichIs -> whichIs.setGroup(2))
					.setReference(Entities.PARAMETER, 3, whichIs -> whichIs.setGroup(1))
					.setReference(Entities.PARAMETER, 4)
					.setReference(Entities.PARAMETER, 5, whichIs -> whichIs.setGroup(2))
					.upsertVia(session);
				session.createNewEntity(Entities.PRODUCT, 2)
					.setReference(Entities.PARAMETER, 1)
					.setReference(Entities.PARAMETER, 2, whichIs -> whichIs.setGroup(2))
					.setReference(Entities.PARAMETER, 3, whichIs -> whichIs.setGroup(1))
					.setReference(Entities.PARAMETER, 4)
					.setReference(Entities.PARAMETER, 5, whichIs -> whichIs.setGroup(2))
					.setReference(Entities.PARAMETER, 6, whichIs -> whichIs.setGroup(99))
					.upsertVia(session);
				session.createNewEntity(Entities.PRODUCT, 5)
					.setReference(Entities.PARAMETER, 1, whichIs -> whichIs.setGroup(3))
					.setReference(Entities.PARAMETER, 3, whichIs -> whichIs.setGroup(1))
					.setReference(Entities.PARAMETER, 4)
					.upsertVia(session);
				session.createNewEntity(Entities.PRODUCT, 3)
					.setReference(Entities.PARAMETER, 2, whichIs -> whichIs.setGroup(2))
					.setReference(Entities.PARAMETER, 3, whichIs -> whichIs.setGroup(1))
					.upsertVia(session);
				session.createNewEntity(Entities.PRODUCT, 4)
					.setReference(Entities.PARAMETER, 2, whichIs -> whichIs.setGroup(1))
					.setReference(Entities.PARAMETER, 3, whichIs -> whichIs.setGroup(2))
					.upsertVia(session);
				session.createNewEntity(Entities.PRODUCT, 6)
					.setReference(Entities.PARAMETER, 1, whichIs -> whichIs.setGroup(2))
					.setReference(Entities.PARAMETER, 4)
					.upsertVia(session);
			}
		);
		return new DataCarrier();
	}

	@DisplayName("References without a group are ordered after the grouped ones, by the next ordering")
	@ParameterizedTest(name = "fetching referenced entity bodies: {0}")
	@ValueSource(booleans = {false, true})
	@UseDataSet(UNGROUPED_REFERENCES)
	void shouldOrderReferencesWithoutGroupAfterGroupedOnes(boolean fetchReferencedBodies, Evita evita) {
		// groups by name: Alpha (3), Beta (2 "Ab", 5 "Zy" - by name descending 5, 2), then the references without
		// a group by name descending: 4 "Zz", 1 "Aa"
		assertReferencedParameterOrder(
			evita, ManagedReferencesBehaviour.ANY, fetchReferencedBodies, Map.of(1, new int[]{3, 5, 2, 4, 1})
		);
	}

	@DisplayName("References whose group is filtered out are ordered after the grouped ones, by the next ordering")
	@ParameterizedTest(name = "fetching referenced entity bodies: {0}")
	@ValueSource(booleans = {false, true})
	@UseDataSet(UNGROUPED_REFERENCES)
	void shouldOrderReferencesWithFilteredOutGroupAfterGroupedOnes(boolean fetchReferencedBodies, Evita evita) {
		// the group `99` of parameter 6 does not exist, so the EXISTING behaviour drops it from the ordered groups
		// and parameter 6 joins the references without a group: 4 "Zz", 6 "Mm", 1 "Aa"
		assertReferencedParameterOrder(
			evita, ManagedReferencesBehaviour.EXISTING, fetchReferencedBodies, Map.of(2, new int[]{3, 5, 2, 4, 6, 1})
		);
	}

	@DisplayName("References in a group that does not exist are ordered with the references without a group")
	@ParameterizedTest(name = "fetching referenced entity bodies: {0}")
	@ValueSource(booleans = {false, true})
	@UseDataSet(UNGROUPED_REFERENCES)
	void shouldOrderReferencesInNonExistentGroupWithUngroupedOnes(boolean fetchReferencedBodies, Evita evita) {
		// the ANY behaviour keeps the group `99` of parameter 6, but a group that does not exist has no name to be
		// ordered by, so parameter 6 is ordered with the references without a group: 4 "Zz", 6 "Mm", 1 "Aa"
		assertReferencedParameterOrder(
			evita, ManagedReferencesBehaviour.ANY, fetchReferencedBodies, Map.of(2, new int[]{3, 5, 2, 4, 6, 1})
		);
	}

	@DisplayName("References in a group lacking the ordered attribute are ordered with the references without a group")
	@ParameterizedTest(name = "fetching referenced entity bodies: {0}")
	@ValueSource(booleans = {false, true})
	@UseDataSet(UNGROUPED_REFERENCES)
	void shouldOrderReferencesInGroupLackingOrderedValueWithUngroupedOnes(boolean fetchReferencedBodies, Evita evita) {
		// the group `3` of parameter 1 has no name, so parameter 1 is ordered with the references without a group
		// by its own name descending: 4 "Zz", 1 "Aa"
		assertReferencedParameterOrder(
			evita, ManagedReferencesBehaviour.ANY, fetchReferencedBodies, Map.of(5, new int[]{3, 4, 1})
		);
	}

	@DisplayName("References are ordered by the primary key of their group when the group ordering asks for it")
	@ParameterizedTest(name = "fetching referenced entity bodies: {0}")
	@ValueSource(booleans = {false, true})
	@UseDataSet(UNGROUPED_REFERENCES)
	void shouldOrderReferencesByGroupPrimaryKeyAscending(boolean fetchReferencedBodies, Evita evita) {
		// groups by primary key: 1 (3), 2 (5 "Zy", 2 "Ab" by name descending), then the references without a group by
		// name descending: 4 "Zz", 1 "Aa" - the group `3` of product 5 has no name, but it has a primary key, so its
		// parameter 1 precedes the parameter 4 without a group
		assertReferencedParameterOrder(
			evita, ManagedReferencesBehaviour.ANY,
			orderBy(
				entityGroupProperty(entityPrimaryKeyNatural(OrderDirection.ASC)),
				entityProperty(attributeNatural(ATTRIBUTE_NAME, OrderDirection.DESC))
			),
			fetchReferencedBodies,
			Map.of(1, new int[]{3, 5, 2, 4, 1}, 5, new int[]{3, 1, 4})
		);
		// the primary key ranks the groups the name does not - the group `3` without a name follows the named ones
		assertReferencedParameterOrder(
			evita, ManagedReferencesBehaviour.ANY,
			orderBy(
				entityGroupProperty(
					attributeNatural(ATTRIBUTE_NAME, OrderDirection.ASC), entityPrimaryKeyNatural(OrderDirection.ASC)
				),
				entityProperty(attributeNatural(ATTRIBUTE_NAME, OrderDirection.DESC))
			),
			fetchReferencedBodies,
			Map.of(1, new int[]{3, 5, 2, 4, 1}, 5, new int[]{3, 1, 4})
		);
	}

	@DisplayName("References are ordered by the group each owner assigns them to")
	@ParameterizedTest(name = "fetching referenced entity bodies: {0}")
	@ValueSource(booleans = {false, true})
	@UseDataSet(UNGROUPED_REFERENCES)
	void shouldOrderReferencesByGroupTheirOwnerAssigns(boolean fetchReferencedBodies, Evita evita) {
		// product 3 holds parameter 3 in Alpha and 2 in Beta, product 4 the other way round
		assertReferencedParameterOrder(
			evita, ManagedReferencesBehaviour.ANY, fetchReferencedBodies,
			Map.of(3, new int[]{3, 2}, 4, new int[]{2, 3})
		);
	}

	@DisplayName("A reference without a group is ordered with the ungrouped ones even when another owner groups it")
	@ParameterizedTest(name = "fetching referenced entity bodies: {0}")
	@ValueSource(booleans = {false, true})
	@UseDataSet(UNGROUPED_REFERENCES)
	void shouldOrderReferenceWithoutGroupAsUngroupedWhenAnotherOwnerGroupsIt(
		boolean fetchReferencedBodies,
		Evita evita
	) {
		// product 6 holds parameter 1 in Beta, product 1 holds it without a group - so for product 1 it still follows
		// all grouped references
		assertReferencedParameterOrder(
			evita, ManagedReferencesBehaviour.ANY, fetchReferencedBodies,
			Map.of(1, new int[]{3, 5, 2, 4, 1}, 6, new int[]{1, 4})
		);
	}

	@DisplayName("A named reference set is ordered by its own ordering when the query orders the same reference too")
	@ParameterizedTest(name = "fetching referenced entity bodies: {0}")
	@ValueSource(booleans = {false, true})
	@UseDataSet(UNGROUPED_REFERENCES)
	void shouldOrderNamedReferenceSetByItsOwnOrdering(boolean fetchReferencedBodies, Evita evita) {
		final String ascendingInstance = "ascending";
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> products = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(entityPrimaryKeyInSet(1)),
						require(
							page(1, Integer.MAX_VALUE),
							entityFetch(
								referenceContent(
									Entities.PARAMETER,
									orderBy(
										entityGroupProperty(attributeNatural(ATTRIBUTE_NAME, OrderDirection.ASC)),
										entityProperty(attributeNatural(ATTRIBUTE_NAME, OrderDirection.DESC))
									),
									fetchReferencedBodies ? entityFetch(attributeContent(ATTRIBUTE_NAME)) : null
								),
								new ReferenceContent(
									ascendingInstance,
									ManagedReferencesBehaviour.ANY,
									new String[]{Entities.PARAMETER},
									fetchReferencedBodies ?
										new RequireConstraint[]{entityFetch(attributeContent(ATTRIBUTE_NAME))} :
										new RequireConstraint[0],
									new Constraint<?>[]{
										orderBy(
											entityGroupProperty(attributeNatural(ATTRIBUTE_NAME, OrderDirection.ASC)),
											entityProperty(attributeNatural(ATTRIBUTE_NAME, OrderDirection.ASC))
										)
									}
								)
							)
						)
					),
					SealedEntity.class
				);

				final SealedEntity product = products.getRecordData().getFirst();
				final int[] descendingOrder = product.getReferences(Entities.PARAMETER)
					.stream()
					.mapToInt(ReferenceContract::getReferencedPrimaryKey)
					.toArray();
				assertArrayEquals(
					new int[]{3, 5, 2, 4, 1}, descendingOrder,
					"Unnamed parameter set is in a wrong order: " + Arrays.toString(descendingOrder)
				);
				// Alpha (3), Beta by name ascending (2 "Ab", 5 "Zy"), the ungrouped by name ascending (1 "Aa", 4 "Zz")
				final int[] ascendingOrder = ((ServerEntityDecorator) product)
					.getReferencesForReferenceContentInstance(
						new ReferenceContentKey(ascendingInstance, Entities.PARAMETER)
					)
					.orElseThrow()
					.getData()
					.stream()
					.mapToInt(ReferenceContract::getReferencedPrimaryKey)
					.toArray();
				assertArrayEquals(
					new int[]{3, 2, 5, 1, 4}, ascendingOrder,
					"Named parameter set is in a wrong order: " + Arrays.toString(ascendingOrder)
				);
				return null;
			}
		);
	}

	/**
	 * Fetches the parameter references of the products in a single query, ordered by the name of their group
	 * ascending and then by their own name descending, and asserts the primary keys of the referenced parameters of
	 * each product come in the expected order.
	 *
	 * @param evita                      the evitaDB instance holding {@link #UNGROUPED_REFERENCES}
	 * @param managedReferencesBehaviour behaviour applied to references pointing to non-existing entities
	 * @param fetchReferencedBodies      TRUE when the referenced parameter bodies are fetched too
	 * @param expectedOrderByProduct     the expected primary keys of the referenced parameters in order, indexed by
	 *                                   the primary key of the product
	 */
	private static void assertReferencedParameterOrder(
		@Nonnull Evita evita,
		@Nonnull ManagedReferencesBehaviour managedReferencesBehaviour,
		boolean fetchReferencedBodies,
		@Nonnull Map<Integer, int[]> expectedOrderByProduct
	) {
		assertReferencedParameterOrder(
			evita, managedReferencesBehaviour,
			orderBy(
				entityGroupProperty(attributeNatural(ATTRIBUTE_NAME, OrderDirection.ASC)),
				entityProperty(attributeNatural(ATTRIBUTE_NAME, OrderDirection.DESC))
			),
			fetchReferencedBodies, expectedOrderByProduct
		);
	}

	/**
	 * Fetches the parameter references of the products in a single query, ordered by the passed ordering, and asserts
	 * the primary keys of the referenced parameters of each product come in the expected order.
	 *
	 * @param evita                      the evitaDB instance holding {@link #UNGROUPED_REFERENCES}
	 * @param managedReferencesBehaviour behaviour applied to references pointing to non-existing entities
	 * @param referenceOrderBy           the ordering of the parameter references
	 * @param fetchReferencedBodies      TRUE when the referenced parameter bodies are fetched too
	 * @param expectedOrderByProduct     the expected primary keys of the referenced parameters in order, indexed by
	 *                                   the primary key of the product
	 */
	private static void assertReferencedParameterOrder(
		@Nonnull Evita evita,
		@Nonnull ManagedReferencesBehaviour managedReferencesBehaviour,
		@Nonnull OrderBy referenceOrderBy,
		boolean fetchReferencedBodies,
		@Nonnull Map<Integer, int[]> expectedOrderByProduct
	) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> products = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(entityPrimaryKeyInSet(expectedOrderByProduct.keySet().toArray(Integer[]::new))),
						require(
							page(1, Integer.MAX_VALUE),
							entityFetch(
								referenceContent(
									managedReferencesBehaviour,
									Entities.PARAMETER,
									referenceOrderBy,
									fetchReferencedBodies ? entityFetch(attributeContent(ATTRIBUTE_NAME)) : null
								)
							)
						)
					),
					SealedEntity.class
				);

				assertEquals(expectedOrderByProduct.size(), products.getRecordData().size());
				for (SealedEntity product : products.getRecordData()) {
					final int[] receivedOrder = product.getReferences(Entities.PARAMETER)
						.stream()
						.mapToInt(ReferenceContract::getReferencedPrimaryKey)
						.toArray();
					assertArrayEquals(
						expectedOrderByProduct.get(product.getPrimaryKey()),
						receivedOrder,
						"Parameters of product `" + product.getPrimaryKey() + "` are in a wrong order: " +
							Arrays.toString(receivedOrder)
					);
				}

				return null;
			}
		);
	}

	/**
	 * Returns the products localized in Czech that reference more than four parameters, mapped to the number of
	 * parameters each of them references.
	 *
	 * @param originalProducts all products of the data set
	 * @return number of referenced parameters indexed by the product primary key
	 */
	@Nonnull
	private static Map<Integer, Integer> getCzechProductsWithLotsOfParameters(
		@Nonnull List<SealedEntity> originalProducts
	) {
		return originalProducts.stream()
			.filter(it -> it.getReferences(Entities.PARAMETER).size() > 4 && it.getLocales().contains(CZECH_LOCALE))
			.collect(
				Collectors.toMap(
					EntityContract::getPrimaryKey,
					it -> it.getReferences(Entities.PARAMETER).size()
				)
			);
	}

	/**
	 * Asserts that the group names of the references of a product, read in the order the references were returned,
	 * descend by the Czech collation - i.e. that the references are ordered by the name of their group.
	 *
	 * @param product            the product owning the references
	 * @param references         the references in the order they were returned
	 * @param receivedGroupNames the names of the groups of `references`, in the same order
	 */
	private static void assertGroupNamesInDescendingOrder(
		@Nonnull SealedEntity product,
		@Nonnull Collection<ReferenceContract> references,
		@Nonnull String[] receivedGroupNames
	) {
		final LocalizedStringComparator czechComparator = new LocalizedStringComparator(
			Collator.getInstance(CZECH_LOCALE));
		final String[] expectedGroupNames = Arrays.stream(receivedGroupNames)
			.sorted((o1, o2) -> czechComparator.compare(o1, o2) * -1)
			.toArray(String[]::new);
		assertArrayEquals(
			expectedGroupNames,
			receivedGroupNames,
			"Parameters of product `" + product.getPrimaryKey() + "` are not ordered by the name of their group: " +
				references.stream()
					.map(
						it -> it.getReferencedPrimaryKey() + "(group " +
							it.getGroup().map(EntityClassifier::getPrimaryKey).orElse(null) + ")"
					)
					.toList()
		);
	}

	@DisplayName("References can be eagerly deeply fetched, filtered by attribute and ordered")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldEagerlyDeepFetchReferenceEntityBodiesFilteredByAttributeAndOrdered(
		Evita evita, List<SealedEntity> originalProducts, List<SealedEntity> originalStores) {
		final Map<Integer, SealedEntity> storesIndexedByPk = originalStores.stream()
			.collect(Collectors.toMap(
				EntityContract::getPrimaryKey,
				Function.identity()
			));

		final Map<Integer, Set<String>> productsWithLotsOfStores = originalProducts.stream()
			.filter(it -> it.getReferences(Entities.STORE).size() > 4 && it.getLocales().contains(CZECH_LOCALE))
			.collect(
				Collectors.toMap(
					EntityContract::getPrimaryKey,
					it -> it.getReferences(Entities.STORE)
						.stream()
						.map(ref -> ref.getReferenceKey().primaryKey())
						.map(storesIndexedByPk::get)
						.map(store -> store.getAttribute(ATTRIBUTE_CODE, String.class))
						.collect(Collectors.toSet())
				)
			);

		final AtomicBoolean atLeastFirst = new AtomicBoolean();
		final Random rnd = new Random(5);
		final String[] randomStores = productsWithLotsOfStores
			.values()
			.stream()
			.flatMap(Collection::stream)
			.filter(it -> atLeastFirst.compareAndSet(false, true) || rnd.nextInt(10) == 0)
			.distinct()
			.toArray(String[]::new);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> productByPk = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							and(
								entityPrimaryKeyInSet(productsWithLotsOfStores.keySet().toArray(Integer[]::new)),
								entityLocaleEquals(CZECH_LOCALE)
							)
						),
						require(
							page(1, Integer.MAX_VALUE),
							entityFetch(
								referenceContent(
									Entities.STORE,
									filterBy(
										entityHaving(
											attributeInSet(ATTRIBUTE_CODE, randomStores)
										)
									),
									orderBy(
										entityProperty(
											attributeNatural(ATTRIBUTE_NAME, OrderDirection.DESC)
										)
									),
									entityFetch(attributeContent(), associatedDataContentAll())
								)
							)
						)
					),
					SealedEntity.class
				);

				assertEquals(productsWithLotsOfStores.size(), productByPk.getRecordData().size());
				assertEquals(productsWithLotsOfStores.size(), productByPk.getTotalRecordCount());

				for (final SealedEntity product : productByPk.getRecordData()) {
					final Collection<ReferenceContract> references = product.getReferences(Entities.STORE);

					// references should be ordered by name
					final String[] receivedOrderedNames = references.stream()
						.map(it -> it.getReferencedEntity().orElseThrow())
						.map(it -> it.getAttribute(ATTRIBUTE_NAME, String.class))
						.toArray(String[]::new);
					assertArrayEquals(
						Arrays.stream(receivedOrderedNames)
							.sorted(new LocalizedStringComparator(Collator.getInstance(CZECH_LOCALE)).reversed())
							.toArray(String[]::new),
						receivedOrderedNames
					);

					final Set<String> storeCodes = productsWithLotsOfStores.get(product.getPrimaryKey());
					final Set<String> filteredStoreIds = storeCodes.stream()
						.filter(it -> Arrays.asList(randomStores).contains(it))
						.collect(Collectors.toSet());
					assertEquals(filteredStoreIds.size(), references.size());
					for (ReferenceContract reference : references) {
						final SealedEntity referencedStore = reference.getReferencedEntity().orElseThrow();
						assertTrue(
							filteredStoreIds.contains(referencedStore.getAttribute(ATTRIBUTE_CODE, String.class)));
						final Optional<SealedEntity> storeEntity = reference.getReferencedEntity();
						assertTrue(storeEntity.isPresent());
						assertFalse(storeEntity.get().getAttributeValues().isEmpty());
						assertFalse(storeEntity.get().getAssociatedDataValues().isEmpty());
					}
				}

				return null;
			}
		);
	}

	@DisplayName("References can be eagerly fetched, filtered by attribute and ordered")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldEagerlyFetchReferencesFilteredByAttributeAndOrdered(
		Evita evita, List<SealedEntity> originalProducts, List<SealedEntity> originalStores) {
		final Map<Integer, SealedEntity> storesIndexedByPk = originalStores.stream()
			.collect(Collectors.toMap(
				EntityContract::getPrimaryKey,
				Function.identity()
			));

		final Map<Integer, Set<String>> productsWithLotsOfStores = originalProducts.stream()
			.filter(it -> it.getReferences(Entities.STORE).size() > 4 && it.getLocales().contains(CZECH_LOCALE))
			.collect(
				Collectors.toMap(
					EntityContract::getPrimaryKey,
					it -> it.getReferences(Entities.STORE)
						.stream()
						.map(ref -> ref.getReferenceKey().primaryKey())
						.map(storesIndexedByPk::get)
						.map(store -> store.getAttribute(ATTRIBUTE_CODE, String.class))
						.collect(Collectors.toSet())
				)
			);

		final AtomicBoolean atLeastFirst = new AtomicBoolean();
		final Random rnd = new Random(5);
		final String[] randomStores = productsWithLotsOfStores
			.values()
			.stream()
			.flatMap(Collection::stream)
			.filter(it -> atLeastFirst.compareAndSet(false, true) || rnd.nextInt(10) == 0)
			.distinct()
			.toArray(String[]::new);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> productByPk = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							and(
								entityPrimaryKeyInSet(productsWithLotsOfStores.keySet().toArray(Integer[]::new)),
								entityLocaleEquals(CZECH_LOCALE)
							)
						),
						require(
							page(1, Integer.MAX_VALUE),
							entityFetch(
								referenceContent(
									Entities.STORE,
									filterBy(
										entityHaving(
											attributeInSet(ATTRIBUTE_CODE, randomStores)
										)
									),
									orderBy(
										entityProperty(
											attributeNatural(ATTRIBUTE_NAME, OrderDirection.DESC)
										)
									)
								)
							)
						)
					),
					SealedEntity.class
				);

				assertEquals(productsWithLotsOfStores.size(), productByPk.getRecordData().size());
				assertEquals(productsWithLotsOfStores.size(), productByPk.getTotalRecordCount());

				for (final SealedEntity product : productByPk.getRecordData()) {
					final Collection<ReferenceContract> references = product.getReferences(Entities.STORE);

					final Set<String> storeCodes = productsWithLotsOfStores.get(product.getPrimaryKey());
					final Set<String> filteredStoreIds = storeCodes.stream()
						.filter(it -> Arrays.asList(randomStores).contains(it))
						.collect(Collectors.toSet());
					assertEquals(filteredStoreIds.size(), references.size());
				}

				return null;
			}
		);
	}

	/**
	 * Verifies a getEntity query that filters references by locale both on the reference level and inside
	 * entityHaving.
	 * The selected stores distinguish Czech descending order from primary-key and English descending order.
	 */
	@DisplayName("References can be eagerly deeply fetched, filtered and ordered (via. getEntity)")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldEagerlyDeepFetchReferenceEntityBodiesFilteredAndOrderedViaGetEntity(
		@Nonnull Evita evita, @Nonnull List<SealedEntity> originalProducts, @Nonnull List<SealedEntity> originalStores
	) {
		final Map.Entry<Integer, List<SealedEntity>> productWithStores =
			selectProductWithStoresOrderedByCzechNames(originalProducts, originalStores);
		final Integer[] filteredStores = productWithStores.getValue().stream()
			.map(EntityClassifier::getPrimaryKeyOrThrowException)
			.toArray(Integer[]::new);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final SealedEntity product = session.getEntity(
					Entities.PRODUCT,
					productWithStores.getKey(),
					referenceContent(
						Entities.STORE,
						filterBy(
							entityPrimaryKeyInSet(filteredStores),
							entityLocaleEquals(LOCALE_CZECH),
							entityHaving(and(
								entityPrimaryKeyInSet(filteredStores),
								entityLocaleEquals(LOCALE_CZECH)
							))
						),
						orderBy(
							entityProperty(
								attributeNatural(ATTRIBUTE_NAME, OrderDirection.DESC)
							)
						),
						entityFetch(attributeContent(), associatedDataContentAll())
					)
				).orElseThrow();

				final Collection<ReferenceContract> references = product.getReferences(Entities.STORE);
				final Set<Integer> filteredStoreIds = Set.of(filteredStores);
				assertEquals(filteredStoreIds.size(), references.size());
				for (final ReferenceContract reference : references) {
					assertTrue(filteredStoreIds.contains(reference.getReferencedPrimaryKey()));
					final Optional<SealedEntity> storeEntity = reference.getReferencedEntity();
					assertTrue(storeEntity.isPresent());
					assertFalse(storeEntity.get().getAttributeValues().isEmpty());
					assertFalse(storeEntity.get().getAssociatedDataValues().isEmpty());
				}

				// references should be ordered by name
				final String[] receivedOrderedNames = references.stream()
					.map(it -> it.getReferencedEntity().orElseThrow())
					.map(it -> it.getAttribute(ATTRIBUTE_NAME, String.class))
					.filter(Objects::nonNull)
					.toArray(String[]::new);

				assertEquals(references.size(), receivedOrderedNames.length);

				final Collator collator = Collator.getInstance(CZECH_LOCALE);
				assertArrayEquals(
					Arrays.stream(receivedOrderedNames)
						.sorted(new LocalizedStringComparator(collator).reversed())
						.toArray(String[]::new),
					receivedOrderedNames,
					"References must be ordered by Czech names descending"
				);

				return null;
			}
		);
	}

	/**
	 * Selects a Czech product with at least two bilingual stores whose Czech descending order differs both from their
	 * primary-key order and from their English descending order, so that only an ordering by Czech names yields the
	 * order the caller expects.
	 *
	 * @param originalProducts the generated products
	 * @param originalStores   the generated stores
	 * @return the primary key of the selected product and its bilingual stores in primary-key order
	 */
	@Nonnull
	private static Map.Entry<Integer, List<SealedEntity>> selectProductWithStoresOrderedByCzechNames(
		@Nonnull List<SealedEntity> originalProducts, @Nonnull List<SealedEntity> originalStores
	) {
		final Map<Integer, SealedEntity> bilingualStores = originalStores.stream()
			.filter(it -> it.getAttribute(ATTRIBUTE_NAME, LOCALE_CZECH, String.class) != null)
			.filter(it -> it.getAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, String.class) != null)
			.collect(Collectors.toMap(EntityClassifier::getPrimaryKeyOrThrowException, Function.identity()));
		final Comparator<SealedEntity> czechNameDescending = Comparator.comparing(
			it -> it.getAttribute(ATTRIBUTE_NAME, LOCALE_CZECH, String.class),
			new LocalizedStringComparator(Collator.getInstance(LOCALE_CZECH)).reversed()
		);
		final Comparator<SealedEntity> englishNameDescending = Comparator.comparing(
			it -> it.getAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, String.class),
			new LocalizedStringComparator(Collator.getInstance(Locale.ENGLISH)).reversed()
		);
		// Select by the actual generated names: the first product with Czech stores need not expose a wrong order.
		final Optional<Map.Entry<Integer, List<SealedEntity>>> selectedProduct = originalProducts.stream()
			.filter(it -> it.getLocales().contains(LOCALE_CZECH))
			.map(it -> Map.entry(
				it.getPrimaryKeyOrThrowException(),
				it.getReferences(Entities.STORE).stream()
					.map(ReferenceContract::getReferencedPrimaryKey)
					.filter(bilingualStores::containsKey)
					.map(bilingualStores::get)
					.sorted(Comparator.comparingInt(EntityClassifier::getPrimaryKeyOrThrowException))
					.toList()
			))
			.filter(it -> it.getValue().size() >= 2)
			.filter(it -> !it.getValue().equals(it.getValue().stream().sorted(czechNameDescending).toList()))
			.filter(it -> !it.getValue().stream().sorted(czechNameDescending).toList().equals(
				it.getValue().stream().sorted(englishNameDescending).toList()
			))
			.findFirst();
		assertTrue(selectedProduct.isPresent(),
			"The data set must contain stores whose Czech DESC order differs from PK and English DESC order");
		final Map.Entry<Integer, List<SealedEntity>> productWithStores = selectedProduct.orElseThrow();
		final List<SealedEntity> stores = productWithStores.getValue();
		final List<SealedEntity> storesInCzechOrder = stores.stream().sorted(czechNameDescending).toList();
		assertTrue(stores.stream().map(it -> it.getAttribute(ATTRIBUTE_NAME, LOCALE_CZECH, String.class))
			.distinct().count() >= 2, "At least two filtered stores must have different Czech names");
		assertNotEquals(stores, storesInCzechOrder, "Czech DESC order must differ from primary-key order");
		assertNotEquals(stores.stream().sorted(englishNameDescending).toList(), storesInCzechOrder,
			"Czech DESC order must differ from English DESC order");
		return productWithStores;
	}

	@DisplayName("References are ordered by a localized entity attribute in the locale of the reference filter when the query has none")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldOrderReferencesByLocalizedEntityAttributeInReferenceFilterLocale(
		Evita evita, List<SealedEntity> originalProducts, List<SealedEntity> originalStores
	) {
		final Map<Integer, String> czechStoreNames = originalStores
			.stream()
			.filter(it -> it.getLocales().contains(CZECH_LOCALE))
			.collect(
				Collectors.toMap(
					EntityClassifier::getPrimaryKeyOrThrowException,
					it -> Objects.requireNonNull(it.getAttribute(ATTRIBUTE_NAME, CZECH_LOCALE, String.class))
				)
			);
		// an owner not localized in Czech keeps no reference through a reference filter by the Czech locale, so only
		// the Czech owners are examined - the same restriction the other ordered fetches of this class apply
		final Map<Integer, Set<Integer>> czechStoresOfProducts = originalProducts
			.stream()
			.filter(it -> it.getLocales().contains(CZECH_LOCALE))
			.map(
				it -> Map.entry(
					it.getPrimaryKeyOrThrowException(),
					it.getReferences(Entities.STORE)
						.stream()
						.map(ReferenceContract::getReferencedPrimaryKey)
						.filter(czechStoreNames::containsKey)
						.collect(Collectors.toSet())
				)
			)
			.filter(it -> it.getValue().size() > 2)
			.collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
		assertFalse(czechStoresOfProducts.isEmpty(), "The data set must hold a product with several Czech stores");

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				// neither the query nor its filter names a locale - only the filter of the references does
				final EvitaResponse<SealedEntity> products = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(entityPrimaryKeyInSet(czechStoresOfProducts.keySet().toArray(Integer[]::new))),
						require(
							page(1, Integer.MAX_VALUE),
							entityFetch(
								referenceContent(
									Entities.STORE,
									filterBy(entityLocaleEquals(CZECH_LOCALE)),
									orderBy(
										entityProperty(
											attributeNatural(ATTRIBUTE_NAME, OrderDirection.DESC)
										)
									),
									entityFetch(attributeContent())
								)
							)
						)
					),
					SealedEntity.class
				);

				assertEquals(czechStoresOfProducts.size(), products.getRecordData().size());
				final LocalizedStringComparator czechComparator = new LocalizedStringComparator(
					Collator.getInstance(CZECH_LOCALE)
				);
				for (final SealedEntity product : products.getRecordData()) {
					final Collection<ReferenceContract> references = product.getReferences(Entities.STORE);
					final Set<Integer> expectedStores = czechStoresOfProducts.get(product.getPrimaryKey());
					assertEquals(
						expectedStores,
						references.stream().map(ReferenceContract::getReferencedPrimaryKey).collect(Collectors.toSet()),
						"Product `" + product.getPrimaryKey() + "` must reference exactly its Czech stores"
					);
					final String[] expectedNames = expectedStores
						.stream()
						.map(czechStoreNames::get)
						.sorted(czechComparator.reversed())
						.toArray(String[]::new);
					final String[] receivedNames = references
						.stream()
						.map(it -> czechStoreNames.get(it.getReferencedPrimaryKey()))
						.toArray(String[]::new);
					assertArrayEquals(
						expectedNames, receivedNames,
						"Product `" + product.getPrimaryKey() + "` must have its stores ordered by their Czech names"
					);
				}
				return null;
			}
		);
	}

	@DisplayName("References are ordered by a localized group attribute in the locale of the reference filter when the query has none")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldOrderReferencesByLocalizedGroupAttributeInReferenceFilterLocale(
		Evita evita, List<SealedEntity> originalProducts, List<SealedEntity> originalParameters
	) {
		final Set<Integer> czechParameters = originalParameters
			.stream()
			.filter(it -> it.getLocales().contains(CZECH_LOCALE))
			.map(EntityClassifier::getPrimaryKeyOrThrowException)
			.collect(Collectors.toSet());

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Map<Integer, String> czechGroupNames = session.queryListOfSealedEntities(
						query(
							collection(Entities.PARAMETER_GROUP),
							filterBy(entityLocaleEquals(CZECH_LOCALE)),
							require(page(1, Integer.MAX_VALUE), entityFetch(attributeContent(ATTRIBUTE_NAME)))
						)
					)
					.stream()
					.collect(
						Collectors.toMap(
							EntityClassifier::getPrimaryKeyOrThrowException,
							it -> Objects.requireNonNull(it.getAttribute(ATTRIBUTE_NAME, String.class))
						)
					);
				// only the owners whose Czech parameters all sit in a group with a Czech name, so that every
				// fetched reference has a value to be ordered by
				final Map<Integer, Map<Integer, String>> groupNamesOfProducts = originalProducts
					.stream()
					.filter(it -> it.getLocales().contains(CZECH_LOCALE))
					.map(
						it -> Map.entry(
							it.getPrimaryKeyOrThrowException(),
							it.getReferences(Entities.PARAMETER)
								.stream()
								.filter(ref -> czechParameters.contains(ref.getReferencedPrimaryKey()))
								.collect(
									Collectors.toMap(
										ReferenceContract::getReferencedPrimaryKey,
										ref -> ref.getGroup()
											.map(group -> czechGroupNames.get(group.getPrimaryKey()))
											.orElse("")
									)
								)
						)
					)
					.filter(it -> it.getValue().size() > 2 && !it.getValue().containsValue(""))
					.collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
				assertFalse(groupNamesOfProducts.isEmpty(), "The data set must hold a product with several Czech groups");

				// neither the query nor its filter names a locale - only the filter of the references does
				final EvitaResponse<SealedEntity> products = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(entityPrimaryKeyInSet(groupNamesOfProducts.keySet().toArray(Integer[]::new))),
						require(
							page(1, Integer.MAX_VALUE),
							entityFetch(
								referenceContent(
									Entities.PARAMETER,
									filterBy(entityLocaleEquals(CZECH_LOCALE)),
									orderBy(
										entityGroupProperty(
											attributeNatural(ATTRIBUTE_NAME, OrderDirection.DESC)
										)
									),
									entityFetch(attributeContent()),
									entityGroupFetch(attributeContent())
								)
							)
						)
					),
					SealedEntity.class
				);

				assertEquals(groupNamesOfProducts.size(), products.getRecordData().size());
				final LocalizedStringComparator czechComparator = new LocalizedStringComparator(
					Collator.getInstance(CZECH_LOCALE)
				);
				for (final SealedEntity product : products.getRecordData()) {
					final Map<Integer, String> groupNames = groupNamesOfProducts.get(product.getPrimaryKey());
					final Collection<ReferenceContract> references = product.getReferences(Entities.PARAMETER);
					assertEquals(
						groupNames.keySet(),
						references.stream().map(ReferenceContract::getReferencedPrimaryKey).collect(Collectors.toSet()),
						"Product `" + product.getPrimaryKey() + "` must reference exactly its Czech parameters"
					);
					final String[] expectedGroupNames = groupNames.values()
						.stream()
						.sorted(czechComparator.reversed())
						.toArray(String[]::new);
					final String[] receivedGroupNames = references
						.stream()
						.map(it -> groupNames.get(it.getReferencedPrimaryKey()))
						.toArray(String[]::new);
					assertArrayEquals(
						expectedGroupNames, receivedGroupNames,
						"Product `" + product.getPrimaryKey() + "` must have its parameters ordered by the Czech " +
							"names of their groups"
					);
				}
				return null;
			}
		);
	}

	@DisplayName("References cannot be ordered by a localized attribute when neither the query nor the reference filter names a locale")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldRefuseOrderingReferencesByLocalizedAttributeWithoutLocale(Evita evita, List<SealedEntity> originalStores) {
		final Integer[] stores = originalStores
			.stream()
			.map(EntityClassifier::getPrimaryKeyOrThrowException)
			.toArray(Integer[]::new);
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				// the reference filter names no locale, so there is none the ordering could use
				final EvitaInvalidUsageException entityOrderingRefusal = assertThrows(
					EvitaInvalidUsageException.class,
					() -> session.query(
						query(
							collection(Entities.PRODUCT),
							require(
								entityFetch(
									referenceContent(
										Entities.STORE,
										filterBy(entityPrimaryKeyInSet(stores)),
										orderBy(entityProperty(attributeNatural(ATTRIBUTE_NAME)))
									)
								)
							)
						),
						SealedEntity.class
					)
				);
				assertTrue(
					entityOrderingRefusal.getMessage().contains("without locale"),
					entityOrderingRefusal.getMessage()
				);
				final EvitaInvalidUsageException groupOrderingRefusal = assertThrows(
					EvitaInvalidUsageException.class,
					() -> session.query(
						query(
							collection(Entities.PRODUCT),
							require(
								entityFetch(
									referenceContent(
										Entities.PARAMETER,
										orderBy(entityGroupProperty(attributeNatural(ATTRIBUTE_NAME)))
									)
								)
							)
						),
						SealedEntity.class
					)
				);
				assertTrue(
					groupOrderingRefusal.getMessage().contains("without locale"),
					groupOrderingRefusal.getMessage()
				);
				return null;
			}
		);
	}

	@DisplayName("References can be eagerly deeply fetched, filtered by reference attribute")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldEagerlyDeepFetchReferenceEntityBodiesFilteredByReferenceAttribute(
		Evita evita, List<SealedEntity> originalProducts) {
		final SealedEntity productWithLotsOfParameters = originalProducts.stream()
			.filter(it -> it.getReferences(Entities.PARAMETER).size() > 4)
			.max(Comparator.comparingInt(o -> o.getReferences(Entities.PARAMETER).size()))
			.orElseThrow();
		final List<Long> categoryPriorities = productWithLotsOfParameters
			.getReferences(Entities.PARAMETER)
			.stream()
			.map(it -> it.getAttribute(ATTRIBUTE_CATEGORY_PRIORITY, Long.class))
			.filter(Objects::nonNull)
			.sorted()
			.toList();
		final Long secondCategoryPriority = categoryPriorities.get(1);
		final List<ReferenceKey> expectedReferenceKeys = productWithLotsOfParameters.getReferences(Entities.PARAMETER)
			.stream()
			.filter(it -> it.getAttribute(ATTRIBUTE_CATEGORY_PRIORITY, Long.class) >= secondCategoryPriority)
			.map(it -> new ReferenceKey(Entities.PARAMETER, it.getReferencedPrimaryKey()))
			.sorted(ReferenceKey.GENERIC_COMPARATOR)
			.toList();

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> productByPk = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							and(
								entityPrimaryKeyInSet(productWithLotsOfParameters.getPrimaryKey())
							)
						),
						require(
							page(1, Integer.MAX_VALUE),
							entityFetch(
								referenceContentWithAttributes(
									Entities.PARAMETER,
									filterBy(
										attributeGreaterThanEquals(ATTRIBUTE_CATEGORY_PRIORITY, secondCategoryPriority)
									),
									orderBy(
										attributeNatural(ATTRIBUTE_CATEGORY_PRIORITY, OrderDirection.DESC)
									),
									entityFetch(
										attributeContent(),
										associatedDataContentAll()
									)
								)
							)
						)
					),
					SealedEntity.class
				);

				assertEquals(1, productByPk.getRecordData().size());
				assertEquals(1, productByPk.getTotalRecordCount());

				final List<ReferenceKey> fetchedReferenceIds = productByPk.getRecordData()
					.getFirst()
					.getReferences(Entities.PARAMETER)
					.stream()
					.map(ReferenceContract::getReferenceKey)
					.sorted(ReferenceKey.FULL_COMPARATOR)
					.toList();

				assertEquals(expectedReferenceKeys, fetchedReferenceIds);

				// references should be ordered by name
				final Long[] receivedCategoryPriorities = productByPk.getRecordData()
					.getFirst()
					.getReferences(Entities.PARAMETER)
					.stream()
					.map(it -> it.getAttribute(ATTRIBUTE_CATEGORY_PRIORITY, Long.class))
					.toArray(Long[]::new);
				assertArrayEquals(
					Arrays.stream(receivedCategoryPriorities).sorted(Comparator.reverseOrder()).toArray(Long[]::new),
					receivedCategoryPriorities
				);

				return null;
			}
		);
	}

	@DisplayName("References can be eagerly deeply fetched, filtered and ordered by both reference and entity attribute")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldEagerlyDeepFetchReferenceEntityBodiesFilteredAndOrderedByReferenceAndEntityAttribute(
		Evita evita, List<SealedEntity> originalProducts, Map<Integer, SealedEntity> originalCategories) {
		final SealedEntity productWithLotsOfParameters = originalProducts.stream()
			.filter(it -> {
				final Collection<ReferenceContract> references = it.getReferences(Entities.CATEGORY);
				return references.stream().anyMatch(x -> x.getAttribute(ATTRIBUTE_CATEGORY_PRIORITY) == null) &&
					references.stream().anyMatch(x -> x.getAttribute(ATTRIBUTE_CATEGORY_PRIORITY) != null);
			})
			.max(Comparator.comparingInt(o -> o.getReferences(Entities.CATEGORY).size()))
			.orElseThrow();

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> productByPk = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							and(
								entityPrimaryKeyInSet(productWithLotsOfParameters.getPrimaryKey()),
								entityLocaleEquals(LOCALE_CZECH)
							)
						),
						require(
							page(1, Integer.MAX_VALUE),
							entityFetch(
								referenceContentWithAttributes(
									Entities.CATEGORY,
									orderBy(
										attributeNatural(ATTRIBUTE_CATEGORY_PRIORITY, OrderDirection.DESC),
										entityProperty(
											attributeNatural(ATTRIBUTE_NAME, OrderDirection.ASC)
										)
									),
									entityFetch(attributeContent())
								)
							)
						)
					),
					SealedEntity.class
				);

				assertEquals(1, productByPk.getRecordData().size());
				assertEquals(1, productByPk.getTotalRecordCount());

				// references should be ordered by priority and where missing by categoryName
				final Object[] receivedCategoryPriorities = productByPk.getRecordData()
					.getFirst()
					.getReferences(Entities.CATEGORY)
					.stream()
					.map(it -> ofNullable(it.getAttribute(ATTRIBUTE_CATEGORY_PRIORITY))
						.orElseGet(() -> it.getReferencedEntity().get().getAttribute(ATTRIBUTE_NAME, LOCALE_CZECH)))
					.toArray();

				final LocalizedStringComparator czechComparator = new LocalizedStringComparator(
					Collator.getInstance(CZECH_LOCALE));
				final Object[] expectedOrder = productWithLotsOfParameters
					.getReferences(Entities.CATEGORY)
					.stream()
					.map(it -> ofNullable(it.getAttribute(ATTRIBUTE_CATEGORY_PRIORITY))
						.orElseGet(() -> {
							final SealedEntity category = originalCategories.get(it.getReferencedPrimaryKey());
							return category.getAttribute(ATTRIBUTE_NAME, LOCALE_CZECH);
						}))
					.sorted((o1, o2) -> {
						if (o1 instanceof Long o1Long && o2 instanceof Long o2Long) {
							return o1Long.compareTo(o2Long) * -1;
						} else if (o1 instanceof String o1String && o2 instanceof String o2String) {
							return czechComparator.compare(o1String, o2String);
						} else if (o1 instanceof Long) {
							return -1;
						} else {
							return 1;
						}
					})
					.toArray();

				assertArrayEquals(
					expectedOrder,
					receivedCategoryPriorities
				);

				return null;
			}
		);
	}

	@DisplayName("References should be returned even if referenced entity doesn't exist")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldFetchEvenMissingReferences(
		Evita evita, List<SealedEntity> originalProducts, List<EntityReferenceContract> originalPriceLists) {
		final Set<Integer> existingPriceLists = originalPriceLists.stream()
			.map(EntityReferenceContract::getPrimaryKey)
			.collect(Collectors.toSet());

		final SealedEntity productWithMissingPriceLists = originalProducts.stream()
			.filter(it -> {
				final Collection<ReferenceContract> references = it.getReferences(Entities.PRICE_LIST);
				return references.stream().anyMatch(x -> existingPriceLists.contains(x.getReferencedPrimaryKey())) &&
					references.stream().anyMatch(x -> !existingPriceLists.contains(x.getReferencedPrimaryKey()));
			})
			.findFirst()
			.orElseThrow();

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> productByPk = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							and(
								entityPrimaryKeyInSet(productWithMissingPriceLists.getPrimaryKey())
							)
						),
						require(
							page(1, Integer.MAX_VALUE),
							entityFetch(
								referenceContentWithAttributes(Entities.PRICE_LIST)
							)
						)
					),
					SealedEntity.class
				);

				assertEquals(1, productByPk.getRecordData().size());
				assertEquals(1, productByPk.getTotalRecordCount());

				assertEquals(
					productWithMissingPriceLists.getReferences(Entities.PRICE_LIST).size(),
					productByPk.getRecordData().getFirst().getReferences(Entities.PRICE_LIST).size()
				);

				return null;
			}
		);
	}

	@DisplayName("References should be returned even if referenced entity bodies doesn't exist")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldFetchEvenMissingReferenceBodies(
		Evita evita, List<SealedEntity> originalProducts, List<EntityReferenceContract> originalPriceLists) {
		final Set<Integer> existingPriceLists = originalPriceLists.stream()
			.map(EntityReferenceContract::getPrimaryKey)
			.collect(Collectors.toSet());

		final SealedEntity productWithMissingPriceLists = originalProducts.stream()
			.filter(it -> {
				final Collection<ReferenceContract> references = it.getReferences(Entities.PRICE_LIST);
				return references.stream().anyMatch(x -> existingPriceLists.contains(x.getReferencedPrimaryKey())) &&
					references.stream().anyMatch(x -> !existingPriceLists.contains(x.getReferencedPrimaryKey()));
			})
			.findFirst()
			.orElseThrow();

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> productByPk = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							and(
								entityPrimaryKeyInSet(productWithMissingPriceLists.getPrimaryKey())
							)
						),
						require(
							page(1, Integer.MAX_VALUE),
							entityFetch(
								referenceContentWithAttributes(Entities.PRICE_LIST, entityFetchAll())
							)
						)
					),
					SealedEntity.class
				);

				assertEquals(1, productByPk.getRecordData().size());
				assertEquals(1, productByPk.getTotalRecordCount());

				final SealedEntity returnedProduct = productByPk.getRecordData().getFirst();
				assertEquals(
					productWithMissingPriceLists.getReferences(Entities.PRICE_LIST).size(),
					returnedProduct.getReferences(Entities.PRICE_LIST).size()
				);

				assertEquals(
					productWithMissingPriceLists.getReferences(Entities.PRICE_LIST)
						.stream()
						.filter(it -> existingPriceLists.contains(it.getReferencedPrimaryKey()))
						.count(),
					returnedProduct.getReferences(Entities.PRICE_LIST).stream()
						.filter(it -> it.getReferencedEntity().isPresent())
						.count()
				);

				assertEquals(
					productWithMissingPriceLists.getReferences(Entities.PRICE_LIST)
						.stream()
						.filter(it -> !existingPriceLists.contains(it.getReferencedPrimaryKey()))
						.count(),
					returnedProduct.getReferences(Entities.PRICE_LIST).stream()
						.filter(it -> it.getReferencedEntity().isEmpty())
						.count()
				);

				return null;
			}
		);
	}

	@DisplayName("References should be returned only if referenced entity exists")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldFetchOnlyExistingReferences(
		Evita evita, List<SealedEntity> originalProducts, List<EntityReferenceContract> originalPriceLists) {
		final Set<Integer> existingPriceLists = originalPriceLists.stream()
			.map(EntityReferenceContract::getPrimaryKey)
			.collect(Collectors.toSet());

		final SealedEntity productWithMissingPriceLists = originalProducts.stream()
			.filter(it -> {
				final Collection<ReferenceContract> references = it.getReferences(Entities.PRICE_LIST);
				return references.stream().anyMatch(x -> existingPriceLists.contains(x.getReferencedPrimaryKey())) &&
					references.stream().anyMatch(x -> !existingPriceLists.contains(x.getReferencedPrimaryKey()));
			})
			.findFirst()
			.orElseThrow();

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> productByPk = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							and(
								entityPrimaryKeyInSet(productWithMissingPriceLists.getPrimaryKey())
							)
						),
						require(
							page(1, Integer.MAX_VALUE),
							entityFetch(
								referenceContentWithAttributes(ManagedReferencesBehaviour.EXISTING, Entities.PRICE_LIST)
							)
						)
					),
					SealedEntity.class
				);

				assertEquals(1, productByPk.getRecordData().size());
				assertEquals(1, productByPk.getTotalRecordCount());

				assertEquals(
					productWithMissingPriceLists.getReferences(Entities.PRICE_LIST)
						.stream()
						.filter(it -> existingPriceLists.contains(it.getReferencedPrimaryKey()))
						.count(),
					productByPk.getRecordData().getFirst().getReferences(Entities.PRICE_LIST).size()
				);

				return null;
			}
		);
	}

	@DisplayName("References should be returned only if referenced entity bodies exists")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldFetchOnlyExistingReferenceBodies(
		Evita evita, List<SealedEntity> originalProducts, List<EntityReferenceContract> originalPriceLists) {
		final Set<Integer> existingPriceLists = originalPriceLists.stream()
			.map(EntityReferenceContract::getPrimaryKey)
			.collect(Collectors.toSet());

		final SealedEntity productWithMissingPriceLists = originalProducts.stream()
			.filter(it -> {
				final Collection<ReferenceContract> references = it.getReferences(Entities.PRICE_LIST);
				return references.stream().anyMatch(x -> existingPriceLists.contains(x.getReferencedPrimaryKey())) &&
					references.stream().anyMatch(x -> !existingPriceLists.contains(x.getReferencedPrimaryKey()));
			})
			.findFirst()
			.orElseThrow();

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> productByPk = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							and(
								entityPrimaryKeyInSet(productWithMissingPriceLists.getPrimaryKey())
							)
						),
						require(
							page(1, Integer.MAX_VALUE),
							entityFetch(
								referenceContentWithAttributes(
									ManagedReferencesBehaviour.EXISTING, Entities.PRICE_LIST, entityFetchAll())
							)
						)
					),
					SealedEntity.class
				);

				assertEquals(1, productByPk.getRecordData().size());
				assertEquals(1, productByPk.getTotalRecordCount());

				final SealedEntity returnedProduct = productByPk.getRecordData().getFirst();
				assertEquals(
					productWithMissingPriceLists.getReferences(Entities.PRICE_LIST)
						.stream()
						.filter(it -> existingPriceLists.contains(it.getReferencedPrimaryKey()))
						.count(),
					returnedProduct.getReferences(Entities.PRICE_LIST).size()
				);

				assertEquals(
					productWithMissingPriceLists.getReferences(Entities.PRICE_LIST)
						.stream()
						.filter(it -> existingPriceLists.contains(it.getReferencedPrimaryKey()))
						.count(),
					returnedProduct.getReferences(Entities.PRICE_LIST).stream()
						.filter(it -> it.getReferencedEntity().isPresent())
						.count()
				);

				assertEquals(
					0,
					returnedProduct.getReferences(Entities.PRICE_LIST).stream()
						.filter(it -> it.getReferencedEntity().isEmpty())
						.count()
				);

				return null;
			}
		);
	}

	@DisplayName("References can be eagerly deeply fetched in binary form")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldEagerlyDeepFetchReferenceEntityBinaryBodies(
		Evita evita, List<SealedEntity> originalProducts, List<SealedEntity> originalBrands) {
		final Set<Integer> brandsWithGroupedCategory = originalBrands.stream()
			.filter(it -> it.getReferences(Entities.STORE).stream().anyMatch(ref -> ref.getGroup().isPresent()))
			.map(EntityContract::getPrimaryKey)
			.collect(Collectors.toSet());

		final SealedEntity selectedEntity = findEntityByPredicate(
			originalProducts,
			it -> !it.getReferences(Entities.BRAND).isEmpty() &&
				it.getReferences(Entities.BRAND)
					.stream()
					.anyMatch(brand -> brandsWithGroupedCategory.contains(brand.getReferencedPrimaryKey())) &&
				!it.getReferences(Entities.STORE).isEmpty()
		);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<BinaryEntity> productByPk = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(selectedEntity.getPrimaryKey())
						),
						require(
							entityFetch(
								referenceContent(
									Entities.BRAND,
									entityFetch(
										attributeContent(),
										referenceContent(
											Entities.STORE,
											entityFetch(attributeContent()),
											entityGroupFetch(attributeContent(), associatedDataContentAll())
										)
									),
									entityGroupFetch(attributeContent())
								),
								referenceContent(
									Entities.STORE,
									entityFetch(attributeContent(), associatedDataContentAll())
								)
							)
						)
					),
					BinaryEntity.class
				);

				assertEquals(1, productByPk.getRecordData().size());
				assertEquals(1, productByPk.getTotalRecordCount());

				final BinaryEntity product = productByPk.getRecordData().getFirst();
				assertTrue(product.getReferencedEntities().length > 0);

				final Optional<BinaryEntity> referencedStore = Arrays.stream(product.getReferencedEntities())
					.filter(it -> Entities.STORE.equals(it.getType()) && it.getPrimaryKey()
						.equals(REFERENCED_ID_EXTRACTOR.apply(selectedEntity, Entities.STORE)[0]))
					.findFirst();

				assertTrue(referencedStore.isPresent());
				assertTrue(referencedStore.get().getAttributeStorageParts().length > 0);
				assertTrue(referencedStore.get().getAssociatedDataStorageParts().length > 0);

				final Optional<BinaryEntity> brand = Arrays.stream(product.getReferencedEntities())
					.filter(it -> Entities.BRAND.equals(it.getType()) && brandsWithGroupedCategory.contains(
						it.getPrimaryKey()))
					.findFirst();

				assertTrue(brand.isPresent());
				assertTrue(brand.get().getAttributeStorageParts().length > 0);
				assertEquals(0, brand.get().getAssociatedDataStorageParts().length);

				final Optional<BinaryEntity> referencedBrandStore = Arrays.stream(brand.get().getReferencedEntities())
					.filter(it -> Entities.STORE.equals(it.getType()))
					.findFirst();
				final Optional<BinaryEntity> referencedBrandStoreCategory = Arrays.stream(
						brand.get().getReferencedEntities())
					.filter(it -> Entities.CATEGORY.equals(it.getType()))
					.findFirst();

				assertTrue(referencedBrandStore.isPresent());
				assertTrue(referencedBrandStore.get().getAttributeStorageParts().length > 0);
				assertEquals(0, referencedBrandStore.get().getAssociatedDataStorageParts().length);

				assertTrue(referencedBrandStoreCategory.isPresent());
				assertTrue(referencedBrandStoreCategory.get().getAttributeStorageParts().length > 0);
				assertTrue(referencedBrandStoreCategory.get().getAssociatedDataStorageParts().length > 0);

				return null;
			},
			SessionFlags.BINARY
		);
	}

}
