/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2026
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

package io.evitadb.api.requestResponse;

import io.evitadb.api.requestResponse.EvitaRequest.ResultForm;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.dataType.Scope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.time.OffsetDateTime;
import java.util.Currency;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.api.query.filter.AttributeSpecialValue.NOT_NULL;
import static io.evitadb.api.query.require.DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS;
import static io.evitadb.api.query.require.FacetRelationType.*;
import static io.evitadb.api.query.require.PriceContentMode.*;
import static io.evitadb.api.query.require.QueryPriceMode.WITHOUT_TAX;
import static io.evitadb.api.query.require.QueryPriceMode.WITH_TAX;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link EvitaRequest} verifying lazy-memoized accessor
 * methods, pagination, entity fetch requirements, pricing,
 * facet configuration, scopes, and copy derivation.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@DisplayName("EvitaRequest")
class EvitaRequestTest {

	/** Shared timestamp for all tests. */
	private static final OffsetDateTime NOW =
		OffsetDateTime.now();

	/**
	 * Creates a basic {@link EvitaRequest} from the given
	 * query.
	 */
	@Nonnull
	private static EvitaRequest createRequest(
		@Nonnull io.evitadb.api.query.Query q
	) {
		return new EvitaRequest(
			q, NOW, SealedEntity.class, null
		);
	}

	@Nested
	@DisplayName("Entity type resolution")
	class EntityTypeTest {

		/**
		 * Verifies entity type from collection header.
		 */
		@Test
		@DisplayName("returns type from collection")
		void shouldReturnTypeFromCollection() {
			final EvitaRequest request = createRequest(
				query(collection("product"))
			);

			assertEquals("product", request.getEntityType());
			assertTrue(request.isEntityTypeRequested());
		}

		/**
		 * Verifies entity type from fallback parameter.
		 */
		@Test
		@DisplayName("returns type from fallback param")
		void shouldReturnTypeFromFallback() {
			final EvitaRequest request = new EvitaRequest(
				query(
					filterBy(
						entityPrimaryKeyInSet(1)
					)
				),
				NOW, SealedEntity.class, "category"
			);

			assertEquals(
				"category", request.getEntityType()
			);
		}

		/**
		 * Verifies null entity type when not specified.
		 */
		@Test
		@DisplayName("returns null when not specified")
		void shouldReturnNullWhenNotSpecified() {
			final EvitaRequest request = new EvitaRequest(
				query(
					filterBy(
						entityPrimaryKeyInSet(1)
					)
				),
				NOW, SealedEntity.class, null
			);

			assertNull(request.getEntityType());
			assertFalse(request.isEntityTypeRequested());
		}

		/**
		 * Verifies collection header takes precedence
		 * over fallback.
		 */
		@Test
		@DisplayName(
			"collection header overrides fallback"
		)
		void shouldPreferCollectionOverFallback() {
			final EvitaRequest request = new EvitaRequest(
				query(collection("product")),
				NOW, SealedEntity.class, "ignored"
			);

			assertEquals(
				"product", request.getEntityType()
			);
		}

		/**
		 * Verifies expected type is stored.
		 */
		@Test
		@DisplayName("stores expected type")
		void shouldStoreExpectedType() {
			final EvitaRequest request = createRequest(
				query(collection("product"))
			);

			assertEquals(
				SealedEntity.class,
				request.getExpectedType()
			);
		}
	}

	@Nested
	@DisplayName("Labels")
	class LabelsTest {

		/**
		 * Verifies empty labels when none specified.
		 */
		@Test
		@DisplayName("returns empty labels when none")
		void shouldReturnEmptyLabelsWhenNone() {
			final EvitaRequest request = createRequest(
				query(collection("product"))
			);

			assertEquals(0, request.getLabels().length);
		}

		/**
		 * Verifies labels are extracted from head.
		 */
		@Test
		@DisplayName("returns labels from head")
		void shouldReturnLabelsFromHead() {
			final EvitaRequest request = createRequest(
				query(
					head(
						collection("product"),
						label("name", "test")
					)
				)
			);

			assertEquals(1, request.getLabels().length);
			assertEquals(
				"name",
				request.getLabels()[0].getLabelName()
			);
		}
	}

	@Nested
	@DisplayName("Locale resolution")
	class LocaleTest {

		/**
		 * Verifies null locale when not set.
		 */
		@Test
		@DisplayName("returns null when no locale set")
		void shouldReturnNullWhenNoLocale() {
			final EvitaRequest request = createRequest(
				query(collection("product"))
			);

			assertNull(request.getLocale());
		}

		/**
		 * Verifies locale from entityLocaleEquals filter.
		 */
		@Test
		@DisplayName("returns locale from filter")
		void shouldReturnLocaleFromFilter() {
			final EvitaRequest request = createRequest(
				query(
					collection("product"),
					filterBy(
						entityLocaleEquals(Locale.FRENCH)
					)
				)
			);

			assertEquals(Locale.FRENCH, request.getLocale());
		}

