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

package io.evitadb.externalApi.grpc.requestResponse.schema.mutation.reference;

import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.mutation.reference.CreateReferenceSchemaMutation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CreateReferenceSchemaMutationConverterTest {

	private static CreateReferenceSchemaMutationConverter converter;

	@BeforeAll
	static void setup() {
		converter = new CreateReferenceSchemaMutationConverter();
	}

	@Test
	void shouldConvertMutation() {
		final CreateReferenceSchemaMutation mutation1 = new CreateReferenceSchemaMutation(
			"tags",
			"desc",
			"depr",
			Cardinality.ONE_OR_MORE,
			"tag",
			false,
			"tagGroup",
			false,
			true,
			true
		);
		assertEquals(mutation1, converter.convert(converter.convert(mutation1)));

		final CreateReferenceSchemaMutation mutation2 = new CreateReferenceSchemaMutation(
			"tags",
			null,
			null,
			null,
			"tag",
			false,
			null,
			false,
			true,
			true
		);
		assertEquals(mutation2, converter.convert(converter.convert(mutation2)));
	}
}
