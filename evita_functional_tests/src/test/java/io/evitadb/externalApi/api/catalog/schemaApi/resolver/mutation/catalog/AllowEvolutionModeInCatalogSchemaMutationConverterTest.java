/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

import io.evitadb.api.requestResponse.schema.CatalogEvolutionMode;
import io.evitadb.api.requestResponse.schema.mutation.catalog.AllowEvolutionModeInCatalogSchemaMutation;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.externalApi.api.catalog.mutation.TestMutationResolvingExceptionFactory;
import io.evitadb.externalApi.api.catalog.resolver.mutation.PassThroughMutationObjectParser;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.catalog.AllowEvolutionModeInCatalogSchemaMutationDescriptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.evitadb.utils.MapBuilder.map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link AllowEvolutionModeInCatalogSchemaMutationConverter}
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
class AllowEvolutionModeInCatalogSchemaMutationConverterTest {

	private AllowEvolutionModeInCatalogSchemaMutationConverter converter;

	@BeforeEach
	void init() {
		converter = new AllowEvolutionModeInCatalogSchemaMutationConverter(new PassThroughMutationObjectParser(), new TestMutationResolvingExceptionFactory());
	}

	@Test
	void shouldResolveInputToLocalMutation() {
		final AllowEvolutionModeInCatalogSchemaMutation expectedMutation = new AllowEvolutionModeInCatalogSchemaMutation(
			CatalogEvolutionMode.ADDING_ENTITY_TYPES
		);

		final AllowEvolutionModeInCatalogSchemaMutation convertedMutation1 = converter.convertFromInput(
			map()
				.e(AllowEvolutionModeInCatalogSchemaMutationDescriptor.EVOLUTION_MODES.name(), List.of(
					CatalogEvolutionMode.ADDING_ENTITY_TYPES
				))
				.build()
		);
		assertEquals(expectedMutation, convertedMutation1);

		final AllowEvolutionModeInCatalogSchemaMutation convertedMutation2 = converter.convertFromInput(
			map()
				.e(AllowEvolutionModeInCatalogSchemaMutationDescriptor.EVOLUTION_MODES.name(), List.of(
					"ADDING_ENTITY_TYPES"
				))
				.build()
		);
		assertEquals(expectedMutation, convertedMutation2);
	}
	@Test
	void shouldResolveInputToLocalMutationWithOnlyRequiredData() {
		final AllowEvolutionModeInCatalogSchemaMutation expectedMutation = new AllowEvolutionModeInCatalogSchemaMutation();

		final AllowEvolutionModeInCatalogSchemaMutation convertedMutation1 = converter.convertFromInput(
			map()
				.e(AllowEvolutionModeInCatalogSchemaMutationDescriptor.EVOLUTION_MODES.name(), List.of())
				.build()
		);
		assertEquals(expectedMutation, convertedMutation1);
	}

	@Test
	void shouldNotResolveInputWhenMissingRequiredData() {
		assertThrows(EvitaInvalidUsageException.class, () -> converter.convertFromInput(Map.of()));
		assertThrows(EvitaInvalidUsageException.class, () -> converter.convertFromInput((Object) null));
	}
}