		/**
		 * Verifies implicit locale is null by default.
		 */
		@Test
		@DisplayName("implicit locale is null by default")
		void shouldReturnNullImplicitLocale() {
			final EvitaRequest request = createRequest(
				query(collection("product"))
			);

			assertNull(request.getImplicitLocale());
		}

		/**
		 * Verifies implicit locale propagation via copy
		 * constructor.
		 */
		@Test
		@DisplayName("propagates implicit locale via copy")
		void shouldPropagateImplicitLocale() {
			final EvitaRequest original = createRequest(
				query(collection("product"))
			);
			final EvitaRequest copy = new EvitaRequest(
				original, Locale.GERMAN
			);

			assertEquals(
				Locale.GERMAN, copy.getImplicitLocale()
			);
		}

		/**
		 * Verifies getRequiredOrImplicitLocale falls back.
		 */
		@Test
		@DisplayName(
			"returns explicit locale over implicit"
		)
		void shouldReturnExplicitOverImplicit() {
			final EvitaRequest original = createRequest(
				query(
					collection("product"),
					filterBy(
						entityLocaleEquals(Locale.ENGLISH)
					)
				)
			);
			final EvitaRequest copy = new EvitaRequest(
				original, Locale.GERMAN
			);

			assertEquals(
				Locale.ENGLISH,
				copy.getRequiredOrImplicitLocale()
			);
		}

		/**
		 * Verifies fallback to implicit locale.
		 */
		@Test
		@DisplayName("falls back to implicit locale")
		void shouldFallBackToImplicitLocale() {
			final EvitaRequest original = createRequest(
				query(collection("product"))
			);
			final EvitaRequest copy = new EvitaRequest(
				original, Locale.JAPANESE
			);

			assertEquals(
				Locale.JAPANESE,
				copy.getRequiredOrImplicitLocale()
			);
		}

		/**
		 * Verifies required locales from dataInLocales.
		 */
		@Test
		@DisplayName("returns required locales set")
		void shouldReturnRequiredLocalesFromDataIn() {
			final EvitaRequest request = createRequest(
				query(
					collection("product"),
					require(
						entityFetch(
							dataInLocales(
								Locale.ENGLISH,
								Locale.FRENCH
							)
						)
					)
				)
			);

			final Set<Locale> locales =
				request.getRequiredLocales();
			assertNotNull(locales);
			assertEquals(2, locales.size());
			assertTrue(locales.contains(Locale.ENGLISH));
			assertTrue(locales.contains(Locale.FRENCH));
		}

		/**
		 * Verifies required locales falls back to filter
		 * locale when no dataInLocales.
		 */
		@Test
		@DisplayName(
			"required locales falls back to filter"
		)
		void shouldFallBackToFilterLocale() {
			final EvitaRequest request = createRequest(
				query(
					collection("product"),
					filterBy(
						entityLocaleEquals(Locale.ITALIAN)
					),
					require(entityFetch())
				)
			);

			final Set<Locale> locales =
				request.getRequiredLocales();
			assertNotNull(locales);
			assertEquals(1, locales.size());
			assertTrue(locales.contains(Locale.ITALIAN));
		}
	}

	@Nested
	@DisplayName("Primary keys")
	class PrimaryKeysTest {

		/**
		 * Verifies empty keys when not specified.
		 */
		@Test
		@DisplayName("returns empty when not specified")
		void shouldReturnEmptyWhenNotSpecified() {
			final EvitaRequest request = createRequest(
				query(collection("product"))
			);

			assertEquals(
				0, request.getPrimaryKeys().length
			);
		}

		/**
		 * Verifies keys from entityPrimaryKeyInSet.
		 */
		@Test
		@DisplayName("returns keys from filter")
		void shouldReturnKeysFromFilter() {
			final EvitaRequest request = createRequest(
				query(
					collection("product"),
					filterBy(
						entityPrimaryKeyInSet(1, 2, 3)
					)
				)
			);

			assertArrayEquals(
				new int[]{1, 2, 3},
				request.getPrimaryKeys()
			);
		}
	}

	@Nested
	@DisplayName("Pagination")
	class PaginationTest {

		/**
		 * Verifies default pagination (page 1, size 20).
		 */
		@Test
		@DisplayName("defaults to page 1, size 20")
		void shouldDefaultToPage1Size20() {
			final EvitaRequest request = createRequest(
				query(collection("product"))
			);

			assertEquals(1, request.getStart());
			assertEquals(20, request.getLimit());
			assertEquals(
				ResultForm.PAGINATED_LIST,
				request.getResultForm()
			);
		}

