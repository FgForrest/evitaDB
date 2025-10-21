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

import io.evitadb.api.requestResponse.schema.CatalogEvolutionMode;
import io.evitadb.api.requestResponse.schema.mutation.LocalCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.ModifyAttributeSchemaDescriptionMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.AllowEvolutionModeInCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.CreateEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.DisallowEvolutionModeInCatalogSchemaMutation;
import io.evitadb.externalApi.api.catalog.mutation.TestMutationResolvingExceptionFactory;
import io.evitadb.externalApi.api.catalog.resolver.mutation.PassThroughMutationObjectMapper;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.attribute.ModifyAttributeSchemaDescriptionMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.catalog.AllowEvolutionModeInCatalogSchemaMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.catalog.CreateEntitySchemaMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.catalog.DisallowEvolutionModeInCatalogSchemaMutationDescriptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.evitadb.utils.ListBuilder.array;
import static io.evitadb.utils.ListBuilder.list;
import static io.evitadb.utils.MapBuilder.map;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link DelegatingLocalCatalogSchemaMutationConverter}
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
class DelegatingLocalCatalogSchemaMutationConverterTest {

	private DelegatingLocalCatalogSchemaMutationConverter converter;

	@BeforeEach
	void init() {
		this.converter = new DelegatingLocalCatalogSchemaMutationConverter(PassThroughMutationObjectMapper.INSTANCE, TestMutationResolvingExceptionFactory.INSTANCE);
	}

	@Test
	void shouldSerializeLocalMutationToOutput() {
		final List<LocalCatalogSchemaMutation> inputMutation = List.of(
			new ModifyAttributeSchemaDescriptionMutation("code", "desc"),
			new CreateEntitySchemaMutation("product"),
			new AllowEvolutionModeInCatalogSchemaMutation(CatalogEvolutionMode.ADDING_ENTITY_TYPES),
			new DisallowEvolutionModeInCatalogSchemaMutation(CatalogEvolutionMode.ADDING_ENTITY_TYPES)
		);

		//noinspection unchecked
		final List<Map<String, Object>> serializedMutation = (List<Map<String, Object>>) this.converter.convertToOutput(inputMutation);
		assertThat(serializedMutation)
			.usingRecursiveComparison()
			.isEqualTo(
				list()
					.i(map()
						.e(ModifyAttributeSchemaDescriptionMutationDescriptor.MUTATION_TYPE.name(), ModifyAttributeSchemaDescriptionMutation.class.getSimpleName())
						.e(ModifyAttributeSchemaDescriptionMutationDescriptor.NAME.name(), "code")
						.e(ModifyAttributeSchemaDescriptionMutationDescriptor.DESCRIPTION.name(), "desc"))
					.i(map()
						.e(CreateEntitySchemaMutationDescriptor.MUTATION_TYPE.name(), CreateEntitySchemaMutation.class.getSimpleName())
						.e(CreateEntitySchemaMutationDescriptor.NAME.name(), "product"))
					.i(map()
					    .e(AllowEvolutionModeInCatalogSchemaMutationDescriptor.MUTATION_TYPE.name(), AllowEvolutionModeInCatalogSchemaMutation.class.getSimpleName())
						.e(AllowEvolutionModeInCatalogSchemaMutationDescriptor.EVOLUTION_MODES.name(), array()
							.i(CatalogEvolutionMode.ADDING_ENTITY_TYPES.name())))
					.i(map()
					    .e(DisallowEvolutionModeInCatalogSchemaMutationDescriptor.MUTATION_TYPE.name(), DisallowEvolutionModeInCatalogSchemaMutation.class.getSimpleName())
						.e(DisallowEvolutionModeInCatalogSchemaMutationDescriptor.EVOLUTION_MODES.name(), list()
							.i(CatalogEvolutionMode.ADDING_ENTITY_TYPES.name())))
					.build()
			);
	}
}
