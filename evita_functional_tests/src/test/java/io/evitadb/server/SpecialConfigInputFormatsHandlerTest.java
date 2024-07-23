/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

package io.evitadb.server;

import com.fasterxml.jackson.databind.DeserializationContext;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The test verifies conversion logic for loading yaml file.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
class SpecialConfigInputFormatsHandlerTest {
	private static final DeserializationContext MOCK_CONTEXT = Mockito.mock(DeserializationContext.class);
	private static final SpecialConfigInputFormatsHandler TESTED_HANDLER = new SpecialConfigInputFormatsHandler();

	@Test
	void shouldConvertNumberValues() throws IOException {
		assertConvertedValueEquals(1_000L, "1K");
		assertConvertedValueEquals(1_000_000L, "1M");
		assertConvertedValueEquals(1_000_000_000L, "1G");
		assertConvertedValueEquals(1_000_000_000_000L, "1T");
	}

	@Test
	void shouldConvertSizeValues() throws IOException {
		assertConvertedValueEquals(1_024L, "1KB");
		assertConvertedValueEquals(1_024L * 1_024L, "1MB");
		assertConvertedValueEquals(1_024L * 1_024L * 1_024L, "1GB");
		assertConvertedValueEquals(1_024L * 1_024L * 1_024L * 1_024L, "1TB");
	}

	@Test
	void shouldConvertTimeValues() throws IOException {
		assertConvertedValueEquals(1L, "1s");
		assertConvertedValueEquals(60L, "1m");
		assertConvertedValueEquals(60L * 60L, "1h");
		assertConvertedValueEquals(60L * 60L * 24L, "1d");
		assertConvertedValueEquals(604_800L, "1w");
		assertConvertedValueEquals(31_556_926L, "1y");
	}

	private static void assertConvertedValueEquals(long converted, String original) throws IOException {
		assertEquals(converted, TESTED_HANDLER.handleWeirdStringValue(MOCK_CONTEXT, Long.class, original, "Error!"));
	}

}