		/**
		 * Verifies explicit page pagination.
		 */
		@Test
		@DisplayName("uses explicit page")
		void shouldUseExplicitPage() {
			final EvitaRequest request = createRequest(
				query(
					collection("product"),
					require(page(3, 10))
				)
			);

			assertEquals(3, request.getStart());
			assertEquals(10, request.getLimit());
			assertEquals(
				ResultForm.PAGINATED_LIST,
				request.getResultForm()
			);
		}

		/**
		 * Verifies explicit strip pagination.
		 */
		@Test
		@DisplayName("uses explicit strip")
		void shouldUseExplicitStrip() {
			final EvitaRequest request = createRequest(
				query(
					collection("product"),
					require(strip(5, 15))
				)
			);

			assertEquals(5, request.getStart());
			assertEquals(15, request.getLimit());
			assertEquals(
				ResultForm.STRIP_LIST,
				request.getResultForm()
			);
		}

		/**
		 * Verifies empty conditional gaps by default.
		 */
		@Test
		@DisplayName("returns empty gaps by default")
		void shouldReturnEmptyGapsByDefault() {
			final EvitaRequest request = createRequest(
				query(collection("product"))
			);

			assertEquals(
				0, request.getConditionalGaps().length
			);
		}

		/**
		 * Verifies empty gaps with strip pagination.
		 */
		@Test
		@DisplayName("returns empty gaps with strip")
		void shouldReturnEmptyGapsWithStrip() {
			final EvitaRequest request = createRequest(
				query(
					collection("product"),
					require(strip(0, 10))
				)
			);

			assertEquals(
				0, request.getConditionalGaps().length
			);
		}
	}

	@Nested
	@DisplayName("Entity fetch requirements")
	class EntityFetchTest {

		/**
		 * Verifies no entity required by default.
		 */
		@Test
		@DisplayName("not required by default")
		void shouldNotRequireEntityByDefault() {
			final EvitaRequest request = createRequest(
				query(collection("product"))
			);

			assertFalse(request.isRequiresEntity());
			assertNull(request.getEntityRequirement());
		}

		/**
		 * Verifies entity required with entityFetch.
		 */
		@Test
		@DisplayName("required with entityFetch")
		void shouldRequireEntityWithFetch() {
			final EvitaRequest request = createRequest(
				query(
					collection("product"),
					require(entityFetch())
				)
			);

			assertTrue(request.isRequiresEntity());
			assertNotNull(request.getEntityRequirement());
		}

		/**
		 * Verifies parent not required by default.
		 */
		@Test
		@DisplayName("parent not required by default")
		void shouldNotRequireParentByDefault() {
			final EvitaRequest request = createRequest(
				query(collection("product"))
			);

			assertFalse(request.isRequiresParent());
			assertNull(request.getHierarchyContent());
		}

		/**
		 * Verifies parent required with hierarchyContent.
		 */
		@Test
		@DisplayName(
			"parent required with hierarchyContent"
		)
		void shouldRequireParentWithHierarchyContent() {
			final EvitaRequest request = createRequest(
				query(
					collection("product"),
					require(
						entityFetch(
							hierarchyContent()
						)
					)
				)
			);

			assertTrue(request.isRequiresParent());
			assertNotNull(request.getHierarchyContent());
		}
	}

	@Nested
	@DisplayName("Attribute requirements")
	class AttributeTest {

		/**
		 * Verifies no attributes required by default.
		 */
		@Test
		@DisplayName("not required by default")
		void shouldNotRequireAttributesByDefault() {
			final EvitaRequest request = createRequest(
				query(collection("product"))
			);

			assertFalse(
				request.isRequiresEntityAttributes()
			);
			assertTrue(
				request.getEntityAttributeSet().isEmpty()
			);
		}

		/**
		 * Verifies all attributes required.
		 */
		@Test
		@DisplayName("required with attributeContentAll")
		void shouldRequireAllAttributes() {
			final EvitaRequest request = createRequest(
				query(
					collection("product"),
					require(
						entityFetch(attributeContent())
					)
				)
			);

			assertTrue(
				request.isRequiresEntityAttributes()
			);
			// empty set means "all attributes"
			assertTrue(
				request.getEntityAttributeSet().isEmpty()
			);
		}

		/**
		 * Verifies specific attributes required.
		 */
		@Test
		@DisplayName("required with specific names")
		void shouldRequireSpecificAttributes() {
			final EvitaRequest request = createRequest(
				query(
					collection("product"),
					require(
						entityFetch(
							attributeContent(
								"name", "code"
							)
						)
					)
				)
			);

			assertTrue(
				request.isRequiresEntityAttributes()
			);
			final Set<String> attrs =
				request.getEntityAttributeSet();
			assertEquals(2, attrs.size());
			assertTrue(attrs.contains("name"));
			assertTrue(attrs.contains("code"));
		}
	}

	@Nested
	@DisplayName("Associated data requirements")
	class AssociatedDataTest {

