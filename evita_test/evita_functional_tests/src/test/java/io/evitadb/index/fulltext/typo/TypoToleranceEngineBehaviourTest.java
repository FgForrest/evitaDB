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

import javax.annotation.Nonnull;

import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static io.evitadb.api.query.parser.DefaultQueryParser.getInstance;
import static io.evitadb.test.TestTags.*;
import static org.junit.jupiter.api.Assertions.*;

/** Fourteen typo-tolerance scenarios and their expected product order, run through the real evitaQL query path. */
@Tag(ENGINE)
@Tag(QUERY)
@Tag(FULLTEXT)
@DisplayName("Typo tolerance behaviour through real engine queries")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TypoToleranceEngineBehaviourTest implements EvitaTestSupport {
	/**
	 * The catalog: product titles by primary key.
	 */
	static final String[] CATALOG = {
		null,
		"Černé pánské kalhoty",          // 1
		"Modré dámské kalhoty",          // 2
		"Pánská zimní bunda",            // 3
		"Dámská bunda s kapucí",         // 4
		"Dětská mikina",                 // 5
		"Pánská mikina s kapucí",        // 6
		"Kožený pásek",                  // 7
		"Pletená čepice",                // 8
		"Vlněný svetr",                  // 9
		"Dámský vlněný kabát",           // 10
		"Kabátek pro panenky",           // 11
		"Nabíječka AB1284",              // 12
		"Nabíječka AB1234",              // 13
		"Plyšový pes",                   // 14
		"Pás na nářadí",                 // 15
		"Černá kožená kabelka",          // 16
		"Bílé tričko",                   // 17
		"Triko s dlouhým rukávem"        // 18
	};

	/**
	 * One behaviour example.
	 *
	 * @param description what the example pins
	 * @param query       the query text
	 * @param typing      whether the last word is still being typed (search-as-you-type)
	 * @param expected    the expected result, in order
	 * @param mustNot     products that must not be in the result
	 */
	record Example(
		@Nonnull String description,
		@Nonnull String query,
		boolean typing,
		@Nonnull List<Integer> expected,
		@Nonnull List<Integer> mustNot
	) {
		@Override
		public String toString() {
			return this.description + " — `" + this.query + "`" + (this.typing ? " (typing)" : "");
		}
	}

	/** Supplies the fourteen query scenarios and their expected rankings. */
	@Nonnull
	static Stream<Example> examples() {
		return Stream.of(
			new Example("U3: typing without diacritics is not a typo",
				"cerne kalhoty", false, List.of(1), List.of(2, 16)),
			new Example("U3: wrong diacritics are not a typo either",
				"čérne kalhóty", false, List.of(1), List.of(2)),
			new Example("U1: one substitution in an ordinary word",
				"kalhoti", false, List.of(1, 2), List.of()),
			new Example("U2: an adjacent transposition costs one edit",
				"kalhtoy", false, List.of(1, 2), List.of()),
			new Example("U2: a transposition inside the stem",
				"bnuda", false, List.of(3, 4), List.of()),
			new Example("U3 + U2: missing diacritics and a typo together",
				"cerne kalhtoy", false, List.of(1), List.of(2)),
			new Example("U4: a three-letter word gets no typo",
				"pes", false, List.of(14), List.of(15, 7)),
			new Example("U5: a typo in the first letter is not tolerated",
				"vunda", false, List.of(), List.of(3, 4)),
			new Example("§7.5: a word containing a digit matches exactly",
				"ab1234", false, List.of(13), List.of(12)),
			new Example("U7: an exact match ranks above a typo match",
				"triko", false, List.of(18, 17), List.of()),
			new Example("must-match: every query word has to match",
				"panska mikina", false, List.of(6), List.of(5, 3)),
			// the typo expansion of the unfinished word also admits `pásek` (stem `pask`, one deletion from `pansk`);
			// it is ranked below every prefix match, which is the lane order working as proposed
			new Example("search-as-you-type: prefix matches first, a one-edit neighbour trails",
				"pansk", true, List.of(1, 3, 6, 7), List.of(2, 4)),
			// the stem variants of `kabat` are `kabat` and the over-stripped `kab` (the `-at` paradigm fork); the
			// prefix scan from `kab` reaches `kabelk`, ranked last as a prefix hit — the noise of prefix(hypotheses)
			new Example("U7: while typing, exact ranks above prefix; an over-stripped variant trails",
				"kabat", true, List.of(10, 11, 16), List.of()),
			new Example("U6: an unfinished word with a typo is still found while typing",
				"mikna", true, List.of(5, 6), List.of())
		);
	}

	/** Isolated storage paths of the real catalog. */
	private TestPaths paths;
	/** Engine shared by the read-only scenarios. */
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
			for (int pk = 1; pk < CATALOG.length; pk++) {
				session.upsertEntity(session.createNewEntity("product", pk)
					.setAttribute("title", Locale.forLanguageTag("cs"), CATALOG[pk]));
			}
		});
	}

	/** Releases the real engine and its isolated catalog. */
	@AfterAll
	void tearDown() {
		this.evita.close();
		cleanupTestPaths(this.paths);
	}

	/** Runs every scenario against the real catalog query path. */
	@Nested
	@DisplayName("Query scenarios over a Czech product catalog")
	class Behaviour {
		/** Uses bound evitaQL parameters, retaining submitted versus typing query shape. */
		@ParameterizedTest(name = "{0}")
		@MethodSource("io.evitadb.index.fulltext.typo.TypoToleranceEngineBehaviourTest#examples")
		@DisplayName("Returns the expected products in ranking order and none of the excluded ones")
		void shouldBehaveAsSpecified(@Nonnull Example example) {
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
