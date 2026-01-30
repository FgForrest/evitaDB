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

import io.evitadb.api.exception.*;
import io.evitadb.api.query.require.PriceContentMode;
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
import static io.evitadb.test.generator.DataGenerator.*;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests verifying proper exception handling when accessing non-fetched, non-existing,
 * or otherwise unavailable entity data, as well as misplaced content requirements
 * outside entityFetch.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("Evita entity fetch exception handling")
@Tag(FUNCTIONAL_TEST)
@ExtendWith(EvitaParameterResolver.class)
@Slf4j
class EntityFetchExceptionFunctionalTest extends AbstractEntityFetchingFunctionalTest {

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
	void shouldThrowExceptionWhenAccessingExplicitlySpecifiedLocalizedAssociatedDataWithoutSpecifyingLocale(
		Evita evita
	) {
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
	void shouldThrowExceptionWhenAccessingAttributesFetchedInAnotherLocale(
		Evita evita, List<SealedEntity> originalProducts) {
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
					() -> productByPk.getAssociatedData(ASSOCIATED_DATA_REFERENCED_FILES)
				);
				return null;
			}
		);
	}

	@DisplayName("Should throw exception when accessing associated data in different language than fetched")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldThrowExceptionWhenAccessingAssociatedDataFetchedInAnotherLocale(
		Evita evita, List<SealedEntity> originalProducts) {
		final SealedEntity testedProduct = originalProducts
			.stream()
			.filter(it -> it.getAssociatedData(ASSOCIATED_DATA_LABELS, CZECH_LOCALE) != null)
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
								associatedDataContent(ASSOCIATED_DATA_LABELS)
							)
						)
					)
				).orElseThrow();

				assertNotNull(productByPk.getAssociatedData(ASSOCIATED_DATA_LABELS, CZECH_LOCALE));
				assertThrows(
					ContextMissingException.class,
					() -> productByPk.getAssociatedData(ASSOCIATED_DATA_LABELS, Locale.ENGLISH)
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
	void shouldThrowExceptionWhenAccessingNonFetchedPricesAndNotFilteredPrices(
		Evita evita, List<SealedEntity> originalProducts) {
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

	@DisplayName("Should throw exception for price outside entityFetch")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldThrowExceptionWhenPriceContentIsOutsideEntityFetch(Evita evita) {
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
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldThrowExceptionWhenHierarchyContentIsOutsideEntityFetch(Evita evita) {
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
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldThrowExceptionWhenNonExistingAttributeIsAttemptedToBeFetched(Evita evita) {
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
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldThrowExceptionWhenAttributeContentIsOutsideEntityFetch(Evita evita) {
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
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldThrowExceptionWhenNonExistingAssociatedDataIsAttemptedToBeFetched(Evita evita) {
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
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldThrowExceptionWhenAssociatedDataContentIsOutsideEntityFetch(Evita evita) {
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
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldThrowExceptionWhenNonExistingReferenceIsAttemptedToBeFetched(Evita evita) {
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
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldThrowExceptionWhenReferenceContentIsOutsideEntityFetch(Evita evita) {
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

}