		/**
		 * Verifies not required by default.
		 */
		@Test
		@DisplayName("not required by default")
		void shouldNotRequireByDefault() {
			final EvitaRequest request = createRequest(
				query(collection("product"))
			);

			assertFalse(
				request.isRequiresEntityAssociatedData()
			);
			assertTrue(
				request
					.getEntityAssociatedDataSet()
					.isEmpty()
			);
		}

		/**
		 * Verifies specific associated data required.
		 */
		@Test
		@DisplayName("required with specific names")
		void shouldRequireSpecific() {
			final EvitaRequest request = createRequest(
				query(
					collection("product"),
					require(
						entityFetch(
							associatedDataContent(
								"description"
							)
						)
					)
				)
			);

			assertTrue(
				request.isRequiresEntityAssociatedData()
			);
			assertTrue(
				request
					.getEntityAssociatedDataSet()
					.contains("description")
			);
		}

		/**
		 * Verifies all associated data required.
		 */
		@Test
		@DisplayName("required with all")
		void shouldRequireAll() {
			final EvitaRequest request = createRequest(
				query(
					collection("product"),
					require(
						entityFetch(
							associatedDataContent()
						)
					)
				)
			);

			assertTrue(
				request.isRequiresEntityAssociatedData()
			);
			assertTrue(
				request
					.getEntityAssociatedDataSet()
					.isEmpty()
			);
		}
	}

	@Nested
	@DisplayName("Reference requirements")
	class ReferenceTest {

		/**
		 * Verifies not required by default.
		 */
		@Test
		@DisplayName("not required by default")
		void shouldNotRequireByDefault() {
			final EvitaRequest request = createRequest(
				query(collection("product"))
			);

			assertFalse(
				request.isRequiresEntityReferences()
			);
			assertTrue(
				request.getReferenceEntityFetch().isEmpty()
			);
		}

		/**
		 * Verifies all references required.
		 */
		@Test
		@DisplayName("required with referenceContentAll")
		void shouldRequireAll() {
			final EvitaRequest request = createRequest(
				query(
					collection("product"),
					require(
						entityFetch(
							referenceContentAll()
						)
					)
				)
			);

			assertTrue(
				request.isRequiresEntityReferences()
			);
		}

		/**
		 * Verifies specific reference required.
		 */
		@Test
		@DisplayName("required with specific name")
		void shouldRequireSpecific() {
			final EvitaRequest request = createRequest(
				query(
					collection("product"),
					require(
						entityFetch(
							referenceContent("brand")
						)
					)
				)
			);

			assertTrue(
				request.isRequiresEntityReferences()
			);
			assertTrue(
				request
					.getReferenceEntityFetch()
					.containsKey("brand")
			);
		}

		/**
		 * Verifies default reference requirement is null
		 * when specific references requested.
		 */
		@Test
		@DisplayName(
			"default ref requirement null for specific"
		)
		void shouldHaveNullDefaultForSpecific() {
			final EvitaRequest request = createRequest(
				query(
					collection("product"),
					require(
						entityFetch(
							referenceContent("brand")
						)
					)
				)
			);

			assertNull(
				request.getDefaultReferenceRequirement()
			);
		}

		/**
		 * Verifies default reference requirement for all.
		 */
		@Test
		@DisplayName("default ref requirement for all")
		void shouldHaveDefaultForAll() {
			final EvitaRequest request = createRequest(
				query(
					collection("product"),
					require(
						entityFetch(
							referenceContentAll()
						)
					)
				)
			);

			assertNotNull(
				request.getDefaultReferenceRequirement()
			);
		}

		/**
		 * Verifies named reference fetch map is empty
		 * by default.
		 */
		@Test
		@DisplayName("named ref fetch empty by default")
		void shouldHaveEmptyNamedRefFetch() {
			final EvitaRequest request = createRequest(
				query(collection("product"))
			);

			assertTrue(
				request
					.getNamedReferenceEntityFetch()
					.isEmpty()
			);
		}
	}

	@Nested
	@DisplayName("Price requirements")
	class PriceTest {

		/**
		 * Verifies default query price mode is WITH_TAX.
		 */
		@Test
		@DisplayName("defaults to WITH_TAX")
		void shouldDefaultToWithTax() {
			final EvitaRequest request = createRequest(
				query(collection("product"))
			);

			assertEquals(
				WITH_TAX, request.getQueryPriceMode()
			);
		}

		/**
		 * Verifies explicit price type.
		 */
		@Test
		@DisplayName("uses explicit price type")
		void shouldUseExplicitPriceType() {
			final EvitaRequest request = createRequest(
				query(
					collection("product"),
					require(priceType(WITHOUT_TAX))
				)
			);

			assertEquals(
				WITHOUT_TAX, request.getQueryPriceMode()
			);
		}

		/**
		 * Verifies no currency by default.
		 */
		@Test
		@DisplayName("no currency by default")
		void shouldHaveNoCurrencyByDefault() {
			final EvitaRequest request = createRequest(
				query(collection("product"))
			);

			assertNull(request.getRequiresCurrency());
		}

