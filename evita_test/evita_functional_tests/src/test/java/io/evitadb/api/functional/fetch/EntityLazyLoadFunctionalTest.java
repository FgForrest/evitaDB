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

import io.evitadb.api.exception.ContextMissingException;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.PriceContract;
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

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestConstants.FUNCTIONAL_TEST;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.generator.DataGenerator.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Functional tests for entity lazy loading operations including lazy loading of
 * attributes, associated data, prices, and references, as well as entity limiting.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("Evita entity lazy loading functionality")
@Tag(FUNCTIONAL_TEST)
@ExtendWith(EvitaParameterResolver.class)
@Slf4j
class EntityLazyLoadFunctionalTest extends AbstractEntityFetchingFunctionalTest {

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
				assertProduct(product, entitiesMatchingTheRequirements[0], false, false, false, false);

				final SealedEntity enrichedProduct = session.enrichEntity(product, attributeContentAll());
				assertProduct(enrichedProduct, entitiesMatchingTheRequirements[0], true, false, false, false);
				assertNotNull(enrichedProduct.getAttribute(ATTRIBUTE_NAME, LOCALE_CZECH));
				assertEquals(
					(String) enrichedProduct.getAttribute(ATTRIBUTE_NAME, LOCALE_CZECH),
					enrichedProduct.getAttribute(ATTRIBUTE_NAME)
				);
				assertThrows(
					ContextMissingException.class, () -> enrichedProduct.getAttribute(ATTRIBUTE_NAME, Locale.ENGLISH));
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

