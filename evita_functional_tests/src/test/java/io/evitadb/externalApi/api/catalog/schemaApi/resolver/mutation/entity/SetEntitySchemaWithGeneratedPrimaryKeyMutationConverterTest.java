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

import io.evitadb.api.requestResponse.schema.mutation.entity.SetEntitySchemaWithGeneratedPrimaryKeyMutation;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.externalApi.api.catalog.mutation.TestMutationResolvingExceptionFactory;
import io.evitadb.externalApi.api.catalog.resolver.mutation.PassThroughMutationObjectMapper;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.entity.SetEntitySchemaWithGeneratedPrimaryKeyMutationDescriptor;
import io.evitadb.externalApi.api.model.mutation.MutationDescriptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.evitadb.utils.MapBuilder.map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link SetEntitySchemaWithGeneratedPrimaryKeyMutationConverter}.
 *
 * This test class verifies the functionality of the mutation converter that handles
 * setting the generated primary key flag for entity schemas. It tests both input-to-mutation
 * conversion and mutation-to-output serialization, ensuring proper handling of boolean values
 * and error cases.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@DisplayName("SetEntitySchemaWithGeneratedPrimaryKeyMutationConverter functionality")
class SetEntitySchemaWithGeneratedPrimaryKeyMutationConverterTest {

	private SetEntitySchemaWithGeneratedPrimaryKeyMutationConverter converter;

	@BeforeEach
	void init() {
		this.converter = new SetEntitySchemaWithGeneratedPrimaryKeyMutationConverter(PassThroughMutationObjectMapper.INSTANCE, TestMutationResolvingExceptionFactory.INSTANCE);
	}

	/**
	 * Tests that the converter properly resolves input map to local mutation object.
	 * Verifies that both boolean and string representations of true/false values are handled correctly.
	 */
	@Test
	@DisplayName("should resolve input to local mutation with boolean and string values")
	void shouldResolveInputToLocalMutation() {
		// Test with boolean true value
		final SetEntitySchemaWithGeneratedPrimaryKeyMutation expectedMutationTrue = new SetEntitySchemaWithGeneratedPrimaryKeyMutation(
			true
		);

		final SetEntitySchemaWithGeneratedPrimaryKeyMutation convertedMutation1 = this.converter.convertFromInput(
			map()
				.e(SetEntitySchemaWithGeneratedPrimaryKeyMutationDescriptor.WITH_GENERATED_PRIMARY_KEY.name(), true)
				.build()
		);
		assertEquals(expectedMutationTrue, convertedMutation1);

		// Test with string "true" value
		final SetEntitySchemaWithGeneratedPrimaryKeyMutation convertedMutation2 = this.converter.convertFromInput(
			map()
				.e(SetEntitySchemaWithGeneratedPrimaryKeyMutationDescriptor.WITH_GENERATED_PRIMARY_KEY.name(), "true")
				.build()
		);
		assertEquals(expectedMutationTrue, convertedMutation2);

		// Test with boolean false value
		final SetEntitySchemaWithGeneratedPrimaryKeyMutation expectedMutationFalse = new SetEntitySchemaWithGeneratedPrimaryKeyMutation(
			false
		);

		final SetEntitySchemaWithGeneratedPrimaryKeyMutation convertedMutation3 = this.converter.convertFromInput(
			map()
				.e(SetEntitySchemaWithGeneratedPrimaryKeyMutationDescriptor.WITH_GENERATED_PRIMARY_KEY.name(), false)
				.build()
		);
		assertEquals(expectedMutationFalse, convertedMutation3);

		// Test with string "false" value
		final SetEntitySchemaWithGeneratedPrimaryKeyMutation convertedMutation4 = this.converter.convertFromInput(
			map()
				.e(SetEntitySchemaWithGeneratedPrimaryKeyMutationDescriptor.WITH_GENERATED_PRIMARY_KEY.name(), "false")
				.build()
		);
		assertEquals(expectedMutationFalse, convertedMutation4);
	}

	/**
	 * Tests that the converter properly throws exceptions when required data is missing.
	 * Verifies error handling for empty maps and null inputs.
	 */
	@Test
	@DisplayName("should throw exception when missing required data")
	void shouldNotResolveInputWhenMissingRequiredData() {
		assertThrows(EvitaInvalidUsageException.class, () -> this.converter.convertFromInput(Map.of()));
		assertThrows(EvitaInvalidUsageException.class, () -> this.converter.convertFromInput((Object) null));
	}

	/**
	 * Tests that the converter properly serializes local mutation object back to output map.
	 * This test verifies the reverse conversion from mutation object to API output format,
	 * ensuring that the serialized output contains the correct field names and values.
	 */
	@Test
	@DisplayName("should serialize local mutation to output")
	void shouldSerializeLocalMutationToOutput() {
		// Test serialization with true value
		final SetEntitySchemaWithGeneratedPrimaryKeyMutation inputMutationTrue = new SetEntitySchemaWithGeneratedPrimaryKeyMutation(
			true
		);

		//noinspection unchecked
		final Map<String, Object> serializedMutationTrue = (Map<String, Object>) this.converter.convertToOutput(inputMutationTrue);
		assertThat(serializedMutationTrue)
			.usingRecursiveComparison()
			.isEqualTo(
				map()
					.e(MutationDescriptor.MUTATION_TYPE.name(), SetEntitySchemaWithGeneratedPrimaryKeyMutation.class.getSimpleName())
					.e(SetEntitySchemaWithGeneratedPrimaryKeyMutationDescriptor.WITH_GENERATED_PRIMARY_KEY.name(), true)
					.build()
			);

		// Test serialization with false value
		final SetEntitySchemaWithGeneratedPrimaryKeyMutation inputMutationFalse = new SetEntitySchemaWithGeneratedPrimaryKeyMutation(
			false
		);

		//noinspection unchecked
		final Map<String, Object> serializedMutationFalse = (Map<String, Object>) this.converter.convertToOutput(inputMutationFalse);
		assertThat(serializedMutationFalse)
			.usingRecursiveComparison()
			.isEqualTo(
				map()
					.e(MutationDescriptor.MUTATION_TYPE.name(), SetEntitySchemaWithGeneratedPrimaryKeyMutation.class.getSimpleName())
					.e(SetEntitySchemaWithGeneratedPrimaryKeyMutationDescriptor.WITH_GENERATED_PRIMARY_KEY.name(), false)
					.build()
			);
	}
}
