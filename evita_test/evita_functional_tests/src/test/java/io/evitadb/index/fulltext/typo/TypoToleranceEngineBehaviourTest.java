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

package io.evitadb.index.fulltext.typo;

import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.core.Evita;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import java.util.List;
import java.util.Locale;
import static io.evitadb.api.query.parser.DefaultQueryParser.getInstance;
import static io.evitadb.test.TestTags.*;
import static org.junit.jupiter.api.Assertions.*;

/** The fourteen reference examples, with unchanged expectations, through the real evitaQL query path. */
@Tag(ENGINE)
@Tag(QUERY)
@Tag(FULLTEXT)
@DisplayName("Typo tolerance behaviour through real engine queries")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TypoToleranceEngineBehaviourTest implements EvitaTestSupport {
	private TestPaths paths;
	private Evita evita;

	/** Shares one real Czech catalog across the fourteen read-only examples. */
	@BeforeAll
	void setUp() {
		this.paths = createTestPaths("TypoToleranceEngineBehaviourTest");
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
		this.evita.defineCatalog(TEST_CATALOG);
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.defineEntitySchema("product").withoutGeneratedPrimaryKey().withLocale(Locale.forLanguageTag("cs"))
				.withAttribute("title", String.class, a -> a.localized().searchable()).updateVia(session);
			for (int pk = 1; pk < TypoToleranceBehaviourTest.CATALOG.length; pk++) {
				session.upsertEntity(session.createNewEntity("product", pk)
					.setAttribute("title", Locale.forLanguageTag("cs"), TypoToleranceBehaviourTest.CATALOG[pk]));
			}
		});
	}

	/** Releases the real engine and its isolated catalog. */
	@AfterAll
	void tearDown() {
		this.evita.close();
		cleanupTestPaths(this.paths);
	}

	@Nested
	@DisplayName("Unchanged reference examples")
	class Behaviour {
		/** Uses bound evitaQL parameters, retaining submitted versus typing query shape. */
		@ParameterizedTest(name = "{0}")
		@MethodSource("io.evitadb.index.fulltext.typo.TypoToleranceBehaviourTest#examples")
		@DisplayName("Returns the reference products in their expected order")
		void shouldBehaveAsSpecified(TypoToleranceBehaviourTest.Example example) {
			final List<Integer> actual = TypoToleranceEngineBehaviourTest.this.evita.queryCatalog(
				TEST_CATALOG, session -> {
					return session.query(
						getInstance().parseQuery(
							"query(collection(?), filterBy(entityLocaleEquals(?), textMatches(?)), " +
								"orderBy(relevance()), require(page(?, ?)))",
							"product", Locale.forLanguageTag("cs"),
							example.query() + (example.typing() ? "" : " "), 1, 100
						),
						EntityReference.class
					).getRecordData().stream().map(EntityReference::getPrimaryKey).toList();
				}
			);
			assertEquals(example.expected(), actual, example.toString());
			for (final Integer forbidden : example.mustNot()) {
				assertFalse(actual.contains(forbidden), example.toString());
			}
		}
	}
}
