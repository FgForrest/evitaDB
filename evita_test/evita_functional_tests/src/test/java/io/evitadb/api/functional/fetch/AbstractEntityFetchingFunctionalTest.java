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
import io.evitadb.api.exception.ContextMissingException;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.Entity;
import io.evitadb.api.requestResponse.data.structure.EntityDecorator;
import io.evitadb.api.requestResponse.data.structure.EntityReferenceWithParent;
import io.evitadb.core.Evita;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.test.Entities;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.extension.DataCarrier;
import one.edee.oss.pmptt.model.Hierarchy;
import one.edee.oss.pmptt.model.HierarchyItem;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Collection;
import java.util.Currency;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static io.evitadb.test.generator.DataGenerator.ASSOCIATED_DATA_REFERENCED_FILES;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_CODE;
import static io.evitadb.test.generator.DataGenerator.CZECH_LOCALE;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Abstract base class for entity fetching functional tests. Provides shared constants,
 * data set setup, and assertion helper methods used across all entity fetching test classes.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
abstract class AbstractEntityFetchingFunctionalTest extends AbstractHundredProductsFunctionalTest {
	static final String HUNDRED_PRODUCTS = "HundredProducts";
	static final Locale LOCALE_CZECH = CZECH_LOCALE;
	static final BiFunction<SealedEntity, String, int[]> REFERENCED_ID_EXTRACTOR =
		(entity, referencedType) -> entity.getReferences(referencedType)
			.stream()
			.mapToInt(ReferenceContract::getReferencedPrimaryKey)
			.toArray();

	/**
	 * Asserts that the product entity has the expected primary key and expected availability
	 * of attributes, associated data, prices, and references.
	 */
	static void assertProduct(
		@Nonnull SealedEntity product, int primaryKey, boolean hasAttributes, boolean hasAssociatedData,
		boolean hasPrices, boolean hasReferences
	) {
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
			assertThrows(
				ContextMissingException.class, () -> product.getAssociatedData(ASSOCIATED_DATA_REFERENCED_FILES));
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

	/**
	 * Returns primary keys of original products matching the given predicate.
	 */
	@Nonnull
	static Integer[] getRequestedIdsByPredicate(
		@Nonnull List<SealedEntity> originalProducts, @Nonnull Predicate<SealedEntity> predicate) {
		final Integer[] entitiesMatchingTheRequirements = originalProducts
			.stream()
			.filter(predicate)
			.map(EntityContract::getPrimaryKey)
			.toArray(Integer[]::new);

		assertTrue(entitiesMatchingTheRequirements.length > 0, "There are no entities matching the requirements!");
		return entitiesMatchingTheRequirements;
	}

	/**
	 * Finds first entity matching the given predicate.
	 */
	@Nonnull
	static SealedEntity findEntityByPredicate(@Nonnull List<SealedEntity> originalProducts, @Nonnull Predicate<SealedEntity> predicate) {
		return originalProducts
			.stream()
			.filter(predicate)
			.findFirst()
			.orElseThrow(() -> new EvitaInvalidUsageException("There are no entities matching the requirements!"));
	}

	/**
	 * Creates a chain of parent entity references for verifying hierarchy content.
	 */
	@Nonnull
	static EntityReferenceWithParent createParentChain(
		@Nonnull Hierarchy categoryHierarchy,
		int theLeaf,
		@Nullable Integer level,
		@Nullable Integer distance
	) {
		final List<HierarchyItem> parentItems = categoryHierarchy.getParentItems(String.valueOf(theLeaf));
		EntityReferenceWithParent workingNode = null;
		final Integer start = java.util.Optional.ofNullable(level)
			.map(it -> it - 1)
			.orElseGet(() -> java.util.Optional.ofNullable(distance).map(it -> parentItems.size() - it).orElse(0));
		for (int i = start; i < parentItems.size(); i++) {
			HierarchyItem parentItem = parentItems.get(i);
			workingNode = new EntityReferenceWithParent(
				Entities.CATEGORY, Integer.parseInt(parentItem.getCode()), workingNode);
		}

		return workingNode;
	}

	/**
	 * Creates a chain of parent sealed entities for verifying hierarchy content with entity bodies.
	 */
	@Nonnull
	static SealedEntity createParentEntityChain(
		@Nonnull Hierarchy categoryHierarchy, @Nonnull Map<Integer, SealedEntity> categoryIndex, int theLeaf,
		@Nullable Integer level, @Nullable Integer distance
	) {
		final List<HierarchyItem> parentItems = categoryHierarchy.getParentItems(String.valueOf(theLeaf));
		EntityDecorator workingNode = null;
		final Integer start = java.util.Optional.ofNullable(level)
			.map(it -> it - 1)
			.orElseGet(() -> java.util.Optional.ofNullable(distance).map(it -> parentItems.size() - it).orElse(0));
		for (int i = start; i < parentItems.size(); i++) {
			HierarchyItem parentItem = parentItems.get(i);
			final EntityDecorator categoryDecorator = (EntityDecorator) categoryIndex.get(
				Integer.parseInt(parentItem.getCode()));
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
				categoryDecorator.getAlignedNow()
			);
		}

		return workingNode;
	}

