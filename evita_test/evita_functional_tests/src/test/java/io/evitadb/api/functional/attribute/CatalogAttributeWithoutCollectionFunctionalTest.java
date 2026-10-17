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
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.core.Evita;
import io.evitadb.dataType.Scope;
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

/**
 * Pins that a constraint on an attribute of the catalog selects the same entities whether the query names the
 * collection or finds its entities by their globally unique code instead, over three attributes that differ in how the
 * catalog indexes them: one unique across the whole catalog, one that is only filterable, and one that is unique within
 * a collection only. The catalog index answers nothing but a globally unique lookup, so a query without a collection
 * evaluates every other constraint on the entities the code lookup found. A query without a collection that has no
 * such entities to start from is refused.
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

	/**
	 * Creates the test of the constraint asked with the collections and without one; the answer without a collection
	 * must be the union of the answers of the collections and both must be the expected entities.
	 *
	 * @param expected the expected entities as `type:primaryKey`, sorted
	 */
	@Nonnull
	private DynamicTest catalog(@Nonnull String name, @Nonnull FilterConstraint constraint, @Nonnull String... expected) {
		return DynamicTest.dynamicTest(
			name,
			() -> {
				final List<String> perCollection = new ArrayList<>();
				for (String type : new String[]{PRODUCT, CATEGORY}) {
					perCollection.addAll(
						inSession(
							session -> references(
								session.query(
									query(collection(type), filterBy(constraint), require(page(1, 100))),
									EntityReference.class
								)
							)
						)
					);
				}
				perCollection.sort(null);
				final List<String> withoutCollection = inSession(
					session -> references(
						session.query(
							query(filterBy(attributeInSet(CODE, ALL_CODES), constraint), require(page(1, 100))),
							EntityReference.class
						)
					)
				);
				assertEquals(
					"with collection " + Arrays.asList(expected) + ", without " + Arrays.asList(expected),
					"with collection " + perCollection + ", without " + withoutCollection
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
