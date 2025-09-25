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

package io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.engine;

import io.evitadb.api.requestResponse.schema.mutation.engine.SetCatalogMutabilityMutation;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.externalApi.api.catalog.mutation.TestMutationResolvingExceptionFactory;
import io.evitadb.externalApi.api.catalog.resolver.mutation.PassThroughMutationObjectParser;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.engine.EngineMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.engine.SetCatalogMutabilityMutationDescriptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.evitadb.utils.MapBuilder.map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Test class for {@link MakeCatalogAliveMutationConverter}. This test suite verifies
 * the functionality of the catalog schema creation mutation converter, ensuring proper
 * conversion and handling of catalog schema creation operations in the external API context.
 * Tests cover various catalog creation scenarios and validation of input parameters.
 *
 * @author Lukáš Hornych, 2023
 */
public class SetCatalogMutabilityMutationConverterTest {

	private SetCatalogMutabilityMutationConverter converter;

	@BeforeEach
	void init() {
		this.converter = new SetCatalogMutabilityMutationConverter(new PassThroughMutationObjectParser(), new TestMutationResolvingExceptionFactory());
	}

	@Test
	void shouldResolverInputToLocalMutation() {
		final SetCatalogMutabilityMutation expectedMutation = new SetCatalogMutabilityMutation("testCatalog", true);
		final SetCatalogMutabilityMutation convertedMutation = this.converter.convertFromInput(
			map()
				.e(EngineMutationDescriptor.CATALOG_NAME.name(), "testCatalog")
				.e(SetCatalogMutabilityMutationDescriptor.MUTABLE.name(), true)
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
		final SetCatalogMutabilityMutation inputMutation = new SetCatalogMutabilityMutation("testCatalog", true);
		//noinspection unchecked
		final Map<String, Object> serializedMutation = (Map<String, Object>) this.converter.convertToOutput(inputMutation);
		assertThat(serializedMutation)
			.usingRecursiveComparison()
			.isEqualTo(
				map()
					.e(EngineMutationDescriptor.CATALOG_NAME.name(), "testCatalog")
					.e(SetCatalogMutabilityMutationDescriptor.MUTABLE.name(), true)
					.build()
			);
	}
}
