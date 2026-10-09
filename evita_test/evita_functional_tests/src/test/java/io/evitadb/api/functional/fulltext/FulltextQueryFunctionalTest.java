/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

package io.evitadb.api.functional.fulltext;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.exception.AttributeNotFoundException;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.OrderConstraint;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.order.OrderBy;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.query.require.DebugMode;
import io.evitadb.api.query.require.Page;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.exception.AttributeNotSearchableException;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.fulltext.FulltextIndex;
import io.evitadb.index.fulltext.FulltextPhaseOneScorer;
import io.evitadb.index.fulltext.FulltextPhaseOneScorer.Expansion;
import io.evitadb.index.fulltext.FulltextPhaseOneScorer.Result;
import io.evitadb.index.fulltext.analysis.AnalyzedTerm;
import io.evitadb.index.fulltext.analysis.FulltextAnalyzer;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.EvitaParameterResolver;
import io.evitadb.utils.PlanPreference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.IntStream;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.FILTER;
import static io.evitadb.test.TestTags.FULLTEXT;
import static io.evitadb.test.TestTags.ORDER;
import static io.evitadb.test.TestTags.QUERY;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the query side of fulltext - the `textMatches` filter and the `relevance()` ordering - end to end through the
 * real planner, against a small catalog of products whose localized texts are searchable.
 *
 * ## The fixture
 *
 * `product` is searchable in English and Czech through four kinds of field: the entity attribute `name` (searchable in
 * both scopes), the entity attribute `description` (live scope only), the catalog attribute `headline` (both scopes)
 * and the attribute `label` of the `categories` reference (live scope only). `code` is filterable but not searchable.
 * `category` is a tree - `Drinks` (1) with the child `Tea` (2), and `Bakery` (3) - and declares nothing searchable.
 *
 * | pk | name (en) | description (en) | headline | categories (label) |
 * |---|---|---|---|---|
 * | 1 | Green tea | Fresh leaves picked in the mountains | | 2 |
 * | 2 | Black tea | Strong and dark, for breakfast | Bestseller | 2 |
 * | 3 | Green coffee beans | Raw beans for home roasting | | 1 |
 * | 4 | Herbal blend | a long text mentioning green tea once | | 2 |
 * | 5 | Espresso coffee | Dark roast | Bestseller | 1 |
 * | 6 | Tea | Plain tea | | 2 |
 * | 7 | Lemon cake | Baked fresh daily | | 3 (`green choice`) |
 * | 8 | Porcelain cup | A cup for green tea | | |
 * | 9 | Teapot | Cast iron teapot | | |
 * | 10 | Matcha green tea powder | Stone ground leaves | | 2 |
 * | 11 | Green tea limited edition | Sold out | | archived |
 * | 12 | Coffee grinder | Burr grinder | | archived |
 * | 13 | Spacious apartment | Two rooms | | |
 *
 * Czech: every product has a Czech name; 1 (`… skladem`), 2 (`… není skladem`) and 13 (name `Prostorný byt`,
 * `… ještě volný`) have a Czech description too - `není`, `ještě` and `být` are stop words of the Czech chain, and
 * `byt` ("apartment") is the real word `být` folds into.
 *
 * `brand` (searchable `name` in both scopes): 1 `Green Valley Farms`, 2 `Green`, 3 `Mountain Roasters`. The
 * `brands` reference of a product: 1 and 2 → brand 1, 3 and 7 → brand 2, 5 → brand 3, 6 → brands 1 and 2, archived
 * 11 → brand 2. The primary keys are chosen so that scoring owners against the brands' postings - owner 2 read as
 * brand 2 - would produce an order different both from the right one and from no ordering at all.
 *
 * The `placements` reference of a product points at a category and is grouped by a brand, so that `groupHaving`
 * searches the brands: 1 → category 1 in brand 3, 4 → category 2 in brand 2, 8 → category 2 in brand 1 and
 * category 3 in brand 2, 9 → category 3 in brand 1.
 *
 * Expected rankings are not hand-coded where the impact arithmetic decides them: they are computed by running
 * {@link FulltextPhaseOneScorer} directly over the postings of the catalog's own fulltext index (see
 * {@link #expectedRanking}), so the tests pin the plumbing - analysis, field resolution, the accessor lookup, the
 * sorter, paging, prefetch - rather than restate the scorer's numbers. Where an order follows from BM25 alone (a
 * shorter value with the term ranks higher), it is asserted by hand as well.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Fulltext query: textMatches and relevance()")