	/**
	 * Asserts that the product does NOT have references of the given type available.
	 */
	static void assertHasNotReferencesTo(@Nonnull SealedEntity product, @Nonnull String referenceName) {
		assertThrows(ContextMissingException.class, () -> product.getReferences(referenceName));
	}

	/**
	 * Asserts that the product has references to the specified primary keys.
	 */
	static void assertHasReferencesTo(
		@Nonnull SealedEntity product, @Nonnull String referenceName, int... primaryKeys) {
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

	/**
	 * Asserts that the product has attributes in the given locale.
	 */
	static void assertProductHasAttributesInLocale(@Nonnull SealedEntity product, @Nonnull Locale locale, @Nonnull String... attributes) {
		for (String attribute : attributes) {
			assertNotNull(
				product.getAttribute(attribute, locale),
				"Product " + product.getPrimaryKey() + " lacks attribute " + attribute
			);
		}
	}

	/**
	 * Asserts that the product does NOT have attributes in the given locale.
	 */
	static void assertProductHasNotAttributesInLocale(@Nonnull SealedEntity product, @Nonnull Locale locale, @Nonnull String... attributes) {
		for (String attribute : attributes) {
			assertThrows(
				ContextMissingException.class,
				() -> product.getAttribute(attribute, locale),
				"Product " + product.getPrimaryKey() + " has attribute " + attribute
			);
		}
	}

	/**
	 * Asserts that the product has associated data in the given locale.
	 */
	static void assertProductHasAssociatedDataInLocale(
		@Nonnull SealedEntity product, @Nonnull Locale locale, @Nonnull String... associatedDataName) {
		for (String associatedData : associatedDataName) {
			assertNotNull(
				product.getAssociatedData(associatedData, locale),
				"Product " + product.getPrimaryKey() + " lacks associated data " + associatedData
			);
		}
	}

	/**
	 * Asserts that the product does NOT have associated data in the given locale.
	 */
	static void assertProductHasNotAssociatedDataInLocale(
		@Nonnull SealedEntity product, @Nonnull Locale locale, @Nonnull String... associatedDataName) {
		for (String associatedData : associatedDataName) {
			assertThrows(
				ContextMissingException.class,
				() -> product.getAssociatedData(associatedData, locale),
				"Product " + product.getPrimaryKey() + " has associated data " + associatedData
			);
		}
	}

	/**
	 * Asserts that the product does NOT have associated data (without locale).
	 */
	static void assertProductHasNotAssociatedData(@Nonnull SealedEntity product, @Nonnull String... associatedDataName) {
		for (String associatedData : associatedDataName) {
			assertThrows(
				ContextMissingException.class,
				() -> product.getAssociatedData(associatedData),
				"Product " + product.getPrimaryKey() + " has associated data " + associatedData
			);
		}
	}

	/**
	 * Asserts that the product has prices in the given price lists.
	 */
	static void assertHasPriceInPriceList(@Nonnull SealedEntity product, @Nonnull Serializable... priceListName) {
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

	/**
	 * Asserts that the product does NOT have prices in the given price lists.
	 */
	static void assertHasNotPriceInPriceList(@Nonnull SealedEntity product, @Nonnull Serializable... priceList) {
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

	/**
	 * Asserts that the product has prices in the given currencies.
	 */
	static void assertHasPriceInCurrency(@Nonnull SealedEntity product, @Nonnull Currency... currency) {
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

	/**
	 * Asserts that the product does NOT have prices in the given currencies.
	 */
	static void assertHasNotPriceInCurrency(@Nonnull SealedEntity product, @Nonnull Currency... currency) {
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
	protected BiFunction<String, Faker, Integer> getRandomEntityPicker(@Nonnull EvitaSessionContract session) {
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

	@DataSet(value = HUNDRED_PRODUCTS)
	@Override
	protected DataCarrier setUp(@Nonnull Evita evita) {
		return super.setUp(evita);
	}
}
