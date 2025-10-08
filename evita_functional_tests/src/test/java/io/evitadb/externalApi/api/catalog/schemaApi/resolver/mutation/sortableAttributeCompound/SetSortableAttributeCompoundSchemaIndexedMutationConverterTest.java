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

package io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.sortableAttributeCompound;

import io.evitadb.api.requestResponse.schema.mutation.sortableAttributeCompound.SetSortableAttributeCompoundSchemaIndexedMutation;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.externalApi.api.catalog.mutation.TestMutationResolvingExceptionFactory;
import io.evitadb.externalApi.api.catalog.resolver.mutation.PassThroughMutationObjectMapper;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.sortableAttributeCompound.SetSortableAttributeCompoundIndexedMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.sortableAttributeCompound.SortableAttributeCompoundSchemaMutationDescriptor;
import io.evitadb.externalApi.api.model.mutation.MutationDescriptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.evitadb.utils.ListBuilder.array;
import static io.evitadb.utils.ListBuilder.list;
import static io.evitadb.utils.MapBuilder.map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link SetSortableAttributeCompoundIndexedMutationConverter}
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
class SetSortableAttributeCompoundSchemaIndexedMutationConverterTest {

	private SetSortableAttributeCompoundIndexedMutationConverter converter;

	@BeforeEach
	void init() {
		this.converter = new SetSortableAttributeCompoundIndexedMutationConverter(PassThroughMutationObjectMapper.INSTANCE, TestMutationResolvingExceptionFactory.INSTANCE);
	}

	@Test
	void shouldResolveInputToLocalMutation() {
		final SetSortableAttributeCompoundSchemaIndexedMutation expectedMutation = new SetSortableAttributeCompoundSchemaIndexedMutation(
			"code",
			new Scope[] {Scope.LIVE}
		);
		final SetSortableAttributeCompoundSchemaIndexedMutation convertedMutation1 = this.converter.convertFromInput(
			map()
				.e(SortableAttributeCompoundSchemaMutationDescriptor.NAME.name(), "code")
				.e(SetSortableAttributeCompoundIndexedMutationDescriptor.INDEXED_IN_SCOPES.name(), list()
					.i(Scope.LIVE))
				.build()
		);
		assertEquals(expectedMutation, convertedMutation1);

		final SetSortableAttributeCompoundSchemaIndexedMutation convertedMutation2 = this.converter.convertFromInput(
			map()
				.e(SortableAttributeCompoundSchemaMutationDescriptor.NAME.name(), "code")
				.e(SetSortableAttributeCompoundIndexedMutationDescriptor.INDEXED_IN_SCOPES.name(), list()
					.i(Scope.LIVE.name()))
				.build()
		);
		assertEquals(expectedMutation, convertedMutation2);
	}

	@Test
	void shouldNotResolveInputWhenMissingRequiredData() {
		assertThrows(EvitaInvalidUsageException.class, () -> this.converter.convertFromInput(Map.of()));
		assertThrows(EvitaInvalidUsageException.class, () -> this.converter.convertFromInput((Object) null));
	}

	@Test
	void shouldSerializeLocalMutationToOutput() {
		final SetSortableAttributeCompoundSchemaIndexedMutation inputMutation = new SetSortableAttributeCompoundSchemaIndexedMutation(
			"code",
			new Scope[] {Scope.LIVE}
		);

		//noinspection unchecked
		final Map<String, Object> serializedMutation = (Map<String, Object>) this.converter.convertToOutput(inputMutation);
		assertThat(serializedMutation)
			.usingRecursiveComparison()
			.isEqualTo(
				map()
					.e(MutationDescriptor.MUTATION_TYPE.name(), SetSortableAttributeCompoundSchemaIndexedMutation.class.getSimpleName())
					.e(SortableAttributeCompoundSchemaMutationDescriptor.NAME.name(), "code")
					.e(SetSortableAttributeCompoundIndexedMutationDescriptor.INDEXED_IN_SCOPES.name(), array()
						.i(Scope.LIVE.name()))
					.build()
			);
	}
}
