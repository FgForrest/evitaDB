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

import io.evitadb.api.requestResponse.schema.mutation.entity.ModifyEntitySchemaDescriptionMutation;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.externalApi.api.catalog.mutation.TestMutationResolvingExceptionFactory;
import io.evitadb.externalApi.api.catalog.resolver.mutation.PassThroughMutationObjectParser;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.entity.ModifyEntitySchemaDescriptionMutationDescriptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.evitadb.utils.MapBuilder.map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link ModifyEntitySchemaDescriptionMutationConverter}
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
class ModifyEntitySchemaDescriptionMutationConverterTest {

	private ModifyEntitySchemaDescriptionMutationConverter converter;

	@BeforeEach
	void init() {
		this.converter = new ModifyEntitySchemaDescriptionMutationConverter(new PassThroughMutationObjectParser(), new TestMutationResolvingExceptionFactory());
	}

	@Test
	void shouldResolveInputToLocalMutation() {
		final ModifyEntitySchemaDescriptionMutation expectedMutation = new ModifyEntitySchemaDescriptionMutation("desc");

		final ModifyEntitySchemaDescriptionMutation convertedMutation = this.converter.convertFromInput(
			map()
				.e(ModifyEntitySchemaDescriptionMutationDescriptor.DESCRIPTION.name(), "desc")
				.build()
		);
		assertEquals(expectedMutation, convertedMutation);
	}
	@Test
	void shouldResolveInputToLocalMutationWithOnlyRequiredData() {
		final ModifyEntitySchemaDescriptionMutation expectedMutation = new ModifyEntitySchemaDescriptionMutation(null);

		final ModifyEntitySchemaDescriptionMutation convertedMutation = this.converter.convertFromInput(Map.of());
		assertEquals(expectedMutation, convertedMutation);
	}

	@Test
	void shouldNotResolveInputWhenMissingRequiredData() {
		assertThrows(EvitaInvalidUsageException.class, () -> this.converter.convertFromInput((Object) null));
	}

	/**
	 * Tests that the converter properly serializes local mutation object back to output map.
	 * This test verifies the reverse conversion from mutation object to API output format,
	 * ensuring that the serialized output contains the correct field names and description values.
	 */
	@Test
	void shouldSerializeLocalMutationToOutput() {
		final ModifyEntitySchemaDescriptionMutation inputMutation = new ModifyEntitySchemaDescriptionMutation("desc");

		//noinspection unchecked
		final Map<String, Object> serializedMutation = (Map<String, Object>) this.converter.convertToOutput(inputMutation);
		assertThat(serializedMutation)
			.usingRecursiveComparison()
			.isEqualTo(
				map()
					.e(ModifyEntitySchemaDescriptionMutationDescriptor.DESCRIPTION.name(), "desc")
					.build()
			);
	}
}