		/**
		 * Verifies currency from filter.
		 */
		@Test
		@DisplayName("returns currency from filter")
		void shouldReturnCurrencyFromFilter() {
			final EvitaRequest request = createRequest(
				query(
					collection("product"),
					filterBy(
						priceInCurrency(
							Currency.getInstance("EUR")
						)
					)
				)
			);

			assertEquals(
				Currency.getInstance("EUR"),
				request.getRequiresCurrency()
			);
		}

		/**
		 * Verifies no price valid in by default.
		 */
		@Test
		@DisplayName(
			"no price valid in time by default"
		)
		void shouldHaveNoPriceValidInByDefault() {
			final EvitaRequest request = createRequest(
				query(collection("product"))
			);

			assertNull(request.getRequiresPriceValidIn());
		}

		/**
		 * Verifies price valid in from filter.
		 */
		@Test
		@DisplayName("returns price valid in from filter")
		void shouldReturnPriceValidIn() {
			final OffsetDateTime moment =
				OffsetDateTime.now();
			final EvitaRequest request = createRequest(
				query(
					collection("product"),
					filterBy(priceValidIn(moment))
				)
			);

			assertEquals(
				moment,
				request.getRequiresPriceValidIn()
			);
		}

		/**
		 * Verifies no price lists by default.
		 */
		@Test
		@DisplayName("no price lists by default")
		void shouldHaveNoPriceListsByDefault() {
			final EvitaRequest request = createRequest(
				query(collection("product"))
			);

			assertFalse(request.isRequiresPriceLists());
			assertEquals(
				0,
				request.getRequiresPriceLists().length
			);
		}

		/**
		 * Verifies price lists from filter.
		 */
		@Test
		@DisplayName("returns price lists from filter")
		void shouldReturnPriceListsFromFilter() {
			final EvitaRequest request = createRequest(
				query(
					collection("product"),
					filterBy(
						priceInPriceLists(
							"basic", "vip"
						)
					)
				)
			);

			assertTrue(request.isRequiresPriceLists());
			assertArrayEquals(
				new String[]{"basic", "vip"},
				request.getRequiresPriceLists()
			);
		}

		/**
		 * Verifies entity prices mode defaults to NONE.
		 */
		@Test
		@DisplayName("entity prices defaults to NONE")
		void shouldDefaultEntityPricesToNone() {
			final EvitaRequest request = createRequest(
				query(collection("product"))
			);

			assertEquals(
				NONE,
				request.getRequiresEntityPrices()
			);
		}

		/**
		 * Verifies entity prices mode from require.
		 */
		@Test
		@DisplayName("returns entity prices mode")
		void shouldReturnEntityPricesMode() {
			final EvitaRequest request = createRequest(
				query(
					collection("product"),
					require(
						entityFetch(
							priceContent(
								RESPECTING_FILTER
							)
						)
					)
				)
			);

			assertEquals(
				RESPECTING_FILTER,
				request.getRequiresEntityPrices()
			);
		}

		/**
		 * Verifies additional price lists from require.
		 */
		@Test
		@DisplayName("returns additional price lists")
		void shouldReturnAdditionalPriceLists() {
			final EvitaRequest request = createRequest(
				query(
					collection("product"),
					require(
						entityFetch(
							priceContent(
								RESPECTING_FILTER,
								"extra"
							)
						)
					)
				)
			);

			final String[] additional =
				request.getFetchesAdditionalPriceLists();
			assertTrue(additional.length > 0);
		}

		/**
		 * Verifies empty accompanying prices by default.
		 */
		@Test
		@DisplayName(
			"empty accompanying prices by default"
		)
		void shouldHaveEmptyAccompanyingByDefault() {
			final EvitaRequest request = createRequest(
				query(
					collection("product"),
					require(entityFetch())
				)
			);

			assertEquals(
				0,
				request.getAccompanyingPrices().length
			);
		}
	}

	@Nested
	@DisplayName("Facet configuration")
	class FacetTest {

		/**
		 * Verifies default facet relation type.
		 */
		@Test
		@DisplayName("defaults to DISJUNCTION")
		void shouldDefaultToDisjunction() {
			final EvitaRequest request = createRequest(
				query(collection("product"))
			);

			assertEquals(
				DISJUNCTION,
				request.getDefaultFacetRelationType()
			);
		}

		/**
		 * Verifies default group relation type.
		 */
		@Test
		@DisplayName("group defaults to CONJUNCTION")
		void shouldGroupDefaultToConjunction() {
			final EvitaRequest request = createRequest(
				query(collection("product"))
			);

			assertEquals(
				CONJUNCTION,
				request.getDefaultGroupRelationType()
			);
		}

