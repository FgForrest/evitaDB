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

import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.QueryUtils;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.require.AttributeContent;
import io.evitadb.api.query.require.EntityContentRequire;
import io.evitadb.api.query.require.EntityFetch;
import io.evitadb.api.query.require.HierarchyContent;
import io.evitadb.api.query.require.ManagedReferencesBehaviour;
import io.evitadb.api.query.require.ReferenceContent;
import io.evitadb.api.query.require.SeparateEntityContentRequireContainer;
import io.evitadb.api.requestResponse.EvitaRequest.ReferenceContentKey;
import io.evitadb.api.requestResponse.EvitaRequest.RequirementContext;
import io.evitadb.api.requestResponse.EvitaRequest.ResultForm;
import io.evitadb.api.requestResponse.data.PricesContract.AccompanyingPrice;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.EvitaInvalidUsageException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Tag;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.api.query.filter.AttributeSpecialValue.NOT_NULL;
import static io.evitadb.api.query.require.DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS;
import static io.evitadb.api.query.require.FacetRelationType.*;
import static io.evitadb.api.query.require.PriceContentMode.*;
import static io.evitadb.api.query.require.QueryPriceMode.WITHOUT_TAX;
import static io.evitadb.api.query.require.QueryPriceMode.WITH_TAX;
import static org.junit.jupiter.api.Assertions.*;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.HIERARCHY;
import static io.evitadb.test.TestTags.HISTOGRAM;
import static io.evitadb.test.TestTags.PRICE;
import static io.evitadb.test.TestTags.QUERY;
import static io.evitadb.test.TestTags.REFERENCE;
import static io.evitadb.test.TestTags.REQUIRE;

