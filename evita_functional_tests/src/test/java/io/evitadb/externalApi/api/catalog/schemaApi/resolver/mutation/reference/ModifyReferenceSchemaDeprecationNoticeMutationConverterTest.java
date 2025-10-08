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

package io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.reference;

import io.evitadb.api.requestResponse.schema.mutation.reference.ModifyReferenceSchemaDeprecationNoticeMutation;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.externalApi.api.catalog.mutation.TestMutationResolvingExceptionFactory;
import io.evitadb.externalApi.api.catalog.resolver.mutation.PassThroughMutationObjectMapper;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.reference.ModifyReferenceSchemaDeprecationNoticeMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.reference.ReferenceSchemaMutationDescriptor;
import io.evitadb.externalApi.api.model.mutation.MutationDescriptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.evitadb.utils.MapBuilder.map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link ModifyReferenceSchemaDeprecationNoticeMutationConverter}
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
class ModifyReferenceSchemaDeprecationNoticeMutationConverterTest {

	private ModifyReferenceSchemaDeprecationNoticeMutationConverter converter;

	@BeforeEach
	void init() {
		this.converter = new ModifyReferenceSchemaDeprecationNoticeMutationConverter(PassThroughMutationObjectMapper.INSTANCE, TestMutationResolvingExceptionFactory.INSTANCE);
	}

	@Test
	void shouldResolveInputToLocalMutation() {
		final ModifyReferenceSchemaDeprecationNoticeMutation expectedMutation = new ModifyReferenceSchemaDeprecationNoticeMutation(
			"tags",
			"depr"
		);

		final ModifyReferenceSchemaDeprecationNoticeMutation convertedMutation = this.converter.convertFromInput(
			map()
				.e(ReferenceSchemaMutationDescriptor.NAME.name(), "tags")
				.e(ModifyReferenceSchemaDeprecationNoticeMutationDescriptor.DEPRECATION_NOTICE.name(), "depr")
				.build()
		);
		assertEquals(expectedMutation, convertedMutation);
	}

	@Test
	void shouldResolveInputToLocalMutationWithOnlyRequiredData() {
		final ModifyReferenceSchemaDeprecationNoticeMutation expectedMutation = new ModifyReferenceSchemaDeprecationNoticeMutation(
			"tags",
			null
		);

		final ModifyReferenceSchemaDeprecationNoticeMutation convertedMutation = this.converter.convertFromInput(
			map()
				.e(ReferenceSchemaMutationDescriptor.NAME.name(), "tags")
				.build()
		);
		assertEquals(expectedMutation, convertedMutation);
	}

	@Test
	void shouldNotResolveInputWhenMissingRequiredData() {
		assertThrows(EvitaInvalidUsageException.class, () -> this.converter.convertFromInput(Map.of()));
		assertThrows(EvitaInvalidUsageException.class, () -> this.converter.convertFromInput((Object) null));
	}

	@Test
	void shouldSerializeLocalMutationToOutput() {
		final ModifyReferenceSchemaDeprecationNoticeMutation inputMutation = new ModifyReferenceSchemaDeprecationNoticeMutation(
			"tags",
			"depr"
		);

		//noinspection unchecked
		final Map<String, Object> serializedMutation = (Map<String, Object>) this.converter.convertToOutput(inputMutation);
		assertThat(serializedMutation)
			.usingRecursiveComparison()
			.isEqualTo(
				map()
					.e(MutationDescriptor.MUTATION_TYPE.name(), ModifyReferenceSchemaDeprecationNoticeMutation.class.getSimpleName())
					.e(ReferenceSchemaMutationDescriptor.NAME.name(), "tags")
					.e(ModifyReferenceSchemaDeprecationNoticeMutationDescriptor.DEPRECATION_NOTICE.name(), "depr")
					.build()
			);
	}
}
