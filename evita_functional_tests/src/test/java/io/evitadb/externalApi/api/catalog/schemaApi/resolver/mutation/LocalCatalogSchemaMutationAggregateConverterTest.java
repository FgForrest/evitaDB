/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation;

import io.evitadb.api.requestResponse.schema.mutation.LocalCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.ModifyAttributeSchemaDescriptionMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.CreateEntitySchemaMutation;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.externalApi.api.catalog.mutation.TestMutationResolvingExceptionFactory;
import io.evitadb.externalApi.api.catalog.resolver.mutation.PassThroughMutationObjectParser;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.LocalCatalogSchemaMutationAggregateDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.attribute.ModifyAttributeSchemaDescriptionMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.catalog.CreateEntitySchemaMutationDescriptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.evitadb.test.builder.MapBuilder.map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link LocalCatalogSchemaMutationAggregateConverter}
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
class LocalCatalogSchemaMutationAggregateConverterTest {

	private LocalCatalogSchemaMutationAggregateConverter converter;

	@BeforeEach
	void init() {
		converter = new LocalCatalogSchemaMutationAggregateConverter(new PassThroughMutationObjectParser(), new TestMutationResolvingExceptionFactory());
	}

	@Test
	void shouldResolveInputToLocalMutation() {
		final List<LocalCatalogSchemaMutation> expectedMutations = List.of(
			new ModifyAttributeSchemaDescriptionMutation("code", "desc"),
			new CreateEntitySchemaMutation("product")
		);

		final List<LocalCatalogSchemaMutation> convertedMutations1 = converter.convert(
			map()
				.e(LocalCatalogSchemaMutationAggregateDescriptor.MODIFY_ATTRIBUTE_SCHEMA_DESCRIPTION_MUTATION.name(), map()
					.e(ModifyAttributeSchemaDescriptionMutationDescriptor.NAME.name(), "code")
					.e(ModifyAttributeSchemaDescriptionMutationDescriptor.DESCRIPTION.name(), "desc")
					.build())
				.e(LocalCatalogSchemaMutationAggregateDescriptor.CREATE_ENTITY_SCHEMA_MUTATION.name(), map()
					.e(CreateEntitySchemaMutationDescriptor.ENTITY_TYPE.name(), "product")
					.build())
				.build()
		);
		assertEquals(expectedMutations, convertedMutations1);
	}
	@Test
	void shouldResolveInputToLocalMutationWithOnlyRequiredData() {
		final List<LocalCatalogSchemaMutation> convertedMutations = converter.convert(Map.of());
		assertEquals(List.of(), convertedMutations);
	}

	@Test
	void shouldNotResolveInputWhenMissingRequiredData() {
		assertThrows(EvitaInvalidUsageException.class, () -> converter.convert((Object) null));
	}
}