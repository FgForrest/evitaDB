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

package io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.associatedData;

import io.evitadb.api.requestResponse.mutation.conflict.ConflictResolutionOverride;
import io.evitadb.api.requestResponse.schema.mutation.associatedData.SetAssociatedDataSchemaConflictResolutionOverrideMutation;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.externalApi.api.catalog.mutation.TestMutationResolvingExceptionFactory;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.associatedData.AssociatedDataSchemaMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.associatedData.SetAssociatedDataSchemaConflictResolutionOverrideMutationDescriptor;
import io.evitadb.externalApi.api.model.mutation.MutationDescriptor;
import io.evitadb.externalApi.api.resolver.mutation.PassThroughMutationObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.evitadb.test.TestTags.EXTERNAL_API;
import static io.evitadb.test.TestTags.QUERY;
import static io.evitadb.test.TestTags.SCHEMA;
import static io.evitadb.test.TestTags.TRANSACTION;
import static io.evitadb.utils.MapBuilder.map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link SetAssociatedDataSchemaConflictResolutionOverrideMutationConverter}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@Tag(EXTERNAL_API)
@Tag(QUERY)
@Tag(SCHEMA)
@Tag(TRANSACTION)
class SetAssociatedDataSchemaConflictResolutionOverrideMutationConverterTest {

	private SetAssociatedDataSchemaConflictResolutionOverrideMutationConverter converter;

	@BeforeEach
	void init() {
		this.converter = new SetAssociatedDataSchemaConflictResolutionOverrideMutationConverter(
			PassThroughMutationObjectMapper.INSTANCE, TestMutationResolvingExceptionFactory.INSTANCE);
	}

	@Test
	void shouldResolveInputToLocalMutation() {
		final SetAssociatedDataSchemaConflictResolutionOverrideMutation expectedMutation = new SetAssociatedDataSchemaConflictResolutionOverrideMutation(
			"code", ConflictResolutionOverride.GRANULAR
		);
		final SetAssociatedDataSchemaConflictResolutionOverrideMutation convertedMutation = this.converter.convertFromInput(
			map()
				.e(AssociatedDataSchemaMutationDescriptor.NAME.name(), "code")
				.e(SetAssociatedDataSchemaConflictResolutionOverrideMutationDescriptor.CONFLICT_RESOLUTION_OVERRIDE.name(), ConflictResolutionOverride.GRANULAR)
				.build()
		);
		assertEquals(expectedMutation, convertedMutation);
	}

	@Test
	void shouldNotResolveInputWhenMissingRequiredData() {
		assertThrows(EvitaInvalidUsageException.class, () -> this.converter.convertFromInput(
			map().e(SetAssociatedDataSchemaConflictResolutionOverrideMutationDescriptor.CONFLICT_RESOLUTION_OVERRIDE.name(), ConflictResolutionOverride.GRANULAR).build()));
		assertThrows(EvitaInvalidUsageException.class, () -> this.converter.convertFromInput(Map.of()));
		assertThrows(EvitaInvalidUsageException.class, () -> this.converter.convertFromInput((Object) null));
	}

	@Test
	void shouldRoundTripNonDefaultValue() {
		final SetAssociatedDataSchemaConflictResolutionOverrideMutation inputMutation = new SetAssociatedDataSchemaConflictResolutionOverrideMutation(
			"code", ConflictResolutionOverride.ENTITY
		);
		final SetAssociatedDataSchemaConflictResolutionOverrideMutation roundTripped =
			this.converter.convertFromInput(this.converter.convertToOutput(inputMutation));
		assertEquals(inputMutation, roundTripped);
	}

	@Test
	void shouldSerializeLocalMutationToOutput() {
		final SetAssociatedDataSchemaConflictResolutionOverrideMutation inputMutation = new SetAssociatedDataSchemaConflictResolutionOverrideMutation(
			"code", ConflictResolutionOverride.GRANULAR
		);
		//noinspection unchecked
		final Map<String, Object> serializedMutation = (Map<String, Object>) this.converter.convertToOutput(inputMutation);
		assertThat(serializedMutation)
			.usingRecursiveComparison()
			.isEqualTo(
				map()
					.e(MutationDescriptor.MUTATION_TYPE.name(), SetAssociatedDataSchemaConflictResolutionOverrideMutation.class.getSimpleName())
					.e(AssociatedDataSchemaMutationDescriptor.NAME.name(), "code")
					.e(SetAssociatedDataSchemaConflictResolutionOverrideMutationDescriptor.CONFLICT_RESOLUTION_OVERRIDE.name(), ConflictResolutionOverride.GRANULAR.name())
					.build()
			);
	}
}
