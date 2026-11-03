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
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.TestInstance;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Stream;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.FILTER;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the answers of a query that names no collection and combines several attribute constraints. Such a query finds
 * its entities by their globally unique code, and every constraint on an attribute that is not a catalog one is then
 * evaluated on the found entities. Each constraint must narrow the answer by itself, whatever its neighbours are and in
 * whichever order they come.
 *
 * The fixture holds five products of one collection, each with an `a` and a `b` attribute and a localized title:
 *
 * | product | a | b | locale |
 * |---------|---|---|--------|
 * | 1       | x | 1 | cs     |
 * | 2       | x | 5 | en     |
 * | 3       | y | 1 | cs     |
 * | 4       | y | 5 | en     |
 * | 5       | x | 1 | en     |
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("A query naming no collection applies every attribute constraint to the entities it finds")
@Tag(ENGINE)
@Tag(FILTER)
@Tag(ATTRIBUTE)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EntityFilteringConjunctionFunctionalTest implements EvitaTestSupport {

	private static final String PRODUCT = "Product";
	private static final String CODE = "code";
	private static final String A = "a";
	private static final String B = "b";
	private static final String TITLE = "title";
	private static final Locale CZECH = Locale.forLanguageTag("cs");
	private static final Locale ENGLISH = Locale.forLanguageTag("en");
	private static final String[] CODES = {"p1", "p2", "p3", "p4", "p5"};
	private static final String[] A_VALUES = {"x", "x", "y", "y", "x"};
	private static final int[] B_VALUES = {1, 5, 1, 5, 1};
	private static final Locale[] LOCALES = {CZECH, ENGLISH, CZECH, ENGLISH, ENGLISH};

	private TestPaths paths;
	private Evita evita;

	@BeforeAll
	void setUp() {
		this.paths = createTestPaths("EntityFilteringConjunctionFunctionalTest");
		this.evita = new Evita(
			newTestEvitaConfigurationBuilder(this.paths)
				.server(ServerOptions.builder().closeSessionsAfterSecondsOfInactivity(-1).build())
				.build()
		);
		this.evita.defineCatalog(TEST_CATALOG)
			.withAttribute(CODE, String.class, thatIs -> thatIs.uniqueGloballyInScope(Scope.values()))
			.updateViaNewSession(this.evita);
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(PRODUCT)
					.withGlobalAttribute(CODE)
					.withLocale(CZECH, ENGLISH)
					.withAttribute(A, String.class, whichIs -> whichIs.filterable().nullable())
					.withAttribute(B, Integer.class, whichIs -> whichIs.filterable().nullable())
					.withAttribute(TITLE, String.class, whichIs -> whichIs.localized().nullable())
					.updateVia(session);
				for (int i = 0; i < CODES.length; i++) {
					session.createNewEntity(PRODUCT, i + 1)
						.setAttribute(CODE, CODES[i])
						.setAttribute(A, A_VALUES[i])
						.setAttribute(B, B_VALUES[i])
						.setAttribute(TITLE, LOCALES[i], "title" + (i + 1))
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
	@DisplayName("every constraint of a collection-less query narrows the answer")
	Stream<DynamicTest> shouldApplyEveryAttributeConstraint() {
		final List<DynamicTest> tests = new ArrayList<>(32);
		// two attribute constraints, both orders
		tests.add(entityFiltering("a=x, b<3", "[1, 5]", attributeEquals(A, "x"), attributeLessThan(B, 3)));
		tests.add(entityFiltering("b<3, a=x", "[1, 5]", attributeLessThan(B, 3), attributeEquals(A, "x")));
		tests.add(entityFiltering("a=y, b>3", "[4]", attributeEquals(A, "y"), attributeGreaterThan(B, 3)));
		tests.add(entityFiltering("b>3, a=y", "[4]", attributeGreaterThan(B, 3), attributeEquals(A, "y")));
		// a locale and an attribute constraint, both orders
		tests.add(
			entityFiltering(
				"locale cs, a=x", "[1]", entityLocaleEquals(CZECH), attributeEquals(A, "x")
			)
		);
		tests.add(
			entityFiltering(
				"a=x, locale cs", "[1]", attributeEquals(A, "x"), entityLocaleEquals(CZECH)
			)
		);
		// attribute constraints inside the logical containers
		tests.add(
			entityFiltering(
				"and(a=x, b<3)", "[1, 5]", and(attributeEquals(A, "x"), attributeLessThan(B, 3))
			)
		);
		tests.add(
			entityFiltering(
				"and(b<3, a=x)", "[1, 5]", and(attributeLessThan(B, 3), attributeEquals(A, "x"))
			)
		);
		tests.add(
			entityFiltering(
				"or(a=y, b>3)", "[2, 3, 4]", or(attributeEquals(A, "y"), attributeGreaterThan(B, 3))
			)
		);
		tests.add(
			entityFiltering(
				"or(b>3, a=y)", "[2, 3, 4]", or(attributeGreaterThan(B, 3), attributeEquals(A, "y"))
			)
		);
		tests.add(
			entityFiltering(
				"not(a=x), b<3", "[3]", not(attributeEquals(A, "x")), attributeLessThan(B, 3)
			)
		);
		tests.add(
			entityFiltering(
				"b<3, not(a=x)", "[3]", attributeLessThan(B, 3), not(attributeEquals(A, "x"))
			)
		);
		tests.add(
			entityFiltering(
				"a=x, not(b<3)", "[2]", attributeEquals(A, "x"), not(attributeLessThan(B, 3))
			)
		);
		// the same constraint twice answers as it does once
		tests.add(entityFiltering("a=x, a=x", "[1, 2, 5]", attributeEquals(A, "x"), attributeEquals(A, "x")));
		// three constraints
		tests.add(
			entityFiltering(
				"a=x, b>3, locale en", "[2]",
				attributeEquals(A, "x"), attributeGreaterThan(B, 3), entityLocaleEquals(ENGLISH)
			)
		);
		tests.add(
			entityFiltering(
				"locale en, a=x, b<3", "[5]",
				entityLocaleEquals(ENGLISH), attributeEquals(A, "x"), attributeLessThan(B, 3)
			)
		);
		return tests.stream();
	}

	/**
	 * Creates the test of a query naming no collection that finds the products by their codes and applies the
	 * constraints.
	 */
	@Nonnull
	private DynamicTest entityFiltering(
		@Nonnull String name,
		@Nonnull String expected,
		@Nonnull FilterConstraint... constraints
	) {
		return DynamicTest.dynamicTest(
			name,
			() -> assertEquals(expected, this.inSession(session -> {
				final FilterConstraint[] filter = new FilterConstraint[constraints.length + 1];
				filter[0] = attributeInSet(CODE, CODES);
				System.arraycopy(constraints, 0, filter, 1, constraints.length);
				return keys(session.query(query(filterBy(filter), require(page(1, 100))), EntityReference.class));
			}))
		);
	}

	/**
	 * Evaluates the function in a read-only session of the test catalog.
	 */
	@Nonnull
	private String inSession(@Nonnull Function<EvitaSessionContract, String> function) {
		return this.evita.queryCatalog(TEST_CATALOG, function);
	}

	/**
	 * Returns the primary keys of the response as the sorted set of them.
	 */
	@Nonnull
	private static String keys(@Nonnull EvitaResponse<EntityReference> response) {
		final Set<Integer> result = new TreeSet<>();
		for (EntityReference reference : response.getRecordData()) {
			result.add(reference.getPrimaryKey());
		}
		return result.toString();
	}

}
