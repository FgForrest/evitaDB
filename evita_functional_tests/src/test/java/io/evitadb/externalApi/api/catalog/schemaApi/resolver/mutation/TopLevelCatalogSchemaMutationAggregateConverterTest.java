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

package io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation;

import io.evitadb.api.requestResponse.schema.mutation.TopLevelCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.CreateCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.RemoveCatalogSchemaMutation;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.externalApi.api.catalog.mutation.TestMutationResolvingExceptionFactory;
import io.evitadb.externalApi.api.catalog.resolver.mutation.PassThroughMutationObjectParser;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.TopLevelCatalogSchemaMutationAggregateDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.catalog.TopLevelCatalogSchemaMutationDescriptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.evitadb.utils.ListBuilder.list;
import static io.evitadb.utils.MapBuilder.map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Test class for {@link TopLevelCatalogSchemaMutationAggregateConverter}. This test suite
 * verifies the functionality of the top-level catalog schema mutation aggregate converter,
 * ensuring proper conversion and handling of catalog schema mutations in the external API context.
 * Tests cover various catalog schema mutation scenarios including creation, modification, and removal.
 *
 * @author Lukáš Hornych, 2023
 */
public class TopLevelCatalogSchemaMutationAggregateConverterTest {

	private TopLevelCatalogSchemaMutationAggregateConverter converter;

	@BeforeEach
	void init() {
		this.converter = new TopLevelCatalogSchemaMutationAggregateConverter(new PassThroughMutationObjectParser(), new TestMutationResolvingExceptionFactory());
	}

	@Test
	void shouldResolveInputToMutation() {
		final List<TopLevelCatalogSchemaMutation> expectedMutations = List.of(
			new CreateCatalogSchemaMutation("entityType"),
			new RemoveCatalogSchemaMutation("entityType")
		);

		final List<TopLevelCatalogSchemaMutation> convertedMutations = this.converter.convertFromInput(
			map()
				.e(TopLevelCatalogSchemaMutationAggregateDescriptor.CREATE_CATALOG_SCHEMA_MUTATION.name(), map()
					.e(TopLevelCatalogSchemaMutationDescriptor.CATALOG_NAME.name(), "entityType"))
				.e(TopLevelCatalogSchemaMutationAggregateDescriptor.REMOVE_CATALOG_SCHEMA_MUTATION.name(), map()
					.e(TopLevelCatalogSchemaMutationDescriptor.CATALOG_NAME.name(), "entityType"))
				.build()
		);
		assertEquals(expectedMutations, convertedMutations);
	}

	@Test
	void shouldResolveInputToLocalMutationWithOnlyRequiredData() {
		final List<TopLevelCatalogSchemaMutation> convertedMutations = this.converter.convertFromInput(Map.of());
		assertEquals(List.of(), convertedMutations);
	}

	@Test
	void shouldNotResolveInputWhenMissingRequiredData() {
		assertThrows(EvitaInvalidUsageException.class, () -> this.converter.convertFromInput(null));
	}

	@Test
	void shouldSerializeLocalMutationToOutput() {
		final List<TopLevelCatalogSchemaMutation> inputMutation = List.of(
			new CreateCatalogSchemaMutation("entityType"),
			new RemoveCatalogSchemaMutation("entityType")
		);

		//noinspection unchecked
		final List<Map<String, Object>> serializedMutation = (List<Map<String, Object>>) this.converter.convertToOutput(inputMutation);
		assertThat(serializedMutation)
			.usingRecursiveComparison()
			.isEqualTo(
				list()
					.i(map()
						.e(TopLevelCatalogSchemaMutationAggregateDescriptor.CREATE_CATALOG_SCHEMA_MUTATION.name(), map()
							.e(TopLevelCatalogSchemaMutationDescriptor.CATALOG_NAME.name(), "entityType")))
					.i(map()
						.e(TopLevelCatalogSchemaMutationAggregateDescriptor.REMOVE_CATALOG_SCHEMA_MUTATION.name(), map()
							.e(TopLevelCatalogSchemaMutationDescriptor.CATALOG_NAME.name(), "entityType")))
					.build()
			);
	}
}