@ExtendWith(EvitaParameterResolver.class)
@Tag(CONTRACT)
@Tag(QUERY)
@Tag(FILTER)
@Tag(ORDER)
@Tag(FULLTEXT)
public class FulltextQueryFunctionalTest {
	private static final String FULLTEXT_DATASET = "fulltextQuery";
	private static final String PRODUCT = "product";
	private static final String CATEGORY = "category";
	private static final String BRAND = "brand";
	private static final String REFERENCE_BRANDS = "brands";
	private static final String REFERENCE_CATEGORIES = "categories";
	private static final String REFERENCE_PLACEMENTS = "placements";
	private static final String ATTRIBUTE_NAME = "name";
	private static final String ATTRIBUTE_DESCRIPTION = "description";
	private static final String ATTRIBUTE_HEADLINE = "headline";
	private static final String ATTRIBUTE_LABEL = "label";
	private static final String ATTRIBUTE_CODE = "code";
	private static final Locale CZECH = Locale.forLanguageTag("cs");
	private static final int[] LIVE_PRODUCTS = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 13};
	private static final int[] ALL_PRODUCTS = IntStream.rangeClosed(1, 13).toArray();
	private static final int[] ARCHIVED_PRODUCTS = {11, 12};

	/**
	 * One product of the fixture.
	 *
	 * @param primaryKey    primary key
	 * @param nameEn        English name
	 * @param nameCs        Czech name
	 * @param description   English description
	 * @param headline      English headline, or null
	 * @param category      the referenced category, or null
	 * @param categoryLabel the English label of the category reference, or null
	 */
	private record Product(
		int primaryKey,
		@Nonnull String nameEn,
		@Nonnull String nameCs,
		@Nonnull String description,
		@Nullable String headline,
		@Nullable Integer category,
		@Nullable String categoryLabel
	) {
	}

	/**
	 * The products of the fixture, see the class documentation.
	 */
	private static final Product[] PRODUCTS = {
		new Product(1, "Green tea", "Zelený čaj", "Fresh leaves picked in the mountains", null, 2, "loose leaf"),
		new Product(2, "Black tea", "Černý čaj", "Strong and dark, for breakfast", "Bestseller", 2, "loose leaf"),
		new Product(3, "Green coffee beans", "Zelená kávová zrna", "Raw beans for home roasting", null, 1, null),
		new Product(
			4, "Herbal blend", "Bylinná směs",
			"Mint and lemon balm with chamomile flowers, rose hips and dried apple pieces, a caffeine free " +
				"alternative to green tea and black coffee for quiet evenings",
			null, 2, null
		),
		new Product(5, "Espresso coffee", "Kávové espreso", "Dark roast", "Bestseller", 1, null),
		new Product(6, "Tea", "Čaj", "Plain tea", null, 2, null),
		new Product(7, "Lemon cake", "Citronový koláč", "Baked fresh daily", null, 3, "green choice"),
		new Product(8, "Porcelain cup", "Porcelánový šálek", "A cup for green tea", null, null, null),
		new Product(9, "Teapot", "Konvice", "Cast iron teapot", null, null, null),
		new Product(10, "Matcha green tea powder", "Matcha prášek", "Stone ground leaves", null, 2, null),
		new Product(11, "Green tea limited edition", "Zelený čaj limitovaná edice", "Sold out", null, null, null),
		new Product(12, "Coffee grinder", "Mlýnek na kávu", "Burr grinder", null, null, null),
		new Product(13, "Spacious apartment", "Prostorný byt", "Two rooms", null, null, null)
	};
	/**
	 * Czech descriptions of the products that have one, see the class documentation.
	 */
	private static final Map<Integer, String> CZECH_DESCRIPTIONS = Map.of(
		1, "Čerstvé lístky, skladem",
		2, "Silný a tmavý, není skladem",
		13, "Dva pokoje, ještě volný"
	);
	/**
	 * English names of the brands, by primary key.
	 */
	private static final Map<Integer, String> BRAND_NAMES = Map.of(
		1, "Green Valley Farms",
		2, "Green",
		3, "Mountain Roasters"
	);
	/**
	 * The brands each product references, see the class documentation.
	 */
	private static final Map<Integer, int[]> PRODUCT_BRANDS = Map.of(
		1, new int[]{1},
		2, new int[]{1},
		3, new int[]{2},
		5, new int[]{3},
		6, new int[]{1, 2},
		7, new int[]{2},
		11, new int[]{2}
	);

	/**
	 * The placements of each product as (category, brand group) pairs, see the class documentation.
	 */
	private static final Map<Integer, int[][]> PRODUCT_PLACEMENTS = Map.of(
		1, new int[][]{{1, 3}},
		4, new int[][]{{2, 2}},
		8, new int[][]{{2, 1}, {3, 2}},
		9, new int[][]{{3, 1}}
	);

	/**
	 * Builds the fixture described on the class.
	 *
	 * @param evita the evita instance
	 */
	@DataSet(value = FULLTEXT_DATASET, destroyAfterClass = true)
	void setUpFulltextDataSet(@Nonnull Evita evita) {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.getCatalogSchema()
					.openForWrite()
					.withAttribute(
						ATTRIBUTE_HEADLINE, String.class,
						whichIs -> whichIs.localized().nullable().searchableInScope(Scope.LIVE, Scope.ARCHIVED)
					)
					.updateVia(session);
				session.defineEntitySchema(CATEGORY)
					.withoutGeneratedPrimaryKey()
					.withHierarchy()
					.withLocale(Locale.ENGLISH, CZECH)
					.withAttribute(ATTRIBUTE_NAME, String.class, whichIs -> whichIs.localized().filterable().nullable())
					.updateVia(session);
				session.defineEntitySchema(BRAND)
					.withoutGeneratedPrimaryKey()
					.withLocale(Locale.ENGLISH, CZECH)
					.withAttribute(
						ATTRIBUTE_NAME, String.class,
						whichIs -> whichIs.localized().nullable().searchableInScope(Scope.LIVE, Scope.ARCHIVED)
					)
					.updateVia(session);
				session.defineEntitySchema(PRODUCT)
					.withoutGeneratedPrimaryKey()
					.withLocale(Locale.ENGLISH, CZECH)
					.withGlobalAttribute(ATTRIBUTE_HEADLINE)
					.withAttribute(
						ATTRIBUTE_NAME, String.class,
						whichIs -> whichIs.localized().nullable().searchableInScope(Scope.LIVE, Scope.ARCHIVED)
					)
					.withAttribute(
						ATTRIBUTE_DESCRIPTION, String.class, whichIs -> whichIs.localized().nullable().searchable()
					)
					.withAttribute(
						ATTRIBUTE_CODE, String.class,
						whichIs -> whichIs.filterableInScope(Scope.LIVE, Scope.ARCHIVED)
							.sortableInScope(Scope.LIVE, Scope.ARCHIVED)
					)
					.withReferenceToEntity(
						REFERENCE_CATEGORIES, CATEGORY, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs
							.indexedForFilteringAndPartitioning()
							.withAttribute(
								ATTRIBUTE_LABEL, String.class, thatIs -> thatIs.localized().nullable().searchable()
							)
					)
					.withReferenceToEntity(
						REFERENCE_BRANDS, BRAND, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs.indexedForFilteringAndPartitioningInScope(Scope.LIVE, Scope.ARCHIVED)
					)
					.withReferenceToEntity(
						REFERENCE_PLACEMENTS, CATEGORY, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs
							.indexedForFilteringAndPartitioning()
							.indexedWithComponents(
								ReferenceIndexedComponents.REFERENCED_ENTITY,
								ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
							)
							.withGroupTypeRelatedToEntity(BRAND)
					)
					.updateVia(session);
				for (Map.Entry<Integer, String> brand : BRAND_NAMES.entrySet()) {
					session.upsertEntity(
						session.createNewEntity(BRAND, brand.getKey())
							.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, brand.getValue())
					);
				}

				session.upsertEntity(
					session.createNewEntity(CATEGORY, 1).setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "Drinks")
				);
				session.upsertEntity(
					session.createNewEntity(CATEGORY, 2)
						.setParent(1)
						.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "Tea")
				);
				session.upsertEntity(
					session.createNewEntity(CATEGORY, 3).setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "Bakery")
				);

				for (Product product : PRODUCTS) {
					final var builder = session.createNewEntity(PRODUCT, product.primaryKey())
						.setAttribute(ATTRIBUTE_CODE, "P" + (100 + product.primaryKey()))
						.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, product.nameEn())
						.setAttribute(ATTRIBUTE_NAME, CZECH, product.nameCs())
						.setAttribute(ATTRIBUTE_DESCRIPTION, Locale.ENGLISH, product.description());
					final String czechDescription = CZECH_DESCRIPTIONS.get(product.primaryKey());
					if (czechDescription != null) {
						builder.setAttribute(ATTRIBUTE_DESCRIPTION, CZECH, czechDescription);
					}
					for (int brand : PRODUCT_BRANDS.getOrDefault(product.primaryKey(), new int[0])) {
						builder.setReference(REFERENCE_BRANDS, brand);
					}
					for (int[] placement : PRODUCT_PLACEMENTS.getOrDefault(product.primaryKey(), new int[0][])) {
						builder.setReference(
							REFERENCE_PLACEMENTS, placement[0], whichIs -> whichIs.setGroup(placement[1])
						);
					}
					if (product.headline() != null) {
						builder.setAttribute(ATTRIBUTE_HEADLINE, Locale.ENGLISH, product.headline());
						builder.setAttribute(ATTRIBUTE_HEADLINE, CZECH, "Nejprodávanější");
					}
					if (product.category() != null) {
						builder.setReference(
							REFERENCE_CATEGORIES, product.category(),
							whichIs -> {
								if (product.categoryLabel() != null) {
									whichIs.setAttribute(ATTRIBUTE_LABEL, Locale.ENGLISH, product.categoryLabel());
								}
							}
						);
					}
					session.upsertEntity(builder);
				}
				for (int archivedPk : ARCHIVED_PRODUCTS) {
					session.archiveEntity(PRODUCT, archivedPk);
				}
			}
		);
	}

	@DisplayName("Match set")
	@Nested
	class MatchSet {

		@DisplayName("Should match the entities containing every word in some searchable field")
		@UseDataSet(FULLTEXT_DATASET)
		@Test
		void shouldMatchEntitiesContainingEveryWord(Evita evita) {
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					// 1 and 10 by name, 4 and 8 by description; 3 has `green` only, 2 and 6 `tea` only
					assertMatches(session, new int[]{1, 4, 8, 10}, textMatches("green tea"));
					// a word order or letter case do not matter
					assertMatches(session, new int[]{1, 4, 8, 10}, textMatches("TEA Green"));
					return null;
				}
			);
		}

		@DisplayName("Should search the attribute of a reference and a global attribute by default")
		@UseDataSet(FULLTEXT_DATASET)
		@Test
		void shouldSearchReferenceAndGlobalAttributesByDefault(Evita evita) {
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					// 7 holds `green` only in the label of its category reference
					assertMatches(session, new int[]{1, 3, 4, 7, 8, 10}, textMatches("green"));
					assertMatches(session, new int[]{2, 5}, textMatches("bestseller"));
					return null;
				}
			);
		}

		@DisplayName("Should restrict the search to the fields named by inField")
		@UseDataSet(FULLTEXT_DATASET)
		@Test
		void shouldRestrictTheSearchToInFields(Evita evita) {
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					assertMatches(session, new int[]{1, 10}, textMatches("green tea", inField(ATTRIBUTE_NAME)));
					assertMatches(session, new int[]{1, 3, 10}, textMatches("green", inField(ATTRIBUTE_NAME)));
					assertMatches(
						session, new int[]{1, 3, 4, 8, 10},
						textMatches("green", inField(ATTRIBUTE_NAME), inField(ATTRIBUTE_DESCRIPTION))
					);
					// a duplicate field is searched once
					assertMatches(
						session, new int[]{1, 10},
						textMatches("green tea", inField(ATTRIBUTE_NAME), inField(ATTRIBUTE_NAME))
					);
					assertMatches(session, new int[]{2, 5}, textMatches("bestseller", inField(ATTRIBUTE_HEADLINE)));
					assertMatches(session, new int[0], textMatches("bestseller", inField(ATTRIBUTE_NAME)));
					return null;
				}
			);
		}

		@DisplayName("Should match nothing when a word occurs nowhere or the text has only stop words")
		@UseDataSet(FULLTEXT_DATASET)
		@Test
		void shouldMatchNothingForAnUnknownWordOrStopWordsOnly(Evita evita) {
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					assertMatches(session, new int[0], textMatches("green unicorn"));
					assertMatches(session, new int[0], textMatches("the and of"));
					return null;
				}
			);
		}

		@DisplayName("Should combine with other constraints and with the scopes of the query")
		@UseDataSet(FULLTEXT_DATASET)
		@Test
		void shouldCombineWithOtherConstraintsAndScopes(Evita evita) {
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					assertMatches(
						session, new int[]{1, 10},
						textMatches("green tea"), attributeInSet(ATTRIBUTE_CODE, "P101", "P110", "P103")
					);
					// 7 holds `green` only in its live category label, 11 in its archived name
					assertMatches(
						session, new int[]{1, 3, 4, 7, 8, 10, 11},
						textMatches("green"), scope(Scope.LIVE, Scope.ARCHIVED)
					);
					assertMatches(session, new int[]{11}, textMatches("green"), scope(Scope.ARCHIVED));
					// the description is not searchable in the archive, so only the name of 11 is
					assertMatches(session, new int[0], textMatches("sold"), scope(Scope.ARCHIVED));
					assertMatches(
						session, new int[]{11, 12},
						or(textMatches("limited"), textMatches("grinder")), scope(Scope.ARCHIVED)
					);
					assertMatches(
						session, new int[]{1, 10, 11},
						inScope(Scope.LIVE, textMatches("green tea", inField(ATTRIBUTE_NAME))),
						inScope(Scope.ARCHIVED, textMatches("limited")),
						scope(Scope.LIVE, Scope.ARCHIVED)
					);
					return null;
				}
			);
		}

		@DisplayName("Should find an accented Czech value by a query typed without diacritics")
		@UseDataSet(FULLTEXT_DATASET)
		@Test
		void shouldFindAccentedValueByQueryWithoutDiacritics(Evita evita) {
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final int[] accented = queryPks(
						session, new FilterConstraint[]{entityLocaleEquals(CZECH), textMatches("zelený čaj")}, null
					);
					final int[] folded = queryPks(
						session, new FilterConstraint[]{entityLocaleEquals(CZECH), textMatches("zeleny caj")}, null
					);
					assertTrue(Arrays.stream(accented).anyMatch(it -> it == 1), "`zelený čaj` must find 1");
					assertArrayEquals(accented, folded);
					return null;
				}
			);
		}

		@DisplayName("Should answer a reduced target index like a plain conjunction")
		@UseDataSet(FULLTEXT_DATASET)
		@Test
		void shouldAnswerReducedIndexLikePlainConjunction(Evita evita) {
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					// products within the `Tea` category are 1, 2, 4, 6 and 10
					final FilterConstraint hierarchy = hierarchyWithin(REFERENCE_CATEGORIES, entityPrimaryKeyInSet(2));
					final int[] expected = {1, 4, 10};
					final int[] answered = queryPks(
						session,
						new FilterConstraint[]{entityLocaleEquals(Locale.ENGLISH), hierarchy, textMatches("green tea")},
						null,
						debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
					);
					assertArrayEquals(expected, answered);
					final int[] byReference = queryPks(
						session,
						new FilterConstraint[]{
							entityLocaleEquals(Locale.ENGLISH),
							referenceHaving(REFERENCE_CATEGORIES, entityPrimaryKeyInSet(2)),
							textMatches("green tea")
						},
						null,
						debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
					);
					assertArrayEquals(expected, byReference);
					// the relevance order over the reduced indexes is the order over the same candidates globally
					final int[] ranked = queryPks(
						session,
						new FilterConstraint[]{entityLocaleEquals(Locale.ENGLISH), hierarchy, textMatches("green tea")},
						relevance(),
						debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS)
					);
					assertArrayEquals(
						expectedRanking(evita, Locale.ENGLISH, "green tea", expected, Scope.LIVE), ranked
					);
					return null;
				}
			);
		}
	}

	@DisplayName("Planning-time errors")
	@Nested
	class PlanningErrors {

		@DisplayName("Should require the query locale")
		@UseDataSet(FULLTEXT_DATASET)
		@Test
		void shouldRequireTheQueryLocale(Evita evita) {
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final EvitaInvalidUsageException exception = assertThrows(
						EvitaInvalidUsageException.class,
						() -> queryPks(session, new FilterConstraint[]{textMatches("tea")}, null)
					);
					assertTrue(exception.getMessage().contains("entityLocaleEquals"), exception.getMessage());
					return null;
				}
			);
		}

		@DisplayName("Should refuse a collection without a searchable field")
		@UseDataSet(FULLTEXT_DATASET)
		@Test
		void shouldRefuseCollectionWithoutSearchableField(Evita evita) {
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final EvitaInvalidUsageException exception = assertThrows(
						EvitaInvalidUsageException.class,
						() -> session.query(
							query(
								collection(CATEGORY),
								filterBy(entityLocaleEquals(Locale.ENGLISH), textMatches("tea"))
							),
							EntityReference.class
						)
					);
					assertTrue(exception.getMessage().contains("no attribute or associated data searchable"), exception.getMessage());
					return null;
				}
			);
		}

		@DisplayName("Should refuse an unknown or non-searchable inField")
		@UseDataSet(FULLTEXT_DATASET)
		@Test
		void shouldRefuseUnknownOrNonSearchableInField(Evita evita) {
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					assertThrows(
						AttributeNotFoundException.class,
						() -> assertMatches(session, new int[0], textMatches("tea", inField("unknown")))
					);
					final AttributeNotSearchableException notSearchable = assertThrows(
						AttributeNotSearchableException.class,
						() -> assertMatches(session, new int[0], textMatches("tea", inField(ATTRIBUTE_CODE)))
					);
					assertTrue(notSearchable.getMessage().contains("`code`"), notSearchable.getMessage());
					// the description is searchable in the live scope only
					final AttributeNotSearchableException notInArchive = assertThrows(
						AttributeNotSearchableException.class,
						() -> assertMatches(
							session, new int[0],
							textMatches("tea", inField(ATTRIBUTE_DESCRIPTION)), scope(Scope.LIVE, Scope.ARCHIVED)
						)
					);
					assertTrue(notInArchive.getMessage().contains("ARCHIVED"), notInArchive.getMessage());
					return null;
				}
			);
		}

		@DisplayName("Should refuse textMatches nested anywhere under userFilter")
		@UseDataSet(FULLTEXT_DATASET)
		@Test
		void shouldRefuseTextMatchesNestedInUserFilter(Evita evita) {
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final EvitaInvalidUsageException exception = assertThrows(
						EvitaInvalidUsageException.class,
						() -> assertMatches(
							session, new int[0],
							userFilter(or(textMatches("tea"), attributeEquals(ATTRIBUTE_CODE, "P101")))
						)
					);
					assertTrue(exception.getMessage().contains("userFilter"), exception.getMessage());
					return null;
				}
			);
		}

		@DisplayName("Should refuse inField outside textMatches and textMatches in a reference scope")
		@UseDataSet(FULLTEXT_DATASET)
		@Test
		void shouldRefuseMisplacedConstraints(Evita evita) {
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final EvitaInvalidUsageException strayInField = assertThrows(
						EvitaInvalidUsageException.class,
						() -> assertMatches(
							session, new int[0], or(inField(ATTRIBUTE_NAME), attributeEquals(ATTRIBUTE_CODE, "P101"))
						)
					);
					assertTrue(strayInField.getMessage().contains("textMatches"), strayInField.getMessage());
					assertThrows(
						EvitaInvalidUsageException.class,
						() -> assertMatches(
							session, new int[0], referenceHaving(REFERENCE_CATEGORIES, textMatches("loose"))
						)
					);
					return null;
				}
			);
		}

		@DisplayName("Should refuse a text with more words than the scorer counts")
		@UseDataSet(FULLTEXT_DATASET)
		@Test
		void shouldRefuseTooManyWords(Evita evita) {
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final String text = "tea ".repeat(FulltextPhaseOneScorer.MAX_QUERY_TOKENS + 1);
					final EvitaInvalidUsageException exception = assertThrows(
						EvitaInvalidUsageException.class,
						() -> assertMatches(session, new int[0], textMatches(text))
					);
					assertTrue(
						exception.getMessage().contains(String.valueOf(FulltextPhaseOneScorer.MAX_QUERY_TOKENS)),
						exception.getMessage()
					);
					// the limit itself is accepted
					assertMatches(
						session, new int[]{1, 2, 4, 6, 8, 10},
						textMatches("tea ".repeat(FulltextPhaseOneScorer.MAX_QUERY_TOKENS))
					);
					return null;
				}
			);
		}

		@DisplayName("Should refuse a text with too many words in entityHaving over a scope holding no target")
		@UseDataSet(FULLTEXT_DATASET)
		@Test
		void shouldRefuseTooManyWordsInsideEntityHavingWithoutTargetData(Evita evita) {
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					// no brand is archived, so the nested filter is only checked in the archived scope
					final String text = "green ".repeat(FulltextPhaseOneScorer.MAX_QUERY_TOKENS + 1);
					final EvitaInvalidUsageException exception = assertThrows(
						EvitaInvalidUsageException.class,
						() -> queryPks(
							session,
							new FilterConstraint[]{
								entityLocaleEquals(Locale.ENGLISH),
								scope(Scope.ARCHIVED),
								referenceHaving(REFERENCE_BRANDS, entityHaving(textMatches(text)))
							},
							null
						)
					);
					assertTrue(
						exception.getMessage().contains(String.valueOf(FulltextPhaseOneScorer.MAX_QUERY_TOKENS)),
						exception.getMessage()
					);
					return null;
				}
			);
		}
	}

	@DisplayName("Relevance ordering")
	@Nested
	class RelevanceOrdering {

		@DisplayName("Should rank shorter values with the term first, as the scorer does, on both plans")
		@UseDataSet(FULLTEXT_DATASET)
		@Test
		void shouldRankAsTheScorerDoes(Evita evita) {
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final int[] matched = {1, 2, 4, 6, 8, 10};
					final int[] expected = expectedRanking(evita, Locale.ENGLISH, "tea", matched, Scope.LIVE);
					for (PlanPreference plan : PlanPreference.values()) {
						final int[] ranked = queryOnPlan(session, plan, relevance(), textMatches("tea"));
						assertArrayEquals(expected, ranked, "Order on the " + plan + " plan");
					}
					// BM25 alone: the single-word name ranks first, then the two-word names in primary key order,
					// and the long description last
					assertEquals(6, expected[0]);
					assertEquals(1, expected[1]);
					assertEquals(2, expected[2]);
					assertEquals(4, expected[expected.length - 1]);
					return null;
				}
			);
		}

		@DisplayName("Should rank more matched words first and hand the unmatched entities on")
		@UseDataSet(FULLTEXT_DATASET)
		@Test
		void shouldRankMoreMatchedWordsFirst(Evita evita) {
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					// 3 contains `green` only and 5 neither word - both enter through the other branch of `or`
					final FilterConstraint filter = or(textMatches("green tea"), entityPrimaryKeyInSet(3, 5));
					final int[] strict = rankLive(evita, "green tea", 1, 4, 8, 10);
					final int[] expected = IntStream.concat(Arrays.stream(strict), IntStream.of(3, 5)).toArray();
					assertArrayEquals(
						expected, rankLive(evita, "green tea", 1, 3, 4, 5, 8, 10)
					);
					for (PlanPreference plan : PlanPreference.values()) {
						assertArrayEquals(
							expected, queryOnPlan(session, plan, relevance(), filter), "Order on the " + plan + " plan"
						);
					}
					// the unmatched entity is ordered by the next sorter
					final int[] byCode = queryPks(
						session,
						new FilterConstraint[]{
							entityLocaleEquals(Locale.ENGLISH), or(textMatches("coffee"), entityPrimaryKeyInSet(1, 2))
						},
						orderBy(relevance(), attributeNatural(ATTRIBUTE_CODE, OrderDirection.DESC))
					);
					assertArrayEquals(
						IntStream.concat(
							Arrays.stream(rankLive(evita, "coffee", 3, 4, 5)),
							IntStream.of(2, 1)
						).toArray(),
						byCode
					);
					return null;
				}
			);
		}

		@DisplayName("Should keep pages consistent with the whole ranking")
		@UseDataSet(FULLTEXT_DATASET)
		@Test
		void shouldKeepPagesConsistent(Evita evita) {
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final FilterConstraint[] filter = {entityLocaleEquals(Locale.ENGLISH), textMatches("tea")};
					final int[] whole = queryPks(session, filter, relevance());
					final List<Integer> paged = new ArrayList<>(whole.length);
					for (int pageNumber = 1; pageNumber <= 3; pageNumber++) {
						for (int pk : queryPks(session, filter, relevance(), page(pageNumber, 2))) {
							paged.add(pk);
						}
					}
					assertArrayEquals(whole, paged.stream().mapToInt(Integer::intValue).toArray());
					// and after another sorter filled part of the page
					final int[] promoted = queryPks(
						session, filter,
						orderBy(entityPrimaryKeyExact(10), relevance()),
						page(2, 2)
					);
					final int[] promotedWhole = queryPks(
						session, filter, orderBy(entityPrimaryKeyExact(10), relevance())
					);
					assertArrayEquals(Arrays.copyOfRange(promotedWhole, 2, 4), promoted);
					return null;
				}
			);
		}

		@DisplayName("Should pass every entity on when there is no textMatches")
		@UseDataSet(FULLTEXT_DATASET)
		@Test
		void shouldPassEverythingOnWithoutTextMatches(Evita evita) {
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final FilterConstraint[] filter = {entityLocaleEquals(Locale.ENGLISH)};
					assertArrayEquals(queryPks(session, filter, null), queryPks(session, filter, relevance()));
					assertArrayEquals(
						queryPks(session, filter, orderBy(attributeNatural(ATTRIBUTE_CODE, OrderDirection.DESC))),
						queryPks(
							session, filter, orderBy(relevance(), attributeNatural(ATTRIBUTE_CODE, OrderDirection.DESC))
						)
					);
					// the factory drops a blank text, so an empty search box needs no branching in the client
					assertArrayEquals(
						queryPks(session, filter, null),
						queryPks(
							session,
							new FilterConstraint[]{entityLocaleEquals(Locale.ENGLISH), textMatches("  ")},
							relevance()
						)
					);
					return null;
				}
			);
		}

		@DisplayName("Should merge the rankings of several scopes and honour inScope")
		@UseDataSet(FULLTEXT_DATASET)
		@Test
		void shouldMergeScopes(Evita evita) {
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final int[] matched = {1, 4, 8, 10, 11};
					final FilterConstraint[] filter = {
						entityLocaleEquals(Locale.ENGLISH), textMatches("green tea"), scope(Scope.LIVE, Scope.ARCHIVED)
					};
					assertArrayEquals(
						expectedRanking(evita, Locale.ENGLISH, "green tea", matched, Scope.LIVE, Scope.ARCHIVED),
						queryPks(session, filter, relevance())
					);
					// ranking only the live entities leaves the archived one to the default order, after them
					assertArrayEquals(
						IntStream.concat(
							Arrays.stream(
								rankLive(evita, "green tea", 1, 4, 8, 10)
							),
							IntStream.of(11)
						).toArray(),
						queryPks(session, filter, orderBy(inScope(Scope.LIVE, relevance())))
					);
					return null;
				}
			);
		}

		@DisplayName("Should rank inside a segment")
		@UseDataSet(FULLTEXT_DATASET)
		@Test
		void shouldRankInsideSegment(Evita evita) {
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final int[] matched = {1, 2, 4, 6, 8, 10};
					final int[] ranked = expectedRanking(evita, Locale.ENGLISH, "tea", matched, Scope.LIVE);
					// the two best by relevance, then the rest by code descending
					final Set<Integer> rest = new TreeSet<>(Comparator.reverseOrder());
					for (int i = 2; i < ranked.length; i++) {
						rest.add(ranked[i]);
					}
					final int[] expected = IntStream.concat(
						IntStream.of(ranked[0], ranked[1]), rest.stream().mapToInt(Integer::intValue)
					).toArray();
					final OrderConstraint segmented = segments(
						segment(orderBy(relevance()), limit(2)),
						segment(orderBy(attributeNatural(ATTRIBUTE_CODE, OrderDirection.DESC)))
					);
					for (PlanPreference plan : PlanPreference.values()) {
						assertArrayEquals(
							expected, queryOnPlan(session, plan, segmented, textMatches("tea")),
							"Order on the " + plan + " plan"
						);
					}
					return null;
				}
			);
		}

		@DisplayName("Should refuse relevance in a nested ordering")
		@UseDataSet(FULLTEXT_DATASET)
		@Test
		void shouldRefuseRelevanceInNestedOrdering(Evita evita) {
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final FilterConstraint[] filter = {entityLocaleEquals(Locale.ENGLISH), textMatches("tea")};
					for (OrderConstraint nested : new OrderConstraint[]{
						referenceProperty(REFERENCE_CATEGORIES, relevance()),
						referenceProperty(REFERENCE_CATEGORIES, traverseByEntityProperty(relevance())),
						referenceProperty(REFERENCE_CATEGORIES, pickFirstByEntityProperty(relevance()))
					}) {
						final EvitaInvalidUsageException exception = assertThrows(
							EvitaInvalidUsageException.class,
							() -> queryPks(session, filter, nested),
							"`" + nested + "` must be refused"
						);
						assertTrue(
							exception.getMessage().contains("can only be used in the top-level"),
							exception.getMessage()
						);
					}
					final EvitaInvalidUsageException inReferenceContent = assertThrows(
						EvitaInvalidUsageException.class,
						() -> session.query(
							query(
								collection(PRODUCT),
								filterBy(filter),
								require(entityFetch(referenceContent(REFERENCE_CATEGORIES, orderBy(relevance()))))
							),
							SealedEntity.class
						)
					);
					assertTrue(
						inReferenceContent.getMessage().contains("can only be used in the top-level"),
						inReferenceContent.getMessage()
					);
					return null;
				}
			);
		}
	}

	@DisplayName("Stop words typed without diacritics")
	@Nested
	class StopWordsWithoutDiacritics {

		@DisplayName("Should ignore a stop word the index dropped when it is typed without diacritics")
		@UseDataSet(FULLTEXT_DATASET)
		@Test
		void shouldIgnoreStopWordTypedWithoutDiacritics(Evita evita) {
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					assertMatchesIn(session, CZECH, new int[]{1, 2}, textMatches("skladem"));
					// `není` is dropped by both chains, `neni` only by the folded lookup
					assertMatchesIn(session, CZECH, new int[]{1, 2}, textMatches("není skladem"));
					assertMatchesIn(session, CZECH, new int[]{1, 2}, textMatches("neni skladem"));
					assertMatchesIn(session, CZECH, new int[]{13}, textMatches("volny"));
					assertMatchesIn(session, CZECH, new int[]{13}, textMatches("jeste volny"));
					// a text of stop words only still matches nothing
					assertMatchesIn(session, CZECH, new int[0], textMatches("neni jeste"));
					return null;
				}
			);
		}

		@DisplayName("Should keep a word the index holds although it folds into a stop word")
		@UseDataSet(FULLTEXT_DATASET)
		@Test
		void shouldKeepRealWordCollidingWithStopWord(Evita evita) {
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					// `byt` is what the stop word `být` folds into, and the real word the apartment is named by
					assertMatchesIn(session, CZECH, new int[]{13}, textMatches("byt"));
					assertMatchesIn(session, CZECH, new int[]{13}, textMatches("prostorny byt"));
					// held by the index, so not dropped even where the searched field lacks it
					assertMatchesIn(
						session, CZECH, new int[0], textMatches("volny byt", inField(ATTRIBUTE_DESCRIPTION))
					);
					return null;
				}
			);
		}

		@DisplayName("Should still match nothing for an unknown word that is no stop word")
		@UseDataSet(FULLTEXT_DATASET)
		@Test
		void shouldMatchNothingForUnknownWord(Evita evita) {
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					assertMatchesIn(session, CZECH, new int[0], textMatches("neexistujici skladem"));
					return null;
				}
			);
		}

		@DisplayName("Should rank as if the dropped stop word were not there")
		@UseDataSet(FULLTEXT_DATASET)
		@Test
		void shouldRankWithoutTheDroppedStopWord(Evita evita) {
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final FilterConstraint stock = or(textMatches("skladem"), entityPrimaryKeyInSet(13));
					final FilterConstraint stockWithStopWord = or(
						textMatches("neni skladem"), entityPrimaryKeyInSet(13)
					);
					final int[] expected = queryPks(
						session, new FilterConstraint[]{entityLocaleEquals(CZECH), stock}, relevance()
					);
					assertArrayEquals(
						orderByScores(
							scoresOf(evita, PRODUCT, CZECH, "skladem", new int[]{1, 2, 13}, Scope.LIVE),
							new int[]{1, 2, 13}
						),
						expected
					);
					assertArrayEquals(
						expected,
						queryPks(
							session, new FilterConstraint[]{entityLocaleEquals(CZECH), stockWithStopWord}, relevance()
						)
					);
					return null;
				}
			);
		}
	}

	@DisplayName("Relevance through entityHaving")
	@Nested
	class RelevanceThroughEntityHaving {

		@DisplayName("Should match the owners of the matching targets")
		@UseDataSet(FULLTEXT_DATASET)
		@Test
		void shouldMatchOwnersOfMatchingTargets(Evita evita) {
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					assertMatches(
						session, new int[]{1, 2, 3, 6, 7},
						referenceHaving(REFERENCE_BRANDS, entityHaving(textMatches("green")))
					);
					assertMatches(
						session, new int[]{3, 6, 7},
						referenceHaving(REFERENCE_BRANDS, entityHaving(textMatches("green", inField(ATTRIBUTE_NAME)))),
						referenceHaving(REFERENCE_BRANDS, entityPrimaryKeyInSet(2))
					);
					return null;
				}
			);
		}

		@DisplayName("Should rank the owners by the best score of the targets they reference, on both plans")
		@UseDataSet(FULLTEXT_DATASET)
		@Test
		void shouldRankOwnersByTheirTargets(Evita evita) {
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final int[] owners = {1, 2, 3, 6, 7};
					final int[] expected = orderByScores(brandScoresOfOwners(evita, "green", owners), owners);
					// `Green` (2) outranks `Green Valley Farms` (1), and 6 takes the better of the two it references
					assertArrayEquals(new int[]{3, 6, 7, 1, 2}, expected);
					for (PlanPreference plan : PlanPreference.values()) {
						assertArrayEquals(
							expected,
							queryOnPlan(
								session, plan, relevance(),
								referenceHaving(REFERENCE_BRANDS, entityHaving(textMatches("green")))
							),
							"Order on the " + plan + " plan"
						);
					}
					return null;
				}
			);
		}

		@DisplayName("Should combine the owner's own scores with the scores of its targets")
		@UseDataSet(FULLTEXT_DATASET)
		@Test
		void shouldCombineOwnScoresWithTargetScores(Evita evita) {
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final int[] candidates = {1, 2, 3, 4, 6, 7, 8, 10};
					final Map<Integer, Long> scores = new HashMap<>(
						scoresOf(evita, PRODUCT, Locale.ENGLISH, "tea", candidates, Scope.LIVE)
					);
					brandScoresOfOwners(evita, "green", candidates)
						.forEach((pk, score) -> scores.merge(pk, score, FulltextQueryFunctionalTest::better));
					final int[] expected = orderByScores(scores, candidates);
					for (PlanPreference plan : PlanPreference.values()) {
						assertArrayEquals(
							expected,
							queryOnPlan(
								session, plan, relevance(),
								or(
									textMatches("tea"),
									referenceHaving(REFERENCE_BRANDS, entityHaving(textMatches("green")))
								)
							),
							"Order on the " + plan + " plan"
						);
					}
					return null;
				}
			);
		}

		@DisplayName("Should answer the same text in the owner and in a referenced collection from their own indexes")
		@UseDataSet(FULLTEXT_DATASET)
		@Test
		void shouldNotShareTheFormulaOfTheSameTextAcrossCollections(Evita evita) {
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final FilterConstraint sameTextTwice = or(
						textMatches("green"),
						referenceHaving(REFERENCE_BRANDS, entityHaving(textMatches("green")))
					);
					// 1, 3, 4, 7, 8 and 10 by their own text, 1, 2 and 6 through brand 1, 3, 6 and 7 through brand 2
					final int[] candidates = {1, 2, 3, 4, 6, 7, 8, 10};
					assertMatches(session, candidates, sameTextTwice);
					final Map<Integer, Long> scores = new HashMap<>(
						scoresOf(evita, PRODUCT, Locale.ENGLISH, "green", candidates, Scope.LIVE)
					);
					brandScoresOfOwners(evita, "green", candidates)
						.forEach((pk, score) -> scores.merge(pk, score, FulltextQueryFunctionalTest::better));
					final int[] expected = orderByScores(scores, candidates);
					for (PlanPreference plan : PlanPreference.values()) {
						assertArrayEquals(
							expected, queryOnPlan(session, plan, relevance(), sameTextTwice),
							"Order on the " + plan + " plan"
						);
					}
					return null;
				}
			);
		}

		@DisplayName("Should rank the owners of both scopes")
		@UseDataSet(FULLTEXT_DATASET)
		@Test
		void shouldRankOwnersOfBothScopes(Evita evita) {
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final int[] owners = {1, 2, 3, 6, 7, 11};
					assertArrayEquals(
						orderByScores(brandScoresOfOwners(evita, "green", owners), owners),
						queryPks(
							session,
							new FilterConstraint[]{
								entityLocaleEquals(Locale.ENGLISH),
								referenceHaving(REFERENCE_BRANDS, entityHaving(textMatches("green"))),
								scope(Scope.LIVE, Scope.ARCHIVED)
							},
							relevance()
						)
					);
					return null;
				}
			);
		}
	}

	@DisplayName("Relevance through groupHaving")
	@Nested
	class RelevanceThroughGroupHaving {

		@DisplayName("Should rank the owners by the best score of the groups they reference, on both plans")
		@UseDataSet(FULLTEXT_DATASET)
		@Test
		void shouldRankOwnersByTheirGroups(Evita evita) {
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final int[] owners = {4, 8, 9};
					final int[] expected = orderByScores(placementGroupScoresOfOwners(evita, "green", owners), owners);
					// 1 is placed in `Mountain Roasters` (3) only and does not match; `Green` (2) outranks
					// `Green Valley Farms` (1), and 8 takes the better of the two groups it has
					assertArrayEquals(new int[]{4, 8, 9}, expected);
					for (PlanPreference plan : PlanPreference.values()) {
						assertArrayEquals(
							expected,
							queryOnPlan(
								session, plan, relevance(),
								referenceHaving(REFERENCE_PLACEMENTS, groupHaving(textMatches("green")))
							),
							"Order on the " + plan + " plan"
						);
					}
					return null;
				}
			);
		}

		@DisplayName("Should combine the owner's own scores with the scores of its groups")
		@UseDataSet(FULLTEXT_DATASET)
		@Test
		void shouldCombineOwnScoresWithGroupScores(Evita evita) {
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final int[] candidates = {1, 2, 4, 6, 8, 9, 10};
					final Map<Integer, Long> scores = new HashMap<>(
						scoresOf(evita, PRODUCT, Locale.ENGLISH, "tea", candidates, Scope.LIVE)
					);
					placementGroupScoresOfOwners(evita, "green", candidates)
						.forEach((pk, score) -> scores.merge(pk, score, FulltextQueryFunctionalTest::better));
					final int[] expected = orderByScores(scores, candidates);
					for (PlanPreference plan : PlanPreference.values()) {
						assertArrayEquals(
							expected,
							queryOnPlan(
								session, plan, relevance(),
								or(
									textMatches("tea"),
									referenceHaving(REFERENCE_PLACEMENTS, groupHaving(textMatches("green")))
								)
							),
							"Order on the " + plan + " plan"
						);
					}
					return null;
				}
			);
		}
	}

	/**
	 * Asserts the English query with the filter matches exactly the expected products, on both plans.
	 *
	 * @param session  the session
	 * @param expected the expected primary keys, ascending
	 * @param filter   the filter constraints besides the locale
	 */
	private static void assertMatches(
		@Nonnull EvitaSessionContract session,
		@Nonnull int[] expected,
		@Nonnull FilterConstraint... filter
	) {
		assertMatchesIn(session, Locale.ENGLISH, expected, filter);
	}

	/**
	 * Asserts the query in the locale with the filter matches exactly the expected products, on both plans.
	 *
	 * @param session  the session
	 * @param locale   the locale of the query
	 * @param expected the expected primary keys, ascending
	 * @param filter   the filter constraints besides the locale
	 */
	private static void assertMatchesIn(
		@Nonnull EvitaSessionContract session,
		@Nonnull Locale locale,
		@Nonnull int[] expected,
		@Nonnull FilterConstraint... filter
	) {
		final FilterConstraint[] localized = new FilterConstraint[filter.length + 1];
		localized[0] = entityLocaleEquals(locale);
		System.arraycopy(filter, 0, localized, 1, filter.length);
		for (PlanPreference plan : PlanPreference.values()) {
			final int[] answered = queryPks(
				session, plan.filter(ALL_PRODUCTS, localized), null, plan.debug()
			);
			Arrays.sort(answered);
			assertArrayEquals(
				expected, answered,
				() -> "Match set on the " + plan + " plan: expected " + Arrays.toString(expected) + " but was " +
					Arrays.toString(answered)
			);
		}
	}

	/**
	 * Runs the English query on the plan and returns the primary keys in the order returned, asserting the plan ran.
	 *
	 * @param session the session
	 * @param plan    the plan to steer the query onto
	 * @param order   the ordering
	 * @param filter  the filter constraints besides the locale
	 * @return the primary keys in the order returned
	 */
	@Nonnull
	private static int[] queryOnPlan(
		@Nonnull EvitaSessionContract session,
		@Nonnull PlanPreference plan,
		@Nonnull OrderConstraint order,
		@Nonnull FilterConstraint... filter
	) {
		final FilterConstraint[] localized = new FilterConstraint[filter.length + 1];
		localized[0] = entityLocaleEquals(Locale.ENGLISH);
		System.arraycopy(filter, 0, localized, 1, filter.length);
		final EvitaResponse<EntityReference> response = session.query(
			query(
				collection(PRODUCT),
				filterBy(plan.filter(LIVE_PRODUCTS, localized)),
				orderBy(order),
				require(page(1, 100), plan.debug(), queryTelemetry())
			),
			EntityReference.class
		);
		plan.assertTaken(response);
		return toPks(response);
	}

	/**
	 * Runs the query and returns the primary keys in the order returned.
	 *
	 * @param session the session
	 * @param filter  the filter constraints
	 * @param order   the ordering, or null
	 * @param require additional requirements
	 * @return the primary keys in the order returned
	 */
	@Nonnull
	private static int[] queryPks(
		@Nonnull EvitaSessionContract session,
		@Nonnull FilterConstraint[] filter,
		@Nullable OrderConstraint order,
		@Nonnull RequireConstraint... require
	) {
		final RequireConstraint[] requirements;
		if (Arrays.stream(require).anyMatch(it -> it instanceof Page)) {
			requirements = require;
		} else {
			requirements = Arrays.copyOf(require, require.length + 1);
			requirements[require.length] = page(1, 100);
		}
		final Query query = order == null ?
			query(collection(PRODUCT), filterBy(filter), require(requirements)) :
			query(
				collection(PRODUCT), filterBy(filter),
				order instanceof OrderBy theOrderBy ? theOrderBy : orderBy(order),
				require(requirements)
			);
		return toPks(session.query(query, EntityReference.class));
	}

	/**
	 * Returns the primary keys of the response in the order returned.
	 *
	 * @param response the response
	 * @return the primary keys
	 */
	@Nonnull
	private static int[] toPks(@Nonnull EvitaResponse<EntityReference> response) {
		return response.getRecordData().stream().mapToInt(EntityReference::getPrimaryKey).toArray();
	}

	/**
	 * Computes the relevance order of English candidates in the live scope - see {@link #expectedRanking}.
	 *
	 * @param evita      the evita instance
	 * @param text       the text of the query
	 * @param candidates the candidates, ascending
	 * @return the candidates in the expected order
	 */
	@Nonnull
	private static int[] rankLive(@Nonnull Evita evita, @Nonnull String text, @Nonnull int... candidates) {
		return expectedRanking(evita, Locale.ENGLISH, text, candidates, Scope.LIVE);
	}

	/**
	 * Computes the relevance order of product candidates - see {@link #scoresOf} and {@link #orderByScores}.
	 *
	 * @param evita      the evita instance
	 * @param locale     the locale of the query
	 * @param text       the text of the query
	 * @param candidates the candidates, ascending
	 * @param scopes     the scopes whose indexes rank
	 * @return the candidates in the expected order
	 */
	@Nonnull
	private static int[] expectedRanking(
		@Nonnull Evita evita,
		@Nonnull Locale locale,
		@Nonnull String text,
		@Nonnull int[] candidates,
		@Nonnull Scope... scopes
	) {
		return orderByScores(scoresOf(evita, PRODUCT, locale, text, candidates, scopes), candidates);
	}

	/**
	 * Computes the composite score of the candidates by running {@link FulltextPhaseOneScorer} directly over the
	 * postings of the collection's fulltext indexes: the text is analyzed by the search analyzer of the collection,
	 * each term with a positive position increment starts a token, every field of the index is searched, and a
	 * candidate ranked in several scopes keeps its best composite (unsigned).
	 *
	 * @param evita      the evita instance
	 * @param entityType the collection
	 * @param locale     the locale of the query
	 * @param text       the text of the query
	 * @param candidates the candidates, ascending
	 * @param scopes     the scopes whose indexes rank
	 * @return the composite of every candidate the scorer ranked, by primary key
	 */
	@Nonnull
	private static Map<Integer, Long> scoresOf(
		@Nonnull Evita evita,
		@Nonnull String entityType,
		@Nonnull Locale locale,
		@Nonnull String text,
		@Nonnull int[] candidates,
		@Nonnull Scope... scopes
	) {
		final Catalog catalog = (Catalog) evita.getCatalogInstanceOrThrowException(TEST_CATALOG);
		final List<List<String>> tokens = new ArrayList<>(8);
		final FulltextAnalyzer analyzer = catalog.getFulltextAnalyzerRegistry().getSearchAnalyzer(entityType, locale);
		for (AnalyzedTerm term : analyzer.getTerms(text)) {
			if (term.positionIncrement() > 0 || tokens.isEmpty()) {
				tokens.add(new ArrayList<>(4));
			}
			final List<String> token = tokens.get(tokens.size() - 1);
			if (!token.contains(term.term())) {
				token.add(term.term());
			}
		}
		final Map<Integer, Long> best = new HashMap<>(candidates.length * 2);
		for (Scope scope : scopes) {
			final GlobalEntityIndex globalIndex = (GlobalEntityIndex) catalog.getCollectionForEntityInternal(entityType)
				.orElseThrow()
				.getIndexByKeyIfExists(new EntityIndexKey(EntityIndexType.GLOBAL, scope));
			final FulltextIndex fulltextIndex = globalIndex == null ? null : globalIndex.getFulltextIndex(locale);
			if (fulltextIndex == null) {
				continue;
			}
			final Expansion[][] expansions = new Expansion[tokens.size()][];
			for (int i = 0; i < tokens.size(); i++) {
				final List<Expansion> tokenExpansions = new ArrayList<>(8);
				for (int fieldId = 0; fieldId < fulltextIndex.getFieldCount(); fieldId++) {
					if (fulltextIndex.isFieldRetired(fieldId)) {
						continue;
					}
					for (String term : tokens.get(i)) {
						final Bitmap postings = fulltextIndex.getPostings(fieldId, term);
						if (!postings.isEmpty()) {
							tokenExpansions.add(
								new Expansion(postings.getArray(), fulltextIndex.getImpacts(fieldId, term), 0)
							);
						}
					}
				}
				expansions[i] = tokenExpansions.toArray(Expansion[]::new);
			}
			final Result result = FulltextPhaseOneScorer.score(candidates, expansions, candidates.length);
			for (int i = 0; i < result.primaryKeys().length; i++) {
				best.merge(result.primaryKeys()[i], result.composites()[i], FulltextQueryFunctionalTest::better);
			}
		}
		return best;
	}

	/**
	 * Returns the better of two composites, compared unsigned as the scorer defines them.
	 *
	 * @param a one composite
	 * @param b other composite
	 * @return the greater one
	 */
	private static long better(long a, long b) {
		return Long.compareUnsigned(a, b) >= 0 ? a : b;
	}

	/**
	 * Orders the scored candidates by composite (unsigned, best first), ties by ascending primary key, and appends the
	 * candidates without a score in ascending order - where the default sorter puts them.
	 *
	 * @param scores     the composite of every scored candidate
	 * @param candidates every candidate, ascending
	 * @return the candidates in the expected order
	 */
	@Nonnull
	private static int[] orderByScores(@Nonnull Map<Integer, Long> scores, @Nonnull int[] candidates) {
		final Set<Integer> ranked = new LinkedHashSet<>(candidates.length * 2);
		scores.entrySet()
			.stream()
			.sorted(
				(a, b) -> {
					final int byComposite = Long.compareUnsigned(b.getValue(), a.getValue());
					return byComposite == 0 ? Integer.compare(a.getKey(), b.getKey()) : byComposite;
				}
			)
			.forEach(it -> ranked.add(it.getKey()));
		for (int candidate : candidates) {
			ranked.add(candidate);
		}
		return ranked.stream().mapToInt(Integer::intValue).toArray();
	}

	/**
	 * Computes the scores the owners get through the brands they reference: every brand matching the text is scored
	 * as {@link #scoresOf} does, and an owner keeps the best composite of the matching brands it references.
	 *
	 * @param evita  the evita instance
	 * @param text   the English text searched in the brands
	 * @param owners the owners to score
	 * @return the composite of every owner referencing a matching brand, by primary key
	 */
	@Nonnull
	private static Map<Integer, Long> brandScoresOfOwners(
		@Nonnull Evita evita,
		@Nonnull String text,
		@Nonnull int[] owners
	) {
		return brandScoresOfOwners(evita, text, owners, PRODUCT_BRANDS);
	}

	/**
	 * Computes the scores the owners get through the brands grouping their placements - see
	 * {@link #brandScoresOfOwners(Evita, String, int[], Map)}.
	 *
	 * @param evita  the evita instance
	 * @param text   the English text searched in the brands
	 * @param owners the owners to score
	 * @return the composite of every owner with a placement in a matching brand, by primary key
	 */
	@Nonnull
	private static Map<Integer, Long> placementGroupScoresOfOwners(
		@Nonnull Evita evita,
		@Nonnull String text,
		@Nonnull int[] owners
	) {
		final Map<Integer, int[]> groupsByOwner = new HashMap<>(PRODUCT_PLACEMENTS.size() * 2);
		PRODUCT_PLACEMENTS.forEach(
			(owner, placements) -> groupsByOwner.put(
				owner, Arrays.stream(placements).mapToInt(placement -> placement[1]).toArray()
			)
		);
		return brandScoresOfOwners(evita, text, owners, groupsByOwner);
	}

	/**
	 * Computes the scores the owners get through the brands they are related to: every brand matching the text is
	 * scored as {@link #scoresOf} does, and an owner keeps the best composite of the matching brands it relates to.
	 *
	 * @param evita         the evita instance
	 * @param text          the English text searched in the brands
	 * @param owners        the owners to score
	 * @param brandsByOwner the brands each owner relates to
	 * @return the composite of every owner related to a matching brand, by primary key
	 */
	@Nonnull
	private static Map<Integer, Long> brandScoresOfOwners(
		@Nonnull Evita evita,
		@Nonnull String text,
		@Nonnull int[] owners,
		@Nonnull Map<Integer, int[]> brandsByOwner
	) {
		final int[] brands = BRAND_NAMES.keySet().stream().mapToInt(Integer::intValue).sorted().toArray();
		final Map<Integer, Long> brandScores = scoresOf(evita, BRAND, Locale.ENGLISH, text, brands, Scope.LIVE);
		final Map<Integer, Long> ownerScores = new HashMap<>(owners.length * 2);
		for (int owner : owners) {
			for (int brand : brandsByOwner.getOrDefault(owner, new int[0])) {
				final Long brandScore = brandScores.get(brand);
				if (brandScore != null) {
					ownerScores.merge(owner, brandScore, FulltextQueryFunctionalTest::better);
				}
			}
		}
		return ownerScores;
	}

}