/**
 * Tests for {@link EvitaRequest} verifying lazy-memoized accessor
 * methods, pagination, entity fetch requirements, pricing,
 * facet configuration, scopes, and copy derivation.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@DisplayName("EvitaRequest")
@Tag(CONTRACT)
@Tag(QUERY)
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
			// the moment carries sub-millisecond digits, which the query boundary discards
			final OffsetDateTime moment =
				OffsetDateTime.of(
					2026, 5, 20, 12, 19, 26,
					123_456_789, ZoneOffset.UTC
				);
			final EvitaRequest request = createRequest(
				query(
					collection("product"),
					filterBy(priceValidIn(moment))
				)
			);

			assertEquals(
				OffsetDateTime.of(
					2026, 5, 20, 12, 19, 26,
					123_000_000, ZoneOffset.UTC
				),
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
	@DisplayName("Price histogram requirement detection")
	@Tag(HISTOGRAM)
	class PriceHistogramRequestedTest {

		/**
		 * Verifies the lazy probe returns `true` when the
		 * query carries a `priceHistogram(...)` require.
		 * This is the flag read by the filter planner to opt
		 * the outer {@code LowestPriceTerminationFormula}
		 * into per-inner-record histogram collection.
		 */
		@Test
		@DisplayName(
			"returns true when priceHistogram require is present"
		)
		void shouldReturnTrueWhenPriceHistogramRequireIsPresent() {
			final EvitaRequest request = createRequest(
				query(
					collection("product"),
					require(priceHistogram(20))
				)
			);

			assertTrue(request.isPriceHistogramRequested());
		}

		/**
		 * Verifies the probe returns `false` for a filter-only
		 * query without any `priceHistogram` require — the
		 * planner must NOT flip the LP histogram flag in this
		 * case.
		 */
		@Test
		@DisplayName(
			"returns false when priceHistogram require is absent"
		)
		void shouldReturnFalseWhenPriceHistogramRequireIsAbsent() {
			final EvitaRequest request = createRequest(
				query(
					collection("product"),
					filterBy(
						entityPrimaryKeyInSet(1, 2, 3)
					)
				)
			);

			assertFalse(request.isPriceHistogramRequested());
		}

		/**
		 * Verifies that the probe is memoised — two
		 * consecutive calls return the same value and do not
		 * re-parse the query. The cached boolean field is
		 * what makes the LP-construction-time read O(1).
		 */
		@Test
		@DisplayName(
			"memoises result across successive calls"
		)
		void shouldMemoiseResultAcrossCalls() {
			final EvitaRequest request = createRequest(
				query(
					collection("product"),
					require(priceHistogram(20))
				)
			);

			final boolean first = request.isPriceHistogramRequested();
			final boolean second = request.isPriceHistogramRequested();

			assertTrue(first);
			assertEquals(first, second);
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

	@Nested
	@DisplayName("Duplicate content requirements")
	@Tag(REQUIRE)
	@Tag(REFERENCE)
	class DuplicateContentRequirementTest {

		/**
		 * Builds a request fetching the passed content requirements
		 * from the `product` collection.
		 */
		@Nonnull
		private EvitaRequest createFetchRequest(
			@Nonnull EntityContentRequire... requirements
		) {
			return createRequest(
				query(
					collection("product"),
					require(entityFetch(requirements))
				)
			);
		}

		/**
		 * Returns the single {@link AttributeContent} requirement held
		 * directly by the passed fetch container.
		 */
		@Nonnull
		private AttributeContent attributeContentOf(
			@Nonnull EntityFetch entityFetch
		) {
			AttributeContent found = null;
			for (final EntityContentRequire requirement :
				entityFetch.getRequirements()) {
				if (requirement instanceof AttributeContent attributeContent) {
					assertNull(
						found,
						"More than one attributeContent in " + entityFetch
					);
					found = attributeContent;
				}
			}
			assertNotNull(
				found, "No attributeContent in " + entityFetch
			);
			return found;
		}

		/**
		 * Builds a `referenceContent` carrying an instance name
		 * (alias), the shape produced by the GraphQL API.
		 */
		@Nonnull
		private ReferenceContent namedReferenceContent(
			@Nonnull String instanceName,
			@Nonnull String referenceName,
			@Nonnull EntityFetch entityRequirement
		) {
			return new ReferenceContent(
				instanceName,
				ManagedReferencesBehaviour.ANY,
				new String[]{referenceName},
				new RequireConstraint[]{entityRequirement},
				new Constraint<?>[0]
			);
		}

		/**
		 * Verifies two reference contents for one reference are
		 * merged into a single requirement with both bodies.
		 */
		@Test
		@DisplayName("merges two referenceContent of one reference")
		void shouldMergeTwoReferenceContentsOfSameReference() {
			final EvitaRequest request = createFetchRequest(
				referenceContent(
					"category",
					entityFetch(attributeContent("code"))
				),
				referenceContent(
					"category",
					entityFetch(attributeContent("name"))
				)
			);

			final Map<String, RequirementContext> referenceFetch =
				request.getReferenceEntityFetch();
			assertEquals(1, referenceFetch.size());
			final RequirementContext categoryContext =
				referenceFetch.get("category");
			assertNotNull(categoryContext);
			final EntityFetch categoryFetch =
				categoryContext.entityFetch();
			assertNotNull(categoryFetch);
			assertEquals(
				Set.of("code", "name"),
				attributeContentOf(categoryFetch)
					.getAttributeNamesAsSet()
			);
			assertNull(
				request.getDefaultReferenceRequirement()
			);
		}

		/**
		 * Verifies two default reference contents collapse into a
		 * single default requirement.
		 */
		@Test
		@DisplayName("merges two referenceContentAll")
		void shouldMergeTwoDefaultReferenceContents() {
			final EvitaRequest request = createFetchRequest(
				referenceContentAll(),
				referenceContentAll()
			);

			assertNotNull(
				request.getDefaultReferenceRequirement()
			);
			assertTrue(
				request.getReferenceEntityFetch().isEmpty()
			);
			assertTrue(
				request.isRequiresEntityReferences()
			);
		}

		/**
		 * Verifies a default reference content and a reference
		 * specific one stay two independent requirements.
		 */
		@Test
		@DisplayName("keeps default beside name specific")
		void shouldKeepDefaultBesideNameSpecific() {
			final EvitaRequest request = createFetchRequest(
				referenceContentAll(),
				referenceContent(
					"category",
					entityFetch(attributeContent("code"))
				)
			);

			assertNotNull(
				request.getDefaultReferenceRequirement()
			);
			final Map<String, RequirementContext> referenceFetch =
				request.getReferenceEntityFetch();
			assertEquals(1, referenceFetch.size());
			final RequirementContext categoryContext =
				referenceFetch.get("category");
			assertNotNull(categoryContext);
			final EntityFetch categoryFetch =
				categoryContext.entityFetch();
			assertNotNull(categoryFetch);
			assertEquals(
				Set.of("code"),
				attributeContentOf(categoryFetch)
					.getAttributeNamesAsSet()
			);
		}

		/**
		 * Verifies two occurrences of one reference content alias
		 * merge into a single named requirement.
		 */
		@Test
		@DisplayName("merges two occurrences of one alias")
		void shouldMergeTwoOccurrencesOfOneAlias() {
			final EvitaRequest request = createFetchRequest(
				namedReferenceContent(
					"alias", "category",
					entityFetch(attributeContent("code"))
				),
				namedReferenceContent(
					"alias", "category",
					entityFetch(attributeContent("name"))
				)
			);

			final Map<ReferenceContentKey, RequirementContext> namedFetch =
				request.getNamedReferenceEntityFetch();
			assertEquals(1, namedFetch.size());
			final RequirementContext aliasContext = namedFetch.get(
				new ReferenceContentKey("alias", "category")
			);
			assertNotNull(aliasContext);
			final EntityFetch aliasFetch = aliasContext.entityFetch();
			assertNotNull(aliasFetch);
			assertEquals(
				Set.of("code", "name"),
				attributeContentOf(aliasFetch)
					.getAttributeNamesAsSet()
			);
		}

		/**
		 * Verifies two distinct aliases of one reference stay two
		 * independent named requirements.
		 */
		@Test
		@DisplayName("keeps two distinct aliases apart")
		void shouldKeepTwoDistinctAliasesApart() {
			final EvitaRequest request = createFetchRequest(
				namedReferenceContent(
					"first", "category",
					entityFetch(attributeContent("code"))
				),
				namedReferenceContent(
					"second", "category",
					entityFetch(attributeContent("name"))
				)
			);

			final Map<ReferenceContentKey, RequirementContext> namedFetch =
				request.getNamedReferenceEntityFetch();
			assertEquals(2, namedFetch.size());
			assertNotNull(
				namedFetch.get(
					new ReferenceContentKey("first", "category")
				)
			);
			assertNotNull(
				namedFetch.get(
					new ReferenceContentKey("second", "category")
				)
			);
		}

		/**
		 * Verifies two attribute contents merge and that the "all"
		 * variant absorbs the specific one.
		 */
		@Test
		@DisplayName("merges attributeContent with all variant")
		void shouldMergeAttributeContentWithAllVariant() {
			final EvitaRequest request = createFetchRequest(
				entityFetchAllContentAnd(
					attributeContent("code")
				)
			);

			assertTrue(
				request.isRequiresEntityAttributes()
			);
			assertTrue(
				request.getEntityAttributeSet().isEmpty()
			);
		}

		/**
		 * Verifies two associated data contents merge and that the
		 * "all" variant absorbs the specific one.
		 */
		@Test
		@DisplayName("merges associatedDataContent with all variant")
		void shouldMergeAssociatedDataContentWithAllVariant() {
			final EvitaRequest request = createFetchRequest(
				associatedDataContent("labels"),
				associatedDataContentAll()
			);

			assertTrue(
				request.isRequiresEntityAssociatedData()
			);
			assertTrue(
				request.getEntityAssociatedDataSet().isEmpty()
			);
		}

		/**
		 * Verifies two price contents merge into the richer fetch
		 * mode while keeping the union of additional price lists.
		 */
		@Test
		@DisplayName("merges priceContent into richer mode")
		@Tag(PRICE)
		void shouldMergePriceContentIntoRicherMode() {
			final EvitaRequest request = createFetchRequest(
				priceContent(
					RESPECTING_FILTER, "basic"
				),
				priceContentAll()
			);

			assertEquals(
				ALL, request.getRequiresEntityPrices()
			);
			assertArrayEquals(
				new String[]{"basic"},
				request.getFetchesAdditionalPriceLists()
			);
		}

		/**
		 * Verifies two identical accompanying price requirements
		 * collapse into a single calculated price.
		 */
		@Test
		@DisplayName("merges two identical accompanyingPriceContent")
		@Tag(PRICE)
		void shouldMergeTwoIdenticalAccompanyingPriceContents() {
			final EvitaRequest request = createFetchRequest(
				priceContentAll(),
				accompanyingPriceContent(
					"reference", "a", "b"
				),
				accompanyingPriceContent(
					"reference", "a", "b"
				)
			);

			final AccompanyingPrice[] accompanyingPrices =
				request.getAccompanyingPrices();
			assertEquals(1, accompanyingPrices.length);
			assertEquals(
				"reference",
				accompanyingPrices[0].priceName()
			);
			assertArrayEquals(
				new String[]{"a", "b"},
				accompanyingPrices[0].priceListPriority()
			);
		}

		/**
		 * Verifies two differently named accompanying price
		 * requirements both survive the reduction.
		 */
		@Test
		@DisplayName("keeps two named accompanyingPriceContent apart")
		@Tag(PRICE)
		void shouldKeepTwoNamedAccompanyingPriceContentsApart() {
			final EvitaRequest request = createFetchRequest(
				priceContentAll(),
				accompanyingPriceContent("first", "a"),
				accompanyingPriceContent("second", "b")
			);

			final AccompanyingPrice[] accompanyingPrices =
				request.getAccompanyingPrices();
			assertEquals(2, accompanyingPrices.length);
			final Set<String> priceNames = new HashSet<>();
			for (final AccompanyingPrice price : accompanyingPrices) {
				priceNames.add(price.priceName());
			}
			assertEquals(
				Set.of("first", "second"), priceNames
			);
		}

		/**
		 * Verifies one accompanying price requested from two
		 * different price list sequences is refused.
		 */
		@Test
		@DisplayName("refuses conflicting accompanying price lists")
		@Tag(PRICE)
		void shouldRefuseConflictingAccompanyingPriceLists() {
			final EvitaRequest request = createFetchRequest(
				priceContentAll(),
				accompanyingPriceContent(
					"reference", "a"
				),
				accompanyingPriceContent(
					"reference", "b"
				)
			);

			final EvitaInvalidUsageException exception = assertThrows(
				EvitaInvalidUsageException.class,
				request::getAccompanyingPrices
			);
			assertTrue(
				exception.getMessage().contains("reference"),
				exception.getMessage()
			);
		}

		/**
		 * Verifies two data in locales requirements merge and that
		 * the "all" variant absorbs the specific one.
		 */
		@Test
		@DisplayName("merges dataInLocales with all variant")
		void shouldMergeDataInLocalesWithAllVariant() {
			final EvitaRequest request = createFetchRequest(
				dataInLocales(Locale.GERMAN),
				dataInLocalesAll()
			);

			final Set<Locale> requiredLocales =
				request.getRequiredLocales();
			assertNotNull(requiredLocales);
			// empty set means all locales - see DataInLocales
			assertTrue(requiredLocales.isEmpty());
		}

		/**
		 * Verifies two hierarchy contents merge instead of making
		 * the parent lookup fail.
		 */
		@Test
		@DisplayName("merges two hierarchyContent")
		@Tag(HIERARCHY)
		void shouldMergeTwoHierarchyContents() {
			final EvitaRequest request = createFetchRequest(
				hierarchyContent(),
				hierarchyContent()
			);

			assertTrue(request.isRequiresParent());
			assertNotNull(request.getHierarchyContent());
		}

		/**
		 * Verifies two reference contents of one reference that
		 * disagree on the filter are refused.
		 */
		@Test
		@DisplayName("refuses conflicting reference filters")
		void shouldRefuseConflictingReferenceFilters() {
			final EvitaRequest request = createFetchRequest(
				referenceContent(
					"category",
					filterBy(entityPrimaryKeyInSet(1)),
					entityFetch(attributeContent("code"))
				),
				referenceContent(
					"category",
					filterBy(entityPrimaryKeyInSet(2)),
					entityFetch(attributeContent("name"))
				)
			);

			assertThrows(
				EvitaInvalidUsageException.class,
				request::getEntityRequirement
			);
		}

		/**
		 * Verifies two reference contents whose reference name sets
		 * overlap without being equal are refused.
		 */
		@Test
		@DisplayName("refuses overlapping reference name sets")
		void shouldRefuseOverlappingReferenceNameSets() {
			final EvitaRequest request = createFetchRequest(
				referenceContent("brand", "category"),
				referenceContent("category", "parameter")
			);

			final EvitaInvalidUsageException exception = assertThrows(
				EvitaInvalidUsageException.class,
				request::getReferenceEntityFetch
			);
			assertTrue(
				exception.getMessage().contains("category"),
				exception.getMessage()
			);
			assertFalse(
				exception.getMessage().contains("null"),
				exception.getMessage()
			);
		}

		/**
		 * Verifies the fetch found in the query is handed over
		 * untouched when it holds no duplicates.
		 */
		@Test
		@DisplayName("keeps fetch identity without duplicates")
		void shouldKeepFetchIdentityWithoutDuplicates() {
			final EntityFetch entityFetch = entityFetch(
				attributeContent("code"),
				referenceContent("category")
			);
			final EvitaRequest request = createRequest(
				query(
					collection("product"),
					require(entityFetch)
				)
			);

			assertSame(
				entityFetch, request.getEntityRequirement()
			);
		}

		/**
		 * Verifies the reduced fetch replaces the original one while
		 * the query itself keeps what the client sent.
		 */
		@Test
		@DisplayName("reduces fetch but keeps query verbatim")
		void shouldReduceFetchButKeepQueryVerbatim() {
			final EntityFetch entityFetch = entityFetch(
				attributeContent("code"),
				attributeContent("name")
			);
			final EvitaRequest request = createRequest(
				query(
					collection("product"),
					require(entityFetch)
				)
			);

			final EntityFetch reduced =
				request.getEntityRequirement();
			assertNotNull(reduced);
			assertNotSame(entityFetch, reduced);
			assertEquals(1, reduced.getRequirements().length);
			assertEquals(
				Set.of("code", "name"),
				attributeContentOf(reduced)
					.getAttributeNamesAsSet()
			);
			assertTrue(
				request.isRequiresEntityAttributes()
			);
			assertEquals(
				Set.of("code", "name"),
				request.getEntityAttributeSet()
			);

			// the query still carries the two original requirements
			final EntityFetch fetchInQuery =
				QueryUtils.findRequire(
					request.getQuery(),
					EntityFetch.class,
					SeparateEntityContentRequireContainer.class
				);
			assertSame(entityFetch, fetchInQuery);
			assertEquals(
				2, entityFetch.getRequirements().length
			);
		}

		/**
		 * Verifies a derived request reduces the requirements it is
		 * handed.
		 */
		@Test
		@DisplayName("reduces requirements of a derived request")
		void shouldReduceRequirementsOfDerivedRequest() {
			final EvitaRequest request = createRequest(
				query(collection("product"))
			);

			final EvitaRequest derived = request.deriveCopyWith(
				"category",
				entityFetch(
					attributeContent("code"),
					attributeContent("name")
				)
			);

			final EntityFetch derivedFetch =
				derived.getEntityRequirement();
			assertNotNull(derivedFetch);
			assertEquals(
				1, derivedFetch.getRequirements().length
			);
			assertTrue(
				derived.isRequiresEntityAttributes()
			);
			assertEquals(
				Set.of("code", "name"),
				derived.getEntityAttributeSet()
			);
		}
		/**
		 * Verifies the fetch is handed over untouched when a kind
		 * repeats but nothing combines.
		 */
		@Test
		@DisplayName("keeps fetch identity when a repeated kind does not combine")
		void shouldKeepFetchIdentityWhenRepeatedKindIsNotCombinable() {
			final EntityFetch entityFetch = entityFetch(
				referenceContent("category"),
				referenceContent("brand")
			);
			final EvitaRequest request = createRequest(
				query(
					collection("product"),
					require(entityFetch)
				)
			);

			assertSame(
				entityFetch, request.getEntityRequirement()
			);
		}

		/**
		 * Verifies the reduction is single level - a duplicate
		 * nested inside a referenceContent survives in the request
		 * and is folded only when the sub-request is derived.
		 */
		@Test
		@DisplayName("reduces a nested fetch only when the sub-request is derived")
		void shouldReduceNestedFetchOnlyWhenSubRequestIsDerived() {
			final EvitaRequest request = createFetchRequest(
				referenceContent(
					"category",
					entityFetch(
						attributeContent("code"),
						attributeContent("name")
					)
				)
			);

			final RequirementContext categoryContext =
				request.getReferenceEntityFetch().get("category");
			assertNotNull(categoryContext);
			final EntityFetch nestedFetch =
				categoryContext.entityFetch();
			assertNotNull(nestedFetch);
			// the top level holds a single referenceContent, so no fold happens and the nested fetch is untouched
			assertEquals(
				2, nestedFetch.getRequirements().length
			);

			final EvitaRequest derived = request.deriveCopyWith(
				"category", nestedFetch
			);
			final EntityFetch derivedFetch =
				derived.getEntityRequirement();
			assertNotNull(derivedFetch);
			assertEquals(
				1, derivedFetch.getRequirements().length
			);
			assertEquals(
				Set.of("code", "name"),
				attributeContentOf(derivedFetch)
					.getAttributeNamesAsSet()
			);
		}

		/**
		 * Verifies a conflict two levels down surfaces when the
		 * sub-request is derived, not when the request is built.
		 */
		@Test
		@DisplayName("refuses conflicting nested reference filters when derived")
		void shouldRefuseConflictingNestedReferenceFiltersWhenDerived() {
			final EvitaRequest request = createFetchRequest(
				referenceContent(
					"category",
					entityFetch(
						referenceContent(
							"brand",
							filterBy(entityPrimaryKeyInSet(1))
						),
						referenceContent(
							"brand",
							filterBy(entityPrimaryKeyInSet(2))
						)
					)
				)
			);

			final RequirementContext categoryContext =
				request.getReferenceEntityFetch().get("category");
			assertNotNull(categoryContext);
			final EntityFetch nestedFetch =
				categoryContext.entityFetch();
			assertNotNull(nestedFetch);

			assertThrows(
				EvitaInvalidUsageException.class,
				() -> request.deriveCopyWith("category", nestedFetch)
			);
		}

		/**
		 * Verifies a reference named twice inside a single
		 * requirement claims its slot once.
		 */
		@Test
		@DisplayName("accepts a reference name repeated within one requirement")
		void shouldAcceptReferenceNameRepeatedWithinOneRequirement() {
			final EvitaRequest request = createFetchRequest(
				referenceContent("brand", "brand")
			);

			final Map<String, RequirementContext> referenceEntityFetch =
				request.getReferenceEntityFetch();
			assertEquals(1, referenceEntityFetch.size());
			assertNotNull(referenceEntityFetch.get("brand"));
		}

		/**
		 * Verifies a stop constraint written on one side only does
		 * not narrow the parent chain the other side asked for.
		 */
		@Test
		@DisplayName("drops a hierarchy stop constraint present on a single side only")
		@Tag(HIERARCHY)
		void shouldDropHierarchyStopAtPresentOnSingleSideOnly() {
			final EvitaRequest request = createFetchRequest(
				hierarchyContent(),
				hierarchyContent(stopAt(level(2)))
			);

			final HierarchyContent hierarchyContent =
				request.getHierarchyContent();
			assertNotNull(hierarchyContent);
			assertTrue(
				hierarchyContent.getStopAt().isEmpty()
			);
		}

		/**
		 * Verifies a refused request fails the same way however
		 * many times it is asked.
		 */
		@Test
		@DisplayName("refuses an aborted request the same way on every call")
		void shouldRefuseAbortedRequestTheSameWayOnEveryCall() {
			final EvitaRequest request = createFetchRequest(
				namedReferenceContent(
					"alias", "category",
					entityFetch(attributeContent("code"))
				),
				referenceContent("brand", "category"),
				referenceContent("category", "parameter")
			);

			final EvitaInvalidUsageException firstAttempt = assertThrows(
				EvitaInvalidUsageException.class,
				request::getReferenceEntityFetch
			);
			assertTrue(
				firstAttempt.getMessage().contains("category"),
				firstAttempt.getMessage()
			);
			final EvitaInvalidUsageException secondAttempt = assertThrows(
				EvitaInvalidUsageException.class,
				request::getReferenceEntityFetch
			);
			assertEquals(firstAttempt.getMessage(), secondAttempt.getMessage());
		}
	}

}