	@DisplayName("Associated data can be lazy auto loaded while respecting language")
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
				assertEquals(
					enrichedProduct.getAssociatedDataValue(ASSOCIATED_DATA_LABELS, LOCALE_CZECH),
					enrichedProduct.getAssociatedDataValue(ASSOCIATED_DATA_LABELS)
				);
				assertThrows(
				ContextMissingException.class,
				() -> enrichedProduct.getAssociatedData(ASSOCIATED_DATA_LABELS, Locale.ENGLISH)
			);
				return null;
			}
		);
	}

	@DisplayName("Associated data can be lazy auto loaded in different languages lazily")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldLazyLoadAssociatedDataWithIncrementallyAddingLanguages(
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
				assertEquals(
					enrichedProduct.getAssociatedDataValue(ASSOCIATED_DATA_LABELS, LOCALE_CZECH),
					enrichedProduct.getAssociatedDataValue(ASSOCIATED_DATA_LABELS)
				);
				assertThrows(
				ContextMissingException.class,
				() -> enrichedProduct.getAssociatedData(ASSOCIATED_DATA_LABELS, Locale.ENGLISH)
			);

				final SealedEntity enrichedProductWithAdditionalLanguage = session.enrichEntity(
					enrichedProduct, dataInLocales(Locale.ENGLISH));
				assertNotNull(
					enrichedProductWithAdditionalLanguage.getAssociatedData(ASSOCIATED_DATA_LABELS, LOCALE_CZECH));
				assertNotNull(
					enrichedProductWithAdditionalLanguage.getAssociatedData(ASSOCIATED_DATA_LABELS, Locale.ENGLISH));

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

				final SealedEntity enrichedProduct1 = session.enrichEntity(
					product, associatedDataContent(ASSOCIATED_DATA_LABELS)
				);

				assertNotNull(enrichedProduct1.getAssociatedData(ASSOCIATED_DATA_LABELS, LOCALE_CZECH));
				assertThrows(
					ContextMissingException.class,
					() -> enrichedProduct1.getAssociatedData(ASSOCIATED_DATA_REFERENCED_FILES, LOCALE_CZECH)
				);

				final SealedEntity enrichedProduct2 = session.enrichEntity(
					enrichedProduct1, associatedDataContent(ASSOCIATED_DATA_REFERENCED_FILES));
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
				assertHasPriceInPriceList(
					enrichedProduct, PRICE_LIST_BASIC, PRICE_LIST_VIP, PRICE_LIST_REFERENCE, PRICE_LIST_B2B,
					PRICE_LIST_INTRODUCTION
				);
				return null;
			}
		);
	}

	@DisplayName("Filtered prices can be lazy auto loaded")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldLazyLoadFilteredPrices(Evita evita, List<SealedEntity> originalProducts) {
		final SealedEntity product = originalProducts
			.stream()
			.filter(it -> it.getPrices()
				.stream()
				.filter(PriceContract::indexed)
				.anyMatch(price -> price.validity() != null))
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

				final SealedEntity enrichedProduct = session.enrichEntity(
					returnedProduct, priceContentRespectingFilter());
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
				assertHasReferencesTo(
					enrichedProduct, Entities.CATEGORY, REFERENCED_ID_EXTRACTOR.apply(theEntity, Entities.CATEGORY));
				assertHasReferencesTo(
					enrichedProduct, Entities.BRAND, REFERENCED_ID_EXTRACTOR.apply(theEntity, Entities.BRAND));
				assertHasReferencesTo(
					enrichedProduct, Entities.STORE, REFERENCED_ID_EXTRACTOR.apply(theEntity, Entities.STORE));
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

				final SealedEntity enrichedProduct1 = session.enrichEntity(
					product, referenceContent(Entities.CATEGORY));
				assertHasReferencesTo(
					enrichedProduct1, Entities.CATEGORY, REFERENCED_ID_EXTRACTOR.apply(theEntity, Entities.CATEGORY));
				assertHasNotReferencesTo(enrichedProduct1, Entities.BRAND);
				assertHasNotReferencesTo(enrichedProduct1, Entities.STORE);

				final SealedEntity enrichedProduct2 = session.enrichEntity(
					enrichedProduct1, referenceContent(Entities.BRAND));
				assertHasReferencesTo(
					enrichedProduct2, Entities.CATEGORY, REFERENCED_ID_EXTRACTOR.apply(theEntity, Entities.CATEGORY));
				assertHasReferencesTo(
					enrichedProduct2, Entities.BRAND, REFERENCED_ID_EXTRACTOR.apply(theEntity, Entities.BRAND));
				assertHasNotReferencesTo(enrichedProduct2, Entities.STORE);

				final SealedEntity enrichedProduct3 = session.enrichEntity(
					enrichedProduct2, referenceContent(Entities.STORE));
				assertHasReferencesTo(
					enrichedProduct3, Entities.CATEGORY, REFERENCED_ID_EXTRACTOR.apply(theEntity, Entities.CATEGORY));
				assertHasReferencesTo(
					enrichedProduct2, Entities.BRAND, REFERENCED_ID_EXTRACTOR.apply(theEntity, Entities.BRAND));
				assertHasReferencesTo(
					enrichedProduct3, Entities.STORE, REFERENCED_ID_EXTRACTOR.apply(theEntity, Entities.STORE));
				return null;
			}
		);
	}

	@DisplayName("Rich entity should be limited to requested content")
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

				final SealedEntity limitedToBodyAndPrices = session.enrichOrLimitEntity(
					product, priceContentRespectingFilter());
				assertFalse(limitedToBodyAndPrices.getPrices().isEmpty());
				assertTrue(limitedToBodyAndPrices.getPrices().size() < product.getPrices().size());
				assertFalse(limitedToBodyAndPrices.attributesAvailable());
				assertThrows(ContextMissingException.class, limitedToBodyAndPrices::getAttributeValues);
				assertFalse(limitedToBodyAndPrices.associatedDataAvailable());
				assertThrows(ContextMissingException.class, limitedToBodyAndPrices::getAssociatedDataValues);

				final SealedEntity limitedToAttributes = session.enrichOrLimitEntity(
					product, attributeContent(), dataInLocalesAll());
				assertThrows(ContextMissingException.class, limitedToAttributes::getPrices);
				assertFalse(limitedToAttributes.getAttributeValues().isEmpty());
				assertFalse(limitedToAttributes.associatedDataAvailable());
				assertThrows(ContextMissingException.class, limitedToAttributes::getAssociatedDataValues);

				final SealedEntity limitedToAssociatedData = session.enrichOrLimitEntity(
					product, associatedDataContentAll(), dataInLocalesAll());
				assertThrows(ContextMissingException.class, limitedToAssociatedData::getPrices);
				assertFalse(limitedToAssociatedData.attributesAvailable());
				assertThrows(ContextMissingException.class, limitedToAssociatedData::getAttributeValues);
				assertFalse(limitedToAssociatedData.getAssociatedDataValues().isEmpty());

				return null;
			}
		);
	}

}
