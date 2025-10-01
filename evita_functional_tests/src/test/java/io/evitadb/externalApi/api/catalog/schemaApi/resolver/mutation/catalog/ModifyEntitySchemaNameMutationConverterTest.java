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

package io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.catalog;

import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyEntitySchemaNameMutation;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.externalApi.api.catalog.mutation.TestMutationResolvingExceptionFactory;
import io.evitadb.externalApi.api.catalog.resolver.mutation.PassThroughMutationObjectMapper;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.catalog.ModifyEntitySchemaNameMutationDescriptor;
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
 * Tests for {@link ModifyEntitySchemaNameMutationConverter}
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@DisplayName("ModifyEntitySchemaNameMutationConverter should")
class ModifyEntitySchemaNameMutationConverterTest {

	private ModifyEntitySchemaNameMutationConverter converter;

	@BeforeEach
	void init() {
		this.converter = new ModifyEntitySchemaNameMutationConverter(PassThroughMutationObjectMapper.INSTANCE, TestMutationResolvingExceptionFactory.INSTANCE);
	}

	@Test
	@DisplayName("resolve input to local mutation")
	void shouldResolveInputToLocalMutation() {
		final ModifyEntitySchemaNameMutation expectedMutation = new ModifyEntitySchemaNameMutation(
			"product",
			"newProduct",
			true
		);
		final ModifyEntitySchemaNameMutation convertedMutation = this.converter.convertFromInput(
			map()
				.e(ModifyEntitySchemaNameMutationDescriptor.NAME.name(), "product")
				.e(ModifyEntitySchemaNameMutationDescriptor.NEW_NAME.name(), "newProduct")
				.e(ModifyEntitySchemaNameMutationDescriptor.OVERWRITE_TARGET.name(), true)
				.build()
		);
		assertEquals(expectedMutation, convertedMutation);
	}

	@Test
	@DisplayName("not resolve input when missing required data")
	void shouldNotResolveInputWhenMissingRequiredData() {
		assertThrows(EvitaInvalidUsageException.class, () -> this.converter.convertFromInput(Map.of()));
		assertThrows(
			EvitaInvalidUsageException.class,
			() -> this.converter.convertFromInput(
				map()
					.e(ModifyEntitySchemaNameMutationDescriptor.NAME.name(), "product")
					.e(ModifyEntitySchemaNameMutationDescriptor.NEW_NAME.name(), "newProduct")
					.build()
			)
		);
		assertThrows(
			EvitaInvalidUsageException.class,
			() -> this.converter.convertFromInput(
				map()
					.e(ModifyEntitySchemaNameMutationDescriptor.NAME.name(), "product")
					.e(ModifyEntitySchemaNameMutationDescriptor.OVERWRITE_TARGET.name(), true)
					.build()
			)
		);
		assertThrows(
			EvitaInvalidUsageException.class,
			() -> this.converter.convertFromInput(
				map()
					.e(ModifyEntitySchemaNameMutationDescriptor.NEW_NAME.name(), "newProduct")
					.e(ModifyEntitySchemaNameMutationDescriptor.OVERWRITE_TARGET.name(), true)
					.build()
			)
		);
		assertThrows(EvitaInvalidUsageException.class, () -> this.converter.convertFromInput((Object) null));
	}

	@Test
	@DisplayName("serialize local mutation to output")
	void shouldSerializeLocalMutationToOutput() {
		final ModifyEntitySchemaNameMutation inputMutation = new ModifyEntitySchemaNameMutation(
			"product",
			"newProduct",
			false
		);

		//noinspection unchecked
		final Map<String, Object> serializedMutation = (Map<String, Object>) this.converter.convertToOutput(inputMutation);
		assertThat(serializedMutation)
			.usingRecursiveComparison()
			.isEqualTo(
				map()
					.e(MutationDescriptor.MUTATION_TYPE.name(), ModifyEntitySchemaNameMutation.class.getSimpleName())
					.e(ModifyEntitySchemaNameMutationDescriptor.NAME.name(), "product")
					.e(ModifyEntitySchemaNameMutationDescriptor.NEW_NAME.name(), "newProduct")
					.e(ModifyEntitySchemaNameMutationDescriptor.OVERWRITE_TARGET.name(), false)
					.build()
			);
	}
}