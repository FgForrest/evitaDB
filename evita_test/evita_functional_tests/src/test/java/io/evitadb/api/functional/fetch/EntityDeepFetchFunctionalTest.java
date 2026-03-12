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
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.query.require.ManagedReferencesBehaviour;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.BinaryEntity;
import io.evitadb.comparator.LocalizedStringComparator;
import io.evitadb.core.Evita;
import io.evitadb.test.Entities;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.EvitaParameterResolver;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.text.Collator;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
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
import static io.evitadb.test.TestConstants.FUNCTIONAL_TEST;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_CATEGORY_PRIORITY;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_CODE;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_NAME;
import static io.evitadb.test.generator.DataGenerator.CZECH_LOCALE;
import static java.util.Optional.ofNullable;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This test verifies deep fetching of referenced entity bodies, including eager and lazy deep
 * fetching, filtered and ordered reference fetching, reference attribute filtering/ordering,
 * handling of missing references, and binary entity deep fetching.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("Evita entity deep fetch functionality")
@Tag(FUNCTIONAL_TEST)
@ExtendWith(EvitaParameterResolver.class)
@Slf4j
class EntityDeepFetchFunctionalTest extends AbstractEntityFetchingFunctionalTest {

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

				final SealedEntity product = productByPk.getRecordData().get(0);
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

				final SealedEntity product = productByPk.getRecordData().get(0);
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

				final SealedEntity product = productByPk.getRecordData().get(0);
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

	@DisplayName("References can be eagerly deeply fetched, filtered and ordered (via. getEntity)")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldEagerlyDeepFetchReferenceEntityBodiesFilteredAndOrderedViaGetEntity(
		Evita evita, List<SealedEntity> originalProducts, List<SealedEntity> originalStores) {
		final Set<Integer> storesWithCzechLocale = originalStores
			.stream()
			.filter(it -> it.getLocales().contains(CZECH_LOCALE))
			.map(EntityClassifier::getPrimaryKeyOrThrowException)
			.collect(Collectors.toSet());

		final Map<Integer, Set<Integer>> productsWithLotsOfStores = originalProducts.stream()
			.filter(it -> it.getLocales().contains(CZECH_LOCALE))
			.filter(
				it -> it.getReferences(Entities.STORE)
					.stream()
					.filter(x -> storesWithCzechLocale.contains(x.getReferencedPrimaryKey()))
					.count() > 3
			)
			.limit(1)
			.collect(
				Collectors.toMap(
					EntityContract::getPrimaryKey,
					it -> it.getReferences(Entities.STORE)
						.stream()
						.filter(x -> storesWithCzechLocale.contains(x.getReferencedPrimaryKey()))
						.map(ref -> ref.getReferenceKey().primaryKey())
						.collect(Collectors.toSet())
				)
			);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Integer[] filteredStores = productsWithLotsOfStores.values().iterator().next().toArray(
					new Integer[0]);
				final SealedEntity product = session.getEntity(
					Entities.PRODUCT,
					productsWithLotsOfStores.keySet().iterator().next(),
					referenceContent(
						Entities.STORE,
						filterBy(
							entityPrimaryKeyInSet(filteredStores),
							entityLocaleEquals(LOCALE_CZECH)
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
				final Set<Integer> storeIds = productsWithLotsOfStores.get(product.getPrimaryKey());
				final Set<Integer> filteredStoreIds = storeIds.stream()
					.filter(it -> Arrays.asList(filteredStores).contains(it))
					.collect(Collectors.toSet());
				assertEquals(filteredStoreIds.size(), references.size());
				for (ReferenceContract reference : references) {
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
					receivedOrderedNames
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
					.get(0)
					.getReferences(Entities.PARAMETER)
					.stream()
					.map(ReferenceContract::getReferenceKey)
					.sorted(ReferenceKey.FULL_COMPARATOR)
					.toList();

				assertEquals(expectedReferenceKeys, fetchedReferenceIds);

				// references should be ordered by name
				final Long[] receivedCategoryPriorities = productByPk.getRecordData()
					.get(0)
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
					.get(0)
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
					productByPk.getRecordData().get(0).getReferences(Entities.PRICE_LIST).size()
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

				final SealedEntity returnedProduct = productByPk.getRecordData().get(0);
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
					productByPk.getRecordData().get(0).getReferences(Entities.PRICE_LIST).size()
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

				final SealedEntity returnedProduct = productByPk.getRecordData().get(0);
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

				final BinaryEntity product = productByPk.getRecordData().get(0);
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
