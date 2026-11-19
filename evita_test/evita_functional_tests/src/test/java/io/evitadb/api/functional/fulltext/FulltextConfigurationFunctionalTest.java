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

import io.evitadb.api.configuration.FulltextOptions;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.core.Evita;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.*;

import java.util.Locale;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.api.requestResponse.schema.analysis.AnalysisSteps.lowercase;
import static io.evitadb.test.TestTags.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Query results use the options of the actual embedded engine. */
@Tag(ENGINE)
@Tag(QUERY)
@Tag(FULLTEXT)
@DisplayName("Configured fulltext queries")
class FulltextConfigurationFunctionalTest implements EvitaTestSupport {
	/** Exercises each option against an independent engine using the built-in defaults. */
	@Nested
	@DisplayName("Configured query results")
	class Queries {
		/** A final fragment is completed only while prefix expansion is enabled. */
		@Test
		@DisplayName("Disables completion of the unfinished final word")
		void shouldDisablePrefixLeg() {
			assertMatches(new FulltextOptions(), "watermelon", "water", 1);
			assertMatches(FulltextOptions.builder().prefixLeg(false).build(), "watermelon", "water", 0);
		}

		/** A five-letter typo loses its budget when the first threshold rises to six. */
		@Test
		@DisplayName("Changes the minimum length for one typo")
		void shouldRaiseOneTypoThreshold() {
			assertMatches(new FulltextOptions(), "apple", "apxle ", 1);
			assertMatches(FulltextOptions.builder().minLengthOneTypo(6).build(), "apple", "apxle ", 0);
		}

		/** Digit-containing terms get the ordinary length budget when digit exactness is off. */
		@Test
		@DisplayName("Allows a typo in a term containing a digit")
		void shouldAllowDigitTypo() {
			assertMatches(new FulltextOptions(), "ab1234", "ab1235 ", 0);
			assertMatches(FulltextOptions.builder().digitsExact(false).build(), "ab1234", "ab1235 ", 1);
		}

		/** A seven-letter word gets a second typo only once the second threshold drops to seven. */
		@Test
		@DisplayName("Changes the minimum length for two typos")
		void shouldLowerTwoTyposThreshold() {
			assertMatches(new FulltextOptions(), "dolphin", "dxlphxn ", 0);
			assertMatches(FulltextOptions.builder().minLengthTwoTypos(7).build(), "dolphin", "dxlphxn ", 1);
		}

		/** A typo in the first letter is tolerated only once no leading letter is frozen. */
		@Test
		@DisplayName("Allows a typo in the first letter without a frozen prefix")
		void shouldAllowFirstLetterTypo() {
			assertMatches(new FulltextOptions(), "apple", "xpple ", 0);
			assertMatches(FulltextOptions.builder().nonFuzzyPrefix(0).build(), "apple", "xpple ", 1);
		}

		/** Two swapped neighbours cost one typo with transpositions and two ordinary edits without them. */
		@Test
		@DisplayName("Disables adjacent swaps as a single typo")
		void shouldDisableTranspositions() {
			assertMatches(new FulltextOptions(), "apple", "aplpe ", 1);
			assertMatches(FulltextOptions.builder().transpositions(false).build(), "apple", "aplpe ", 0);
		}

		/** A three-letter unfinished fragment completes only once the fragment minimum drops to three. */
		@Test
		@DisplayName("Changes the minimum length of a completed fragment")
		void shouldLowerMinFragmentLength() {
			assertMatches(new FulltextOptions(), "watermelon", "wat", 0);
			assertMatches(FulltextOptions.builder().minFragmentLength(3).build(), "watermelon", "wat", 1);
		}

		/** A five-letter unfinished fragment with a typo completes only while it reaches the open-ended start. */
		@Test
		@DisplayName("Changes the minimum length for completion with typos")
		void shouldRaiseOpenEndedStartLength() {
			assertMatches(new FulltextOptions(), "watermelon", "watxr", 1);
			assertMatches(FulltextOptions.builder().openEndedStartLength(6).build(), "watermelon", "watxr", 0);
		}
	}

	/** Starts an isolated real engine, writes the indexed value and queries it with the supplied options. */
	private void assertMatches(FulltextOptions options, String indexed, String text, int expected) {
		final TestPaths paths = createTestPaths("FulltextConfigurationFunctionalTest");
		try (final Evita evita = new Evita(newTestEvitaConfigurationBuilder(paths).fulltext(options).build())) {
			evita.defineCatalog(TEST_CATALOG);
			evita.updateCatalog(TEST_CATALOG, session -> {
				session.getCatalogSchema().openForWrite()
					.withFulltextAnalyzer("plain", a -> a.words(lowercase())).updateVia(session);
				session.defineEntitySchema("product").withoutGeneratedPrimaryKey().withLocale(Locale.ENGLISH)
					.withAttribute("title", String.class, a -> a.localized().searchable().analyzer("plain"))
					.updateVia(session);
				session.upsertEntity(session.createNewEntity("product", 1)
					.setAttribute("title", Locale.ENGLISH, indexed));
			});
			final int count = evita.queryCatalog(TEST_CATALOG, session -> {
				return session.query(query(collection("product"),
					filterBy(entityLocaleEquals(Locale.ENGLISH), textMatches(text))), EntityReference.class)
					.getTotalRecordCount();
			});
			assertEquals(expected, count, text + " with " + options);
		} finally {
			cleanupTestPaths(paths);
		}
	}
}