		/**
		 * Verifies custom facet calculation rules.
		 */
		@Test
		@DisplayName("uses custom calculation rules")
		void shouldUseCustomCalculationRules() {
			final EvitaRequest request = createRequest(
				query(
					collection("product"),
					require(
						facetCalculationRules(
							CONJUNCTION,
							DISJUNCTION
						)
					)
				)
			);

			assertEquals(
				CONJUNCTION,
				request.getDefaultFacetRelationType()
			);
			assertEquals(
				DISJUNCTION,
				request.getDefaultGroupRelationType()
			);
		}

		/**
		 * Verifies facet group conjunction is empty
		 * when not specified.
		 */
		@Test
		@DisplayName("empty conjunction by default")
		void shouldHaveEmptyConjunctionByDefault() {
			final EvitaRequest request = createRequest(
				query(collection("product"))
			);

			assertTrue(
				request
					.getFacetGroupConjunction("brand")
					.isEmpty()
			);
		}

		/**
		 * Verifies facet group conjunction setup.
		 */
		@Test
		@DisplayName("returns conjunction for reference")
		void shouldReturnConjunction() {
			final EvitaRequest request = createRequest(
				query(
					collection("product"),
					require(
						facetGroupsConjunction("brand")
					)
				)
			);

			assertTrue(
				request
					.getFacetGroupConjunction("brand")
					.isPresent()
			);
		}

		/**
		 * Verifies facet group disjunction is empty
		 * when not specified.
		 */
		@Test
		@DisplayName("empty disjunction by default")
		void shouldHaveEmptyDisjunctionByDefault() {
			final EvitaRequest request = createRequest(
				query(collection("product"))
			);

			assertTrue(
				request
					.getFacetGroupDisjunction("brand")
					.isEmpty()
			);
		}

		/**
		 * Verifies facet group disjunction setup.
		 */
		@Test
		@DisplayName(
			"returns disjunction for reference"
		)
		void shouldReturnDisjunction() {
			final EvitaRequest request = createRequest(
				query(
					collection("product"),
					require(
						facetGroupsDisjunction(
							"category"
						)
					)
				)
			);

			assertTrue(
				request
					.getFacetGroupDisjunction("category")
					.isPresent()
			);
		}

		/**
		 * Verifies facet group negation empty by default.
		 */
		@Test
		@DisplayName("empty negation by default")
		void shouldHaveEmptyNegationByDefault() {
			final EvitaRequest request = createRequest(
				query(collection("product"))
			);

			assertTrue(
				request
					.getFacetGroupNegation("brand")
					.isEmpty()
			);
		}
	}

	@Nested
	@DisplayName("Debug and telemetry")
	class DebugTest {

		/**
		 * Verifies telemetry not requested by default.
		 */
		@Test
		@DisplayName("telemetry not requested by default")
		void shouldNotRequestTelemetryByDefault() {
			final EvitaRequest request = createRequest(
				query(collection("product"))
			);

			assertFalse(
				request.isQueryTelemetryRequested()
			);
		}

		/**
		 * Verifies telemetry requested when present.
		 */
		@Test
		@DisplayName("telemetry requested when present")
		void shouldRequestTelemetryWhenPresent() {
			final EvitaRequest request = createRequest(
				query(
					collection("product"),
					require(queryTelemetry())
				)
			);

			assertTrue(
				request.isQueryTelemetryRequested()
			);
		}

		/**
		 * Verifies debug mode not enabled by default.
		 */
		@Test
		@DisplayName("debug mode not enabled by default")
		void shouldNotEnableDebugByDefault() {
			final EvitaRequest request = createRequest(
				query(collection("product"))
			);

			assertFalse(
				request.isDebugModeEnabled(
					VERIFY_ALTERNATIVE_INDEX_RESULTS
				)
			);
		}

		/**
		 * Verifies debug mode enabled when specified.
		 */
		@Test
		@DisplayName("debug mode enabled when specified")
		void shouldEnableDebugWhenSpecified() {
			final EvitaRequest request = createRequest(
				query(
					collection("product"),
					require(
						debug(
							VERIFY_ALTERNATIVE_INDEX_RESULTS
						)
					)
				)
			);

			assertTrue(
				request.isDebugModeEnabled(
					VERIFY_ALTERNATIVE_INDEX_RESULTS
				)
			);
		}
	}

	@Nested
	@DisplayName("Scopes")
	class ScopeTest {

		/**
		 * Verifies default scope is LIVE.
		 */
		@Test
		@DisplayName("defaults to LIVE scope")
		void shouldDefaultToLiveScope() {
			final EvitaRequest request = createRequest(
				query(collection("product"))
			);

			final Set<Scope> scopes = request.getScopes();
			assertEquals(1, scopes.size());
			assertTrue(scopes.contains(Scope.LIVE));
		}

