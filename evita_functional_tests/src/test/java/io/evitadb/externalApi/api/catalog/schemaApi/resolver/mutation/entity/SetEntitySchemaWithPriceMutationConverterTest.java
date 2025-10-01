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

package io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.entity;

import io.evitadb.api.requestResponse.schema.mutation.entity.SetEntitySchemaWithPriceMutation;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.externalApi.api.catalog.mutation.TestMutationResolvingExceptionFactory;
import io.evitadb.externalApi.api.catalog.resolver.mutation.PassThroughMutationObjectMapper;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.entity.SetEntitySchemaWithPriceMutationDescriptor;
import io.evitadb.externalApi.api.model.mutation.MutationDescriptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.evitadb.utils.ListBuilder.list;
import static io.evitadb.utils.MapBuilder.map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link SetEntitySchemaWithPriceMutationConverter}.
 *
 * This test class verifies the functionality of the converter that handles the transformation
 * between external API input/output format and internal {@link SetEntitySchemaWithPriceMutation} objects.
 * The converter is responsible for:
 * - Converting input maps to mutation objects with proper type conversion and validation
 * - Converting mutation objects back to output maps for serialization
 * - Handling required field validation and error cases
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@DisplayName("SetEntitySchemaWithPriceMutationConverter functionality")
class SetEntitySchemaWithPriceMutationConverterTest {

	private SetEntitySchemaWithPriceMutationConverter converter;

	@BeforeEach
	void init() {
		this.converter = new SetEntitySchemaWithPriceMutationConverter(PassThroughMutationObjectMapper.INSTANCE, TestMutationResolvingExceptionFactory.INSTANCE);
	}

	@Test
	@DisplayName("should resolve input to local mutation with all fields")
	void shouldResolveInputToLocalMutation() {
		final SetEntitySchemaWithPriceMutation expectedMutation = new SetEntitySchemaWithPriceMutation(
			true,
			new Scope[] { Scope.LIVE },
			2
		);

		// Test with native types
		final SetEntitySchemaWithPriceMutation convertedMutation1 = this.converter.convertFromInput(
			map()
				.e(SetEntitySchemaWithPriceMutationDescriptor.WITH_PRICE.name(), true)
				.e(SetEntitySchemaWithPriceMutationDescriptor.INDEXED_IN_SCOPES.name(), list()
					.i(Scope.LIVE))
				.e(SetEntitySchemaWithPriceMutationDescriptor.INDEXED_PRICE_PLACES.name(), 2)
				.build()
		);
		assertEquals(expectedMutation, convertedMutation1);

		// Test with string representations (common in external APIs)
		final SetEntitySchemaWithPriceMutation convertedMutation2 = this.converter.convertFromInput(
			map()
				.e(SetEntitySchemaWithPriceMutationDescriptor.WITH_PRICE.name(), "true")
				.e(SetEntitySchemaWithPriceMutationDescriptor.INDEXED_IN_SCOPES.name(), list()
					.i(Scope.LIVE.name()))
				.e(SetEntitySchemaWithPriceMutationDescriptor.INDEXED_PRICE_PLACES.name(), "2")
				.build()
		);
		assertEquals(expectedMutation, convertedMutation2);
	}

	@Test
	@DisplayName("should resolve input to local mutation with only required data")
	void shouldResolveInputToLocalMutationWithOnlyRequiredData() {
		// Test with null indexedInScopes (should default to Scope.NO_SCOPE)
		final SetEntitySchemaWithPriceMutation expectedMutation = new SetEntitySchemaWithPriceMutation(
			false,
			null, // This will be converted to Scope.NO_SCOPE internally
			3
		);
		final SetEntitySchemaWithPriceMutation convertedMutation = this.converter.convertFromInput(
			map()
				.e(SetEntitySchemaWithPriceMutationDescriptor.WITH_PRICE.name(), false)
				.e(SetEntitySchemaWithPriceMutationDescriptor.INDEXED_PRICE_PLACES.name(), 3)
				.build()
		);
		assertEquals(expectedMutation, convertedMutation);
	}

	@Test
	@DisplayName("should not resolve input when missing required data")
	void shouldNotResolveInputWhenMissingRequiredData() {
		// Missing withPrice field
		assertThrows(
			EvitaInvalidUsageException.class,
			() -> this.converter.convertFromInput(
				map()
					.e(SetEntitySchemaWithPriceMutationDescriptor.INDEXED_PRICE_PLACES.name(), 2)
					.build()
			)
		);

		// Missing indexedPricePlaces field
		assertThrows(
			EvitaInvalidUsageException.class,
			() -> this.converter.convertFromInput(
				map()
					.e(SetEntitySchemaWithPriceMutationDescriptor.WITH_PRICE.name(), true)
					.build()
			)
		);

		// Empty input
		assertThrows(EvitaInvalidUsageException.class, () -> this.converter.convertFromInput(Map.of()));

		// Null input
		assertThrows(EvitaInvalidUsageException.class, () -> this.converter.convertFromInput((Object) null));
	}

	@Test
	@DisplayName("should serialize local mutation to output")
	void shouldSerializeLocalMutationToOutput() {
		// Test with all fields populated
		final SetEntitySchemaWithPriceMutation inputMutation = new SetEntitySchemaWithPriceMutation(
			true,
			new Scope[] { Scope.LIVE, Scope.ARCHIVED },
			2
		);

		//noinspection unchecked
		final Map<String, Object> serializedMutation = (Map<String, Object>) this.converter.convertToOutput(inputMutation);
		assertThat(serializedMutation)
			.usingRecursiveComparison()
			.isEqualTo(
				map()
					.e(SetEntitySchemaWithPriceMutationDescriptor.MUTATION_TYPE.name(), SetEntitySchemaWithPriceMutation.class.getSimpleName())
					.e(SetEntitySchemaWithPriceMutationDescriptor.WITH_PRICE.name(), true)
					.e(SetEntitySchemaWithPriceMutationDescriptor.INDEXED_IN_SCOPES.name(), new String[] { "LIVE", "ARCHIVED" })
					.e(SetEntitySchemaWithPriceMutationDescriptor.INDEXED_PRICE_PLACES.name(), 2)
					.build()
			);
	}

	@Test
	@DisplayName("should serialize local mutation to output with minimal data")
	void shouldSerializeLocalMutationToOutputWithMinimalData() {
		// Test with null indexedInScopes (defaults to Scope.NO_SCOPE)
		final SetEntitySchemaWithPriceMutation inputMutation = new SetEntitySchemaWithPriceMutation(
			false,
			null,
			0
		);

		//noinspection unchecked
		final Map<String, Object> serializedMutation = (Map<String, Object>) this.converter.convertToOutput(inputMutation);
		assertThat(serializedMutation)
			.usingRecursiveComparison()
			.isEqualTo(
				map()
					.e(MutationDescriptor.MUTATION_TYPE.name(), SetEntitySchemaWithPriceMutation.class.getSimpleName())
					.e(SetEntitySchemaWithPriceMutationDescriptor.WITH_PRICE.name(), false)
					.e(SetEntitySchemaWithPriceMutationDescriptor.INDEXED_IN_SCOPES.name(), new String[0]) // Scope.NO_SCOPE is an empty array
					.e(SetEntitySchemaWithPriceMutationDescriptor.INDEXED_PRICE_PLACES.name(), 0)
					.build()
			);
	}
}
