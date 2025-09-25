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

import io.evitadb.api.requestResponse.schema.mutation.sortableAttributeCompound.ModifySortableAttributeCompoundSchemaDescriptionMutation;
import io.evitadb.api.requestResponse.schema.mutation.sortableAttributeCompound.ModifySortableAttributeCompoundSchemaNameMutation;
import io.evitadb.api.requestResponse.schema.mutation.sortableAttributeCompound.ReferenceSortableAttributeCompoundSchemaMutation;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.externalApi.api.catalog.mutation.TestMutationResolvingExceptionFactory;
import io.evitadb.externalApi.api.catalog.resolver.mutation.PassThroughMutationObjectParser;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.attribute.AttributeSchemaMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.attribute.ModifyAttributeSchemaDescriptionMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.attribute.ModifyAttributeSchemaNameMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.SortableAttributeCompoundSchemaMutationAggregateDescriptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.evitadb.utils.MapBuilder.map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link SortableAttributeCompoundSchemaMutationAggregateConverter}
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
class SortableAttributeCompoundSchemaMutationAggregateConverterTest {

	private SortableAttributeCompoundSchemaMutationAggregateConverter converter;

	@BeforeEach
	void init() {
		this.converter = new SortableAttributeCompoundSchemaMutationAggregateConverter(new PassThroughMutationObjectParser(), new TestMutationResolvingExceptionFactory());
	}

	@Test
	void shouldResolveInputToLocalMutation() {
		final List<ReferenceSortableAttributeCompoundSchemaMutation> expectedMutations = List.of(
			new ModifySortableAttributeCompoundSchemaDescriptionMutation("code", "desc"),
			new ModifySortableAttributeCompoundSchemaNameMutation("code", "betterCode")
		);

		final List<ReferenceSortableAttributeCompoundSchemaMutation> convertedMutations = this.converter.convertFromInput(
			map()
				.e(
					SortableAttributeCompoundSchemaMutationAggregateDescriptor.MODIFY_SORTABLE_ATTRIBUTE_COMPOUND_SCHEMA_DESCRIPTION_MUTATION.name(), map()
					.e(AttributeSchemaMutationDescriptor.NAME.name(), "code")
					.e(ModifyAttributeSchemaDescriptionMutationDescriptor.DESCRIPTION.name(), "desc")
					.build())
				.e(
					SortableAttributeCompoundSchemaMutationAggregateDescriptor.MODIFY_SORTABLE_ATTRIBUTE_COMPOUND_SCHEMA_NAME_MUTATION.name(), map()
					.e(AttributeSchemaMutationDescriptor.NAME.name(), "code")
					.e(ModifyAttributeSchemaNameMutationDescriptor.NEW_NAME.name(), "betterCode")
					.build())
				.build()
		);
		assertEquals(expectedMutations, convertedMutations);
	}

	@Test
	void shouldResolveInputToLocalMutationWithOnlyRequiredData() {
		final List<ReferenceSortableAttributeCompoundSchemaMutation> convertedMutations = this.converter.convertFromInput(Map.of());
		assertEquals(List.of(), convertedMutations);
	}

	@Test
	void shouldNotResolveInputWhenMissingRequiredData() {
		assertThrows(EvitaInvalidUsageException.class, () -> this.converter.convertFromInput((Object) null));
	}

	@Test
	void shouldConvertMutationToOutput() {
		final List<Map<String, Object>> expectedMutations = List.of(
			map()
				.e(
					SortableAttributeCompoundSchemaMutationAggregateDescriptor.MODIFY_SORTABLE_ATTRIBUTE_COMPOUND_SCHEMA_DESCRIPTION_MUTATION.name(), map()
					.e(AttributeSchemaMutationDescriptor.NAME.name(), "code")
					.e(ModifyAttributeSchemaDescriptionMutationDescriptor.DESCRIPTION.name(), "desc")
					.build())
				.build(),
			map()
				.e(
					SortableAttributeCompoundSchemaMutationAggregateDescriptor.MODIFY_SORTABLE_ATTRIBUTE_COMPOUND_SCHEMA_NAME_MUTATION.name(), map()
					.e(AttributeSchemaMutationDescriptor.NAME.name(), "code")
					.e(ModifyAttributeSchemaNameMutationDescriptor.NEW_NAME.name(), "betterCode")
					.build())
				.build()
		);

		final Object convertedMutations = this.converter.convertToOutput(
			List.of(
				new ModifySortableAttributeCompoundSchemaDescriptionMutation("code", "desc"),
				new ModifySortableAttributeCompoundSchemaNameMutation("code", "betterCode")
			)
		);
		assertEquals(expectedMutations, convertedMutations);
	}
}
