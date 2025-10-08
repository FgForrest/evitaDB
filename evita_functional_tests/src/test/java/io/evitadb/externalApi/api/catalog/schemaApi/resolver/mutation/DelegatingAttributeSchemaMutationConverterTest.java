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

import io.evitadb.api.requestResponse.schema.mutation.AttributeSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.ModifyAttributeSchemaDescriptionMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.ModifyAttributeSchemaNameMutation;
import io.evitadb.externalApi.api.catalog.mutation.TestMutationResolvingExceptionFactory;
import io.evitadb.externalApi.api.catalog.resolver.mutation.PassThroughMutationObjectMapper;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.attribute.ModifyAttributeSchemaDescriptionMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.attribute.ModifyAttributeSchemaNameMutationDescriptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.evitadb.utils.ListBuilder.list;
import static io.evitadb.utils.MapBuilder.map;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link DelegatingAttributeSchemaMutationConverter}
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
class DelegatingAttributeSchemaMutationConverterTest {

	private DelegatingAttributeSchemaMutationConverter converter;

	@BeforeEach
	void init() {
		this.converter = new DelegatingAttributeSchemaMutationConverter(PassThroughMutationObjectMapper.INSTANCE, TestMutationResolvingExceptionFactory.INSTANCE);
	}

	@Test
	void shouldSerializeLocalMutationToOutput() {
		final List<AttributeSchemaMutation> inputMutation = List.of(
			new ModifyAttributeSchemaDescriptionMutation("code", "desc"),
			new ModifyAttributeSchemaNameMutation("code", "betterCode")
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
						.e(ModifyAttributeSchemaNameMutationDescriptor.MUTATION_TYPE.name(), ModifyAttributeSchemaNameMutation.class.getSimpleName())
						.e(ModifyAttributeSchemaNameMutationDescriptor.NAME.name(), "code")
						.e(ModifyAttributeSchemaNameMutationDescriptor.NEW_NAME.name(), "betterCode"))
					.build()
			);
	}
}
