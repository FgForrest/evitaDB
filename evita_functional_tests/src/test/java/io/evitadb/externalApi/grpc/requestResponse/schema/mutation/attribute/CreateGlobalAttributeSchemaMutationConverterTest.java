/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.externalApi.grpc.requestResponse.schema.mutation.attribute;

import io.evitadb.api.requestResponse.schema.mutation.attribute.CreateGlobalAttributeSchemaMutation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CreateGlobalAttributeSchemaMutationConverterTest {

	private static CreateGlobalAttributeSchemaMutationConverter converter;

	@BeforeAll
	static void setup() {
		converter = new CreateGlobalAttributeSchemaMutationConverter();
	}

	@Test
	void shouldConvertMutation() {
		final CreateGlobalAttributeSchemaMutation mutation1 = new CreateGlobalAttributeSchemaMutation(
			"code",
			"desc",
			"depr",
			true,
			true,
			true,
			true,
			true,
			false,
			String.class,
			"defaultCode",
			0
		);
		assertEquals(mutation1, converter.convert(converter.convert(mutation1)));

		final CreateGlobalAttributeSchemaMutation mutation2 = new CreateGlobalAttributeSchemaMutation(
			"code",
			null,
			null,
			false,
			false,
			false,
			false,
			false,
			true,
			String.class,
			null,
			0
		);
		assertEquals(mutation2, converter.convert(converter.convert(mutation2)));
	}
}