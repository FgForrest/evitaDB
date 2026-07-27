/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

package io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.attribute;

import io.evitadb.api.requestResponse.mutation.conflict.ConflictResolutionOverride;
import io.evitadb.api.requestResponse.schema.mutation.attribute.SetAttributeSchemaConflictResolutionOverrideMutation;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.externalApi.api.catalog.mutation.TestMutationResolvingExceptionFactory;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.attribute.AttributeSchemaMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.attribute.SetAttributeSchemaConflictResolutionOverrideMutationDescriptor;
import io.evitadb.externalApi.api.model.mutation.MutationDescriptor;
import io.evitadb.externalApi.api.resolver.mutation.PassThroughMutationObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.EXTERNAL_API;
import static io.evitadb.test.TestTags.QUERY;
import static io.evitadb.test.TestTags.SCHEMA;
import static io.evitadb.test.TestTags.TRANSACTION;
import static io.evitadb.utils.MapBuilder.map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link SetAttributeSchemaConflictResolutionOverrideMutationConverter}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@Tag(EXTERNAL_API)
@Tag(QUERY)
@Tag(SCHEMA)
@Tag(ATTRIBUTE)
@Tag(TRANSACTION)
class SetAttributeSchemaConflictResolutionOverrideMutationConverterTest {

	private SetAttributeSchemaConflictResolutionOverrideMutationConverter converter;

	@BeforeEach
	void init() {
		this.converter = new SetAttributeSchemaConflictResolutionOverrideMutationConverter(
			PassThroughMutationObjectMapper.INSTANCE, TestMutationResolvingExceptionFactory.INSTANCE);
	}

	@Test
	void shouldResolveInputToLocalMutation() {
		final SetAttributeSchemaConflictResolutionOverrideMutation expectedMutation = new SetAttributeSchemaConflictResolutionOverrideMutation(
			"code", ConflictResolutionOverride.GRANULAR
		);
		final SetAttributeSchemaConflictResolutionOverrideMutation convertedMutation = this.converter.convertFromInput(
			map()
				.e(AttributeSchemaMutationDescriptor.NAME.name(), "code")
				.e(SetAttributeSchemaConflictResolutionOverrideMutationDescriptor.CONFLICT_RESOLUTION_OVERRIDE.name(), ConflictResolutionOverride.GRANULAR)
				.build()
		);
		assertEquals(expectedMutation, convertedMutation);
	}

	@Test
	void shouldNotResolveInputWhenMissingRequiredData() {
		assertThrows(EvitaInvalidUsageException.class, () -> this.converter.convertFromInput(
			map().e(SetAttributeSchemaConflictResolutionOverrideMutationDescriptor.CONFLICT_RESOLUTION_OVERRIDE.name(), ConflictResolutionOverride.GRANULAR).build()));
		assertThrows(EvitaInvalidUsageException.class, () -> this.converter.convertFromInput(Map.of()));
		assertThrows(EvitaInvalidUsageException.class, () -> this.converter.convertFromInput((Object) null));
	}

	@Test
	void shouldRoundTripNonDefaultValue() {
		final SetAttributeSchemaConflictResolutionOverrideMutation inputMutation = new SetAttributeSchemaConflictResolutionOverrideMutation(
			"code", ConflictResolutionOverride.ENTITY
		);
		final SetAttributeSchemaConflictResolutionOverrideMutation roundTripped =
			this.converter.convertFromInput(this.converter.convertToOutput(inputMutation));
		assertEquals(inputMutation, roundTripped);
	}

	@Test
	void shouldSerializeLocalMutationToOutput() {
		final SetAttributeSchemaConflictResolutionOverrideMutation inputMutation = new SetAttributeSchemaConflictResolutionOverrideMutation(
			"code", ConflictResolutionOverride.GRANULAR
		);
		//noinspection unchecked
		final Map<String, Object> serializedMutation = (Map<String, Object>) this.converter.convertToOutput(inputMutation);
		assertThat(serializedMutation)
			.usingRecursiveComparison()
			.isEqualTo(
				map()
					.e(MutationDescriptor.MUTATION_TYPE.name(), SetAttributeSchemaConflictResolutionOverrideMutation.class.getSimpleName())
					.e(AttributeSchemaMutationDescriptor.NAME.name(), "code")
					.e(SetAttributeSchemaConflictResolutionOverrideMutationDescriptor.CONFLICT_RESOLUTION_OVERRIDE.name(), ConflictResolutionOverride.GRANULAR.name())
					.build()
			);
	}
}
