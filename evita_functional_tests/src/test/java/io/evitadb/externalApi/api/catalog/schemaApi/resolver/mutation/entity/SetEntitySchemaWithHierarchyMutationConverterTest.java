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

import io.evitadb.api.requestResponse.schema.mutation.entity.SetEntitySchemaWithHierarchyMutation;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.externalApi.api.catalog.mutation.TestMutationResolvingExceptionFactory;
import io.evitadb.externalApi.api.catalog.resolver.mutation.PassThroughMutationObjectParser;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.entity.SetEntitySchemaWithHierarchyMutationDescriptor;
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
 * Tests for {@link SetEntitySchemaWithHierarchyMutationConverter}.
 *
 * This test class verifies the functionality of the converter that handles mutations for setting
 * entity schema hierarchy configuration. It tests both input-to-mutation conversion and
 * mutation-to-output serialization, ensuring proper handling of required and optional fields,
 * edge cases, and error conditions.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@DisplayName("SetEntitySchemaWithHierarchyMutationConverter functionality")
class SetEntitySchemaWithHierarchyMutationConverterTest {

	private SetEntitySchemaWithHierarchyMutationConverter converter;

	@BeforeEach
	void init() {
		this.converter = new SetEntitySchemaWithHierarchyMutationConverter(new PassThroughMutationObjectParser(), new TestMutationResolvingExceptionFactory());
	}

	@Test
	@DisplayName("should resolve input to local mutation with all fields")
	void shouldResolveInputToLocalMutation() {
		final SetEntitySchemaWithHierarchyMutation expectedMutation = new SetEntitySchemaWithHierarchyMutation(
			true,
			new Scope[] { Scope.LIVE }
		);

		final SetEntitySchemaWithHierarchyMutation convertedMutation1 = this.converter.convertFromInput(
			map()
				.e(SetEntitySchemaWithHierarchyMutationDescriptor.WITH_HIERARCHY.name(), true)
				.e(SetEntitySchemaWithHierarchyMutationDescriptor.INDEXED_IN_SCOPES.name(), list()
					.i(Scope.LIVE))
				.build()
		);
		assertEquals(expectedMutation, convertedMutation1);

		final SetEntitySchemaWithHierarchyMutation convertedMutation2 = this.converter.convertFromInput(
			map()
				.e(SetEntitySchemaWithHierarchyMutationDescriptor.WITH_HIERARCHY.name(), "true")
				.e(SetEntitySchemaWithHierarchyMutationDescriptor.INDEXED_IN_SCOPES.name(), list()
					.i(Scope.LIVE.name()))
				.build()
		);
		assertEquals(expectedMutation, convertedMutation2);
	}

	@Test
	@DisplayName("should resolve input to local mutation with only required data")
	void shouldResolveInputToLocalMutationWithOnlyRequiredData() {
		final SetEntitySchemaWithHierarchyMutation expectedMutation = new SetEntitySchemaWithHierarchyMutation(
			false,
			null
		);

		final SetEntitySchemaWithHierarchyMutation convertedMutation = this.converter.convertFromInput(
			map()
				.e(SetEntitySchemaWithHierarchyMutationDescriptor.WITH_HIERARCHY.name(), false)
				.build()
		);
		assertEquals(expectedMutation, convertedMutation);
	}

	@Test
	@DisplayName("should resolve input to local mutation with multiple scopes")
	void shouldResolveInputToLocalMutationWithMultipleScopes() {
		final SetEntitySchemaWithHierarchyMutation expectedMutation = new SetEntitySchemaWithHierarchyMutation(
			true,
			new Scope[] { Scope.LIVE, Scope.ARCHIVED }
		);

		final SetEntitySchemaWithHierarchyMutation convertedMutation = this.converter.convertFromInput(
			map()
				.e(SetEntitySchemaWithHierarchyMutationDescriptor.WITH_HIERARCHY.name(), true)
				.e(SetEntitySchemaWithHierarchyMutationDescriptor.INDEXED_IN_SCOPES.name(), list()
					.i(Scope.LIVE)
					.i(Scope.ARCHIVED))
				.build()
		);
		assertEquals(expectedMutation, convertedMutation);
	}

	@Test
	@DisplayName("should resolve input to local mutation with empty scopes array")
	void shouldResolveInputToLocalMutationWithEmptyScopes() {
		final SetEntitySchemaWithHierarchyMutation expectedMutation = new SetEntitySchemaWithHierarchyMutation(
			true,
			Scope.NO_SCOPE
		);

		final SetEntitySchemaWithHierarchyMutation convertedMutation = this.converter.convertFromInput(
			map()
				.e(SetEntitySchemaWithHierarchyMutationDescriptor.WITH_HIERARCHY.name(), true)
				.e(SetEntitySchemaWithHierarchyMutationDescriptor.INDEXED_IN_SCOPES.name(), list())
				.build()
		);
		assertEquals(expectedMutation, convertedMutation);
	}

	@Test
	@DisplayName("should not resolve input when missing required data")
	void shouldNotResolveInputWhenMissingRequiredData() {
		assertThrows(EvitaInvalidUsageException.class, () -> this.converter.convertFromInput(Map.of()));
		assertThrows(EvitaInvalidUsageException.class, () -> this.converter.convertFromInput((Object) null));
	}

	@Test
	@DisplayName("should serialize local mutation to output")
	void shouldSerializeLocalMutationToOutput() {
		final SetEntitySchemaWithHierarchyMutation inputMutation = new SetEntitySchemaWithHierarchyMutation(
			true,
			new Scope[] { Scope.LIVE, Scope.ARCHIVED }
		);

		//noinspection unchecked
		final Map<String, Object> serializedMutation = (Map<String, Object>) this.converter.convertToOutput(inputMutation);
		assertThat(serializedMutation)
			.usingRecursiveComparison()
			.isEqualTo(
				map()
					.e(SetEntitySchemaWithHierarchyMutationDescriptor.WITH_HIERARCHY.name(), true)
					.e(SetEntitySchemaWithHierarchyMutationDescriptor.INDEXED_IN_SCOPES.name(), new String[] { "LIVE", "ARCHIVED" })
					.build()
			);
	}

	@Test
	@DisplayName("should serialize local mutation to output with only required data")
	void shouldSerializeLocalMutationToOutputWithOnlyRequiredData() {
		final SetEntitySchemaWithHierarchyMutation inputMutation = new SetEntitySchemaWithHierarchyMutation(
			false,
			null
		);

		//noinspection unchecked
		final Map<String, Object> serializedMutation = (Map<String, Object>) this.converter.convertToOutput(inputMutation);
		assertThat(serializedMutation)
			.usingRecursiveComparison()
			.isEqualTo(
				map()
					.e(SetEntitySchemaWithHierarchyMutationDescriptor.WITH_HIERARCHY.name(), false)
					.e(SetEntitySchemaWithHierarchyMutationDescriptor.INDEXED_IN_SCOPES.name(), Scope.NO_SCOPE)
					.build()
			);
	}
}
