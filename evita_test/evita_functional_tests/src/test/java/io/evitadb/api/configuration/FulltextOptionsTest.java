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

package io.evitadb.api.configuration;

import io.evitadb.exception.EvitaInvalidUsageException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static io.evitadb.test.TestTags.*;
import static org.junit.jupiter.api.Assertions.*;

/** Configuration contracts for submitted fulltext searches. */
@Tag(CONTRACT)
@Tag(MANAGEMENT)
@Tag(FULLTEXT)
@DisplayName("Fulltext options")
class FulltextOptionsTest {
	/** Defaults, every builder setter and the configuration copy. */
	@Nested
	@DisplayName("Defaults and builders")
	class Builders {
		/** Pins every submitted-search default. */
		@Test
		@DisplayName("Uses the specified defaults")
		void shouldUseSpecDefaults() {
			assertEquals(new FulltextOptions(4, 8, 1, true, true, true, 4, 4), new FulltextOptions());
			assertEquals(new FulltextOptions(), FulltextOptions.builder().build());
			assertEquals(new FulltextOptions(), EvitaConfiguration.builder().name("options-test").build().fulltext());
		}

		/** Every setter reaches its own item, and a configuration copy keeps the options. */
		@Test
		@DisplayName("Sets every builder field and keeps the options in a configuration copy")
		void shouldSetEveryItemAndKeepOptionsInConfigurationCopy() {
			final FulltextOptions custom = FulltextOptions.builder().minLengthOneTypo(6).minLengthTwoTypos(10)
				.nonFuzzyPrefix(0).transpositions(false).digitsExact(false).prefixLeg(false)
				.minFragmentLength(3).openEndedStartLength(5).build();
			assertEquals(new FulltextOptions(6, 10, 0, false, false, false, 3, 5), custom);
			final EvitaConfiguration config = EvitaConfiguration.builder().name("options-test").fulltext(custom).build();
			assertSame(custom, EvitaConfiguration.builder(config).build().fulltext());
		}

		/** Existing embedded constructor callers continue to receive the defaults. */
		@Test
		@SuppressWarnings("removal") // the constructor is @Deprecated(forRemoval); testing it is the point
		@DisplayName("Keeps the previous public configuration constructor")
		void shouldKeepOldConstructor() {
			final EvitaConfiguration config = EvitaConfiguration.builder().name("options-test").build();
			assertEquals(config, new EvitaConfiguration(config.name(), config.server(), config.storage(),
				config.transaction(), config.cache(), config.export()));
		}
	}

	/** Invalid user input names the exact YAML key. */
	@Nested
	@DisplayName("Validation")
	class Validation {
		/** Every numeric lower bound is checked, including the relation between typo thresholds. */
		@ParameterizedTest
		@CsvSource({"0,8,1,4,4,minLengthOneTypo", "-1,8,1,4,4,minLengthOneTypo",
			"4,3,1,4,4,minLengthTwoTypos", "4,0,1,4,4,minLengthTwoTypos",
			"4,8,-1,4,4,nonFuzzyPrefix", "4,8,1,0,4,minFragmentLength",
			"4,8,1,-1,4,minFragmentLength", "4,8,1,4,0,openEndedStartLength",
			"4,8,1,4,-1,openEndedStartLength"})
		@DisplayName("Rejects values below each bound and names the YAML key")
		void shouldRejectInvalidValues(int one, int two, int prefix, int fragment, int openEnd, String key) {
			final EvitaInvalidUsageException error = assertThrows(EvitaInvalidUsageException.class,
				() -> new FulltextOptions(one, two, prefix, true, true, true, fragment, openEnd));
			assertTrue(error.getMessage().contains("fulltext." + key), error.getMessage());
		}

		/** There is no artificial upper bound; equal thresholds and a zero frozen prefix are legal. */
		@Test
		@DisplayName("Accepts lower bounds, equal thresholds and the largest integer values")
		void shouldAcceptBoundaries() {
			assertDoesNotThrow(() -> new FulltextOptions(1, 1, 0, true, true, true, 1, 1));
			assertDoesNotThrow(() -> new FulltextOptions(Integer.MAX_VALUE, Integer.MAX_VALUE,
				Integer.MAX_VALUE, false, false, false, Integer.MAX_VALUE, Integer.MAX_VALUE));
		}
	}
}
