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

import io.evitadb.api.requestResponse.mutation.EngineMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.CreateCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.RemoveCatalogSchemaMutation;
import io.evitadb.externalApi.api.catalog.mutation.TestMutationResolvingExceptionFactory;
import io.evitadb.externalApi.api.catalog.resolver.mutation.PassThroughMutationObjectMapper;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.engine.CreateCatalogSchemaMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.engine.RemoveCatalogSchemaMutationDescriptor;
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
 * Test class for {@link DelegatingEngineMutationConverter}. This test suite
 * verifies the functionality of the top-level catalog schema mutation aggregate converter,
 * ensuring proper conversion and handling of catalog schema mutations in the external API context.
 * Tests cover various catalog schema mutation scenarios including creation, modification, and removal.
 *
 * @author Lukáš Hornych, 2023
 */
public class DelegatingEngineMutationConverterTest {

	private DelegatingEngineMutationConverter converter;

	@BeforeEach
	void init() {
		this.converter = new DelegatingEngineMutationConverter(PassThroughMutationObjectMapper.INSTANCE, TestMutationResolvingExceptionFactory.INSTANCE);
	}

	@Test
	void shouldSerializeLocalMutationToOutput() {
		final List<EngineMutation<?>> inputMutation = List.of(
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
						.e(CreateCatalogSchemaMutationDescriptor.MUTATION_TYPE.name(), CreateCatalogSchemaMutation.class.getSimpleName())
						.e(CreateCatalogSchemaMutationDescriptor.CATALOG_NAME.name(), "entityType"))
					.i(map()
						.e(RemoveCatalogSchemaMutationDescriptor.MUTATION_TYPE.name(), RemoveCatalogSchemaMutation.class.getSimpleName())
						.e(RemoveCatalogSchemaMutationDescriptor.CATALOG_NAME.name(), "entityType"))
					.build()
			);
	}
}
