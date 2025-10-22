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

package io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.reference;

import io.evitadb.api.requestResponse.schema.mutation.reference.ModifyReferenceSortableAttributeCompoundSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.sortableAttributeCompound.ModifySortableAttributeCompoundSchemaDescriptionMutation;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.externalApi.api.catalog.mutation.TestMutationResolvingExceptionFactory;
import io.evitadb.externalApi.api.catalog.resolver.mutation.PassThroughMutationObjectMapper;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.reference.ModifyReferenceSortableAttributeCompoundSchemaMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.reference.ReferenceSchemaMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.sortableAttributeCompound.ModifySortableAttributeCompoundSchemaDescriptionMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.SortableAttributeCompoundSchemaMutationInputAggregateDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.sortableAttributeCompound.SortableAttributeCompoundSchemaMutationDescriptor;
import io.evitadb.externalApi.api.model.mutation.MutationDescriptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.evitadb.utils.MapBuilder.map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link ModifyReferenceAttributeSchemaMutationConverter}
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
class ModifyReferenceSortableAttributeCompoundSchemaMutationConverterTest {

	private ModifyReferenceSortableAttributeCompoundSchemaMutationConverter converter;

	@BeforeEach
	void init() {
		this.converter = new ModifyReferenceSortableAttributeCompoundSchemaMutationConverter(PassThroughMutationObjectMapper.INSTANCE, TestMutationResolvingExceptionFactory.INSTANCE);
	}

	@Test
	void shouldResolveInputToLocalMutation() {
		final ModifyReferenceSortableAttributeCompoundSchemaMutation expectedMutation = new ModifyReferenceSortableAttributeCompoundSchemaMutation(
			"tags",
			new ModifySortableAttributeCompoundSchemaDescriptionMutation("code", "desc")
		);

		final ModifyReferenceSortableAttributeCompoundSchemaMutation convertedMutation = this.converter.convertFromInput(
			map()
				.e(ReferenceSchemaMutationDescriptor.NAME.name(), "tags")
				.e(ModifyReferenceSortableAttributeCompoundSchemaMutationDescriptor.SORTABLE_ATTRIBUTE_COMPOUND_SCHEMA_MUTATION.name(), map()
					.e(
						SortableAttributeCompoundSchemaMutationInputAggregateDescriptor.MODIFY_SORTABLE_ATTRIBUTE_COMPOUND_SCHEMA_DESCRIPTION_MUTATION.name(), map()
						.e(SortableAttributeCompoundSchemaMutationDescriptor.NAME.name(), "code")
						.e(ModifySortableAttributeCompoundSchemaDescriptionMutationDescriptor.DESCRIPTION.name(), "desc")
						.build())
					.build())
				.build()
		);
		assertEquals(expectedMutation, convertedMutation);
	}

	@Test
	void shouldNotResolveInputWhenMissingRequiredData() {
		assertThrows(
			EvitaInvalidUsageException.class,
			() -> this.converter.convertFromInput(
				map()
					.e(ReferenceSchemaMutationDescriptor.NAME.name(), "tags")
					.e(ModifyReferenceSortableAttributeCompoundSchemaMutationDescriptor.SORTABLE_ATTRIBUTE_COMPOUND_SCHEMA_MUTATION.name(), Map.of())
					.build()
			)
		);
		assertThrows(
			EvitaInvalidUsageException.class,
			() -> this.converter.convertFromInput(
				map()
					.e(ModifyReferenceSortableAttributeCompoundSchemaMutationDescriptor.SORTABLE_ATTRIBUTE_COMPOUND_SCHEMA_MUTATION.name(), Map.of())
					.build()
			)
		);
		assertThrows(
			EvitaInvalidUsageException.class,
			() -> this.converter.convertFromInput(
				map()
					.e(ReferenceSchemaMutationDescriptor.NAME.name(), "tags")
					.build()
			)
		);
		assertThrows(EvitaInvalidUsageException.class, () -> this.converter.convertFromInput(Map.of()));
		assertThrows(EvitaInvalidUsageException.class, () -> this.converter.convertFromInput((Object) null));
	}

	@Test
	void shouldSerializeLocalMutationToOutput() {
		final ModifyReferenceSortableAttributeCompoundSchemaMutation inputMutation = new ModifyReferenceSortableAttributeCompoundSchemaMutation(
			"tags",
			new ModifySortableAttributeCompoundSchemaDescriptionMutation("code", "desc")
		);

		//noinspection unchecked
		final Map<String, Object> serializedMutation = (Map<String, Object>) this.converter.convertToOutput(inputMutation);
		assertThat(serializedMutation)
			.usingRecursiveComparison()
			.isEqualTo(map()
	           .e(MutationDescriptor.MUTATION_TYPE.name(), ModifyReferenceSortableAttributeCompoundSchemaMutation.class.getSimpleName())
				.e(ReferenceSchemaMutationDescriptor.NAME.name(), "tags")
				.e(ModifyReferenceSortableAttributeCompoundSchemaMutationDescriptor.SORTABLE_ATTRIBUTE_COMPOUND_SCHEMA_MUTATION.name(), map()
					.e(SortableAttributeCompoundSchemaMutationDescriptor.MUTATION_TYPE.name(), ModifySortableAttributeCompoundSchemaDescriptionMutation.class.getSimpleName())
					.e(SortableAttributeCompoundSchemaMutationDescriptor.NAME.name(), "code")
					.e(ModifySortableAttributeCompoundSchemaDescriptionMutationDescriptor.DESCRIPTION.name(), "desc")
					.build())
				.build());
	}
}
