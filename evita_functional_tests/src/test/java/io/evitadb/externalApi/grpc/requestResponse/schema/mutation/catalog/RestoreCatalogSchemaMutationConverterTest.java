/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

package io.evitadb.externalApi.grpc.requestResponse.schema.mutation.catalog;

import io.evitadb.api.requestResponse.schema.mutation.engine.RestoreCatalogSchemaMutation;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.engine.RestoreCatalogSchemaMutationConverter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Test class for {@link RestoreCatalogSchemaMutationConverter}. This test suite verifies
 * the functionality of the gRPC converter for catalog schema restoration mutations,
 * ensuring proper bidirectional conversion between Java objects and gRPC messages.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("RestoreCatalogSchemaMutationConverter gRPC functionality")
class RestoreCatalogSchemaMutationConverterTest {

	private static RestoreCatalogSchemaMutationConverter converter;

	@BeforeAll
	static void setup() {
		converter = RestoreCatalogSchemaMutationConverter.INSTANCE;
	}

	@Test
	@DisplayName("Should convert mutation in both directions when restoring catalog")
	void shouldConvertMutationWhenRestoringCatalog() {
		final RestoreCatalogSchemaMutation mutation1 = new RestoreCatalogSchemaMutation(
			"testCatalog"
		);
		assertEquals(mutation1, converter.convert(converter.convert(mutation1)));
	}
}