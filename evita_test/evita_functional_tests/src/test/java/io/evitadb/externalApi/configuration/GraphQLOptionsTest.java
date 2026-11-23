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

package io.evitadb.externalApi.configuration;

import io.evitadb.externalApi.graphql.configuration.GraphQLOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import javax.annotation.Nullable;

import static io.evitadb.test.TestTags.EXTERNAL_API;
import static io.evitadb.test.TestTags.GRAPHQL;
import static io.evitadb.test.TestTags.MANAGEMENT;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies the parallel fetching default used by the GraphQL JSON creator constructor.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(EXTERNAL_API)
@Tag(GRAPHQL)
@Tag(MANAGEMENT)
class GraphQLOptionsTest {

	/**
	 * The JSON creator constructor receives a `null` when the configuration omits `parallelize`; it must resolve it
	 * to the same default as the other constructors and keep an explicit value.
	 */
	@ParameterizedTest
	@DisplayName("should resolve a missing parallelize value to true and keep an explicit one")
	@CsvSource(value = {"null, true", "false, false", "true, true"}, nullValues = "null")
	void shouldDefaultParallelizeToTrueInJsonCreator(@Nullable Boolean parallelize, boolean expected) {
		final GraphQLOptions options = new GraphQLOptions(
			null, "localhost:5555", null, null, null, null, parallelize, null, null, null, null
		);

		assertEquals(expected, options.isParallelize(), "parallelize=" + parallelize);
	}
}
