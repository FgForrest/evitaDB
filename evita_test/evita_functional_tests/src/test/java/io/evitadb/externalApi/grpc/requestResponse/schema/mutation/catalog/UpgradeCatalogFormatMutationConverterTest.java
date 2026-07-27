/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025-2026
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

import io.evitadb.api.requestResponse.schema.mutation.engine.UpgradeCatalogFormatMutation;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.engine.UpgradeCatalogFormatMutationConverter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static io.evitadb.test.TestTags.GRPC;
import static io.evitadb.test.TestTags.EXTERNAL_API;
import static io.evitadb.test.TestTags.QUERY;

/**
 * Tests for the gRPC round-trip of `UpgradeCatalogFormatMutation` ( external-API surface).
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("UpgradeCatalogFormatMutationConverter gRPC round-trip")
@Tag(GRPC)
@Tag(EXTERNAL_API)
@Tag(QUERY)
class UpgradeCatalogFormatMutationConverterTest {

	private static UpgradeCatalogFormatMutationConverter converter;

	@BeforeAll
	static void setup() {
		converter = UpgradeCatalogFormatMutationConverter.INSTANCE;
	}

	@Test
	@DisplayName("should round-trip UpgradeCatalogFormatMutation through gRPC")
	void shouldRoundTripUpgradeCatalogFormatMutationThroughGrpc() {
		final UpgradeCatalogFormatMutation mutation = new UpgradeCatalogFormatMutation(
			"testCatalog",
			3,
			5
		);
		assertEquals(mutation, converter.convert(converter.convert(mutation)));
	}
}