		/**
		 * Verifies explicit scope from filter.
		 */
		@Test
		@DisplayName("returns explicit scope from filter")
		void shouldReturnExplicitScope() {
			final EvitaRequest request = createRequest(
				query(
					collection("product"),
					filterBy(
						scope(Scope.ARCHIVED)
					)
				)
			);

			final Set<Scope> scopes = request.getScopes();
			assertTrue(scopes.contains(Scope.ARCHIVED));
		}

		/**
		 * Verifies scopesAsArray matches getScopes.
		 */
		@Test
		@DisplayName("scopesAsArray matches getScopes")
		void shouldMatchScopesAsArray() {
			final EvitaRequest request = createRequest(
				query(collection("product"))
			);

			final Scope[] arr = request.getScopesAsArray();
			final Set<Scope> set = request.getScopes();

			assertEquals(set.size(), arr.length);
			for (final Scope s : arr) {
				assertTrue(set.contains(s));
			}
		}
	}

	@Nested
	@DisplayName("Copy derivation with scope enforcement")
	class DeriveCopyWithScopeTest {

		/**
		 * Verifies scope added to request without filter.
		 */
		@Test
		@DisplayName("adds scope without filter")
		void shouldAddScopeToRequestWithoutFilter() {
			final EvitaRequest request = createRequest(
				query(collection("a"))
			);

			final EvitaRequest copy =
				request.deriveCopyWith(
					"b", null, null,
					Locale.ENGLISH,
					EnumSet.of(Scope.ARCHIVED)
				);

			assertEquals("b", copy.getEntityType());
			assertEquals(
				Locale.ENGLISH, copy.getLocale()
			);
			assertEquals(
				EnumSet.of(Scope.ARCHIVED),
				copy.getScopes()
			);
			assertEquals(
				"""
					query(
						collection('b'),
						filterBy(
							scope(ARCHIVED)
						),
						require()
					)""",
				copy.getQuery().prettyPrint()
			);
		}

		/**
		 * Verifies scope added replacing existing filter.
		 */
		@Test
		@DisplayName("adds scope replacing filter")
		void shouldAddScopeToRequest() {
			final EvitaRequest request = createRequest(
				query(
					collection("a"),
					filterBy(
						entityLocaleEquals(Locale.GERMAN)
					)
				)
			);

			final EvitaRequest copy =
				request.deriveCopyWith(
					"b", null, null,
					Locale.ENGLISH,
					EnumSet.of(Scope.ARCHIVED)
				);

			assertEquals("b", copy.getEntityType());
			assertEquals(
				Locale.ENGLISH, copy.getLocale()
			);
			assertEquals(
				EnumSet.of(Scope.ARCHIVED),
				copy.getScopes()
			);
			assertEquals(
				"""
					query(
						collection('b'),
						filterBy(
							scope(ARCHIVED)
						),
						require()
					)""",
				copy.getQuery().prettyPrint()
			);
		}

		/**
		 * Verifies scope replacement in existing filter.
		 */
		@Test
		@DisplayName("replaces existing scope")
		void shouldReplaceScopeInRequest() {
			final EvitaRequest request = createRequest(
				query(
					collection("a"),
					filterBy(scope(Scope.LIVE))
				)
			);

			final EvitaRequest copy =
				request.deriveCopyWith(
					"b", null, null,
					Locale.ENGLISH,
					EnumSet.of(Scope.ARCHIVED)
				);

			assertEquals("b", copy.getEntityType());
			assertEquals(
				Locale.ENGLISH, copy.getLocale()
			);
			assertEquals(
				EnumSet.of(Scope.ARCHIVED),
				copy.getScopes()
			);
			assertEquals(
				"""
					query(
						collection('b'),
						filterBy(
							scope(ARCHIVED)
						),
						require()
					)""",
				copy.getQuery().prettyPrint()
			);
		}

		/**
		 * Verifies scope replacement excludes non-matching
		 * inScope containers.
		 */
		@Test
		@DisplayName("excludes non-matching containers")
		void shouldExcludeNonMatchingContainers() {
			final EvitaRequest request = createRequest(
				query(
					collection("a"),
					filterBy(
						inScope(
							Scope.LIVE,
							attributeIs(
								"code", NOT_NULL
							)
						),
						scope(
							Scope.LIVE,
							Scope.ARCHIVED
						)
					)
				)
			);

			final EvitaRequest copy =
				request.deriveCopyWith(
					"b", null, null,
					Locale.ENGLISH,
					EnumSet.of(Scope.ARCHIVED)
				);

			assertEquals("b", copy.getEntityType());
			assertEquals(
				Locale.ENGLISH, copy.getLocale()
			);
			assertEquals(
				EnumSet.of(Scope.ARCHIVED),
				copy.getScopes()
			);
			assertEquals(
				"""
					query(
						collection('b'),
						filterBy(
							scope(ARCHIVED)
						),
						require()
					)""",
				copy.getQuery().prettyPrint()
			);
		}
	}

