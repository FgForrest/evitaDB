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

import io.evitadb.api.requestResponse.schema.mutation.engine.CreateCatalogSchemaMutation;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.engine.CreateCatalogSchemaMutationConverter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CreateCatalogSchemaMutationConverterTest {

	private static CreateCatalogSchemaMutationConverter converter;

	@BeforeAll
	static void setup() {
		converter = CreateCatalogSchemaMutationConverter.INSTANCE;
	}

	@Test
	void shouldConvertMutation() {
		final CreateCatalogSchemaMutation mutation1 = new CreateCatalogSchemaMutation(
			"testCatalog"
		);
		assertEquals(mutation1, converter.convert(converter.convert(mutation1)));
	}
}
