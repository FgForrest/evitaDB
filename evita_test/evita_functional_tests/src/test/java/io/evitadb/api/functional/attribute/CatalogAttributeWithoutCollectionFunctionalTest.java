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

package io.evitadb.api.functional.attribute;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.exception.EntityCollectionRequiredException;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.core.Evita;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.TestInstance;

import javax.annotation.Nonnull;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.FILTER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins that a constraint on an attribute of the catalog selects the same entities whether the query names the
 * collection or finds its entities by their globally unique code instead, over three attributes that differ in how the
 * catalog indexes them: one unique across the whole catalog, one that is only filterable, and one that is unique within
 * a collection only. The catalog index answers nothing but a globally unique lookup, so a query without a collection
 * evaluates every other constraint on the entities the code lookup found. A query without a collection that has no
 * such entities to start from is refused. An `inScope` container over both scopes restricts only the entities of its
 * scope in either kind of query; the catalog index holds no set of all the entities of a scope, so a query without a
 * collection tells them apart on the entities it found. Inside such a container, a decimal attribute of the collections
 * is refused for a value its index cannot encode only when the container names a scope that indexes the attribute.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("A constraint on a catalog attribute answers with and without a collection")
@Tag(ENGINE)
@Tag(FILTER)
@Tag(ATTRIBUTE)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CatalogAttributeWithoutCollectionFunctionalTest implements EvitaTestSupport {

	private static final String PRODUCT = "Product";
	private static final String CATEGORY = "Category";
	private static final String CODE = "code";
	private static final String CATALOG_DECIMAL = "catalogDecimal";
	private static final String RANK = "rank";
	private static final String LABEL = "label";
	/**
	 * A decimal attribute of the collections, not of the catalog, indexed in the live scope only.
	 */
	private static final String LIVE_DECIMAL = "liveDecimal";
	/**
	 * A decimal too large for the integer key of an index at two decimal places.
	 */
	private static final BigDecimal BEYOND_INDEX_KEY = new BigDecimal("99999999999");
	/**
	 * The codes of all the entities, the globally unique anchor a query without a collection starts from.
	 */
	private static final String[] ALL_CODES = {"p1", "p2", "p3", "p4", "p5", "c1", "c2", "c3", "c4", "c5"};
	/**
	 * The decimals of the products 1-5 followed by those of the categories 1-5, all unique across the catalog.
	 */
	private static final int[] DECIMALS = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
	/**
	 * The ranks of the products 1-5 followed by those of the categories 1-5, 0 standing for no rank.
	 */
	private static final int[] RANKS = {10, 20, 30, 40, 50, 10, 20, 30, 0, 50};
	/**
	 * The labels of the products 1-5 followed by those of the categories 1-5, null standing for no label.
	 */
	private static final String[] LABELS = {
		"red-apple", "red-berry", "green-apple", "blue-berry", "green-fig",
		"red-cherry", "green-grape", "blue-apple", "blue-plum", null
	};
	/**
	 * The codes of the archived product 6 and category 6, which no query of the live scope alone sees.
	 */
	private static final String[] ARCHIVED_CODES = {"p6", "c6"};
	/**
	 * The decimals of the archived product 6 and category 6.
	 */
	private static final int[] ARCHIVED_DECIMALS = {11, 12};
	/**
	 * The ranks of the archived product 6 and category 6.
	 */
	private static final int[] ARCHIVED_RANKS = {30, 60};
	/**
	 * The codes of the entities of both scopes, the anchor of a query without a collection over both of them.
	 */
	private static final String[] CODES_OF_BOTH_SCOPES = Stream.concat(
		Arrays.stream(ALL_CODES), Arrays.stream(ARCHIVED_CODES)
	).toArray(String[]::new);

	private TestPaths paths;
	private Evita evita;

	@BeforeAll
	void setUp() {
		this.paths = createTestPaths("CatalogAttributeWithoutCollectionFunctionalTest");
		this.evita = new Evita(
			newTestEvitaConfigurationBuilder(this.paths)
				.server(ServerOptions.builder().closeSessionsAfterSecondsOfInactivity(-1).build())
				.build()
		);
		this.evita.defineCatalog(TEST_CATALOG)
			.withAttribute(CODE, String.class, thatIs -> thatIs.uniqueGloballyInScope(Scope.values()))
			.withAttribute(
				CATALOG_DECIMAL, BigDecimal.class,
				thatIs -> thatIs.uniqueGloballyInScope(Scope.values()).nullable().indexDecimalPlaces(2)
			)
			.withAttribute(RANK, Integer.class, thatIs -> thatIs.filterable().nullable())
			.withAttribute(LABEL, String.class, thatIs -> thatIs.unique().nullable())
			.updateViaNewSession(this.evita);
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				for (String type : new String[]{PRODUCT, CATEGORY}) {
					session.defineEntitySchema(type)
						.withGlobalAttribute(CODE)
						.withGlobalAttribute(CATALOG_DECIMAL)
						.withGlobalAttribute(RANK)
						.withGlobalAttribute(LABEL)
						.withAttribute(
							LIVE_DECIMAL, BigDecimal.class,
							thatIs -> thatIs.filterable().nullable().indexDecimalPlaces(2)
						)
						.updateVia(session);
				}
				for (int i = 0; i < ALL_CODES.length; i++) {
					session.createNewEntity(i < 5 ? PRODUCT : CATEGORY, i % 5 + 1)
						.setAttribute(CODE, ALL_CODES[i])
						.setAttribute(CATALOG_DECIMAL, new BigDecimal(DECIMALS[i]))
						.setAttribute(RANK, RANKS[i] == 0 ? (Integer) null : Integer.valueOf(RANKS[i]))
						.setAttribute(LABEL, LABELS[i])
						.upsertVia(session);
				}
				for (int i = 0; i < ARCHIVED_CODES.length; i++) {
					session.createNewEntity(i == 0 ? PRODUCT : CATEGORY, 6)
						.setAttribute(CODE, ARCHIVED_CODES[i])
						.setAttribute(CATALOG_DECIMAL, new BigDecimal(ARCHIVED_DECIMALS[i]))
						.setAttribute(RANK, ARCHIVED_RANKS[i])
						.setAttribute(LIVE_DECIMAL, i == 0 ? new BigDecimal("2.5") : null)
						.setScope(Scope.ARCHIVED)
						.upsertVia(session);
				}
				session.goLiveAndClose();
			}
		);
	}

	@AfterAll
	void tearDown() {
		this.evita.close();
		cleanupTestPaths(this.paths);
	}

	@TestFactory
	@DisplayName("a globally unique decimal")
	Stream<DynamicTest> shouldAnswerGloballyUniqueDecimal() {
		return Stream.of(
			catalog("equals(3)", attributeEquals(CATALOG_DECIMAL, new BigDecimal(3)), "Product:3"),
			catalog(
				"inSet(2,7)", attributeInSet(CATALOG_DECIMAL, new BigDecimal(2), new BigDecimal(7)),
				"Category:2", "Product:2"
			),
			catalog(
				"lessThan(3)", attributeLessThan(CATALOG_DECIMAL, new BigDecimal(3)),
				"Product:1", "Product:2"
			),
			catalog(
				"lessThanEquals(7)", attributeLessThanEquals(CATALOG_DECIMAL, new BigDecimal(7)),
				"Category:1", "Category:2", "Product:1", "Product:2", "Product:3", "Product:4", "Product:5"
			),
			catalog(
				"greaterThan(8)", attributeGreaterThan(CATALOG_DECIMAL, new BigDecimal(8)),
				"Category:4", "Category:5"
			),
			catalog(
				"greaterThanEquals(8)", attributeGreaterThanEquals(CATALOG_DECIMAL, new BigDecimal(8)),
				"Category:3", "Category:4", "Category:5"
			),
			catalog(
				"between(4,7)", attributeBetween(CATALOG_DECIMAL, new BigDecimal(4), new BigDecimal(7)),
				"Category:1", "Category:2", "Product:4", "Product:5"
			)
		);
	}

	@TestFactory
	@DisplayName("a filterable integer")
	Stream<DynamicTest> shouldAnswerFilterableInteger() {
		return Stream.of(
			catalog("equals(30)", attributeEquals(RANK, 30), "Category:3", "Product:3"),
			catalog(
				"inSet(10,50)", attributeInSet(RANK, 10, 50),
				"Category:1", "Category:5", "Product:1", "Product:5"
			),
			catalog(
				"lessThan(30)", attributeLessThan(RANK, 30),
				"Category:1", "Category:2", "Product:1", "Product:2"
			),
			catalog(
				"lessThanEquals(30)", attributeLessThanEquals(RANK, 30),
				"Category:1", "Category:2", "Category:3", "Product:1", "Product:2", "Product:3"
			),
			catalog(
				"greaterThan(30)", attributeGreaterThan(RANK, 30),
				"Category:5", "Product:4", "Product:5"
			),
			catalog(
				"greaterThanEquals(30)", attributeGreaterThanEquals(RANK, 30),
				"Category:3", "Category:5", "Product:3", "Product:4", "Product:5"
			),
			catalog(
				"between(20,40)", attributeBetween(RANK, 20, 40),
				"Category:2", "Category:3", "Product:2", "Product:3", "Product:4"
			)
		);
	}

	@TestFactory
	@DisplayName("a string unique within a collection")
	Stream<DynamicTest> shouldAnswerCollectionUniqueString() {
		return Stream.of(
			catalog("equals(red-apple)", attributeEquals(LABEL, "red-apple"), "Product:1"),
			catalog(
				"inSet(red-apple,blue-apple)", attributeInSet(LABEL, "red-apple", "blue-apple"),
				"Category:3", "Product:1"
			),
			catalog(
				"lessThan(green)", attributeLessThan(LABEL, "green"),
				"Category:3", "Category:4", "Product:4"
			),
			catalog(
				"lessThanEquals(green-fig)", attributeLessThanEquals(LABEL, "green-fig"),
				"Category:3", "Category:4", "Product:3", "Product:4", "Product:5"
			),
			catalog(
				"greaterThan(red)", attributeGreaterThan(LABEL, "red"),
				"Category:1", "Product:1", "Product:2"
			),
			catalog(
				"greaterThanEquals(green-grape)", attributeGreaterThanEquals(LABEL, "green-grape"),
				"Category:1", "Category:2", "Product:1", "Product:2"
			),
			catalog(
				"between(green-a,red-apple)", attributeBetween(LABEL, "green-a", "red-apple"),
				"Category:2", "Product:1", "Product:3", "Product:5"
			),
			catalog("startsWith(red)", attributeStartsWith(LABEL, "red"), "Category:1", "Product:1", "Product:2"),
			catalog(
				"endsWith(apple)", attributeEndsWith(LABEL, "apple"),
				"Category:3", "Product:1", "Product:3"
			),
			catalog("contains(ee)", attributeContains(LABEL, "ee"), "Category:2", "Product:3", "Product:5")
		);
	}

	@TestFactory
	@DisplayName("a globally unique string")
	Stream<DynamicTest> shouldAnswerGloballyUniqueString() {
		return Stream.of(
			catalog("equals(p3)", attributeEquals(CODE, "p3"), "Product:3"),
			catalog("inSet(p1,c2)", attributeInSet(CODE, "p1", "c2"), "Category:2", "Product:1"),
			catalog("lessThan(c3)", attributeLessThan(CODE, "c3"), "Category:1", "Category:2"),
			catalog(
				"lessThanEquals(c2)", attributeLessThanEquals(CODE, "c2"), "Category:1", "Category:2"
			),
			catalog(
				"greaterThan(p3)", attributeGreaterThan(CODE, "p3"), "Product:4", "Product:5"
			),
			catalog(
				"greaterThanEquals(p4)", attributeGreaterThanEquals(CODE, "p4"), "Product:4", "Product:5"
			),
			catalog(
				"between(c4,p1)", attributeBetween(CODE, "c4", "p1"), "Category:4", "Category:5", "Product:1"
			),
			catalog(
				"startsWith(c)", attributeStartsWith(CODE, "c"),
				"Category:1", "Category:2", "Category:3", "Category:4", "Category:5"
			),
			catalog("endsWith(1)", attributeEndsWith(CODE, "1"), "Category:1", "Product:1"),
			catalog("contains(2)", attributeContains(CODE, "2"), "Category:2", "Product:2")
		);
	}

	@TestFactory
	@DisplayName("an inScope container over both scopes")
	Stream<DynamicTest> shouldAnswerInScopeContainer() {
		return Stream.of(
			bothScopes(
				"inScope(ARCHIVED, lessThan(12))",
				inScope(Scope.ARCHIVED, attributeLessThan(CATALOG_DECIMAL, new BigDecimal(12))),
				"Category:1", "Category:2", "Category:3", "Category:4", "Category:5",
				"Product:1", "Product:2", "Product:3", "Product:4", "Product:5", "Product:6"
			),
			bothScopes(
				"inScope(LIVE, lessThan(3))",
				inScope(Scope.LIVE, attributeLessThan(CATALOG_DECIMAL, new BigDecimal(3))),
				"Category:6", "Product:1", "Product:2", "Product:6"
			),
			bothScopes(
				"inScope(ARCHIVED, equals(c6))",
				inScope(Scope.ARCHIVED, attributeEquals(CODE, "c6")),
				"Category:1", "Category:2", "Category:3", "Category:4", "Category:5", "Category:6",
				"Product:1", "Product:2", "Product:3", "Product:4", "Product:5"
			),
			bothScopes(
				"inScope(LIVE, equals(rank 30))",
				inScope(Scope.LIVE, attributeEquals(RANK, 30)),
				"Category:3", "Category:6", "Product:3", "Product:6"
			),
			bothScopes(
				"inScope(LIVE, startsWith(c)) and inScope(ARCHIVED, startsWith(p))",
				and(
					inScope(Scope.LIVE, attributeStartsWith(CODE, "c")),
					inScope(Scope.ARCHIVED, attributeStartsWith(CODE, "p"))
				),
				"Category:1", "Category:2", "Category:3", "Category:4", "Category:5", "Product:6"
			),
			bothScopes(
				"inScope(ARCHIVED, not(equals(c6)))",
				inScope(Scope.ARCHIVED, not(attributeEquals(CODE, "c6"))),
				"Category:1", "Category:2", "Category:3", "Category:4", "Category:5",
				"Product:1", "Product:2", "Product:3", "Product:4", "Product:5", "Product:6"
			)
		);
	}

	@Test
	@DisplayName("a comparison alone is refused without a collection")
	void shouldRefuseComparisonWithoutCollectionAndEntitiesToStartFrom() {
		assertThrows(
			EntityCollectionRequiredException.class,
			() -> inSession(
				session -> session.query(
					query(
						filterBy(attributeLessThan(CATALOG_DECIMAL, new BigDecimal(3))),
						require(page(1, 100))
					),
					EntityReference.class
				)
			)
		);
	}

	@Test
	@DisplayName("an equality on a filterable-only attribute alone is refused without a collection")
	void shouldRefuseEqualityOnFilterableOnlyAttributeWithoutCollectionAndEntitiesToStartFrom() {
		assertThrows(
			EntityCollectionRequiredException.class,
			() -> inSession(
				session -> session.query(
					query(filterBy(attributeEquals(RANK, 30)), require(page(1, 100))),
					EntityReference.class
				)
			)
		);
	}

	@Test
	@DisplayName("an inScope container answers no entity when the lookup finds none")
	void shouldAnswerNoEntityInScopeContainerWhenLookupFindsNone() {
		assertEquals(
			List.of(),
			inSession(
				session -> references(
					session.query(
						query(
							filterBy(
								attributeEquals(CODE, "none"),
								inScope(Scope.ARCHIVED, attributeLessThan(CATALOG_DECIMAL, new BigDecimal(12))),
								scope(Scope.LIVE, Scope.ARCHIVED)
							),
							require(page(1, 100))
						),
						EntityReference.class
					)
				)
			)
		);
	}

	@Test
	@DisplayName("a decimal beyond the index key is answered in a scope where the attribute has no index")
	void shouldAnswerDecimalBeyondIndexKeyInScopeWithoutIndex() {
		assertEquals(
			List.of(
				"Category:1", "Category:2", "Category:3", "Category:4", "Category:5",
				"Product:1", "Product:2", "Product:3", "Product:4", "Product:5", "Product:6"
			),
			inSession(
				session -> references(
					session.query(
						query(
							filterBy(
								attributeInSet(CODE, CODES_OF_BOTH_SCOPES),
								inScope(Scope.ARCHIVED, attributeLessThan(LIVE_DECIMAL, BEYOND_INDEX_KEY)),
								scope(Scope.LIVE, Scope.ARCHIVED)
							),
							require(page(1, 100))
						),
						EntityReference.class
					)
				)
			)
		);
	}

	@Test
	@DisplayName("a decimal beyond the index key is refused in a scope where the attribute has an index")
	void shouldRefuseDecimalBeyondIndexKeyInScopeWithIndex() {
		// the refusal names the decimal - a missing collection is refused with the same type of exception
		final EvitaInvalidUsageException refusal = assertThrows(
			EvitaInvalidUsageException.class,
			() -> inSession(
				session -> session.query(
					query(
						filterBy(
							attributeInSet(CODE, CODES_OF_BOTH_SCOPES),
							inScope(Scope.LIVE, attributeLessThan(LIVE_DECIMAL, BEYOND_INDEX_KEY)),
							scope(Scope.LIVE, Scope.ARCHIVED)
						),
						require(page(1, 100))
					),
					EntityReference.class
				)
			)
		);
		assertTrue(refusal.getMessage().contains(BEYOND_INDEX_KEY.toPlainString()), refusal.getMessage());
	}

	/**
	 * Creates the test of the constraint asked in the live scope with the collections and without one; the answer
	 * without a collection must be the union of the answers of the collections and both must be the expected entities.
	 *
	 * @param expected the expected entities as `type:primaryKey`, sorted
	 */
	@Nonnull
	private DynamicTest catalog(@Nonnull String name, @Nonnull FilterConstraint constraint, @Nonnull String... expected) {
		return parity(
			name, filterBy(constraint), filterBy(attributeInSet(CODE, ALL_CODES), constraint), expected
		);
	}

	/**
	 * Creates the test of the constraint asked in both scopes with the collections and without one, as
	 * {@link #catalog} does in the live scope.
	 *
	 * @param expected the expected entities as `type:primaryKey`, sorted
	 */
	@Nonnull
	private DynamicTest bothScopes(@Nonnull String name, @Nonnull FilterConstraint constraint, @Nonnull String... expected) {
		final FilterConstraint scopes = scope(Scope.LIVE, Scope.ARCHIVED);
		return parity(
			name,
			filterBy(constraint, scopes),
			filterBy(attributeInSet(CODE, CODES_OF_BOTH_SCOPES), constraint, scopes),
			expected
		);
	}

	/**
	 * Creates the test comparing the union of the answers of the collections to the filter with the answer of a query
	 * without a collection to the filter anchored by the codes; both must be the expected entities.
	 *
	 * @param withCollection    the filter asked of each collection
	 * @param withoutCollection the filter asked without a collection
	 * @param expected          the expected entities as `type:primaryKey`, sorted
	 */
	@Nonnull
	private DynamicTest parity(
		@Nonnull String name,
		@Nonnull FilterBy withCollection,
		@Nonnull FilterBy withoutCollection,
		@Nonnull String... expected
	) {
		return DynamicTest.dynamicTest(
			name,
			() -> {
				final List<String> perCollection = new ArrayList<>();
				for (String type : new String[]{PRODUCT, CATEGORY}) {
					perCollection.addAll(
						inSession(
							session -> references(
								session.query(
									query(collection(type), withCollection, require(page(1, 100))),
									EntityReference.class
								)
							)
						)
					);
				}
				perCollection.sort(null);
				final List<String> catalogWide = inSession(
					session -> references(
						session.query(query(withoutCollection, require(page(1, 100))), EntityReference.class)
					)
				);
				assertEquals(
					"with collection " + Arrays.asList(expected) + ", without " + Arrays.asList(expected),
					"with collection " + perCollection + ", without " + catalogWide
				);
			}
		);
	}

	/**
	 * Evaluates the function in a read-only session of the test catalog.
	 */
	@Nonnull
	private <T> T inSession(@Nonnull Function<EvitaSessionContract, T> function) {
		return this.evita.queryCatalog(TEST_CATALOG, function);
	}

	/**
	 * Returns the entities of the response as the sorted list of `type:primaryKey`.
	 */
	@Nonnull
	private static List<String> references(@Nonnull EvitaResponse<EntityReference> response) {
		return response.getRecordData()
			.stream()
			.map(it -> it.getType() + ":" + it.getPrimaryKey())
			.sorted()
			.toList();
	}

}