	@Nested
	@DisplayName(
		"Copy derivation with entity requirements"
	)
	class DeriveCopyWithRequirementsTest {

		/**
		 * Verifies deriveCopyWith entity requirements
		 * overrides entity type.
		 */
		@Test
		@DisplayName("overrides entity type")
		void shouldOverrideEntityType() {
			final EvitaRequest request = createRequest(
				query(
					collection("product"),
					require(entityFetch())
				)
			);

			final EvitaRequest copy =
				request.deriveCopyWith(
					"brand",
					entityFetch(attributeContent())
				);

			assertEquals("brand", copy.getEntityType());
			assertTrue(copy.isRequiresEntity());
		}

		/**
		 * Verifies deriveCopyWith null entity type.
		 */
		@Test
		@DisplayName("allows null entity type")
		void shouldAllowNullEntityType() {
			final EvitaRequest request = createRequest(
				query(
					collection("product"),
					require(entityFetch())
				)
			);

			final EvitaRequest copy =
				request.deriveCopyWith(
					null,
					entityFetch()
				);

			assertNull(copy.getEntityType());
		}

		/**
		 * Verifies 4-param deriveCopyWith with filterBy.
		 */
		@Test
		@DisplayName("overrides with filter and order")
		void shouldOverrideWithFilterAndOrder() {
			final EvitaRequest request = createRequest(
				query(
					collection("product"),
					require(entityFetch())
				)
			);

			final EvitaRequest copy =
				request.deriveCopyWith(
					"brand",
					filterBy(
						entityLocaleEquals(
							Locale.ENGLISH
						)
					),
					null,
					entityFetch()
				);

			assertEquals("brand", copy.getEntityType());
			assertEquals(
				Locale.ENGLISH, copy.getLocale()
			);
		}

		/**
		 * Verifies alignedNow is preserved in copy.
		 */
		@Test
		@DisplayName("preserves alignedNow")
		void shouldPreserveAlignedNow() {
			final EvitaRequest request = createRequest(
				query(collection("product"))
			);

			final EvitaRequest copy =
				request.deriveCopyWith(
					"brand",
					entityFetch()
				);

			assertEquals(
				request.getAlignedNow(),
				copy.getAlignedNow()
			);
		}
	}

	@Nested
	@DisplayName("Implicit locale constructor")
	class ImplicitLocaleConstructorTest {

		/**
		 * Verifies implicit locale copy constructor.
		 */
		@Test
		@DisplayName("sets implicit locale")
		void shouldSetImplicitLocale() {
			final EvitaRequest original = createRequest(
				query(collection("product"))
			);

			final EvitaRequest copy = new EvitaRequest(
				original, Locale.KOREAN
			);

			assertEquals(
				Locale.KOREAN, copy.getImplicitLocale()
			);
			// original query preserved
			assertSame(
				original.getQuery(), copy.getQuery()
			);
		}

		/**
		 * Verifies memoized values are shared in copy.
		 */
		@Test
		@DisplayName("shares memoized values")
		void shouldShareMemoizedValues() {
			final EvitaRequest original = createRequest(
				query(
					collection("product"),
					filterBy(
						entityLocaleEquals(
							Locale.ENGLISH
						)
					)
				)
			);
			// force memoization
			original.getLocale();

			final EvitaRequest copy = new EvitaRequest(
				original, Locale.GERMAN
			);

			// explicit locale should be preserved from
			// original memoized value
			assertEquals(
				Locale.ENGLISH, copy.getLocale()
			);
		}
	}

	@Nested
	@DisplayName("Hierarchy")
	class HierarchyTest {

		/**
		 * Verifies null hierarchy when not in query.
		 */
		@Test
		@DisplayName(
			"returns null when no hierarchy filter"
		)
		void shouldReturnNullWhenNoHierarchy() {
			final EvitaRequest request = createRequest(
				query(collection("product"))
			);

			assertNull(
				request.getHierarchyWithin(null)
			);
		}

		/**
		 * Verifies hierarchy with reference name.
		 */
		@Test
		@DisplayName("returns hierarchy for reference")
		void shouldReturnHierarchyForReference() {
			final EvitaRequest request = createRequest(
				query(
					collection("product"),
					filterBy(
						hierarchyWithin(
							"category",
							entityPrimaryKeyInSet(5)
						)
					)
				)
			);

			assertNotNull(
				request.getHierarchyWithin("category")
			);
		}

		/**
		 * Verifies null hierarchy for wrong reference.
		 */
		@Test
		@DisplayName(
			"returns null for wrong reference name"
		)
		void shouldReturnNullForWrongRef() {
			final EvitaRequest request = createRequest(
				query(
					collection("product"),
					filterBy(
						hierarchyWithin(
							"category",
							entityPrimaryKeyInSet(5)
						)
					)
				)
			);

			assertNull(
				request.getHierarchyWithin("brand")
			);
		}
	}
}