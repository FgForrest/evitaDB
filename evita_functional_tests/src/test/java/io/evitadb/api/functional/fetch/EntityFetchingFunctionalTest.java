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

import com.github.javafaker.Faker;
import io.evitadb.api.AbstractHundredProductsFunctionalTest;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.SessionTraits.SessionFlags;
import io.evitadb.api.exception.*;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.query.require.AccompanyingPriceContent;
import io.evitadb.api.query.require.DebugMode;
import io.evitadb.api.query.require.ManagedReferencesBehaviour;
import io.evitadb.api.query.require.PriceContentMode;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.AttributesAvailabilityChecker;
import io.evitadb.api.requestResponse.data.AttributesContract;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.EntityClassifierWithParent;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.BinaryEntity;
import io.evitadb.api.requestResponse.data.structure.Entity;
import io.evitadb.api.requestResponse.data.structure.EntityDecorator;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.data.structure.EntityReferenceWithParent;
import io.evitadb.api.requestResponse.data.structure.ReferenceFetcher;
import io.evitadb.comparator.LocalizedStringComparator;
import io.evitadb.core.Evita;
import io.evitadb.dataType.PaginatedList;
import io.evitadb.dataType.StripList;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.test.Entities;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.DataCarrier;
import io.evitadb.test.extension.EvitaParameterResolver;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;
import lombok.extern.slf4j.Slf4j;
import one.edee.oss.pmptt.model.Hierarchy;
import one.edee.oss.pmptt.model.HierarchyItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.text.Collator;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestConstants.FUNCTIONAL_TEST;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.generator.DataGenerator.*;
import static java.util.Optional.ofNullable;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This test verifies whether entities can be fetched:
 *
 * - by primary key
 * - in required form of completeness
 * - without any additional data leakage
 * - filtering content according to specific filtering constraints in the query
 * - lazy loading of content
 * - deep fetching facilities
 *
 * TOBEDONE JNO - write test that will have references ordered by both referenced attribute and referenced entity attribute (where missing)
 *                write both variants where we sort first by attributes on reference and then attributes on entity and reversed setup (entity first, reference attribute second)
 *                also write test for ordering by multiple attributes within reference attributes or entity attributes
 *                also write test for ordering by combination of reference attribute, entity attribute, reference attribute, entity attribute
 * TOBEDONE JNO - write test to verify the error message when multiple entityHaving constraints are used withing single reference filter constraint
 * TOBEDONE JNO - write test that deeply fetches attributes / associated data / references by using their names to verify the requirement schema validation
 * TOBEDONE JNO - write test that filters nested referenced products by different price for sale (currency / price lists)
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("Evita entity fetch by primary key functionality")
@Tag(FUNCTIONAL_TEST)
@ExtendWith(EvitaParameterResolver.class)
@Slf4j
public class EntityFetchingFunctionalTest extends AbstractHundredProductsFunctionalTest {
	private static final String HUNDRED_PRODUCTS = "HundredProducts";
	private static final Locale LOCALE_CZECH = CZECH_LOCALE;
	private final static BiFunction<SealedEntity, String, int[]> REFERENCED_ID_EXTRACTOR =
		(entity, referencedType) -> entity.getReferences(referencedType)
			.stream()
			.mapToInt(ReferenceContract::getReferencedPrimaryKey)
			.toArray();

	private static void assertProduct(SealedEntity product, int primaryKey, boolean hasAttributes, boolean hasAssociatedData, boolean hasPrices, boolean hasReferences) {
		assertEquals(primaryKey, (int) Objects.requireNonNull(product.getPrimaryKey()));

		if (hasAttributes) {
			assertFalse(product.getAttributeValues().isEmpty());
			assertNotNull(product.getAttribute(ATTRIBUTE_CODE));
		} else {
			assertFalse(product.attributesAvailable());
			assertThrows(ContextMissingException.class, product::getAttributeValues);
			assertThrows(ContextMissingException.class, () -> product.getAttribute(ATTRIBUTE_CODE));
		}

		if (hasAssociatedData) {
			assertFalse(product.getAssociatedDataValues().isEmpty());
			assertNotNull(product.getAssociatedData(ASSOCIATED_DATA_REFERENCED_FILES));
		} else {
			assertFalse(product.associatedDataAvailable());
			assertThrows(ContextMissingException.class, product::getAssociatedDataValues);
			assertThrows(ContextMissingException.class, () -> product.getAssociatedData(ASSOCIATED_DATA_REFERENCED_FILES));
		}

		if (hasPrices) {
			assertFalse(product.getPrices().isEmpty());
		} else {
			assertThrows(ContextMissingException.class, product::getPrices);
		}

		if (hasReferences) {
			assertFalse(product.getReferences().isEmpty());
		} else {
			assertFalse(product.referencesAvailable());
			assertThrows(ContextMissingException.class, product::getReferences);
		}
	}

	@Nonnull
	private static Integer[] getRequestedIdsByPredicate(List<SealedEntity> originalProducts, Predicate<SealedEntity> predicate) {
		final Integer[] entitiesMatchingTheRequirements = originalProducts
			.stream()
			.filter(predicate)
			.map(EntityContract::getPrimaryKey)
			.toArray(Integer[]::new);

		assertTrue(entitiesMatchingTheRequirements.length > 0, "There are no entities matching the requirements!");
		return entitiesMatchingTheRequirements;
	}

	@Nonnull
	private static SealedEntity findEntityByPredicate(List<SealedEntity> originalProducts, Predicate<SealedEntity> predicate) {
		return originalProducts
			.stream()
			.filter(predicate)
			.findFirst()
			.orElseThrow(() -> new EvitaInvalidUsageException("There are no entities matching the requirements!"));
	}

	@Nonnull
	private static EntityReferenceWithParent createParentChain(
		@Nonnull Hierarchy categoryHierarchy,
		int theLeaf,
		@Nullable Integer level,
		@Nullable Integer distance
	) {
		final List<HierarchyItem> parentItems = categoryHierarchy.getParentItems(String.valueOf(theLeaf));
		EntityReferenceWithParent workingNode = null;
		final Integer start = ofNullable(level)
			.map(it -> it - 1)
			.orElseGet(() -> ofNullable(distance).map(it -> parentItems.size() - it).orElse(0));
		for (int i = start; i < parentItems.size(); i++) {
			HierarchyItem parentItem = parentItems.get(i);
			workingNode = new EntityReferenceWithParent(Entities.CATEGORY, Integer.parseInt(parentItem.getCode()), workingNode);
		}

		return workingNode;
	}

	@Nonnull
	private static SealedEntity createParentEntityChain(@Nonnull Hierarchy categoryHierarchy, @Nonnull Map<Integer, SealedEntity> categoryIndex, int theLeaf, @Nullable Integer level, @Nullable Integer distance) {
		final List<HierarchyItem> parentItems = categoryHierarchy.getParentItems(String.valueOf(theLeaf));
		EntityDecorator workingNode = null;
		final Integer start = ofNullable(level)
			.map(it -> it - 1)
			.orElseGet(() -> ofNullable(distance).map(it -> parentItems.size() - it).orElse(0));
		for (int i = start; i < parentItems.size(); i++) {
			HierarchyItem parentItem = parentItems.get(i);
			final EntityDecorator categoryDecorator = (EntityDecorator) categoryIndex.get(Integer.parseInt(parentItem.getCode()));
			workingNode = Entity.decorate(
				categoryDecorator.getDelegate(),
				categoryDecorator.getSchema(),
				workingNode,
				categoryDecorator.getLocalePredicate(),
				categoryDecorator.getHierarchyPredicate(),
				categoryDecorator.getAttributePredicate(),
				categoryDecorator.getAssociatedDataPredicate(),
				categoryDecorator.getReferencePredicate(),
				categoryDecorator.getPricePredicate(),
				categoryDecorator.getAlignedNow(),
				ReferenceFetcher.NO_IMPLEMENTATION
			);
		}

		return workingNode;
	}

	private static void assertHasNotReferencesTo(@Nonnull SealedEntity product, @Nonnull String referenceName) {
		assertThrows(ContextMissingException.class, () -> product.getReferences(referenceName));
	}

	private static void assertHasReferencesTo(@Nonnull SealedEntity product, @Nonnull String referenceName, int... primaryKeys) {
		final Collection<ReferenceContract> references = product.getReferences(referenceName);
		final Set<Integer> expectedKeys = Arrays.stream(primaryKeys).boxed().collect(Collectors.toSet());
		assertEquals(primaryKeys.length, references.size());
		for (ReferenceContract reference : references) {
			assertEquals(referenceName, reference.getReferenceName());
			expectedKeys.remove(reference.getReferencedPrimaryKey());
		}
		assertTrue(
			expectedKeys.isEmpty(),
			"Expected references to these " + referenceName + ": " +
				expectedKeys.stream().map(Object::toString).collect(Collectors.joining(", ")) +
				" but were not found!"
		);
	}

	private static void assertProductHasAttributesInLocale(SealedEntity product, Locale locale, String... attributes) {
		for (String attribute : attributes) {
			assertNotNull(
				product.getAttribute(attribute, locale),
				"Product " + product.getPrimaryKey() + " lacks attribute " + attribute
			);
		}
	}

	private static void assertProductHasNotAttributesInLocale(SealedEntity product, Locale locale, String... attributes) {
		for (String attribute : attributes) {
			assertThrows(
				ContextMissingException.class,
				() -> product.getAttribute(attribute, locale),
				"Product " + product.getPrimaryKey() + " has attribute " + attribute
			);
		}
	}

	private static void assertProductHasAssociatedDataInLocale(SealedEntity product, Locale locale, String... associatedDataName) {
		for (String associatedData : associatedDataName) {
			assertNotNull(
				product.getAssociatedData(associatedData, locale),
				"Product " + product.getPrimaryKey() + " lacks associated data " + associatedData
			);
		}
	}

	private static void assertProductHasNotAssociatedDataInLocale(SealedEntity product, Locale locale, String... associatedDataName) {
		for (String associatedData : associatedDataName) {
			assertNull(
				product.getAssociatedData(associatedData, locale),
				"Product " + product.getPrimaryKey() + " has associated data " + associatedData
			);
		}
	}

	private static void assertProductHasNotAssociatedData(SealedEntity product, String... associatedDataName) {
		for (String associatedData : associatedDataName) {
			assertThrows(
				ContextMissingException.class,
				() -> product.getAssociatedData(associatedData),
				"Product " + product.getPrimaryKey() + " has associated data " + associatedData
			);
		}
	}

	private static void assertHasPriceInPriceList(SealedEntity product, Serializable... priceListName) {
		final Set<Serializable> foundPriceLists = new HashSet<>();
		for (PriceContract price : product.getPrices()) {
			foundPriceLists.add(price.priceList());
		}
		assertTrue(
			foundPriceLists.size() >= priceListName.length,
			"Expected price in price list " +
				Arrays.stream(priceListName)
					.filter(it -> !foundPriceLists.contains(it))
					.map(Object::toString)
					.collect(Collectors.joining(", ")) +
				" but was not found!"
		);
	}

	private static void assertHasNotPriceInPriceList(SealedEntity product, Serializable... priceList) {
		final Set<Serializable> forbiddenCurrencies = new HashSet<>(Arrays.asList(priceList));
		final Set<Serializable> clashingCurrencies = new HashSet<>();
		for (PriceContract price : product.getPrices()) {
			if (forbiddenCurrencies.contains(price.priceList())) {
				clashingCurrencies.add(price.priceList());
			}
		}
		assertTrue(
			clashingCurrencies.isEmpty(),
			"Price in price list " +
				clashingCurrencies
					.stream()
					.map(Object::toString)
					.collect(Collectors.joining(", ")) +
				" was not expected but was found!"
		);
	}

	private static void assertHasPriceInCurrency(SealedEntity product, Currency... currency) {
		final Set<Currency> foundCurrencies = new HashSet<>();
		for (PriceContract price : product.getPrices()) {
			foundCurrencies.add(price.currency());
		}
		assertTrue(
			foundCurrencies.size() >= currency.length,
			"Expected price in currency " +
				Arrays.stream(currency)
					.filter(it -> !foundCurrencies.contains(it))
					.map(Object::toString)
					.collect(Collectors.joining(", ")) +
				" but was not found!"
		);
	}

	private static void assertHasNotPriceInCurrency(SealedEntity product, Currency... currency) {
		final Set<Currency> forbiddenCurrencies = new HashSet<>(Arrays.asList(currency));
		final Set<Currency> clashingCurrencies = new HashSet<>();
		for (PriceContract price : product.getPrices()) {
			if (forbiddenCurrencies.contains(price.currency())) {
				clashingCurrencies.add(price.currency());
			}
		}
		assertTrue(
			clashingCurrencies.isEmpty(),
			"Price in currency " +
				clashingCurrencies
					.stream()
					.map(Object::toString)
					.collect(Collectors.joining(", ")) +
				" was not expected but was found!"
		);
	}

	@Nonnull
	@Override
	protected BiFunction<String, Faker, Integer> getRandomEntityPicker(EvitaSessionContract session) {
		return (entityType, faker) -> {
			if (Entities.PRICE_LIST.equals(entityType)) {
				final int entityCount = session.getEntityCollectionSize(entityType);
				if (faker.bool().bool()) {
					final int primaryKey = entityCount == 0 ? 0 : faker.random().nextInt(1, entityCount);
					return primaryKey == 0 ? null : primaryKey;
				} else {
					// return reference to non existing entity
					return faker.random().nextInt(entityCount + 1, entityCount + 1000);
				}
			} else {
				final int entityCount = session.getEntityCollectionSize(entityType);
				final int primaryKey = entityCount == 0 ? 0 : faker.random().nextInt(1, entityCount);
				return primaryKey == 0 ? null : primaryKey;
			}
		};
	}

	@DataSet(value = HUNDRED_PRODUCTS, destroyAfterClass = true)
	@Override
	protected DataCarrier setUp(Evita evita) {
		return super.setUp(evita);
	}

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

