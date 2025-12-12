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

package io.evitadb.externalApi.grpc.requestResponse.data.mutation;

import io.evitadb.api.requestResponse.data.mutation.EntityMutation.EntityExistence;
import io.evitadb.api.requestResponse.data.mutation.EntityUpsertMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.UpsertAttributeMutation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EntityUpsertMutationFactoryTest {

	private static EntityUpsertMutationConverter converter;

	@BeforeAll
	static void setup() {
		converter = EntityUpsertMutationConverter.INSTANCE;
	}

	@Test
	void shouldConvertMutation() {
		final EntityUpsertMutation mutation1 = new EntityUpsertMutation(
			"product",
			1,
			EntityExistence.MAY_EXIST,
			List.of(
				new UpsertAttributeMutation("code", "someCode")
			)
		);
		assertEquals(mutation1, converter.convert(converter.convert(mutation1)));

		final EntityUpsertMutation mutation2 = new EntityUpsertMutation(
			"product",
			null,
			EntityExistence.MAY_EXIST,
			List.of(
				new UpsertAttributeMutation("code", "someCode")
			)
		);
		assertEquals(mutation2, converter.convert(converter.convert(mutation2)));
	}
}
