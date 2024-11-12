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

package io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.entity;

import io.evitadb.api.requestResponse.schema.mutation.entity.SetEntitySchemaWithPriceMutation;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.externalApi.api.catalog.mutation.TestMutationResolvingExceptionFactory;
import io.evitadb.externalApi.api.catalog.resolver.mutation.PassThroughMutationObjectParser;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.entity.SetEntitySchemaWithPriceMutationDescriptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.evitadb.test.builder.MapBuilder.map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link SetEntitySchemaWithPriceMutationConverter}
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
class SetEntitySchemaWithPriceMutationConverterTest {

	private SetEntitySchemaWithPriceMutationConverter converter;

	@BeforeEach
	void init() {
		converter = new SetEntitySchemaWithPriceMutationConverter(new PassThroughMutationObjectParser(), new TestMutationResolvingExceptionFactory());
	}

	@Test
	void shouldResolveInputToLocalMutation() {
		final SetEntitySchemaWithPriceMutation expectedMutation = new SetEntitySchemaWithPriceMutation(true, new Scope[] { Scope.LIVE }, 2);

		final SetEntitySchemaWithPriceMutation convertedMutation1 = converter.convert(
			map()
				.e(SetEntitySchemaWithPriceMutationDescriptor.WITH_PRICE.name(), true)
				.e(SetEntitySchemaWithPriceMutationDescriptor.INDEXED_IN_SCOPES.name(), new Scope[] { Scope.LIVE })
				.e(SetEntitySchemaWithPriceMutationDescriptor.INDEXED_PRICE_PLACES.name(), 2)
				.build()
		);
		assertEquals(expectedMutation, convertedMutation1);

		final SetEntitySchemaWithPriceMutation convertedMutation2 = converter.convert(
			map()
				.e(SetEntitySchemaWithPriceMutationDescriptor.WITH_PRICE.name(), "true")
				.e(SetEntitySchemaWithPriceMutationDescriptor.INDEXED_IN_SCOPES.name(), new Scope[] { Scope.LIVE })
				.e(SetEntitySchemaWithPriceMutationDescriptor.INDEXED_PRICE_PLACES.name(), "2")
				.build()
		);
		assertEquals(expectedMutation, convertedMutation2);
	}

	@Test
	void shouldNotResolveInputWhenMissingRequiredData() {
		assertThrows(
			EvitaInvalidUsageException.class,
			() -> converter.convert(
				map()
					.e(SetEntitySchemaWithPriceMutationDescriptor.INDEXED_PRICE_PLACES.name(), 2)
					.build()
			)
		);
		assertThrows(
			EvitaInvalidUsageException.class,
			() -> converter.convert(
				map()
					.e(SetEntitySchemaWithPriceMutationDescriptor.WITH_PRICE.name(), true)
					.build()
			)
		);
		assertThrows(EvitaInvalidUsageException.class, () -> converter.convert(Map.of()));
		assertThrows(EvitaInvalidUsageException.class, () -> converter.convert((Object) null));
	}
}
