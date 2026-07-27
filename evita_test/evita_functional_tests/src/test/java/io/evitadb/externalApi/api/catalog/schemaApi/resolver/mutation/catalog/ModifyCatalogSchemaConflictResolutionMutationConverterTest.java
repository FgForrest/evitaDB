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

package io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.catalog;

import io.evitadb.api.requestResponse.mutation.conflict.ConflictPolicy;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictResolution;
import io.evitadb.api.requestResponse.mutation.conflict.GranularConflictPolicy;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyCatalogSchemaConflictResolutionMutation;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.externalApi.api.catalog.mutation.TestMutationResolvingExceptionFactory;
import io.evitadb.externalApi.api.catalog.schemaApi.model.ConflictResolutionDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.catalog.ModifyCatalogSchemaConflictResolutionMutationDescriptor;
import io.evitadb.externalApi.api.resolver.mutation.PassThroughMutationObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Map;

import static io.evitadb.test.TestTags.EXTERNAL_API;
import static io.evitadb.test.TestTags.QUERY;
import static io.evitadb.test.TestTags.SCHEMA;
import static io.evitadb.test.TestTags.TRANSACTION;
import static io.evitadb.utils.MapBuilder.map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link ModifyCatalogSchemaConflictResolutionMutationConverter}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@Tag(EXTERNAL_API)
@Tag(QUERY)
@Tag(SCHEMA)
@Tag(TRANSACTION)
class ModifyCatalogSchemaConflictResolutionMutationConverterTest {

	private ModifyCatalogSchemaConflictResolutionMutationConverter converter;

	@BeforeEach
	void init() {
		this.converter = new ModifyCatalogSchemaConflictResolutionMutationConverter(
			PassThroughMutationObjectMapper.INSTANCE, TestMutationResolvingExceptionFactory.INSTANCE);
	}

	@Test
	void shouldResolveInputToLocalMutation() {
		final ModifyCatalogSchemaConflictResolutionMutation expectedMutation = new ModifyCatalogSchemaConflictResolutionMutation(
			new ConflictResolution(ConflictPolicy.ENTITY, EnumSet.of(GranularConflictPolicy.PRICE))
		);
		final ModifyCatalogSchemaConflictResolutionMutation convertedMutation = this.converter.convertFromInput(
			map()
				.e(ModifyCatalogSchemaConflictResolutionMutationDescriptor.CONFLICT_RESOLUTION.name(), map()
					.e(ConflictResolutionDescriptor.POLICY.name(), ConflictPolicy.ENTITY)
					.e(ConflictResolutionDescriptor.GRANULARITY.name(), new GranularConflictPolicy[] { GranularConflictPolicy.PRICE })
					.build())
				.build()
		);
		assertEquals(expectedMutation, convertedMutation);
	}

	@Test
	void shouldResolveInputToLocalMutationWithNullConflictResolution() {
		final ModifyCatalogSchemaConflictResolutionMutation expectedMutation = new ModifyCatalogSchemaConflictResolutionMutation(null);
		final ModifyCatalogSchemaConflictResolutionMutation convertedMutation = this.converter.convertFromInput(Map.of());
		assertEquals(expectedMutation, convertedMutation);
	}

	@Test
	void shouldNotResolveInputWhenMissingRequiredData() {
		assertThrows(EvitaInvalidUsageException.class, () -> this.converter.convertFromInput((Object) null));
	}

	@Test
	void shouldRoundTripNonDefaultConflictResolution() {
		final ModifyCatalogSchemaConflictResolutionMutation inputMutation = new ModifyCatalogSchemaConflictResolutionMutation(
			new ConflictResolution(ConflictPolicy.ENTITY, EnumSet.of(GranularConflictPolicy.PRICE))
		);
		final ModifyCatalogSchemaConflictResolutionMutation roundTripped =
			this.converter.convertFromInput(this.converter.convertToOutput(inputMutation));
		assertEquals(inputMutation, roundTripped);
	}

	@Test
	void shouldRoundTripCoarseConflictResolution() {
		final ModifyCatalogSchemaConflictResolutionMutation inputMutation = new ModifyCatalogSchemaConflictResolutionMutation(
			new ConflictResolution(ConflictPolicy.CATALOG)
		);
		final ModifyCatalogSchemaConflictResolutionMutation roundTripped =
			this.converter.convertFromInput(this.converter.convertToOutput(inputMutation));
		assertEquals(inputMutation, roundTripped);
	}

	@Test
	void shouldRoundTripNullConflictResolution() {
		final ModifyCatalogSchemaConflictResolutionMutation inputMutation = new ModifyCatalogSchemaConflictResolutionMutation(null);
		final ModifyCatalogSchemaConflictResolutionMutation roundTripped =
			this.converter.convertFromInput(this.converter.convertToOutput(inputMutation));
		assertEquals(inputMutation, roundTripped);
	}
}