	@DisplayName("Should throw exception for price outside entityFetch")
	@Test
	void shouldThrowExceptionWhenPriceContentIsOutsideEntityFetch(@UseDataSet(HUNDRED_PRODUCTS) Evita evita) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				assertThrows(
					PriceContentMisplacedException.class,
					() -> session.querySealedEntity(
						query(
							collection(Entities.PRODUCT),
							filterBy(
								entityPrimaryKeyInSet(1)
							),
							require(
								priceContent(PriceContentMode.ALL)
							)
						)
					)
				);
			}
		);
	}

	@DisplayName("Should throw exception for hierarchy outside entityFetch")
	@Test
	void shouldThrowExceptionWhenHierarchyContentIsOutsideEntityFetch(@UseDataSet(HUNDRED_PRODUCTS) Evita evita) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				assertThrows(
					HierarchyContentMisplacedException.class,
					() -> session.querySealedEntity(
						query(
							collection(Entities.PRODUCT),
							filterBy(
								entityPrimaryKeyInSet(1)
							),
							require(
								hierarchyContent()
							)
						)
					)
				);
			}
		);
	}

	@DisplayName("Should throw exception for non-existing attribute")
	@Test
	void shouldThrowExceptionWhenNonExistingAttributeIsAttemptedToBeFetched(@UseDataSet(HUNDRED_PRODUCTS) Evita evita) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				assertThrows(
					AttributeNotFoundException.class,
					() -> session.querySealedEntity(
						query(
							collection(Entities.PRODUCT),
							filterBy(
								entityPrimaryKeyInSet(1)
							),
							require(
								entityFetch(
									attributeContent("nonExisting")
								)
							)
						)
					)
				);
			}
		);
	}

	@DisplayName("Should throw exception for attribute outside entityFetch")
	@Test
	void shouldThrowExceptionWhenAttributeContentIsOutsideEntityFetch(@UseDataSet(HUNDRED_PRODUCTS) Evita evita) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				assertThrows(
					AttributeContentMisplacedException.class,
					() -> session.querySealedEntity(
						query(
							collection(Entities.PRODUCT),
							filterBy(
								entityPrimaryKeyInSet(1)
							),
							require(
								attributeContentAll()
							)
						)
					)
				);
			}
		);
	}

	@DisplayName("Should throw exception for non-existing associated data")
	@Test
	void shouldThrowExceptionWhenNonExistingAssociatedDataIsAttemptedToBeFetched(@UseDataSet(HUNDRED_PRODUCTS) Evita evita) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				assertThrows(
					AssociatedDataNotFoundException.class,
					() -> session.querySealedEntity(
						query(
							collection(Entities.PRODUCT),
							filterBy(
								entityPrimaryKeyInSet(1)
							),
							require(
								entityFetch(
									associatedDataContent("nonExisting")
								)
							)
						)
					)
				);
			}
		);
	}

	@DisplayName("Should throw exception for associated data outside entityFetch")
	@Test
	void shouldThrowExceptionWhenAssociatedDataContentIsOutsideEntityFetch(@UseDataSet(HUNDRED_PRODUCTS) Evita evita) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				assertThrows(
					AssociatedDataContentMisplacedException.class,
					() -> session.querySealedEntity(
						query(
							collection(Entities.PRODUCT),
							filterBy(
								entityPrimaryKeyInSet(1)
							),
							require(
								associatedDataContentAll()
							)
						)
					)
				);
			}
		);
	}

	@DisplayName("Should throw exception for non-existing reference")
	@Test
	void shouldThrowExceptionWhenNonExistingReferenceIsAttemptedToBeFetched(@UseDataSet(HUNDRED_PRODUCTS) Evita evita) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				assertThrows(
					ReferenceNotFoundException.class,
					() -> session.querySealedEntity(
						query(
							collection(Entities.PRODUCT),
							filterBy(
								entityPrimaryKeyInSet(1)
							),
							require(
								entityFetch(
									referenceContent("nonExisting")
								)
							)
						)
					)
				);
			}
		);
	}

	@DisplayName("Should throw exception for reference outside entityFetch")
	@Test
	void shouldThrowExceptionWhenReferenceContentIsOutsideEntityFetch(@UseDataSet(HUNDRED_PRODUCTS) Evita evita) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				assertThrows(
					ReferenceContentMisplacedException.class,
					() -> session.querySealedEntity(
						query(
							collection(Entities.PRODUCT),
							filterBy(
								entityPrimaryKeyInSet(1)
							),
							require(
								referenceContent()
							)
						)
					)
				);
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

	@DisplayName("Single entity with attributes only by primary key should be found")
	@Test
	void shouldRetrieveSingleEntityWithAttributesByPrimaryKey(@UseDataSet(HUNDRED_PRODUCTS) Evita evita) {
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
	@Test
	void shouldRetrieveMultipleEntitiesWithAttributesByPrimaryKey(@UseDataSet(HUNDRED_PRODUCTS) Evita evita) {
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
	void shouldRetrieveSingleEntityWithAttributesInLanguageByPrimaryKey(Evita evita, List<SealedEntity> originalProducts) {
		final Integer[] entitiesMatchingTheRequirements = getRequestedIdsByPredicate(
			originalProducts,
			it -> it.getAttribute(ATTRIBUTE_NAME, LOCALE_CZECH) != null && it.getAttribute(ATTRIBUTE_URL, LOCALE_CZECH) != null &&
				it.getAttribute(ATTRIBUTE_NAME, Locale.ENGLISH) == null && it.getAttribute(ATTRIBUTE_URL, Locale.ENGLISH) == null
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
	void shouldRetrieveSingleEntityWithAttributesInMultipleLanguagesByPrimaryKey(Evita evita, List<SealedEntity> originalProducts) {
		final Integer[] entitiesMatchingTheRequirements = getRequestedIdsByPredicate(
			originalProducts,
			it -> it.getAttribute(ATTRIBUTE_NAME, LOCALE_CZECH) != null && it.getAttribute(ATTRIBUTE_URL, LOCALE_CZECH) != null &&
				it.getAttribute(ATTRIBUTE_NAME, Locale.ENGLISH) != null && it.getAttribute(ATTRIBUTE_URL, Locale.ENGLISH) != null
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
	void shouldRetrieveMultipleEntitiesWithAttributesInLanguageByPrimaryKey(Evita evita, List<SealedEntity> originalProducts) {
		final Integer[] pks = originalProducts
			.stream()
			.filter(
				it ->
					(it.getAttribute(ATTRIBUTE_NAME, LOCALE_CZECH) != null && it.getAttribute(ATTRIBUTE_URL, LOCALE_CZECH) != null) &&
						!(it.getAttribute(ATTRIBUTE_NAME, Locale.ENGLISH) != null && it.getAttribute(ATTRIBUTE_URL, Locale.ENGLISH) != null)
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

				assertProduct(productByPk.getRecordData().get(0), productWithAssociatedData.getPrimaryKey(), false, true, false, false);
				return null;
			}
		);
	}

	@DisplayName("Multiple entities with associated data only by their primary keys should be found")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldRetrieveMultipleEntitiesWithAssociatedDataByPrimaryKey(Evita evita, List<SealedEntity> originalProducts) {
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

				assertProduct(productByPk.getRecordData().get(0), entitiesMatchingTheRequirements[0], false, true, false, false);
				assertProduct(productByPk.getRecordData().get(1), entitiesMatchingTheRequirements[1], false, true, false, false);
				assertProduct(productByPk.getRecordData().get(2), entitiesMatchingTheRequirements[2], false, true, false, false);
				assertProduct(productByPk.getRecordData().get(3), entitiesMatchingTheRequirements[3], false, true, false, false);
				return null;
			}
		);
	}

	@DisplayName("Multiple entities with associated data in passed language only by their primary keys should be found")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldRetrieveMultipleEntitiesWithAssociatedDataInLanguageDataByPrimaryKey(Evita evita, List<SealedEntity> originalProducts) {
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
	void shouldRetrieveMultipleEntitiesWithAssociatedDataInMultipleLanguageDataByPrimaryKey(Evita evita, List<SealedEntity> originalProducts) {
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
	void shouldRetrieveMultipleEntitiesWithNamedAssociatedDataByPrimaryKey(Evita evita, List<SealedEntity> originalProducts) {
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
	void shouldRetrieveMultipleEntitiesWithNamedAssociatedDataInLanguageByPrimaryKey(Evita evita, List<SealedEntity> originalProducts) {
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

	@DisplayName("Multiple entities with all prices by their primary keys should be found")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldRetrieveMultipleEntitiesWithAllPricesByPrimaryKey(Evita evita, List<SealedEntity> originalProducts) {
		final Integer[] entitiesMatchingTheRequirements = getRequestedIdsByPredicate(
			originalProducts,
			it -> it.getPrices().stream().map(PriceContract::currency).anyMatch(CURRENCY_EUR::equals) &&
				it.getPrices().stream().map(PriceContract::currency).anyMatch(CURRENCY_USD::equals) &&
				it.getPrices().stream().map(PriceContract::priceList).anyMatch(PRICE_LIST_BASIC::equals) &&
				it.getPrices().stream().map(PriceContract::priceList).anyMatch(PRICE_LIST_B2B::equals)
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
								priceContentAll()
							)
						)
					)
				);

				assertEquals(entitiesMatchingTheRequirements.length, productByPk.getRecordData().size());
				assertEquals(entitiesMatchingTheRequirements.length, productByPk.getTotalRecordCount());

				for (SealedEntity product : productByPk.getRecordData()) {
					assertHasPriceInCurrency(product, CURRENCY_GBP, CURRENCY_USD);
					assertHasPriceInPriceList(product, PRICE_LIST_BASIC, PRICE_LIST_INTRODUCTION);
				}
				return null;
			}
		);
	}

	@DisplayName("Should return entity with price for sale and accompanied price")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnEntityWithPriceForSaleAndAccompanyingPrice(Evita evita, List<SealedEntity> originalProducts) {
		final Integer[] entitiesMatchingTheRequirements = getRequestedIdsByPredicate(
			originalProducts,
			it -> it.getPrices().stream().map(PriceContract::currency).anyMatch(CURRENCY_EUR::equals) &&
				it.getPrices().stream().map(PriceContract::priceList).anyMatch(PRICE_LIST_BASIC::equals) &&
				it.getPrices().stream().map(PriceContract::priceList).anyMatch(PRICE_LIST_B2B::equals)
		);

		assertTrue(entitiesMatchingTheRequirements.length > 0, "None entity match the filter, test would not work!");

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> productByPk = session.querySealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(entitiesMatchingTheRequirements[0]),
							priceInCurrency(CURRENCY_EUR),
							priceInPriceLists(PRICE_LIST_BASIC)
						),
						require(
							page(1, Integer.MAX_VALUE),
							defaultAccompanyingPriceLists(PRICE_LIST_B2B),
							entityFetch(
								priceContent(PriceContentMode.RESPECTING_FILTER),
								accompanyingPriceContentDefault()
							)
						)
					)
				);

				assertEquals(1, productByPk.getRecordData().size());
				assertEquals(1, productByPk.getTotalRecordCount());

				final SealedEntity product = productByPk.getRecordData().get(0);
				final Optional<PriceContract> priceForSale = product.getPriceForSale();

				assertTrue(priceForSale.isPresent(), "Product should have a price for sale!");
				assertEquals(CURRENCY_EUR, priceForSale.get().currency());
				assertEquals(PRICE_LIST_BASIC, priceForSale.get().priceList());

				final Optional<PriceContract> accompanyingPrice = product.getAccompanyingPrice();
				assertTrue(accompanyingPrice.isPresent(), "Product should have an accompanying price!");
				assertEquals(CURRENCY_EUR, accompanyingPrice.get().currency());
				assertEquals(PRICE_LIST_B2B, accompanyingPrice.get().priceList());

				return null;
			}
		);
	}

	@DisplayName("Should return entity with price for sale and two accompanied prices")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnEntityWithPriceForSaleAndTwoAccompanyingPrices(Evita evita, List<SealedEntity> originalProducts) {
		final Integer[] entitiesMatchingTheRequirements = getRequestedIdsByPredicate(
			originalProducts,
			it -> it.getPrices().stream().map(PriceContract::currency).anyMatch(CURRENCY_EUR::equals) &&
				it.getPrices().stream().map(PriceContract::priceList).anyMatch(PRICE_LIST_BASIC::equals) &&
				it.getPrices().stream().map(PriceContract::priceList).anyMatch(PRICE_LIST_B2B::equals) &&
				it.getPrices().stream().map(PriceContract::priceList).anyMatch(PRICE_LIST_VIP::equals)
		);

		assertTrue(entitiesMatchingTheRequirements.length > 0, "None entity match the filter, test would not work!");

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> productByPk = session.querySealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(entitiesMatchingTheRequirements[0]),
							priceInCurrency(CURRENCY_EUR),
							priceInPriceLists(PRICE_LIST_BASIC)
						),
						require(
							page(1, Integer.MAX_VALUE),
							defaultAccompanyingPriceLists(PRICE_LIST_B2B),
							entityFetch(
								priceContent(PriceContentMode.RESPECTING_FILTER),
								accompanyingPriceContentDefault(),
								accompanyingPriceContent("myPrice", PRICE_LIST_VIP)
							)
						)
					)
				);

				assertEquals(1, productByPk.getRecordData().size());
				assertEquals(1, productByPk.getTotalRecordCount());

				final SealedEntity product = productByPk.getRecordData().get(0);
				final Optional<PriceContract> priceForSale = product.getPriceForSale();

				assertTrue(priceForSale.isPresent(), "Product should have a price for sale!");
				assertEquals(CURRENCY_EUR, priceForSale.get().currency());
				assertEquals(PRICE_LIST_BASIC, priceForSale.get().priceList());

				final Optional<PriceContract> accompanyingPrice = product.getAccompanyingPrice();
				assertTrue(accompanyingPrice.isPresent(), "Product should have an accompanying price!");
				assertEquals(CURRENCY_EUR, accompanyingPrice.get().currency());
				assertEquals(PRICE_LIST_B2B, accompanyingPrice.get().priceList());

				final Optional<PriceContract> myAccompanyingPrice = product.getAccompanyingPrice("myPrice");
				assertTrue(myAccompanyingPrice.isPresent(), "Product should have an accompanying price!");
				assertEquals(CURRENCY_EUR, myAccompanyingPrice.get().currency());
				assertEquals(PRICE_LIST_VIP, myAccompanyingPrice.get().priceList());

				assertArrayEquals(
					new String[]{AccompanyingPriceContent.DEFAULT_ACCOMPANYING_PRICE, "myPrice"},
					product.getPriceForSaleWithAccompanyingPrices().orElseThrow().getAccompanyingPrices().keySet().toArray(String[]::new),
					"Product should have two accompanying prices!"
				);

				return null;
			}
		);
	}

	@DisplayName("Should return entity with diferent price for sale and accompanied prices")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnEntityWithDifferentPriceForSaleAndAccompanyingPrices(Evita evita, List<SealedEntity> originalProducts) {
		final Integer[] entitiesMatchingTheRequirements = getRequestedIdsByPredicate(
			originalProducts,
			it -> it.getPrices().stream().map(PriceContract::currency).anyMatch(CURRENCY_EUR::equals) &&
				it.getPrices().stream().map(PriceContract::priceList).anyMatch(PRICE_LIST_BASIC::equals) &&
				it.getPrices().stream().map(PriceContract::priceList).anyMatch(PRICE_LIST_B2B::equals) &&
				it.getPrices().stream().map(PriceContract::priceList).anyMatch(PRICE_LIST_VIP::equals)
		);

		assertTrue(entitiesMatchingTheRequirements.length > 0, "None entity match the filter, test would not work!");

		final Map<Integer, SealedEntity> originalProductsByPk = originalProducts
			.stream()
			.collect(Collectors.toMap(EntityContract::getPrimaryKey, Function.identity()));
		final Set<Integer> matchingPks = Arrays.stream(entitiesMatchingTheRequirements).collect(Collectors.toSet());

		final SealedEntity selectedProduct = originalProducts
			.stream()
			.filter(it -> matchingPks.contains(it.getPrimaryKey()))
			.filter(
				it -> it.getReferences(Entities.PRODUCT)
					.stream()
					.map(ReferenceContract::getReferencedPrimaryKey)
					.map(originalProductsByPk::get)
					.anyMatch(
						refProd -> {
							final Set<String> refPriceLists = refProd
								.getPrices()
								.stream()
								.filter(refPrice -> CURRENCY_EUR.equals(refPrice.currency()))
								.map(PriceContract::priceList)
								.collect(Collectors.toSet());
							return refPriceLists.size() > 1 &&
								refPriceLists.contains(PRICE_LIST_BASIC);
						}
					)
			)
			.findFirst()
			.orElseThrow();
		final String secondPriceList = selectedProduct.getReferences(Entities.PRODUCT)
			.stream()
			.map(ReferenceContract::getReferencedPrimaryKey)
			.map(originalProductsByPk::get)
			.flatMap(refProd -> refProd.getPrices().stream())
			.filter(price -> CURRENCY_EUR.equals(price.currency()) && !PRICE_LIST_BASIC.equals(price.priceList()))
			.map(PriceContract::priceList)
			.findFirst()
			.orElseThrow();

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> productByPk = session.querySealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(selectedProduct.getPrimaryKeyOrThrowException()),
							priceInCurrency(CURRENCY_EUR),
							priceInPriceLists(PRICE_LIST_BASIC)
						),
						require(
							page(1, Integer.MAX_VALUE),
							defaultAccompanyingPriceLists(PRICE_LIST_B2B),
							entityFetch(
								priceContent(PriceContentMode.RESPECTING_FILTER),
								accompanyingPriceContentDefault(),
								referenceContent(
									Entities.PRODUCT,
									(FilterBy) null,
									entityFetch(
										priceContent(PriceContentMode.RESPECTING_FILTER),
										accompanyingPriceContent("myPrice", secondPriceList)
									)
								)
							)
						)
					)
				);

				assertEquals(1, productByPk.getRecordData().size());
				assertEquals(1, productByPk.getTotalRecordCount());

				final SealedEntity product = productByPk.getRecordData().get(0);
				final Optional<PriceContract> priceForSale = product.getPriceForSale();

				assertTrue(priceForSale.isPresent(), "Product should have a price for sale!");
				assertEquals(CURRENCY_EUR, priceForSale.get().currency());
				assertEquals(PRICE_LIST_BASIC, priceForSale.get().priceList());

				final Optional<PriceContract> accompanyingPrice = product.getAccompanyingPrice();
				assertTrue(accompanyingPrice.isPresent(), "Product should have an accompanying price!");
				assertEquals(CURRENCY_EUR, accompanyingPrice.get().currency());
				assertEquals(PRICE_LIST_B2B, accompanyingPrice.get().priceList());

				assertTrue(product.getAccompanyingPrice(secondPriceList).isEmpty());

				final SealedEntity nestedProduct = product.getReferences(Entities.PRODUCT)
					.stream()
					.map(it -> it.getReferencedEntity().orElseThrow())
					.filter(it -> it.getPrice(PRICE_LIST_BASIC, CURRENCY_EUR).isPresent() && it.getPrice(secondPriceList, CURRENCY_EUR).isPresent())
					.findFirst()
					.orElseThrow();

				final Optional<PriceContract> myAccompanyingPrice = nestedProduct.getAccompanyingPrice("myPrice");
				assertTrue(myAccompanyingPrice.isPresent(), "Product should have an accompanying price!");
				assertEquals(CURRENCY_EUR, myAccompanyingPrice.get().currency());
				assertEquals(secondPriceList, myAccompanyingPrice.get().priceList());

				assertArrayEquals(
					new String[]{"myPrice"},
					nestedProduct.getPriceForSaleWithAccompanyingPrices().orElseThrow().getAccompanyingPrices().keySet().toArray(String[]::new),
					"Product should have only `myPrice`!"
				);

				return null;
			}
		);
	}

	@DisplayName("Multiple entities with prices in selected currency by their primary keys should be found")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldRetrieveMultipleEntitiesWithPricesInCurrencyByPrimaryKey(Evita evita, List<SealedEntity> originalProducts) {
		final Integer[] entitiesMatchingTheRequirements = getRequestedIdsByPredicate(
			originalProducts,
			it -> {
				final List<PriceContract> filteredPrices = it.getPrices()
					.stream()
					.filter(PriceContract::indexed)
					.filter(price -> Objects.equals(price.priceList(), PRICE_LIST_B2B))
					.toList();
				return filteredPrices.stream().map(PriceContract::currency).anyMatch(CURRENCY_EUR::equals) &&
					filteredPrices.stream().map(PriceContract::currency).noneMatch(CURRENCY_USD::equals);
			}
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
								priceInPriceLists(PRICE_LIST_B2B),
								priceInCurrency(CURRENCY_EUR)
							)
						),
						require(
							entityFetch(
								priceContentRespectingFilter()
							)
						)
					)
				);
				assertEquals(Math.min(20, entitiesMatchingTheRequirements.length), productByPk.getRecordData().size());
				assertEquals(entitiesMatchingTheRequirements.length, productByPk.getTotalRecordCount());

				for (SealedEntity product : productByPk.getRecordData()) {
					assertHasPriceInCurrency(product, CURRENCY_EUR);
					assertHasNotPriceInCurrency(product, CURRENCY_USD);
				}
				return null;
			}
		);
	}

	@DisplayName("Multiple entities with prices in selected price lists by their primary keys should be found")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldRetrieveMultipleEntitiesWithPricesInPriceListsByPrimaryKey(Evita evita, List<SealedEntity> originalProducts) {
		final Integer[] entitiesMatchingTheRequirements = getRequestedIdsByPredicate(
			originalProducts,
			it -> it.getPrices(CURRENCY_USD)
				.stream()
				.filter(PriceContract::indexed)
				.map(PriceContract::priceList)
				.anyMatch(PRICE_LIST_BASIC::equals) &&
				it.getPrices(CURRENCY_USD).stream()
					.filter(PriceContract::indexed)
					.map(PriceContract::priceList)
					.noneMatch(pl ->
						pl.equals(PRICE_LIST_REFERENCE) &&
							pl.equals(PRICE_LIST_INTRODUCTION) &&
							pl.equals(PRICE_LIST_B2B) &&
							pl.equals(PRICE_LIST_VIP)
					)
		);

		assertTrue(
			entitiesMatchingTheRequirements.length > 0,
			"None entity match the filter, test would not work!"
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
								priceInCurrency(CURRENCY_USD),
								priceInPriceLists(PRICE_LIST_BASIC)
							)
						),
						require(
							entityFetch(
								priceContentRespectingFilter()
							)
						)
					)
				);

				assertEquals(Math.min(entitiesMatchingTheRequirements.length, 20), productByPk.getRecordData().size());
				assertEquals(entitiesMatchingTheRequirements.length, productByPk.getTotalRecordCount());

				for (SealedEntity product : productByPk.getRecordData()) {
					assertHasPriceInPriceList(product, PRICE_LIST_BASIC);
					assertHasNotPriceInPriceList(product, PRICE_LIST_REFERENCE, PRICE_LIST_INTRODUCTION, PRICE_LIST_B2B, PRICE_LIST_VIP);
				}
				return null;
			}
		);
	}

	@DisplayName("Multiple entities with prices valid in specified time by their primary keys should be found")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldRetrieveMultipleEntitiesWithPricesValidInTimeByPrimaryKey(Evita evita, List<SealedEntity> originalProducts) {
		final OffsetDateTime theMoment = OffsetDateTime.of(2015, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
		final Integer[] entitiesMatchingTheRequirements = getRequestedIdsByPredicate(
			originalProducts,
			it -> it.getPrices().stream().filter(PriceContract::indexed).map(PriceContract::validity).anyMatch(validity -> validity == null || validity.isValidFor(theMoment))
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
								priceValidIn(theMoment)
							)
						),
						require(
							entityFetch(
								priceContentRespectingFilter()
							),
							page(1, 100)
						)
					)
				);
				assertEquals(Math.min(100, entitiesMatchingTheRequirements.length), productByPk.getRecordData().size());
				assertEquals(entitiesMatchingTheRequirements.length, productByPk.getTotalRecordCount());

				for (SealedEntity product : productByPk.getRecordData()) {
					for (PriceContract price : product.getPrices()) {
						assertTrue(
							price.validity() == null || price.validity().isValidFor(theMoment),
							"Listed price " + price + " which is not valid for the moment!"
						);
					}
				}
				return null;
			}
		);
	}

	@DisplayName("Multiple entities with references by their primary keys should be found")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldRetrieveMultipleEntitiesWithReferencesByPrimaryKey(Evita evita, List<SealedEntity> originalProducts) {
		final Integer[] entitiesMatchingTheRequirements = getRequestedIdsByPredicate(
			originalProducts,
			it -> !it.getReferences().isEmpty()
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
								referenceContentAll()
							),
							page(1, 4)
						)
					)
				);

				assertEquals(4, productByPk.getRecordData().size());
				assertEquals(entitiesMatchingTheRequirements.length, productByPk.getTotalRecordCount());

				for (SealedEntity product : productByPk.getRecordData()) {
					assertFalse(product.getReferences().isEmpty());
				}
				return null;
			}
		);
	}

	@DisplayName("Multiple entities with specific references by their primary keys should be found")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldRetrieveMultipleEntitiesWithSpecificReferencesByPrimaryKey(Evita evita, List<SealedEntity> originalProducts) {
		final Integer[] entitiesMatchingTheRequirements = getRequestedIdsByPredicate(
			originalProducts,
			it -> !it.getReferences(Entities.CATEGORY).isEmpty() && !it.getReferences(Entities.STORE).isEmpty()
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
								referenceContent(Entities.CATEGORY),
								referenceContent(Entities.STORE)
							),
							page(1, 4)
						)
					)
				);

				assertEquals(4, productByPk.getRecordData().size());
				assertEquals(entitiesMatchingTheRequirements.length, productByPk.getTotalRecordCount());

				for (SealedEntity product : productByPk.getRecordData()) {
					assertFalse(product.getReferences(Entities.CATEGORY).isEmpty());
					assertTrue(product.getReferences(Entities.CATEGORY).stream().noneMatch(AttributesContract::attributesAvailable));
					assertFalse(product.getReferences(Entities.STORE).isEmpty());
					assertTrue(product.getReferences(Entities.STORE).stream().noneMatch(AttributesContract::attributesAvailable));
				}
				return null;
			}
		);
	}

	@DisplayName("Entities should be found by their primary keys with all references without attributes")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldRetrieveEntitiesWithoutReferenceAttributes(Evita evita, List<SealedEntity> originalProducts) {
		final Integer[] entitiesMatchingTheRequirements = getRequestedIdsByPredicate(
			originalProducts,
			it -> it.getReferences().stream().noneMatch(ref -> ref.getAttributeValues().isEmpty())
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
								referenceContentAll()
							),
							page(1, 4)
						)
					)
				);

				assertEquals(4, productByPk.getRecordData().size());
				assertEquals(entitiesMatchingTheRequirements.length, productByPk.getTotalRecordCount());

				for (SealedEntity product : productByPk.getRecordData()) {
					assertTrue(
						product.getReferences()
							.stream()
							.noneMatch(AttributesAvailabilityChecker::attributesAvailable)
					);
				}
				return null;
			}
		);
	}

	@DisplayName("Entities should be found by their primary keys with all references with all attributes")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldRetrieveEntitiesWithAllReferenceAttributes(Evita evita, List<SealedEntity> originalProducts) {
		final Integer[] entitiesMatchingTheRequirements = getRequestedIdsByPredicate(
			originalProducts,
			it -> it.getReferences().stream().noneMatch(ref -> ref.getAttributeValues().isEmpty())
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
								referenceContentAllWithAttributes()
							),
							page(1, 4)
						)
					)
				);

				assertEquals(4, productByPk.getRecordData().size());
				assertEquals(entitiesMatchingTheRequirements.length, productByPk.getTotalRecordCount());

				for (SealedEntity product : productByPk.getRecordData()) {
					assertTrue(
						product.getReferences()
							.stream()
							.allMatch(AttributesAvailabilityChecker::attributesAvailable)
					);
				}
				return null;
			}
		);
	}

	@DisplayName("Multiple entities with specific references with exactly stated attributes can be retrieved")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldRetrieveMultipleEntitiesWithSpecificReferencesByPrimaryKeyWithExactAttributes(Evita evita, List<SealedEntity> originalProducts) {
		final Integer[] entitiesMatchingTheRequirements = getRequestedIdsByPredicate(
			originalProducts,
			it -> !it.getReferences(Entities.CATEGORY).isEmpty() && !it.getReferences(Entities.STORE).isEmpty()
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
								referenceContentWithAttributes(
									Entities.CATEGORY,
									attributeContent(ATTRIBUTE_CATEGORY_SHADOW)
								)
							),
							page(1, 4)
						)
					)
				);

				assertEquals(4, productByPk.getRecordData().size());
				assertEquals(entitiesMatchingTheRequirements.length, productByPk.getTotalRecordCount());

				for (SealedEntity product : productByPk.getRecordData()) {
					assertFalse(product.getReferences(Entities.CATEGORY).isEmpty());
					for (ReferenceContract categoryRef : product.getReferences(Entities.CATEGORY)) {
						assertEquals(1, categoryRef.getAttributeValues().size());
						assertNotNull(categoryRef.getAttributeValue(ATTRIBUTE_CATEGORY_SHADOW));
					}
				}
				return null;
			}
		);
	}

	/*
		PRIVATE METHODS
	 */

	@DisplayName("Multiple entities with specific references with all attributes can be retrieved")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldRetrieveMultipleEntitiesWithSpecificReferencesByPrimaryKeyWithAllAttributes(Evita evita, List<SealedEntity> originalProducts) {
		final Integer[] entitiesMatchingTheRequirements = getRequestedIdsByPredicate(
			originalProducts,
			it -> !it.getReferences(Entities.CATEGORY).isEmpty() && !it.getReferences(Entities.STORE).isEmpty()
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
								referenceContentWithAttributes(
									Entities.CATEGORY,
									attributeContentAll()
								)
							),
							page(1, 4)
						)
					)
				);

				assertEquals(4, productByPk.getRecordData().size());
				assertEquals(entitiesMatchingTheRequirements.length, productByPk.getTotalRecordCount());

				for (SealedEntity product : productByPk.getRecordData()) {
					assertFalse(product.getReferences(Entities.CATEGORY).isEmpty());
					for (ReferenceContract categoryRef : product.getReferences(Entities.CATEGORY)) {
						assertFalse(categoryRef.getAttributeValues().isEmpty());
					}
				}
				return null;
			}
		);
	}

	@DisplayName("Multiple entities with specific references with all attributes (default behaviour) can be retrieved")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldRetrieveMultipleEntitiesWithSpecificReferencesByPrimaryKeyWithAllAttributesDefault(Evita evita, List<SealedEntity> originalProducts) {
		final Integer[] entitiesMatchingTheRequirements = getRequestedIdsByPredicate(
			originalProducts,
			it -> !it.getReferences(Entities.CATEGORY).isEmpty() && !it.getReferences(Entities.STORE).isEmpty()
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
								referenceContentWithAttributes(Entities.CATEGORY)
							),
							page(1, 4)
						)
					)
				);

				assertEquals(4, productByPk.getRecordData().size());
				assertEquals(entitiesMatchingTheRequirements.length, productByPk.getTotalRecordCount());

				for (SealedEntity product : productByPk.getRecordData()) {
					assertFalse(product.getReferences(Entities.CATEGORY).isEmpty());
					for (ReferenceContract categoryRef : product.getReferences(Entities.CATEGORY)) {
						assertFalse(categoryRef.getAttributeValues().isEmpty());
					}
				}
				return null;
			}
		);
	}

	@DisplayName("Multiple entities with references by their primary keys should be found")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldRetrieveMultipleEntitiesWithReferencesByTypeAndByPrimaryKey(Evita evita, List<SealedEntity> originalProducts) {
		final Integer[] entitiesMatchingTheRequirements = getRequestedIdsByPredicate(
			originalProducts,
			it -> !it.getReferences(Entities.STORE).isEmpty()
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
								referenceContent(Entities.STORE)
							)
						)
					)
				);

				assertEquals(Math.min(entitiesMatchingTheRequirements.length, 20), productByPk.getRecordData().size());
				assertEquals(entitiesMatchingTheRequirements.length, productByPk.getTotalRecordCount());

				for (SealedEntity product : productByPk.getRecordData()) {
					assertFalse(product.getReferences(Entities.STORE).isEmpty());
					assertThrows(ContextMissingException.class, () -> product.getReferences(Entities.BRAND));
					assertThrows(ContextMissingException.class, () -> product.getReferences(Entities.CATEGORY));
				}
				return null;
			}
		);
	}

	@DisplayName("Attributes can be lazy auto loaded")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldLazyLoadAttributes(Evita evita, List<SealedEntity> originalProducts) {
		final Integer[] entitiesMatchingTheRequirements = getRequestedIdsByPredicate(
			originalProducts,
			it -> !it.getAttributeValues().isEmpty()
		);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> productByPk = session.querySealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(entitiesMatchingTheRequirements[0])
						),
						require(
							entityFetch()
						)
					)
				);
				assertEquals(1, productByPk.getRecordData().size());
				assertEquals(1, productByPk.getTotalRecordCount());

				final SealedEntity product = productByPk.getRecordData().get(0);
				assertProduct(product, entitiesMatchingTheRequirements[0], false, false, false, false);

				final SealedEntity enrichedProduct = session.enrichEntity(product, attributeContentAll());
				assertProduct(enrichedProduct, entitiesMatchingTheRequirements[0], true, false, false, false);
				return null;
			}
		);
	}

	@DisplayName("Attributes can be lazy auto loaded while respecting language")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldLazyLoadAttributesButLanguageMustBeRespected(Evita evita, List<SealedEntity> originalProducts) {
		final Integer[] entitiesMatchingTheRequirements = getRequestedIdsByPredicate(
			originalProducts,
			it -> it.getAttribute(ATTRIBUTE_NAME, LOCALE_CZECH) != null &&
				it.getAttribute(ATTRIBUTE_NAME, Locale.ENGLISH) != null
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
							entityFetch()
						)
					)
				);
				assertEquals(1, productByPk.getRecordData().size());
				assertEquals(1, productByPk.getTotalRecordCount());

				final SealedEntity product = productByPk.getRecordData().get(0);
				assertProduct(product, 1, false, false, false, false);

				final SealedEntity enrichedProduct = session.enrichEntity(product, attributeContentAll());
				assertProduct(enrichedProduct, 1, true, false, false, false);
				assertNotNull(enrichedProduct.getAttribute(ATTRIBUTE_NAME, LOCALE_CZECH));
				assertEquals((String) enrichedProduct.getAttribute(ATTRIBUTE_NAME, LOCALE_CZECH), enrichedProduct.getAttribute(ATTRIBUTE_NAME));
				assertThrows(ContextMissingException.class, () -> enrichedProduct.getAttribute(ATTRIBUTE_NAME, Locale.ENGLISH));
				return null;
			}
		);
	}

	@DisplayName("Associated data can be lazy auto loaded")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldLazyLoadAssociatedData(Evita evita, List<SealedEntity> originalProducts) {
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
							entityPrimaryKeyInSet(entitiesMatchingTheRequirements[0])
						),
						require(
							entityFetch()
						)
					)
				);
				assertEquals(1, productByPk.getRecordData().size());
				assertEquals(1, productByPk.getTotalRecordCount());

				final SealedEntity product = productByPk.getRecordData().get(0);
				assertProduct(product, entitiesMatchingTheRequirements[0], false, false, false, false);
				final SealedEntity enrichedProduct = session.enrichEntity(product, associatedDataContentAll());
				assertProduct(enrichedProduct, entitiesMatchingTheRequirements[0], false, true, false, false);
				return null;
			}
		);
	}

	@DisplayName("Associated data can be lazy auto loaded")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldLazyLoadAssociatedDataButLanguageMustBeRespected(Evita evita, List<SealedEntity> originalProducts) {
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
							and(
								entityPrimaryKeyInSet(entitiesMatchingTheRequirements[0]),
								entityLocaleEquals(LOCALE_CZECH)
							)
						),
						require(
							entityFetch()
						)
					)
				);
				assertEquals(1, productByPk.getRecordData().size());
				assertEquals(1, productByPk.getTotalRecordCount());

				final SealedEntity product = productByPk.getRecordData().get(0);
				assertProduct(product, entitiesMatchingTheRequirements[0], false, false, false, false);
				final SealedEntity enrichedProduct = session.enrichEntity(product, associatedDataContentAll());
				assertNotNull(enrichedProduct.getAssociatedData(ASSOCIATED_DATA_LABELS, LOCALE_CZECH));
				assertEquals(enrichedProduct.getAssociatedDataValue(ASSOCIATED_DATA_LABELS, LOCALE_CZECH), enrichedProduct.getAssociatedDataValue(ASSOCIATED_DATA_LABELS));
				assertNull(enrichedProduct.getAssociatedData(ASSOCIATED_DATA_LABELS, Locale.ENGLISH));
				return null;
			}
		);
	}

	@DisplayName("Associated data can be lazy auto loaded in different languages lazily")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldLazyLoadAssociatedDataWithIncrementallyAddingLanguages(Evita evita, List<SealedEntity> originalProducts) {
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
							and(
								entityPrimaryKeyInSet(entitiesMatchingTheRequirements[0]),
								entityLocaleEquals(LOCALE_CZECH)
							)
						),
						require(
							entityFetch()
						)
					)
				);
				assertEquals(1, productByPk.getRecordData().size());
				assertEquals(1, productByPk.getTotalRecordCount());

				final SealedEntity product = productByPk.getRecordData().get(0);
				assertProduct(product, entitiesMatchingTheRequirements[0], false, false, false, false);

				final SealedEntity enrichedProduct = session.enrichEntity(product, associatedDataContentAll());
				assertNotNull(enrichedProduct.getAssociatedData(ASSOCIATED_DATA_LABELS, LOCALE_CZECH));
				assertEquals(enrichedProduct.getAssociatedDataValue(ASSOCIATED_DATA_LABELS, LOCALE_CZECH), enrichedProduct.getAssociatedDataValue(ASSOCIATED_DATA_LABELS));
				assertNull(enrichedProduct.getAssociatedData(ASSOCIATED_DATA_LABELS, Locale.ENGLISH));

				final SealedEntity enrichedProductWithAdditionalLanguage = session.enrichEntity(enrichedProduct, dataInLocales(Locale.ENGLISH));
				assertNotNull(enrichedProductWithAdditionalLanguage.getAssociatedData(ASSOCIATED_DATA_LABELS, LOCALE_CZECH));
				assertNotNull(enrichedProductWithAdditionalLanguage.getAssociatedData(ASSOCIATED_DATA_LABELS, Locale.ENGLISH));

				return null;
			}
		);
	}

	@DisplayName("Associated data can be lazy auto loaded incrementally by name")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldLazyLoadAssociatedDataByNameIncrementally(Evita evita, List<SealedEntity> originalProducts) {
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
							and(
								entityPrimaryKeyInSet(entitiesMatchingTheRequirements[0]),
								entityLocaleEquals(LOCALE_CZECH)
							)
						),
						require(
							entityFetch()
						)
					)
				);

				final SealedEntity product = productByPk.getRecordData().get(0);
				assertProduct(product, entitiesMatchingTheRequirements[0], false, false, false, false);

				final SealedEntity enrichedProduct1 = session.enrichEntity(product, associatedDataContent(ASSOCIATED_DATA_LABELS));
				assertNotNull(enrichedProduct1.getAssociatedData(ASSOCIATED_DATA_LABELS, LOCALE_CZECH));
				assertNull(enrichedProduct1.getAssociatedData(ASSOCIATED_DATA_REFERENCED_FILES, LOCALE_CZECH));

				final SealedEntity enrichedProduct2 = session.enrichEntity(enrichedProduct1, associatedDataContent(ASSOCIATED_DATA_REFERENCED_FILES));
				assertNotNull(enrichedProduct2.getAssociatedData(ASSOCIATED_DATA_LABELS, LOCALE_CZECH));
				assertNotNull(enrichedProduct2.getAssociatedData(ASSOCIATED_DATA_REFERENCED_FILES, LOCALE_CZECH));

				return null;
			}
		);
	}

	@DisplayName("Prices can be lazy auto loaded")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldLazyLoadAllPrices(Evita evita, List<SealedEntity> originalProducts) {
		final Integer[] entitiesMatchingTheRequirements = getRequestedIdsByPredicate(
			originalProducts,
			it -> it.getPrices().stream().map(PriceContract::currency).anyMatch(CURRENCY_GBP::equals) &&
				it.getPrices().stream().map(PriceContract::currency).anyMatch(CURRENCY_USD::equals) &&
				it.getPrices().stream().map(PriceContract::priceList).anyMatch(PRICE_LIST_BASIC::equals) &&
				it.getPrices().stream().map(PriceContract::priceList).anyMatch(PRICE_LIST_VIP::equals) &&
				it.getPrices().stream().map(PriceContract::priceList).anyMatch(PRICE_LIST_REFERENCE::equals) &&
				it.getPrices().stream().map(PriceContract::priceList).anyMatch(PRICE_LIST_B2B::equals) &&
				it.getPrices().stream().map(PriceContract::priceList).anyMatch(PRICE_LIST_INTRODUCTION::equals)
		);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> productByPk = session.querySealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(entitiesMatchingTheRequirements[0])
						),
						require(
							entityFetch()
						)
					)
				);
				assertEquals(1, productByPk.getRecordData().size());
				assertEquals(1, productByPk.getTotalRecordCount());

				final SealedEntity product = productByPk.getRecordData().get(0);
				assertThrows(ContextMissingException.class, product::getPrices);

				final SealedEntity enrichedProduct = session.enrichEntity(product, priceContentAll());
				assertHasPriceInCurrency(enrichedProduct, CURRENCY_GBP, CURRENCY_USD);
				assertHasPriceInPriceList(enrichedProduct, PRICE_LIST_BASIC, PRICE_LIST_VIP, PRICE_LIST_REFERENCE, PRICE_LIST_B2B, PRICE_LIST_INTRODUCTION);
				return null;
			}
		);
	}

	@DisplayName("Prices can be lazy auto loaded")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldLazyLoadFilteredPrices(Evita evita, List<SealedEntity> originalProducts) {
		final SealedEntity product = originalProducts
			.stream()
			.filter(it -> it.getPrices().stream().filter(PriceContract::indexed).anyMatch(price -> price.validity() != null))
			.findFirst()
			.orElseThrow();
		final PriceContract thePrice = product.getPrices()
			.stream()
			.filter(PriceContract::indexed)
			.filter(it -> it.validity() != null)
			.findFirst()
			.orElseThrow();
		final OffsetDateTime theMoment = thePrice.validity()
			.getPreciseFrom()
			.plusMinutes(1);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> productByPk = session.querySealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							and(
								entityPrimaryKeyInSet(product.getPrimaryKey()),
								priceInCurrency(thePrice.currency()),
								priceInPriceLists(thePrice.priceList()),
								priceValidIn(theMoment)
							)
						),
						require(
							entityFetch()
						)
					)
				);
				assertEquals(1, productByPk.getRecordData().size());
				assertEquals(1, productByPk.getTotalRecordCount());

				final SealedEntity returnedProduct = productByPk.getRecordData().get(0);
				assertThrows(ContextMissingException.class, returnedProduct::getPrices);

				final SealedEntity enrichedProduct = session.enrichEntity(returnedProduct, priceContentRespectingFilter());
				assertHasPriceInCurrency(enrichedProduct, CURRENCY_USD);
				assertHasPriceInPriceList(enrichedProduct, PRICE_LIST_VIP);
				return null;
			}
		);
	}

	@DisplayName("References can be lazy auto loaded")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldLazyLoadReferences(Evita evita, List<SealedEntity> originalProducts) {
		final Integer[] entitiesMatchingTheRequirements = getRequestedIdsByPredicate(
			originalProducts,
			it -> !it.getReferences(Entities.CATEGORY).isEmpty() &&
				!it.getReferences(Entities.BRAND).isEmpty() &&
				!it.getReferences(Entities.STORE).isEmpty()
		);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> productByPk = session.querySealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(entitiesMatchingTheRequirements[0])
						),
						require(
							entityFetch()
						)
					)
				);
				assertEquals(1, productByPk.getRecordData().size());
				assertEquals(1, productByPk.getTotalRecordCount());

				final SealedEntity product = productByPk.getRecordData().get(0);
				assertFalse(product.referencesAvailable());
				assertThrows(ContextMissingException.class, product::getReferences);

				final SealedEntity theEntity = originalProducts
					.stream()
					.filter(it -> Objects.equals(it.getPrimaryKey(), entitiesMatchingTheRequirements[0]))
					.findFirst()
					.orElseThrow(() -> new IllegalStateException("Should never happen!"));

				final SealedEntity enrichedProduct = session.enrichEntity(product, referenceContentAll());
				assertHasReferencesTo(enrichedProduct, Entities.CATEGORY, REFERENCED_ID_EXTRACTOR.apply(theEntity, Entities.CATEGORY));
				assertHasReferencesTo(enrichedProduct, Entities.BRAND, REFERENCED_ID_EXTRACTOR.apply(theEntity, Entities.BRAND));
				assertHasReferencesTo(enrichedProduct, Entities.STORE, REFERENCED_ID_EXTRACTOR.apply(theEntity, Entities.STORE));
				return null;
			}
		);
	}

	@DisplayName("References can be lazy auto loaded in iterative fashion")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldLazyLoadReferencesIteratively(Evita evita, List<SealedEntity> originalProducts) {
		final Integer[] entitiesMatchingTheRequirements = getRequestedIdsByPredicate(
			originalProducts,
			it -> !it.getReferences(Entities.CATEGORY).isEmpty() &&
				!it.getReferences(Entities.BRAND).isEmpty() &&
				!it.getReferences(Entities.STORE).isEmpty()
		);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> productByPk = session.querySealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(entitiesMatchingTheRequirements[0])
						),
						require(
							entityFetch()
						)
					)
				);
				assertEquals(1, productByPk.getRecordData().size());
				assertEquals(1, productByPk.getTotalRecordCount());

				final SealedEntity product = productByPk.getRecordData().get(0);
				assertFalse(product.referencesAvailable());
				assertThrows(ContextMissingException.class, product::getReferences);

				final SealedEntity theEntity = originalProducts
					.stream()
					.filter(it -> Objects.equals(it.getPrimaryKey(), entitiesMatchingTheRequirements[0]))
					.findFirst()
					.orElseThrow(() -> new IllegalStateException("Should never happen!"));

				final SealedEntity enrichedProduct1 = session.enrichEntity(product, referenceContent(Entities.CATEGORY));
				assertHasReferencesTo(enrichedProduct1, Entities.CATEGORY, REFERENCED_ID_EXTRACTOR.apply(theEntity, Entities.CATEGORY));
				assertHasNotReferencesTo(enrichedProduct1, Entities.BRAND);
				assertHasNotReferencesTo(enrichedProduct1, Entities.STORE);

				final SealedEntity enrichedProduct2 = session.enrichEntity(enrichedProduct1, referenceContent(Entities.BRAND));
				assertHasReferencesTo(enrichedProduct2, Entities.CATEGORY, REFERENCED_ID_EXTRACTOR.apply(theEntity, Entities.CATEGORY));
				assertHasReferencesTo(enrichedProduct2, Entities.BRAND, REFERENCED_ID_EXTRACTOR.apply(theEntity, Entities.BRAND));
				assertHasNotReferencesTo(enrichedProduct2, Entities.STORE);

				final SealedEntity enrichedProduct3 = session.enrichEntity(enrichedProduct2, referenceContent(Entities.STORE));
				assertHasReferencesTo(enrichedProduct3, Entities.CATEGORY, REFERENCED_ID_EXTRACTOR.apply(theEntity, Entities.CATEGORY));
				assertHasReferencesTo(enrichedProduct2, Entities.BRAND, REFERENCED_ID_EXTRACTOR.apply(theEntity, Entities.BRAND));
				assertHasReferencesTo(enrichedProduct3, Entities.STORE, REFERENCED_ID_EXTRACTOR.apply(theEntity, Entities.STORE));
				return null;
			}
		);
	}

	@DisplayName("Should check existence of the entity")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldLimitRichEntity(Evita evita) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> productByPk = session.querySealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							and(
								priceInPriceLists(PRICE_LIST_BASIC),
								priceInCurrency(CURRENCY_CZK)
							)
						),
						require(entityFetchAll())
					)
				);
				assertFalse(productByPk.getRecordData().isEmpty());

				final SealedEntity product = productByPk.getRecordData()
					.stream()
					.filter(
						it -> it.getPrices()
							.stream()
							.map(PriceContract::priceList)
							.distinct()
							.count() > 1
					)
					.findFirst()
					.orElseThrow();

				assertNotNull(product);
				assertFalse(product.getPrices().isEmpty());
				assertFalse(product.getAttributeValues().isEmpty());
				assertFalse(product.getAssociatedDataValues().isEmpty());

				final SealedEntity limitedToBody = session.enrichOrLimitEntity(product);
				assertThrows(ContextMissingException.class, limitedToBody::getPrices);
				assertFalse(limitedToBody.attributesAvailable());
				assertThrows(ContextMissingException.class, limitedToBody::getAttributeValues);
				assertFalse(limitedToBody.associatedDataAvailable());
				assertThrows(ContextMissingException.class, limitedToBody::getAssociatedDataValues);

				final SealedEntity limitedToBodyAndPrices = session.enrichOrLimitEntity(product, priceContentRespectingFilter());
				assertFalse(limitedToBodyAndPrices.getPrices().isEmpty());
				assertTrue(limitedToBodyAndPrices.getPrices().size() < product.getPrices().size());
				assertFalse(limitedToBodyAndPrices.attributesAvailable());
				assertThrows(ContextMissingException.class, limitedToBodyAndPrices::getAttributeValues);
				assertFalse(limitedToBodyAndPrices.associatedDataAvailable());
				assertThrows(ContextMissingException.class, limitedToBodyAndPrices::getAssociatedDataValues);

				final SealedEntity limitedToAttributes = session.enrichOrLimitEntity(product, attributeContent(), dataInLocalesAll());
				assertThrows(ContextMissingException.class, limitedToAttributes::getPrices);
				assertFalse(limitedToAttributes.getAttributeValues().isEmpty());
				assertFalse(limitedToAttributes.associatedDataAvailable());
				assertThrows(ContextMissingException.class, limitedToAttributes::getAssociatedDataValues);

				final SealedEntity limitedToAssociatedData = session.enrichOrLimitEntity(product, associatedDataContentAll(), dataInLocalesAll());
				assertThrows(ContextMissingException.class, limitedToAssociatedData::getPrices);
				assertFalse(limitedToAssociatedData.attributesAvailable());
				assertThrows(ContextMissingException.class, limitedToAssociatedData::getAttributeValues);
				assertFalse(limitedToAssociatedData.getAssociatedDataValues().isEmpty());

				return null;
			}
		);
	}

	@DisplayName("References can be eagerly deeply fetched")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldEagerlyDeepFetchReferenceEntityBodies(Evita evita, List<SealedEntity> originalProducts, List<SealedEntity> originalBrands) {
		final Set<Integer> brandsWithGroupedCategory = originalBrands.stream()
			.filter(it -> it.getReferences(Entities.STORE).stream().anyMatch(ref -> ref.getGroup().isPresent()))
			.map(EntityContract::getPrimaryKey)
			.collect(Collectors.toSet());

		final Integer[] entitiesMatchingTheRequirements = getRequestedIdsByPredicate(
			originalProducts,
			it -> !it.getReferences(Entities.BRAND).isEmpty() &&
				it.getReferences(Entities.BRAND).stream().anyMatch(brand -> brandsWithGroupedCategory.contains(brand.getReferencedPrimaryKey())) &&
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

				final Optional<SealedEntity> referencedStore = product.getReference(Entities.STORE, REFERENCED_ID_EXTRACTOR.apply(product, Entities.STORE)[0])
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
					referencedEntity.ifPresent(sealedEntity -> assertFalse(sealedEntity.getAttributeValues().isEmpty()));
				}
				assertTrue(atLeastOnePriceListBodyFound, "At least one price list body should have been found");

				return null;
			}
		);
	}

	@DisplayName("References can be eagerly deeply fetched in gradual manner")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldLazilyDeepFetchReferenceEntityBodies(Evita evita, List<SealedEntity> originalProducts, List<SealedEntity> originalBrands) {
		final Set<Integer> brandsWithGroupedCategory = originalBrands.stream()
			.filter(it -> it.getReferences(Entities.STORE).stream().anyMatch(ref -> ref.getGroup().isPresent()))
			.map(EntityContract::getPrimaryKey)
			.collect(Collectors.toSet());

		final Integer[] entitiesMatchingTheRequirements = getRequestedIdsByPredicate(
			originalProducts,
			it -> !it.getReferences(Entities.BRAND).isEmpty() &&
				it.getReferences(Entities.BRAND).stream().anyMatch(brand -> brandsWithGroupedCategory.contains(brand.getReferencedPrimaryKey())) &&
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

				final Optional<SealedEntity> referencedStore = enrichedProduct.getReference(Entities.STORE, REFERENCED_ID_EXTRACTOR.apply(enrichedProduct, Entities.STORE)[0])
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
	void shouldEagerlyDeepFetchReferenceEntityBodiesFilteredAndOrdered(Evita evita, List<SealedEntity> originalProducts) {
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

				final LocalizedStringComparator czechComparator = new LocalizedStringComparator(Collator.getInstance(CZECH_LOCALE));
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

				final LocalizedStringComparator czechComparator = new LocalizedStringComparator(Collator.getInstance(CZECH_LOCALE));
				for (final SealedEntity product : productByPk.getRecordData()) {
					final Collection<ReferenceContract> references = product.getReferences(Entities.PARAMETER);

					// references should be ordered by name
					final String[][] receivedOrderedParameterComposedNames = references.stream()
						.map(it -> {
							final String parameterTypeName = it.getGroupEntity().orElseThrow().getAttribute(ATTRIBUTE_NAME, String.class);
							final String parameterName = it.getReferencedEntity().orElseThrow().getAttribute(ATTRIBUTE_NAME, String.class);
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
	void shouldEagerlyDeepFetchReferenceEntityBodiesFilteredByAttributeAndOrdered(Evita evita, List<SealedEntity> originalProducts, List<SealedEntity> originalStores) {
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
						assertTrue(filteredStoreIds.contains(referencedStore.getAttribute(ATTRIBUTE_CODE, String.class)));
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
	void shouldEagerlyFetchReferencesFilteredByAttributeAndOrdered(Evita evita, List<SealedEntity> originalProducts, List<SealedEntity> originalStores) {
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
	void shouldEagerlyDeepFetchReferenceEntityBodiesFilteredAndOrderedViaGetEntity(Evita evita, List<SealedEntity> originalProducts, List<SealedEntity> originalStores) {
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
				final Integer[] filteredStores = productsWithLotsOfStores.values().iterator().next().toArray(new Integer[0]);
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
	void shouldEagerlyDeepFetchReferenceEntityBodiesFilteredByReferenceAttribute(Evita evita, List<SealedEntity> originalProducts) {
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
	void shouldEagerlyDeepFetchReferenceEntityBodiesFilteredAndOrderedByReferenceAndEntityAttribute(Evita evita, List<SealedEntity> originalProducts, Map<Integer, SealedEntity> originalCategories) {
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

				final LocalizedStringComparator czechComparator = new LocalizedStringComparator(Collator.getInstance(CZECH_LOCALE));
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
	void shouldFetchEvenMissingReferences(Evita evita, List<SealedEntity> originalProducts, List<EntityReferenceContract> originalPriceLists) {
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
	void shouldFetchEvenMissingReferenceBodies(Evita evita, List<SealedEntity> originalProducts, List<EntityReferenceContract> originalPriceLists) {
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
	void shouldFetchOnlyExistingReferences(Evita evita, List<SealedEntity> originalProducts, List<EntityReferenceContract> originalPriceLists) {
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
	void shouldFetchOnlyExistingReferenceBodies(Evita evita, List<SealedEntity> originalProducts, List<EntityReferenceContract> originalPriceLists) {
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
								referenceContentWithAttributes(ManagedReferencesBehaviour.EXISTING, Entities.PRICE_LIST, entityFetchAll())
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
	void shouldEagerlyDeepFetchReferenceEntityBinaryBodies(Evita evita, List<SealedEntity> originalProducts, List<SealedEntity> originalBrands) {
		final Set<Integer> brandsWithGroupedCategory = originalBrands.stream()
			.filter(it -> it.getReferences(Entities.STORE).stream().anyMatch(ref -> ref.getGroup().isPresent()))
			.map(EntityContract::getPrimaryKey)
			.collect(Collectors.toSet());

		final SealedEntity selectedEntity = findEntityByPredicate(
			originalProducts,
			it -> !it.getReferences(Entities.BRAND).isEmpty() &&
				it.getReferences(Entities.BRAND).stream().anyMatch(brand -> brandsWithGroupedCategory.contains(brand.getReferencedPrimaryKey())) &&
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
					.filter(it -> Entities.STORE.equals(it.getType()) && it.getPrimaryKey().equals(REFERENCED_ID_EXTRACTOR.apply(selectedEntity, Entities.STORE)[0]))
					.findFirst();

				assertTrue(referencedStore.isPresent());
				assertTrue(referencedStore.get().getAttributeStorageParts().length > 0);
				assertTrue(referencedStore.get().getAssociatedDataStorageParts().length > 0);

				final Optional<BinaryEntity> brand = Arrays.stream(product.getReferencedEntities())
					.filter(it -> Entities.BRAND.equals(it.getType()) && brandsWithGroupedCategory.contains(it.getPrimaryKey()))
					.findFirst();

				assertTrue(brand.isPresent());
				assertTrue(brand.get().getAttributeStorageParts().length > 0);
				assertEquals(0, brand.get().getAssociatedDataStorageParts().length);

				final Optional<BinaryEntity> referencedBrandStore = Arrays.stream(brand.get().getReferencedEntities())
					.filter(it -> Entities.STORE.equals(it.getType()))
					.findFirst();
				final Optional<BinaryEntity> referencedBrandStoreCategory = Arrays.stream(brand.get().getReferencedEntities())
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

	@DisplayName("Should return hierarchy parent id")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnDirectHierarchyParentId(Evita evita, Hierarchy categoryHierarchy) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final HierarchyItem theChild = categoryHierarchy.getAllChildItems(categoryHierarchy.getRootItems().get(0).getCode())
					.stream()
					.max(Comparator.comparingInt(HierarchyItem::getLevel))
					.orElseThrow();
				final int theChildPk = Integer.parseInt(theChild.getCode());
				final int theParentPk = Integer.parseInt(categoryHierarchy.getParentItem(theChild.getCode()).getCode());

				final EvitaResponse<SealedEntity> productByPk = session.querySealedEntity(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							entityPrimaryKeyInSet(theChildPk)
						),
						require(
							entityFetch(
								hierarchyContent()
							)
						)
					)
				);
				assertEquals(1, productByPk.getRecordData().size());
				assertEquals(1, productByPk.getTotalRecordCount());

				assertEquals(theParentPk, productByPk.getRecordData().get(0).getParentEntity().orElseThrow().getPrimaryKey());
				final EntityClassifierWithParent parentEntity = productByPk.getRecordData().get(0).getParentEntity().orElseThrow();
				assertInstanceOf(EntityReferenceWithParent.class, parentEntity);
				assertEquals(theParentPk, ((EntityReferenceWithParent) parentEntity).getPrimaryKey());
				return null;
			}
		);
	}

	@DisplayName("Should return hierarchy parent entity reference")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnDirectHierarchyParents(Evita evita, Hierarchy categoryHierarchy) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final HierarchyItem theChild = categoryHierarchy.getRootItems()
					.stream()
					.flatMap(it -> categoryHierarchy.getAllChildItems(it.getCode()).stream())
					.max(Comparator.comparingInt(HierarchyItem::getLevel))
					.orElseThrow();
				final int theChildPk = Integer.parseInt(theChild.getCode());
				final int theParentPk = Integer.parseInt(categoryHierarchy.getParentItem(theChild.getCode()).getCode());

				final EvitaResponse<SealedEntity> categoryByPk = session.querySealedEntity(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							entityPrimaryKeyInSet(theChildPk)
						),
						require(
							entityFetch(hierarchyContent())
						)
					)
				);
				assertEquals(1, categoryByPk.getRecordData().size());
				assertEquals(1, categoryByPk.getTotalRecordCount());

				assertEquals(theParentPk, categoryByPk.getRecordData().get(0).getParentEntity().orElseThrow().getPrimaryKey());
				assertEquals(createParentChain(categoryHierarchy, theChildPk, null, null), categoryByPk.getRecordData().get(0).getParentEntity().orElseThrow());
				return null;
			}
		);
	}

	@DisplayName("Should return hierarchy parent entity references stopping at level two")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnDirectHierarchyParentsUpToLevelTwo(Evita evita, Hierarchy categoryHierarchy) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final HierarchyItem theChild = categoryHierarchy.getRootItems()
					.stream()
					.flatMap(it -> categoryHierarchy.getAllChildItems(it.getCode()).stream())
					.max(Comparator.comparingInt(HierarchyItem::getLevel))
					.orElseThrow();
				final int theChildPk = Integer.parseInt(theChild.getCode());
				final int theParentPk = Integer.parseInt(categoryHierarchy.getParentItem(theChild.getCode()).getCode());

				final EvitaResponse<SealedEntity> categoryByPk = session.querySealedEntity(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							entityPrimaryKeyInSet(theChildPk)
						),
						require(
							entityFetch(hierarchyContent(stopAt(level(2))))
						)
					)
				);
				assertEquals(1, categoryByPk.getRecordData().size());
				assertEquals(1, categoryByPk.getTotalRecordCount());

				assertEquals(theParentPk, categoryByPk.getRecordData().get(0).getParentEntity().orElseThrow().getPrimaryKey());
				assertEquals(createParentChain(categoryHierarchy, theChildPk, 2, null), categoryByPk.getRecordData().get(0).getParentEntity().orElseThrow());
				return null;
			}
		);
	}

	@DisplayName("Should return hierarchy parent entity references with bodies stopping at level two")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnDirectHierarchyParentsWithBodiesUpToLevelTwo(Evita evita, Hierarchy categoryHierarchy) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final HierarchyItem theChild = categoryHierarchy.getRootItems()
					.stream()
					.flatMap(it -> categoryHierarchy.getAllChildItems(it.getCode()).stream())
					.max(Comparator.comparingInt(HierarchyItem::getLevel))
					.orElseThrow();
				final int theChildPk = Integer.parseInt(theChild.getCode());
				final int theParentPk = Integer.parseInt(categoryHierarchy.getParentItem(theChild.getCode()).getCode());

				final EvitaResponse<SealedEntity> categoryByPk = session.querySealedEntity(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							entityPrimaryKeyInSet(theChildPk)
						),
						require(
							entityFetch(
								hierarchyContent(
									stopAt(level(2)),
									entityFetch(
										attributeContentAll(),
										referenceContent(Entities.PRICE_LIST, entityFetchAll())
									)
								)
							)
						)
					)
				);
				assertEquals(1, categoryByPk.getRecordData().size());
				assertEquals(1, categoryByPk.getTotalRecordCount());

				final SealedEntity returnedEntity = categoryByPk.getRecordData().get(0);
				assertEquals(theParentPk, returnedEntity.getParentEntity().orElseThrow().getPrimaryKey());

				boolean atLeastOnPriceListFound = false;
				Optional<EntityClassifierWithParent> parentEntityRef = returnedEntity.getParentEntity();
				boolean atLeastOnePriceListBodyFound = false;
				while (parentEntityRef.isPresent()) {
					final EntityClassifierWithParent parentEntity = parentEntityRef.get();
					assertInstanceOf(SealedEntity.class, parentEntity);
					final Collection<ReferenceContract> references = ((SealedEntity) parentEntity).getReferences(Entities.PRICE_LIST);
					if (!references.isEmpty()) {
						atLeastOnPriceListFound = true;
						assertEquals(1, references.size());
						atLeastOnePriceListBodyFound = atLeastOnePriceListBodyFound || references.stream().anyMatch(it -> it.getReferencedEntity().isPresent());
						parentEntityRef = parentEntity.getParentEntity();
					}
				}
				assertTrue(atLeastOnPriceListFound, "At least one price list should be found in the hierarchy");
				assertTrue(atLeastOnePriceListBodyFound, "At least one price list body should be found in the hierarchy");

				return null;
			}
		);
	}

	@DisplayName("Should return hierarchy parent entity references stopping at distance one")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnDirectHierarchyParentsWithinDistanceOne(Evita evita, Hierarchy categoryHierarchy) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final HierarchyItem theChild = categoryHierarchy.getRootItems()
					.stream()
					.flatMap(it -> categoryHierarchy.getAllChildItems(it.getCode()).stream())
					.max(Comparator.comparingInt(HierarchyItem::getLevel))
					.orElseThrow();
				final int theChildPk = Integer.parseInt(theChild.getCode());
				final int theParentPk = Integer.parseInt(categoryHierarchy.getParentItem(theChild.getCode()).getCode());

				final EvitaResponse<SealedEntity> categoryByPk = session.querySealedEntity(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							entityPrimaryKeyInSet(theChildPk)
						),
						require(
							entityFetch(hierarchyContent(stopAt(distance(1))))
						)
					)
				);
				assertEquals(1, categoryByPk.getRecordData().size());
				assertEquals(1, categoryByPk.getTotalRecordCount());

				assertEquals(theParentPk, categoryByPk.getRecordData().get(0).getParentEntity().orElseThrow().getPrimaryKey());
				assertEquals(createParentChain(categoryHierarchy, theChildPk, null, 1), categoryByPk.getRecordData().get(0).getParentEntity().orElseThrow());
				return null;
			}
		);
	}

	@DisplayName("Should return hierarchy parent entity references stopping at node defined by attribute filter")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnDirectHierarchyParentsUntilNodeSpecifiedByAttributeFilter(Evita evita, Map<Integer, SealedEntity> originalCategories, Hierarchy categoryHierarchy) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final HierarchyItem theChild = categoryHierarchy.getRootItems()
					.stream()
					.flatMap(it -> categoryHierarchy.getAllChildItems(it.getCode()).stream())
					.max(Comparator.comparingInt(HierarchyItem::getLevel))
					.orElseThrow();
				final int theChildPk = Integer.parseInt(theChild.getCode());
				final int theParentPk = Integer.parseInt(categoryHierarchy.getParentItem(theChild.getCode()).getCode());
				final SealedEntity parentCategory = originalCategories.get(theParentPk);

				final EvitaResponse<SealedEntity> categoryByPk = session.querySealedEntity(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							entityPrimaryKeyInSet(theChildPk)
						),
						require(
							entityFetch(
								hierarchyContent(
									stopAt(
										node(
											filterBy(
												attributeEquals(
													ATTRIBUTE_CODE,
													parentCategory.getAttribute(ATTRIBUTE_CODE, String.class)
												)
											)
										)
									)
								)
							)
						)
					)
				);
				assertEquals(1, categoryByPk.getRecordData().size());
				assertEquals(1, categoryByPk.getTotalRecordCount());

				assertEquals(theParentPk, categoryByPk.getRecordData().get(0).getParentEntity().orElseThrow().getPrimaryKey());
				assertEquals(createParentChain(categoryHierarchy, theChildPk, null, 1), categoryByPk.getRecordData().get(0).getParentEntity().orElseThrow());
				return null;
			}
		);
	}

	@DisplayName("Should limit the scope of parent visibility")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldLimitTheScopeOfParentVisibility(Evita evita, Hierarchy categoryHierarchy, Map<Integer, SealedEntity> originalCategories) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final HierarchyItem theChild = categoryHierarchy.getRootItems()
					.stream()
					.flatMap(it -> categoryHierarchy.getAllChildItems(it.getCode()).stream())
					.max(Comparator.comparingInt(HierarchyItem::getLevel))
					.orElseThrow();
				final int theChildPk = Integer.parseInt(theChild.getCode());
				final int theParentPk = Integer.parseInt(categoryHierarchy.getParentItem(theChild.getCode()).getCode());
				assertTrue(theChild.getLevel() > 2);

				final EvitaResponse<SealedEntity> categoryByPk = session.querySealedEntity(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							entityPrimaryKeyInSet(theChildPk)
						),
						require(
							entityFetch(
								hierarchyContent(
									stopAt(distance(1))
								)
							)
						)
					)
				);
				assertEquals(1, categoryByPk.getRecordData().size());
				assertEquals(1, categoryByPk.getTotalRecordCount());

				final SealedEntity category = categoryByPk.getRecordData().get(0);
				assertTrue(category.getParentEntity().isPresent());
				assertFalse(category.getParentEntity().get().getParentEntity().isPresent());
				return null;
			}
		);
	}

	@DisplayName("Should limit the scope of rich parent visibility")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldLimitTheScopeOfRichParentVisibility(Evita evita, Hierarchy categoryHierarchy, Map<Integer, SealedEntity> originalCategories) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final HierarchyItem theChild = categoryHierarchy.getRootItems()
					.stream()
					.flatMap(it -> categoryHierarchy.getAllChildItems(it.getCode()).stream())
					.max(Comparator.comparingInt(HierarchyItem::getLevel))
					.orElseThrow();
				final int theChildPk = Integer.parseInt(theChild.getCode());
				assertTrue(theChild.getLevel() > 2);

				final EvitaResponse<SealedEntity> categoryByPk = session.querySealedEntity(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							entityPrimaryKeyInSet(theChildPk)
						),
						require(
							entityFetch(
								hierarchyContent(
									stopAt(distance(1)),
									entityFetchAll()
								)
							)
						)
					)
				);
				assertEquals(1, categoryByPk.getRecordData().size());
				assertEquals(1, categoryByPk.getTotalRecordCount());

				final SealedEntity category = categoryByPk.getRecordData().get(0);
				assertTrue(category.getParentEntity().isPresent());
				assertFalse(category.getParentEntity().get().getParentEntity().isPresent());
				return null;
			}
		);
	}

	@DisplayName("Should return hierarchy parent sealed entities")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnDirectHierarchyParentEntities(Evita evita, Hierarchy categoryHierarchy, Map<Integer, SealedEntity> originalCategories) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final HierarchyItem theChild = categoryHierarchy.getRootItems()
					.stream()
					.flatMap(it -> categoryHierarchy.getAllChildItems(it.getCode()).stream())
					.max(Comparator.comparingInt(HierarchyItem::getLevel))
					.orElseThrow();
				final int theChildPk = Integer.parseInt(theChild.getCode());
				final int theParentPk = Integer.parseInt(categoryHierarchy.getParentItem(theChild.getCode()).getCode());

				final EvitaResponse<SealedEntity> categoryByPk = session.querySealedEntity(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							entityPrimaryKeyInSet(theChildPk)
						),
						require(
							entityFetch(hierarchyContent(entityFetchAll()))
						)
					)
				);
				assertEquals(1, categoryByPk.getRecordData().size());
				assertEquals(1, categoryByPk.getTotalRecordCount());

				assertEquals(theParentPk, categoryByPk.getRecordData().get(0).getParentEntity().orElseThrow().getPrimaryKey());
				assertEquals(
					createParentEntityChain(categoryHierarchy, originalCategories, theChildPk, null, null),
					categoryByPk.getRecordData().get(0).getParentEntity().orElseThrow()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return hierarchy parent sealed entities stopping at level two")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnDirectHierarchyParentEntitiesUpToLevelTwo(Evita evita, Hierarchy categoryHierarchy, Map<Integer, SealedEntity> originalCategories) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final HierarchyItem theChild = categoryHierarchy.getRootItems()
					.stream()
					.flatMap(it -> categoryHierarchy.getAllChildItems(it.getCode()).stream())
					.max(Comparator.comparingInt(HierarchyItem::getLevel))
					.orElseThrow();
				final int theChildPk = Integer.parseInt(theChild.getCode());
				final int theParentPk = Integer.parseInt(categoryHierarchy.getParentItem(theChild.getCode()).getCode());

				final EvitaResponse<SealedEntity> categoryByPk = session.querySealedEntity(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							entityPrimaryKeyInSet(theChildPk)
						),
						require(
							entityFetch(hierarchyContent(stopAt(level(2)), entityFetchAll()))
						)
					)
				);
				assertEquals(1, categoryByPk.getRecordData().size());
				assertEquals(1, categoryByPk.getTotalRecordCount());

				assertEquals(theParentPk, categoryByPk.getRecordData().get(0).getParentEntity().orElseThrow().getPrimaryKey());
				assertEquals(
					createParentEntityChain(categoryHierarchy, originalCategories, theChildPk, 2, null),
					categoryByPk.getRecordData().get(0).getParentEntity().orElseThrow()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return hierarchy parent sealed entities stopping at distance one")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnDirectHierarchyParentEntitiesWithinDistanceOne(Evita evita, Hierarchy categoryHierarchy, Map<Integer, SealedEntity> originalCategories) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final HierarchyItem theChild = categoryHierarchy.getRootItems()
					.stream()
					.flatMap(it -> categoryHierarchy.getAllChildItems(it.getCode()).stream())
					.max(Comparator.comparingInt(HierarchyItem::getLevel))
					.orElseThrow();
				final int theChildPk = Integer.parseInt(theChild.getCode());
				final int theParentPk = Integer.parseInt(categoryHierarchy.getParentItem(theChild.getCode()).getCode());

				final EvitaResponse<SealedEntity> categoryByPk = session.querySealedEntity(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							entityPrimaryKeyInSet(theChildPk)
						),
						require(
							entityFetch(hierarchyContent(stopAt(distance(1)), entityFetchAll()))
						)
					)
				);
				assertEquals(1, categoryByPk.getRecordData().size());
				assertEquals(1, categoryByPk.getTotalRecordCount());

				assertEquals(theParentPk, categoryByPk.getRecordData().get(0).getParentEntity().orElseThrow().getPrimaryKey());
				assertEquals(
					createParentEntityChain(categoryHierarchy, originalCategories, theChildPk, null, 1),
					categoryByPk.getRecordData().get(0).getParentEntity().orElseThrow()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return hierarchy parent sealed entities stopping at node defined by attribute filter")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnDirectHierarchyParentEntitiesUntilNodeSpecifiedByAttributeFilter(Evita evita, Map<Integer, SealedEntity> originalCategories, Hierarchy categoryHierarchy) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final HierarchyItem theChild = categoryHierarchy.getRootItems()
					.stream()
					.flatMap(it -> categoryHierarchy.getAllChildItems(it.getCode()).stream())
					.max(Comparator.comparingInt(HierarchyItem::getLevel))
					.orElseThrow();
				final int theChildPk = Integer.parseInt(theChild.getCode());
				final int theParentPk = Integer.parseInt(categoryHierarchy.getParentItem(theChild.getCode()).getCode());
				final SealedEntity parentCategory = originalCategories.get(theParentPk);

				final EvitaResponse<SealedEntity> categoryByPk = session.querySealedEntity(
					query(
						collection(Entities.CATEGORY),
						filterBy(
							entityPrimaryKeyInSet(theChildPk)
						),
						require(
							entityFetch(
								hierarchyContent(
									stopAt(
										node(
											filterBy(
												attributeEquals(
													ATTRIBUTE_CODE,
													parentCategory.getAttribute(ATTRIBUTE_CODE, String.class)
												)
											)
										)
									),
									entityFetchAll()
								)
							)
						)
					)
				);
				assertEquals(1, categoryByPk.getRecordData().size());
				assertEquals(1, categoryByPk.getTotalRecordCount());

				assertEquals(theParentPk, categoryByPk.getRecordData().get(0).getParentEntity().orElseThrow().getPrimaryKey());
				assertEquals(
					createParentEntityChain(categoryHierarchy, originalCategories, theChildPk, null, 1),
					categoryByPk.getRecordData().get(0).getParentEntity().orElseThrow()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return product hierarchy parent entity reference")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnProductHierarchyParents(Evita evita, Hierarchy categoryHierarchy) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final HierarchyItem theChild = categoryHierarchy.getRootItems()
					.stream()
					.flatMap(it -> categoryHierarchy.getAllChildItems(it.getCode()).stream())
					.max(Comparator.comparingInt(HierarchyItem::getLevel))
					.orElseThrow();
				final int theChildPk = Integer.parseInt(theChild.getCode());
				final int theParentPk = Integer.parseInt(categoryHierarchy.getParentItem(theChild.getCode()).getCode());

				final EvitaResponse<SealedEntity> products = session.querySealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							hierarchyWithin(Entities.CATEGORY, entityPrimaryKeyInSet(theChildPk))
						),
						require(
							entityFetch(
								referenceContent(
									Entities.CATEGORY,
									entityFetch(hierarchyContent())
								)
							),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					)
				);
				assertFalse(products.getRecordData().isEmpty());
				assertTrue(products.getTotalRecordCount() > 0);

				final ReferenceContract categoryReference = products.getRecordData().get(0).getReference(Entities.CATEGORY, theChildPk).orElseThrow();
				final SealedEntity referencedCategory = categoryReference.getReferencedEntity().orElseThrow();
				assertEquals(theParentPk, referencedCategory.getParentEntity().orElseThrow().getPrimaryKey());
				assertEquals(
					createParentChain(categoryHierarchy, theChildPk, null, null),
					referencedCategory.getParentEntity().orElseThrow()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return product hierarchy parent entity references stopping at level two")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnProductHierarchyParentsUpToLevelTwo(Evita evita, Hierarchy categoryHierarchy) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final HierarchyItem theChild = categoryHierarchy.getRootItems()
					.stream()
					.flatMap(it -> categoryHierarchy.getAllChildItems(it.getCode()).stream())
					.max(Comparator.comparingInt(HierarchyItem::getLevel))
					.orElseThrow();
				final int theChildPk = Integer.parseInt(theChild.getCode());
				final int theParentPk = Integer.parseInt(categoryHierarchy.getParentItem(theChild.getCode()).getCode());

				final EvitaResponse<SealedEntity> products = session.querySealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							hierarchyWithin(Entities.CATEGORY, entityPrimaryKeyInSet(theChildPk))
						),
						require(
							entityFetch(
								referenceContent(
									Entities.CATEGORY,
									entityFetch(hierarchyContent(stopAt(level(2))))
								)
							)
						)
					)
				);
				assertFalse(products.getRecordData().isEmpty());
				assertTrue(products.getTotalRecordCount() > 0);

				final ReferenceContract categoryReference = products.getRecordData().get(0).getReference(Entities.CATEGORY, theChildPk).orElseThrow();
				final SealedEntity referencedCategory = categoryReference.getReferencedEntity().orElseThrow();
				assertEquals(theParentPk, referencedCategory.getParentEntity().orElseThrow().getPrimaryKey());
				assertEquals(
					createParentChain(categoryHierarchy, theChildPk, 2, null),
					referencedCategory.getParentEntity().orElseThrow()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return product hierarchy parent entity references stopping at distance one")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnProductHierarchyParentsWithinDistanceOne(Evita evita, Hierarchy categoryHierarchy) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final HierarchyItem theChild = categoryHierarchy.getRootItems()
					.stream()
					.flatMap(it -> categoryHierarchy.getAllChildItems(it.getCode()).stream())
					.max(Comparator.comparingInt(HierarchyItem::getLevel))
					.orElseThrow();
				final int theChildPk = Integer.parseInt(theChild.getCode());
				final int theParentPk = Integer.parseInt(categoryHierarchy.getParentItem(theChild.getCode()).getCode());

				final EvitaResponse<SealedEntity> products = session.querySealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							hierarchyWithin(Entities.CATEGORY, entityPrimaryKeyInSet(theChildPk))
						),
						require(
							entityFetch(
								referenceContent(
									Entities.CATEGORY,
									entityFetch(hierarchyContent(stopAt(distance(1))))
								)
							)
						)
					)
				);
				assertFalse(products.getRecordData().isEmpty());
				assertTrue(products.getTotalRecordCount() > 0);

				final ReferenceContract categoryReference = products.getRecordData().get(0).getReference(Entities.CATEGORY, theChildPk).orElseThrow();
				final SealedEntity referencedCategory = categoryReference.getReferencedEntity().orElseThrow();
				assertEquals(theParentPk, referencedCategory.getParentEntity().orElseThrow().getPrimaryKey());
				assertEquals(
					createParentChain(categoryHierarchy, theChildPk, null, 1),
					referencedCategory.getParentEntity().orElseThrow()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return product hierarchy parent entity references stopping at node defined by attribute filter")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnProductHierarchyParentsUntilNodeSpecifiedByAttributeFilter(Evita evita, Map<Integer, SealedEntity> originalCategories, Hierarchy categoryHierarchy) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final HierarchyItem theChild = categoryHierarchy.getRootItems()
					.stream()
					.flatMap(it -> categoryHierarchy.getAllChildItems(it.getCode()).stream())
					.max(Comparator.comparingInt(HierarchyItem::getLevel))
					.orElseThrow();
				final int theChildPk = Integer.parseInt(theChild.getCode());
				final int theParentPk = Integer.parseInt(categoryHierarchy.getParentItem(theChild.getCode()).getCode());
				final SealedEntity parentCategory = originalCategories.get(theParentPk);

				final EvitaResponse<SealedEntity> products = session.querySealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							hierarchyWithin(Entities.CATEGORY, entityPrimaryKeyInSet(theChildPk))
						),
						require(
							entityFetch(
								referenceContent(
									Entities.CATEGORY,
									entityFetch(
										hierarchyContent(
											stopAt(
												node(
													filterBy(
														attributeEquals(
															ATTRIBUTE_CODE,
															parentCategory.getAttribute(ATTRIBUTE_CODE, String.class)
														)
													)
												)
											)
										)
									)
								)
							)
						)
					)
				);
				assertFalse(products.getRecordData().isEmpty());
				assertTrue(products.getTotalRecordCount() > 0);

				final ReferenceContract categoryReference = products.getRecordData().get(0).getReference(Entities.CATEGORY, theChildPk).orElseThrow();
				final SealedEntity referencedCategory = categoryReference.getReferencedEntity().orElseThrow();
				assertEquals(theParentPk, referencedCategory.getParentEntity().orElseThrow().getPrimaryKey());
				assertEquals(
					createParentChain(categoryHierarchy, theChildPk, theChild.getLevel() - 1, null),
					referencedCategory.getParentEntity().orElseThrow()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return product hierarchy parent sealed entities")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnProductHierarchyParentEntities(Evita evita, Hierarchy categoryHierarchy, Map<Integer, SealedEntity> originalCategories) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final HierarchyItem theChild = categoryHierarchy.getRootItems()
					.stream()
					.flatMap(it -> categoryHierarchy.getAllChildItems(it.getCode()).stream())
					.max(Comparator.comparingInt(HierarchyItem::getLevel))
					.orElseThrow();
				final int theChildPk = Integer.parseInt(theChild.getCode());
				final int theParentPk = Integer.parseInt(categoryHierarchy.getParentItem(theChild.getCode()).getCode());

				final EvitaResponse<SealedEntity> products = session.querySealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							hierarchyWithin(Entities.CATEGORY, entityPrimaryKeyInSet(theChildPk))
						),
						require(
							entityFetch(referenceContent(Entities.CATEGORY, entityFetch(hierarchyContent(entityFetchAll()))))
						)
					)
				);
				assertFalse(products.getRecordData().isEmpty());
				assertTrue(products.getTotalRecordCount() > 0);

				final ReferenceContract categoryReference = products.getRecordData().get(0).getReference(Entities.CATEGORY, theChildPk).orElseThrow();
				final SealedEntity referencedCategory = categoryReference.getReferencedEntity().orElseThrow();

				assertEquals(theParentPk, referencedCategory.getParentEntity().orElseThrow().getPrimaryKey());
				assertEquals(
					createParentEntityChain(categoryHierarchy, originalCategories, theChildPk, null, null),
					referencedCategory.getParentEntity().orElseThrow()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return product hierarchy parent sealed entities stopping at level two")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnProductHierarchyParentEntitiesUpToLevelTwo(Evita evita, Hierarchy categoryHierarchy, Map<Integer, SealedEntity> originalCategories) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final HierarchyItem theChild = categoryHierarchy.getRootItems()
					.stream()
					.flatMap(it -> categoryHierarchy.getAllChildItems(it.getCode()).stream())
					.max(Comparator.comparingInt(HierarchyItem::getLevel))
					.orElseThrow();
				final int theChildPk = Integer.parseInt(theChild.getCode());
				final int theParentPk = Integer.parseInt(categoryHierarchy.getParentItem(theChild.getCode()).getCode());

				final EvitaResponse<SealedEntity> products = session.querySealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							hierarchyWithin(Entities.CATEGORY, entityPrimaryKeyInSet(theChildPk))
						),
						require(
							entityFetch(
								referenceContent(
									Entities.CATEGORY,
									entityFetch(hierarchyContent(stopAt(level(2)), entityFetchAll()))
								)
							)
						)
					)
				);
				assertFalse(products.getRecordData().isEmpty());
				assertTrue(products.getTotalRecordCount() > 0);

				final ReferenceContract categoryReference = products.getRecordData().get(0).getReference(Entities.CATEGORY, theChildPk).orElseThrow();
				final SealedEntity referencedCategory = categoryReference.getReferencedEntity().orElseThrow();

				assertEquals(theParentPk, referencedCategory.getParentEntity().orElseThrow().getPrimaryKey());
				assertEquals(
					createParentEntityChain(categoryHierarchy, originalCategories, theChildPk, 2, null),
					referencedCategory.getParentEntity().orElseThrow()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return product hierarchy parent sealed entities stopping at level two")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnProductHierarchyParentEntitiesWithBodiesUpToLevelTwo(Evita evita, Hierarchy categoryHierarchy, Map<Integer, SealedEntity> originalCategories) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final HierarchyItem theChild = categoryHierarchy.getRootItems()
					.stream()
					.flatMap(it -> categoryHierarchy.getAllChildItems(it.getCode()).stream())
					.max(Comparator.comparingInt(HierarchyItem::getLevel))
					.orElseThrow();
				final int theChildPk = Integer.parseInt(theChild.getCode());
				final int theParentPk = Integer.parseInt(categoryHierarchy.getParentItem(theChild.getCode()).getCode());

				final EvitaResponse<SealedEntity> products = session.querySealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							hierarchyWithin(Entities.CATEGORY, entityPrimaryKeyInSet(theChildPk))
						),
						require(
							entityFetch(
								referenceContent(
									Entities.CATEGORY,
									entityFetch(
										hierarchyContent(
											stopAt(level(2)),
											entityFetch(
												attributeContentAll(),
												referenceContent(Entities.PRICE_LIST, entityFetchAll())
											)
										)
									)
								)
							)
						)
					)
				);
				assertFalse(products.getRecordData().isEmpty());
				assertTrue(products.getTotalRecordCount() > 0);

				final ReferenceContract categoryReference = products.getRecordData().get(0).getReference(Entities.CATEGORY, theChildPk).orElseThrow();
				final SealedEntity referencedCategory = categoryReference.getReferencedEntity().orElseThrow();
				assertEquals(theParentPk, referencedCategory.getParentEntity().orElseThrow().getPrimaryKey());

				boolean atLeastOnePriceListFound = false;
				boolean atLeastOnePriceListBodyFound = false;
				for (SealedEntity returnedEntity : products.getRecordData()) {
					final Collection<ReferenceContract> referencedCategories = returnedEntity.getReferences(Entities.CATEGORY);
					for (ReferenceContract category : referencedCategories) {
						final Optional<SealedEntity> referencedEntity = category.getReferencedEntity();
						if (referencedEntity.isPresent()) {
							final int[] parents = categoryHierarchy.getParentItems(String.valueOf(referencedEntity.get().getPrimaryKey()))
								.stream()
								.map(HierarchyItem::getCode)
								.mapToInt(Integer::parseInt)
								.toArray();
							Optional<EntityClassifierWithParent> parentEntityRef = referencedEntity.get().getParentEntity();
							int level = parents.length;
							while (parentEntityRef.isPresent()) {
								final EntityClassifierWithParent parentEntity = parentEntityRef.get();
								if (level >= 2) {
									assertInstanceOf(SealedEntity.class, parentEntity);
									final Collection<ReferenceContract> references = ((SealedEntity) parentEntity).getReferences(Entities.PRICE_LIST);
									if (!references.isEmpty()) {
										atLeastOnePriceListFound = true;
										assertEquals(1, references.size());
										atLeastOnePriceListBodyFound = atLeastOnePriceListBodyFound || references.stream().anyMatch(it -> it.getReferencedEntity().isPresent());
									}
								} else {
									assertInstanceOf(EntityReferenceWithParent.class, parentEntity);
								}
								parentEntityRef = parentEntity.getParentEntity();
								level--;
							}
						}

					}
				}

				assertTrue(atLeastOnePriceListFound, "At least one price list should be found in the hierarchy");
				assertTrue(atLeastOnePriceListBodyFound, "At least one price list body should be found in the hierarchy");
				return null;
			}
		);
	}

	@DisplayName("Should return product hierarchy parent sealed entities stopping at distance one")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnProductHierarchyParentEntitiesWithinDistanceOne(Evita evita, Hierarchy categoryHierarchy, Map<Integer, SealedEntity> originalCategories) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final HierarchyItem theChild = categoryHierarchy.getRootItems()
					.stream()
					.flatMap(it -> categoryHierarchy.getAllChildItems(it.getCode()).stream())
					.max(Comparator.comparingInt(HierarchyItem::getLevel))
					.orElseThrow();
				final int theChildPk = Integer.parseInt(theChild.getCode());
				final int theParentPk = Integer.parseInt(categoryHierarchy.getParentItem(theChild.getCode()).getCode());

				final EvitaResponse<SealedEntity> products = session.querySealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							hierarchyWithin(Entities.CATEGORY, entityPrimaryKeyInSet(theChildPk))
						),
						require(
							entityFetch(
								referenceContent(
									Entities.CATEGORY,
									entityFetch(hierarchyContent(stopAt(distance(1)), entityFetchAll()))
								)
							)
						)
					)
				);
				assertFalse(products.getRecordData().isEmpty());
				assertTrue(products.getTotalRecordCount() > 0);

				final ReferenceContract categoryReference = products.getRecordData().get(0).getReference(Entities.CATEGORY, theChildPk).orElseThrow();
				final SealedEntity referencedCategory = categoryReference.getReferencedEntity().orElseThrow();

				assertEquals(theParentPk, referencedCategory.getParentEntity().orElseThrow().getPrimaryKey());
				assertEquals(
					createParentEntityChain(categoryHierarchy, originalCategories, theChildPk, null, 1),
					referencedCategory.getParentEntity().orElseThrow()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return product hierarchy parent sealed entities stopping at node defined by attribute filter")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnProductHierarchyParentEntitiesUntilNodeSpecifiedByAttributeFilter(Evita evita, Map<Integer, SealedEntity> originalCategories, Hierarchy categoryHierarchy) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final HierarchyItem theChild = categoryHierarchy.getRootItems()
					.stream()
					.flatMap(it -> categoryHierarchy.getAllChildItems(it.getCode()).stream())
					.max(Comparator.comparingInt(HierarchyItem::getLevel))
					.orElseThrow();
				final int theChildPk = Integer.parseInt(theChild.getCode());
				final int theParentPk = Integer.parseInt(categoryHierarchy.getParentItem(theChild.getCode()).getCode());
				final SealedEntity parentCategory = originalCategories.get(theParentPk);

				final EvitaResponse<SealedEntity> products = session.querySealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							hierarchyWithin(Entities.CATEGORY, entityPrimaryKeyInSet(theChildPk))
						),
						require(
							entityFetch(
								referenceContent(
									Entities.CATEGORY,
									entityFetch(
										hierarchyContent(
											stopAt(
												node(
													filterBy(
														attributeEquals(
															ATTRIBUTE_CODE,
															parentCategory.getAttribute(ATTRIBUTE_CODE, String.class)
														)
													)
												)
											),
											entityFetchAll()
										)
									)
								)
							)
						)
					)
				);
				assertFalse(products.getRecordData().isEmpty());
				assertTrue(products.getTotalRecordCount() > 0);

				final ReferenceContract categoryReference = products.getRecordData().get(0).getReference(Entities.CATEGORY, theChildPk).orElseThrow();
				final SealedEntity referencedCategory = categoryReference.getReferencedEntity().orElseThrow();

				assertEquals(theParentPk, referencedCategory.getParentEntity().orElseThrow().getPrimaryKey());
				assertEquals(
					createParentEntityChain(categoryHierarchy, originalCategories, theChildPk, null, 1),
					referencedCategory.getParentEntity().orElseThrow()
				);
				return null;
			}
		);
	}

	@DisplayName("Should return products sorted by primary key in descending order")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnProductsSortedByPrimaryKeyInDescendingOrder(Evita evita) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Integer[] exactOrder = {12, 1, 18, 23, 5};
				final EvitaResponse<SealedEntity> products = session.querySealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(exactOrder)
						),
						orderBy(
							entityPrimaryKeyNatural(OrderDirection.DESC)
						)
					)
				);
				assertEquals(5, products.getRecordData().size());
				assertEquals(5, products.getTotalRecordCount());

				Arrays.sort(exactOrder, (o1, o2) -> Integer.compare(o2, o1));
				assertArrayEquals(
					exactOrder,
					products.getRecordData().stream()
						.map(EntityContract::getPrimaryKey)
						.toArray(Integer[]::new)
				);
				return null;
			}
		);
	}

	@DisplayName("Should return products sorted by exact order in the filter constraint")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnProductSortedByExactOrderInFilter(Evita evita) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Integer[] exactOrder = {12, 1, 18, 23, 5};
				final EvitaResponse<SealedEntity> products = session.querySealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(exactOrder)
						),
						orderBy(
							entityPrimaryKeyInFilter()
						)
					)
				);
				assertEquals(5, products.getRecordData().size());
				assertEquals(5, products.getTotalRecordCount());

				assertArrayEquals(
					exactOrder,
					products.getRecordData().stream()
						.map(EntityContract::getPrimaryKey)
						.toArray(Integer[]::new)
				);
				return null;
			}
		);
	}

	@DisplayName("Should return products sorted by exact order")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnProductSortedByExactOrder(Evita evita) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Integer[] exactOrder = {12, 1, 18, 23, 5};
				final EvitaResponse<SealedEntity> products = session.querySealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(Arrays.stream(exactOrder).sorted().toArray(Integer[]::new))
						),
						orderBy(
							entityPrimaryKeyExact(exactOrder)
						)
					)
				);
				assertEquals(5, products.getRecordData().size());
				assertEquals(5, products.getTotalRecordCount());

				assertArrayEquals(
					exactOrder,
					products.getRecordData().stream()
						.map(EntityContract::getPrimaryKey)
						.toArray(Integer[]::new)
				);
				return null;
			}
		);
	}

	@DisplayName("Should return products sorted by exact order with duplicate keys")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnProductSortedByExactOrderWithDuplicateKeys(Evita evita) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Integer[] exactOrder = {12, 1};
				final Integer[] duplicatedExactOrder = {12, 12, 1};
				final EvitaResponse<SealedEntity> products = session.querySealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(Arrays.stream(exactOrder).sorted().toArray(Integer[]::new))
						),
						orderBy(
							entityPrimaryKeyExact(duplicatedExactOrder)
						)
					)
				);
				assertEquals(2, products.getRecordData().size());
				assertEquals(2, products.getTotalRecordCount());

				assertArrayEquals(
					exactOrder,
					products.getRecordData().stream()
						.map(EntityContract::getPrimaryKey)
						.toArray(Integer[]::new)
				);
				return null;
			}
		);
	}

	@DisplayName("Should return products sorted by exact order appending the rest")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnProductSortedByExactOrderAppendingTheRest(Evita evita, List<SealedEntity> originalProducts) {
		final Integer[] productsStartingWithA = originalProducts.stream()
			.filter(it -> it.getAttribute(ATTRIBUTE_CODE, String.class).startsWith("A"))
			.map(EntityContract::getPrimaryKey)
			.toArray(Integer[]::new);
		Assert.isTrue(productsStartingWithA.length >= 5, "Not enough products starting with A found");

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Integer[] exactOrder = Arrays.copyOfRange(productsStartingWithA, 0, (int) (productsStartingWithA.length * 0.5));
				final Integer[] theRest = Arrays.copyOfRange(productsStartingWithA, (int) (productsStartingWithA.length * 0.5), productsStartingWithA.length);
				ArrayUtils.reverse(exactOrder);
				final EvitaResponse<SealedEntity> products = session.querySealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							attributeStartsWith(ATTRIBUTE_CODE, "A")
						),
						orderBy(
							entityPrimaryKeyExact(exactOrder)
						),
						require(
							page(1, productsStartingWithA.length)
						)
					)
				);
				assertEquals(productsStartingWithA.length, products.getRecordData().size());
				assertEquals(productsStartingWithA.length, products.getTotalRecordCount());

				assertArrayEquals(
					ArrayUtils.mergeArrays(
						exactOrder, theRest
					),
					products.getRecordData().stream()
						.map(EntityContract::getPrimaryKey)
						.toArray(Integer[]::new)
				);
				return null;
			}
		);
	}

	@DisplayName("Should throw exception when accessing non-existing attributes")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldThrowExceptionWhenAccessingNonExistingAttributes(Evita evita) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final SealedEntity productByPk = session.queryOneSealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(2)
						),
						require(entityFetchAll())
					)
				).orElseThrow();

				assertThrows(
					AttributeNotFoundException.class,
					() -> productByPk.getAttribute("unknown")
				);

				assertThrows(
					AttributeNotFoundException.class,
					() -> productByPk.getAttribute("unknown", CZECH_LOCALE)
				);

				assertThrows(
					AttributeNotFoundException.class,
					() -> productByPk.getAttributeValues("unknown")
				);
				return null;
			}
		);
	}

	@DisplayName("Should throw exception when accessing explicitly specified localized attributes without specifying locale")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldThrowExceptionWhenAccessingExplicitlySpecifiedLocalizedAttributesWithoutSpecifyingLocale(Evita evita) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				assertThrows(
					EntityLocaleMissingException.class,
					() -> session.queryOneSealedEntity(
						query(
							collection(Entities.PRODUCT),
							filterBy(
								entityPrimaryKeyInSet(2)
							),
							require(
								entityFetch(
									attributeContent(ATTRIBUTE_NAME, ATTRIBUTE_URL)
								)
							)
						)
					)
				);
				return null;
			}
		);
	}

	@DisplayName("Should throw exception when accessing explicitly specified localized associated data without specifying locale")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldThrowExceptionWhenAccessingExplicitlySpecifiedLocalizedAssociatedDataWithoutSpecifyingLocale(Evita evita) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				assertThrows(
					EntityLocaleMissingException.class,
					() -> session.queryOneSealedEntity(
						query(
							collection(Entities.PRODUCT),
							filterBy(
								entityPrimaryKeyInSet(2)
							),
							require(
								entityFetch(
									associatedDataContent(ASSOCIATED_DATA_LABELS)
								)
							)
						)
					)
				);
				return null;
			}
		);
	}

	@DisplayName("Should throw exception when accessing non-fetched attributes")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldThrowExceptionWhenAccessingNonFetchedAttributes(Evita evita) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final SealedEntity productByPk = session.queryOneSealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(2)
						)
					)
				).orElseThrow();

				assertThrows(
					ContextMissingException.class,
					() -> productByPk.getAttribute(ATTRIBUTE_CODE, String.class)
				);
				assertThrows(
					ContextMissingException.class,
					() -> productByPk.getAttribute(ATTRIBUTE_NAME, String.class)
				);
				assertThrows(
					ContextMissingException.class,
					productByPk::getAttributeValues
				);
				return null;
			}
		);
	}

	@DisplayName("Should throw exception when accessing attributes in different language than fetched")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldThrowExceptionWhenAccessingAttributesFetchedInAnotherLocale(Evita evita, List<SealedEntity> originalProducts) {
		final SealedEntity testedProduct = originalProducts
			.stream()
			.filter(it -> it.getAttribute(ATTRIBUTE_NAME, CZECH_LOCALE, String.class) != null)
			.findFirst()
			.orElseThrow();

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final SealedEntity productByPk = session.queryOneSealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(testedProduct.getPrimaryKey()),
							entityLocaleEquals(CZECH_LOCALE)
						),
						require(
							entityFetch(
								attributeContent(ATTRIBUTE_NAME)
							)
						)
					)
				).orElseThrow();

				assertNotNull(productByPk.getAttribute(ATTRIBUTE_NAME, CZECH_LOCALE, String.class));
				assertThrows(
					ContextMissingException.class,
					() -> productByPk.getAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, String.class)
				);
				return null;
			}
		);
	}

	@DisplayName("Should throw exception when accessing non-existing associated data")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldThrowExceptionWhenAccessingNonExistingAssociatedData(Evita evita) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final SealedEntity productByPk = session.queryOneSealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(2)
						),
						require(entityFetchAll())
					)
				).orElseThrow();

				assertThrows(
					AssociatedDataNotFoundException.class,
					() -> productByPk.getAssociatedData("unknown")
				);
				assertThrows(
					AssociatedDataNotFoundException.class,
					() -> productByPk.getAssociatedData("unknown", CZECH_LOCALE)
				);

				assertThrows(
					AssociatedDataNotFoundException.class,
					() -> productByPk.getAssociatedDataValues("unknown")
				);
				return null;
			}
		);
	}

	@DisplayName("Should throw exception when accessing non-fetched associated data")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldThrowExceptionWhenAccessingNonFetchedAssociatedData(Evita evita) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final SealedEntity productByPk = session.queryOneSealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(2)
						)
					)
				).orElseThrow();

				assertThrows(
					ContextMissingException.class,
					() -> productByPk.getAssociatedData(ASSOCIATED_DATA_LABELS)
				);
				assertThrows(
					ContextMissingException.class,
					() -> productByPk.getAssociatedData(ASSOCIATED_DATA_LABELS)
				);
				return null;
			}
		);
	}

	@DisplayName("Should throw exception when accessing associated data in different language than fetched")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldThrowExceptionWhenAccessingAssociatedDataFetchedInAnotherLocale(Evita evita, List<SealedEntity> originalProducts) {
		final SealedEntity testedProduct = originalProducts
			.stream()
			.filter(it -> it.getAttribute(ATTRIBUTE_NAME, CZECH_LOCALE, String.class) != null)
			.findFirst()
			.orElseThrow();

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final SealedEntity productByPk = session.queryOneSealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(testedProduct.getPrimaryKey()),
							entityLocaleEquals(CZECH_LOCALE)
						),
						require(
							entityFetch(
								attributeContent(ATTRIBUTE_NAME)
							)
						)
					)
				).orElseThrow();

				assertNotNull(productByPk.getAttribute(ATTRIBUTE_NAME, CZECH_LOCALE, String.class));
				assertThrows(
					ContextMissingException.class,
					() -> productByPk.getAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, String.class)
				);
				return null;
			}
		);
	}

	@DisplayName("Should throw exception when accessing non-fetched prices")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldThrowExceptionWhenAccessingNonFetchedPrices(Evita evita) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final SealedEntity productByPk = session.queryOneSealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(2)
						)
					)
				).orElseThrow();

				assertThrows(
					ContextMissingException.class,
					productByPk::getPrices
				);
				return null;
			}
		);
	}

	@DisplayName("Should throw exception when accessing non-fetched and non-filtered prices")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldThrowExceptionWhenAccessingNonFetchedPricesAndNotFilteredPrices(Evita evita, List<SealedEntity> originalProducts) {
		final SealedEntity exampleProduct = originalProducts.stream()
			.filter(
				it -> !it.getPrices(CURRENCY_USD, PRICE_LIST_BASIC).isEmpty() &&
					!it.getPrices(CURRENCY_USD, PRICE_LIST_REFERENCE).isEmpty() &&
					!it.getPrices(CURRENCY_USD, PRICE_LIST_VIP).isEmpty()
			)
			.findFirst()
			.orElseThrow();
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final SealedEntity productByPk = session.queryOneSealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(exampleProduct.getPrimaryKey()),
							priceInPriceLists(PRICE_LIST_BASIC),
							priceInCurrency(CURRENCY_USD)
						),
						require(
							entityFetch(
								priceContentRespectingFilter(PRICE_LIST_REFERENCE)
							)
						)
					)
				).orElseThrow();

				assertNotNull(productByPk.getPriceForSale());
				assertFalse(productByPk.getPrices(PRICE_LIST_BASIC).isEmpty());
				assertFalse(productByPk.getPrices(PRICE_LIST_REFERENCE).isEmpty());
				assertThrows(ContextMissingException.class, () -> productByPk.getPrices(PRICE_LIST_B2B));
				assertFalse(productByPk.getPrices().isEmpty());
				return null;
			}
		);
	}

	@DisplayName("Should throw exception when accessing prices on entity without prices")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldThrowExceptionWhenAccessingPricesOnEntityWithoutPrices(Evita evita) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final SealedEntity categoryByPk = session.getEntity(
					Entities.CATEGORY,
					1,
					entityFetchAllContent()
				).orElseThrow();

				assertThrows(EntityHasNoPricesException.class, categoryByPk::getPrices);
				assertThrows(EntityHasNoPricesException.class, () -> categoryByPk.getPrices(PRICE_LIST_BASIC));
				return null;
			}
		);
	}

	@DisplayName("Should throw exception when accessing parent on entity not allowing hierarchy")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldThrowExceptionWhenAccessingParentOnEntityWithoutAllowedHierarchy(Evita evita) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final SealedEntity productByPk = session.getEntity(
					Entities.PRODUCT, 1, entityFetchAllContent()
				).orElseThrow();

				assertThrows(
					EntityIsNotHierarchicalException.class,
					productByPk::getParentEntity
				);
				return null;
			}
		);
	}

	@DisplayName("Should throw exception when accessing non-fetched parent")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldThrowExceptionWhenAccessingNonFetchedParent(Evita evita) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final SealedEntity productByPk = session.queryOneSealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(2)
						)
					)
				).orElseThrow();

				assertThrows(
					ContextMissingException.class,
					productByPk::getParentEntity
				);
				return null;
			}
		);
	}

	@DisplayName("Should throw exception when accessing reference undefined in the schema")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldThrowExceptionWhenAccessingUndefinedReference(Evita evita) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final SealedEntity productByPk = session.queryOneSealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(2)
						),
						require(
							entityFetchAll()
						)
					)
				).orElseThrow();

				assertThrows(
					ReferenceNotFoundException.class,
					() -> productByPk.getReferences("undefined")
				);
				return null;
			}
		);
	}

	@DisplayName("Should throw exception when accessing reference without referenceContent")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldThrowExceptionWhenAccessingReferenceOnPlainEntity(Evita evita) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final SealedEntity productByPk = session.queryOneSealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(2)
						),
						require(
							entityFetch()
						)
					)
				).orElseThrow();

				assertThrows(
					ContextMissingException.class,
					productByPk::getReferences
				);
				assertThrows(
					ContextMissingException.class,
					() -> productByPk.getReferences(Entities.CATEGORY)
				);
				return null;
			}
		);
	}

	@DisplayName("Should throw exception when accessing non-fetched reference")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldThrowExceptionWhenAccessingNonFetchedReference(Evita evita) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final SealedEntity productByPk = session.queryOneSealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(2)
						),
						require(
							entityFetch(
								referenceContent(
									Entities.BRAND,
									Entities.PARAMETER
								)
							)
						)
					)
				).orElseThrow();

				assertFalse(productByPk.getReferences().isEmpty());
				assertThrows(
					ContextMissingException.class,
					() -> productByPk.getReferences(Entities.CATEGORY)
				);
				return null;
			}
		);
	}

	@DisplayName("Should provide paginated access to references")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnPaginatedReferences(Evita evita, List<SealedEntity> originalProducts) {
		final SealedEntity productWithMaxReferences = originalProducts
			.stream()
			.max(Comparator.comparingInt(o -> o.getReferences(Entities.BRAND).size() + o.getReferences(Entities.PARAMETER).size()))
			.orElseThrow();
		final Set<Integer> originParameters = productWithMaxReferences.getReferences(Entities.PARAMETER)
			.stream()
			.map(ReferenceContract::getReferencedPrimaryKey)
			.collect(Collectors.toSet());
		final int totalParameterCount = originParameters.size();

		assertEquals(
			originParameters,
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final Set<Integer> referencedParameters = CollectionUtils.createHashSet(totalParameterCount);
					for (int pageNumber = 1; pageNumber <= Math.ceil(totalParameterCount / 5.0f); pageNumber++) {
						final SealedEntity productByPk = session.queryOneSealedEntity(
							query(
								collection(Entities.PRODUCT),
								filterBy(
									entityPrimaryKeyInSet(productWithMaxReferences.getPrimaryKeyOrThrowException())
								),
								require(
									entityFetch(
										// provide all brands
										referenceContent(Entities.BRAND),
										// but only first four parameters
										referenceContent(
											Entities.PARAMETER,
											entityFetchAll(),
											entityGroupFetchAll(),
											page(pageNumber, 5)
										)
									)
								)
							)
						).orElseThrow();

						assertEquals(1, productByPk.getReferences(Entities.BRAND).size());

						final Collection<ReferenceContract> foundParameters = productByPk.getReferences(Entities.PARAMETER);
						assertTrue(!foundParameters.isEmpty() && foundParameters.size() <= 5);
						assertEquals(foundParameters.size(), productByPk.getReferences().stream().filter(it -> it.getReferenceName().equals(Entities.PARAMETER)).count());

						for (ReferenceContract foundParameter : foundParameters) {
							assertNotNull(foundParameter.getReferencedEntity());
							assertNotNull(foundParameter.getGroupEntity().orElse(null));
						}

						PaginatedList<ReferenceContract> parameters = new PaginatedList<>(pageNumber, 5, totalParameterCount, new ArrayList<>(foundParameters));
						assertEquals(parameters, productByPk.getReferenceChunk(Entities.PARAMETER));
						foundParameters
							.stream()
							.map(ReferenceContract::getReferencedPrimaryKey)
							.forEach(referencedParameters::add);

					}
					return referencedParameters;
				}
			)
		);
	}

	@DisplayName("Should provide paginated access to references")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnPaginatedReferencesWithSpacing(Evita evita, List<SealedEntity> originalProducts) {
		final SealedEntity productWithMaxReferences = originalProducts
			.stream()
			.max(Comparator.comparingInt(o -> o.getReferences(Entities.BRAND).size() + o.getReferences(Entities.PARAMETER).size()))
			.orElseThrow();
		final Set<Integer> originParameters = productWithMaxReferences.getReferences(Entities.PARAMETER)
			.stream()
			.map(ReferenceContract::getReferencedPrimaryKey)
			.collect(Collectors.toSet());
		final int totalParameterCount = originParameters.size();

		assertEquals(
			originParameters,
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final Set<Integer> referencedParameters = CollectionUtils.createHashSet(totalParameterCount);
					PaginatedList<ReferenceContract> parameters;
					int pageNumber = 1;
					do {
						final SealedEntity productByPk = session.queryOneSealedEntity(
							query(
								collection(Entities.PRODUCT),
								filterBy(
									entityPrimaryKeyInSet(productWithMaxReferences.getPrimaryKeyOrThrowException())
								),
								require(
									entityFetch(
										// but only first four parameters
										referenceContent(
											Entities.PARAMETER,
											entityFetchAll(),
											entityGroupFetchAll(),
											page(pageNumber, 5, spacing(gap(1, "$pageNumber % 2 == 0")))
										)
									)
								)
							)
						).orElseThrow();

						final Collection<ReferenceContract> foundParameters = productByPk.getReferences(Entities.PARAMETER);
						final int maxItemsPerPage = pageNumber % 2 == 0 ? 4 : 5;
						assertTrue(!foundParameters.isEmpty() && foundParameters.size() <= maxItemsPerPage);
						assertEquals(foundParameters.size(), productByPk.getReferences().stream().filter(it -> it.getReferenceName().equals(Entities.PARAMETER)).count());

						for (ReferenceContract foundParameter : foundParameters) {
							assertNotNull(foundParameter.getReferencedEntity());
							assertNotNull(foundParameter.getGroupEntity().orElse(null));
						}

						parameters = new PaginatedList<>(pageNumber, 4, 5, totalParameterCount, new ArrayList<>(foundParameters));
						assertEquals(parameters, productByPk.getReferenceChunk(Entities.PARAMETER));
						foundParameters
							.stream()
							.map(ReferenceContract::getReferencedPrimaryKey)
							.forEach(referencedParameters::add);
						pageNumber++;

					} while (parameters.hasNext());

					return referencedParameters;
				}
			)
		);
	}

	@DisplayName("Should provide stripped access to references")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnStrippedReferences(Evita evita, List<SealedEntity> originalProducts) {
		final SealedEntity productWithMaxReferences = originalProducts
			.stream()
			.max(Comparator.comparingInt(o -> o.getReferences(Entities.BRAND).size() + o.getReferences(Entities.PARAMETER).size()))
			.orElseThrow();
		final Set<Integer> originParameters = productWithMaxReferences.getReferences(Entities.PARAMETER)
			.stream()
			.map(ReferenceContract::getReferencedPrimaryKey)
			.collect(Collectors.toSet());
		final int totalParameterCount = originParameters.size();

		assertEquals(
			originParameters,
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final Set<Integer> referencedParameters = CollectionUtils.createHashSet(totalParameterCount);
					for (int pageNumber = 1; pageNumber <= Math.ceil(totalParameterCount / 5.0f); pageNumber++) {
						final int offset = (pageNumber - 1) * 5;
						final SealedEntity productByPk = session.queryOneSealedEntity(
							query(
								collection(Entities.PRODUCT),
								filterBy(
									entityPrimaryKeyInSet(productWithMaxReferences.getPrimaryKeyOrThrowException())
								),
								require(
									entityFetch(
										// provide all brands
										referenceContent(Entities.BRAND),
										// but only first four parameters
										referenceContent(
											Entities.PARAMETER,
											entityFetchAll(),
											entityGroupFetchAll(),
											strip(offset, 5)
										)
									)
								)
							)
						).orElseThrow();

						assertEquals(1, productByPk.getReferences(Entities.BRAND).size());

						final Collection<ReferenceContract> foundParameters = productByPk.getReferences(Entities.PARAMETER);
						assertTrue(!foundParameters.isEmpty() && foundParameters.size() <= 5);
						assertEquals(foundParameters.size(), productByPk.getReferences().stream().filter(it -> it.getReferenceName().equals(Entities.PARAMETER)).count());

						for (ReferenceContract foundParameter : foundParameters) {
							assertNotNull(foundParameter.getReferencedEntity());
							assertNotNull(foundParameter.getGroupEntity().orElse(null));
						}

						StripList<ReferenceContract> parameters = new StripList<>(offset, 5, totalParameterCount, new ArrayList<>(foundParameters));
						assertEquals(parameters, productByPk.getReferenceChunk(Entities.PARAMETER));
						foundParameters
							.stream()
							.map(ReferenceContract::getReferencedPrimaryKey)
							.forEach(referencedParameters::add);

					}
					return referencedParameters;
				}
			)
		);
	}

	@DisplayName("Should provide paginated and stripped access to references at once")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldCombinePaginatedAndStrippedReferences(Evita evita, List<SealedEntity> originalProducts) {
		final SealedEntity productWithMaxReferences = originalProducts
			.stream()
			.filter(it -> !it.getReferences(Entities.BRAND).isEmpty())
			.max(Comparator.comparingInt(o -> o.getReferences(Entities.BRAND).size() + o.getReferences(Entities.PARAMETER).size() + o.getReferences(Entities.PRICE_LIST).size()))
			.orElseThrow();
		final Set<Integer> originParameters = productWithMaxReferences.getReferences(Entities.PARAMETER)
			.stream()
			.map(ReferenceContract::getReferencedPrimaryKey)
			.collect(Collectors.toSet());
		final int totalParameterCount = originParameters.size();
		final Set<Integer> originPriceLists = productWithMaxReferences.getReferences(Entities.PRICE_LIST)
			.stream()
			.map(ReferenceContract::getReferencedPrimaryKey)
			.collect(Collectors.toSet());
		final int totalPriceListCount = originPriceLists.size();

		final Set[] result = evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Set<Integer> referencedParameters = CollectionUtils.createHashSet(totalParameterCount);
				final Set<Integer> referencedPriceLists = CollectionUtils.createHashSet(totalPriceListCount);
				for (int pageNumber = 1; pageNumber <= Math.ceil(totalParameterCount / 5.0f); pageNumber++) {
					final int offset = (pageNumber - 1) * 5;
					final SealedEntity productByPk = session.queryOneSealedEntity(
						query(
							collection(Entities.PRODUCT),
							filterBy(
								entityPrimaryKeyInSet(productWithMaxReferences.getPrimaryKeyOrThrowException())
							),
							require(
								entityFetch(
									// provide all brands
									referenceContent(Entities.BRAND),
									// but only first four price lists
									referenceContent(
										Entities.PRICE_LIST,
										entityFetchAll(),
										page(pageNumber, 5)
									),
									// but only first four parameters
									referenceContent(
										Entities.PARAMETER,
										entityFetchAll(),
										entityGroupFetchAll(),
										strip(offset, 5)
									)
								)
							)
						)
					).orElseThrow();

					assertEquals(1, productByPk.getReferences(Entities.BRAND).size());

					final StripList<ReferenceContract> foundParameters = (StripList<ReferenceContract>) productByPk.getReferenceChunk(Entities.PARAMETER);
					foundParameters
						.stream()
						.map(ReferenceContract::getReferencedPrimaryKey)
						.forEach(referencedParameters::add);

					final PaginatedList<ReferenceContract> foundPriceLists = (PaginatedList<ReferenceContract>) productByPk.getReferenceChunk(Entities.PRICE_LIST);
					foundPriceLists
						.stream()
						.map(ReferenceContract::getReferencedPrimaryKey)
						.forEach(referencedPriceLists::add);

				}
				return new Set[]{referencedParameters, referencedPriceLists};
			}
		);

		assertEquals(originParameters, result[0]);
		assertEquals(originPriceLists, result[1]);
	}

	@DisplayName("Should fetch entity without chunking and chunk it afterwards using enrichment")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldFetchEntityFirstAndEnrichItWithStrip(Evita evita, List<SealedEntity> originalProducts) {
		final SealedEntity productWithMaxReferences = originalProducts
			.stream()
			.max(Comparator.comparingInt(o -> o.getReferences(Entities.BRAND).size() + o.getReferences(Entities.PARAMETER).size()))
			.orElseThrow();

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final SealedEntity firstFetch = session.queryOneSealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(productWithMaxReferences.getPrimaryKeyOrThrowException())
						)
					)
				).orElseThrow();

				final SealedEntity secondFetch = session.enrichEntity(
					firstFetch,
					referenceContent(
						Entities.PARAMETER,
						entityFetchAll(),
						entityGroupFetchAll(),
						strip(2, 4)
					)
				);

				final Collection<ReferenceContract> originalParameters = productWithMaxReferences.getReferences(Entities.PARAMETER);
				final int[] expectedParameters = originalParameters
					.stream()
					.skip(2)
					.limit(4)
					.mapToInt(ReferenceContract::getReferencedPrimaryKey)
					.toArray();

				final Collection<ReferenceContract> foundParameters = secondFetch.getReferences(Entities.PARAMETER);
				assertTrue(!foundParameters.isEmpty() && foundParameters.size() <= 4);
				assertEquals(foundParameters.size(), secondFetch.getReferences().stream().filter(it -> it.getReferenceName().equals(Entities.PARAMETER)).count());
				assertArrayEquals(
					expectedParameters,
					secondFetch.getReferences(Entities.PARAMETER)
						.stream()
						.mapToInt(ReferenceContract::getReferencedPrimaryKey)
						.toArray()
				);

				for (ReferenceContract foundParameter : foundParameters) {
					assertNotNull(foundParameter.getReferencedEntity());
					assertNotNull(foundParameter.getGroupEntity().orElse(null));
				}

				StripList<ReferenceContract> parameters = new StripList<>(2, 4, (int) originalParameters.stream().count(), new ArrayList<>(foundParameters));
				assertEquals(parameters, secondFetch.getReferenceChunk(Entities.PARAMETER));

				return null;
			}
		);
	}

	@DisplayName("Should fetch entity with one page and enrich it with different one")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldFetchEntityWithPageAndChangeItInEnrichment(Evita evita, List<SealedEntity> originalProducts) {
		final SealedEntity productWithMaxReferences = originalProducts
			.stream()
			.max(Comparator.comparingInt(o -> o.getReferences(Entities.BRAND).size() + o.getReferences(Entities.PARAMETER).size()))
			.orElseThrow();

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final SealedEntity firstFetch = session.queryOneSealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(productWithMaxReferences.getPrimaryKeyOrThrowException())
						),
						require(
							entityFetch(
								referenceContent(
									Entities.PARAMETER,
									entityFetchAll(),
									entityGroupFetchAll(),
									page(1, 4)
								)
							)
						)
					)
				).orElseThrow();

				final Collection<ReferenceContract> originalParameters = productWithMaxReferences.getReferences(Entities.PARAMETER);
				final int[] expectedFirstFetchParameters = originalParameters
					.stream()
					.limit(4)
					.mapToInt(ReferenceContract::getReferencedPrimaryKey)
					.toArray();

				final Collection<ReferenceContract> foundParametersOnFirstFetch = firstFetch.getReferences(Entities.PARAMETER);
				assertTrue(!foundParametersOnFirstFetch.isEmpty() && foundParametersOnFirstFetch.size() <= 4);
				assertEquals(foundParametersOnFirstFetch.size(), firstFetch.getReferences().stream().filter(it -> it.getReferenceName().equals(Entities.PARAMETER)).count());
				assertArrayEquals(
					expectedFirstFetchParameters,
					firstFetch.getReferences(Entities.PARAMETER)
						.stream()
						.mapToInt(ReferenceContract::getReferencedPrimaryKey)
						.toArray()
				);

				for (ReferenceContract foundParameter : foundParametersOnFirstFetch) {
					assertNotNull(foundParameter.getReferencedEntity());
					assertNotNull(foundParameter.getGroupEntity().orElse(null));
				}

				PaginatedList<ReferenceContract> firstFetchChunk = new PaginatedList<>(1, 4, (int) originalParameters.stream().count(), new ArrayList<>(foundParametersOnFirstFetch));
				assertEquals(firstFetchChunk, firstFetch.getReferenceChunk(Entities.PARAMETER));

				final SealedEntity secondFetch = session.enrichEntity(
					firstFetch,
					referenceContent(
						Entities.PARAMETER,
						entityFetchAll(),
						entityGroupFetchAll(),
						strip(2, 4)
					)
				);

				final int[] expectedSecondFetchParameters = originalParameters
					.stream()
					.skip(2)
					.limit(4)
					.mapToInt(ReferenceContract::getReferencedPrimaryKey)
					.toArray();

				final Collection<ReferenceContract> foundParametersOnSecondFetch = secondFetch.getReferences(Entities.PARAMETER);
				assertTrue(!foundParametersOnSecondFetch.isEmpty() && foundParametersOnSecondFetch.size() <= 4);
				assertEquals(foundParametersOnSecondFetch.size(), secondFetch.getReferences().stream().filter(it -> it.getReferenceName().equals(Entities.PARAMETER)).count());
				assertArrayEquals(
					expectedSecondFetchParameters,
					secondFetch.getReferences(Entities.PARAMETER)
						.stream()
						.mapToInt(ReferenceContract::getReferencedPrimaryKey)
						.toArray()
				);

				for (ReferenceContract foundParameter : foundParametersOnSecondFetch) {
					assertNotNull(foundParameter.getReferencedEntity());
					assertNotNull(foundParameter.getGroupEntity().orElse(null));
				}

				StripList<ReferenceContract> secondFetchChunk = new StripList<>(2, 4, (int) originalParameters.stream().count(), new ArrayList<>(foundParametersOnSecondFetch));
				assertEquals(secondFetchChunk, secondFetch.getReferenceChunk(Entities.PARAMETER));

				return null;
			}
		);
	}

}
