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

import io.evitadb.api.configuration.FulltextOptions;
import io.evitadb.exception.EvitaInvalidUsageException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.FULLTEXT;
import static org.junit.jupiter.api.Assertions.*;

/** Submitted-search defaults and validation, independent of configuration plumbing. */
@Tag(ENGINE)
@Tag(FULLTEXT)
@DisplayName("Fulltext typo parameters")
class FulltextTypoParametersTest {
	/** Built-in options mapped to the engine policy. */
	private static final FulltextTypoParameters DEFAULT_PARAMETERS =
		new FulltextTypoParameters(new FulltextOptions());

	/** Submitted-search thresholds. */
	@Nested
	@DisplayName("Submitted-search thresholds")
	class Thresholds {
		/** Counts folded code points and matches every Unicode digit exactly. */
		@ParameterizedTest
		@CsvSource({"'',0", "a,0", "abc,0", "abcd,1", "abcdefg,1", "abcdefgh,2", "abcdefghijk,2",
			"ab123456,0", "abcdefgh١,0", "abcdefgh𝟙,0", "𐐨𐐨𐐨,0", "𐐨𐐨𐐨𐐨,1"})
		@DisplayName("Counts folded code points and matches every Unicode digit exactly")
		void shouldApplyThresholds(String word, int edits) {
			assertEquals(edits, DEFAULT_PARAMETERS.maxEdits(word));
		}

		/** Uses the specified defaults and honors configurable thresholds and digit policy. */
		@Test
		@DisplayName("Uses the specified defaults and honors configurable thresholds and digit policy")
		void shouldExposeDefaultsAndOverrides() {
			assertEquals(
				new FulltextTypoParameters(new FulltextOptions(4, 8, 1, true, true, true, 4, 4)),
				DEFAULT_PARAMETERS
			);
			final FulltextOptions digitTypos = FulltextOptions.builder()
				.minLengthOneTypo(1).minLengthTwoTypos(1).nonFuzzyPrefix(0)
				.transpositions(false).digitsExact(false).build();
			assertEquals(2, new FulltextTypoParameters(digitTypos).maxEdits("𝟙"));
			final FulltextOptions longerTerms = FulltextOptions.builder()
				.minLengthOneTypo(5).minLengthTwoTypos(9).nonFuzzyPrefix(12).build();
			assertEquals(0, new FulltextTypoParameters(longerTerms).maxEdits("abcd"));
		}

		/**
		 * Pairwise-distinct numbers and two boolean patterns expose any two items swapped by the mapping, which the
		 * built-in defaults cannot: three of them are 4 and all the booleans are true.
		 */
		@ParameterizedTest
		@CsvSource({"false,true,false", "true,false,false"})
		@DisplayName("Maps every option item to its own policy component")
		void shouldMapEveryOptionItem(boolean transpositions, boolean digitsExact, boolean prefixLeg) {
			final FulltextOptions options = new FulltextOptions(3, 9, 2, transpositions, digitsExact, prefixLeg, 5, 6);

			final FulltextTypoParameters parameters = new FulltextTypoParameters(options);

			assertEquals(options.minLengthOneTypo(), parameters.minLengthOneTypo());
			assertEquals(options.minLengthTwoTypos(), parameters.minLengthTwoTypos());
			assertEquals(options.nonFuzzyPrefix(), parameters.nonFuzzyPrefix());
			assertEquals(options.transpositions(), parameters.transpositions());
			assertEquals(options.digitsExact(), parameters.digitsExact());
			assertEquals(options.prefixLeg(), parameters.prefixLeg());
			assertEquals(options.minFragmentLength(), parameters.minFragmentLength());
			assertEquals(options.openEndedStartLength(), parameters.openEndedStartLength());
		}
	}
	/** Submitted input-mode defaults. */
	@Nested
	@DisplayName("Submitted input mode")
	class InputMode {
		/** Fragment and open-ended start lengths must both be positive. */
		@ParameterizedTest
		@CsvSource({"0,4", "-1,4", "4,0", "4,-1"})
		@DisplayName("Rejects nonpositive fragment and open-ended start lengths")
		void shouldRejectInvalidInputMode(int fragment, int openEnd) {
			assertThrows(EvitaInvalidUsageException.class,
				() -> new FulltextTypoParameters(FulltextOptions.builder()
					.minFragmentLength(fragment).openEndedStartLength(openEnd).build()));
		}

		/** Input mode belongs to the immutable policy, independently of configuration. */
		@Test
		@DisplayName("Enables prefix and open-ended expansion from four letters")
		void shouldExposeSubmittedDefaults() {
			assertTrue(DEFAULT_PARAMETERS.prefixLeg());
			assertEquals(4, DEFAULT_PARAMETERS.minFragmentLength());
			assertEquals(4, DEFAULT_PARAMETERS.openEndedStartLength());
		}
	}
	/** Validation. */
	@Nested
	@DisplayName("Validation")
	class Validation {
		/** Rejects nonpositive one-typo length, reversed thresholds and negative prefix, naming the parameter. */
		@ParameterizedTest
		@CsvSource({"0,8,1,minLengthOneTypo", "-1,8,1,minLengthOneTypo", "8,4,1,minLengthTwoTypos",
			"4,8,-1,nonFuzzyPrefix"})
		@DisplayName("Rejects nonpositive one-typo length, reversed thresholds and negative prefix")
		void shouldRejectInvalidParameters(
			int minLengthOneTypo, int minLengthTwoTypos, int nonFuzzyPrefix, String parameter
		) {
			final EvitaInvalidUsageException error = assertThrows(
				EvitaInvalidUsageException.class,
				() -> new FulltextTypoParameters(FulltextOptions.builder()
					.minLengthOneTypo(minLengthOneTypo).minLengthTwoTypos(minLengthTwoTypos)
					.nonFuzzyPrefix(nonFuzzyPrefix).build())
			);

			assertTrue(error.getMessage().contains("fulltext." + parameter), error.getMessage());
		}
	}
}
