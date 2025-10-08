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

import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.schema.OrderBehaviour;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement;
import io.evitadb.api.requestResponse.schema.mutation.sortableAttributeCompound.CreateSortableAttributeCompoundSchemaMutation;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.externalApi.api.catalog.mutation.TestMutationResolvingExceptionFactory;
import io.evitadb.externalApi.api.catalog.resolver.mutation.PassThroughMutationObjectMapper;
import io.evitadb.externalApi.api.catalog.schemaApi.model.AttributeElementDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.sortableAttributeCompound.CreateSortableAttributeCompoundSchemaMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.sortableAttributeCompound.SortableAttributeCompoundSchemaMutationDescriptor;
import io.evitadb.externalApi.api.model.mutation.MutationDescriptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.evitadb.utils.ListBuilder.array;
import static io.evitadb.utils.ListBuilder.list;
import static io.evitadb.utils.MapBuilder.map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link CreateSortableAttributeCompoundSchemaMutationConverter}
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
class CreateSortableAttributeCompoundSchemaMutationConverterTest {

	private CreateSortableAttributeCompoundSchemaMutationConverter converter;

	@BeforeEach
	void init() {
		this.converter = new CreateSortableAttributeCompoundSchemaMutationConverter(PassThroughMutationObjectMapper.INSTANCE, TestMutationResolvingExceptionFactory.INSTANCE);
	}

	@Test
	void shouldResolveInputToLocalMutation() {
		final CreateSortableAttributeCompoundSchemaMutation expectedMutation = new CreateSortableAttributeCompoundSchemaMutation(
			"code",
			"desc",
			"depr",
			new Scope[] { Scope.LIVE },
			new AttributeElement("a", OrderDirection.ASC, OrderBehaviour.NULLS_FIRST),
			new AttributeElement("b", OrderDirection.DESC, OrderBehaviour.NULLS_LAST)
		);

		final CreateSortableAttributeCompoundSchemaMutation convertedMutation1 = this.converter.convertFromInput(
			map()
				.e(SortableAttributeCompoundSchemaMutationDescriptor.NAME.name(), "code")
				.e(CreateSortableAttributeCompoundSchemaMutationDescriptor.DESCRIPTION.name(), "desc")
				.e(CreateSortableAttributeCompoundSchemaMutationDescriptor.DEPRECATION_NOTICE.name(), "depr")
				.e(CreateSortableAttributeCompoundSchemaMutationDescriptor.INDEXED_IN_SCOPES.name(), list()
					.i(Scope.LIVE))
				.e(CreateSortableAttributeCompoundSchemaMutationDescriptor.ATTRIBUTE_ELEMENTS.name(), List.of(
					map()
						.e(AttributeElementDescriptor.ATTRIBUTE_NAME.name(), "a")
						.e(AttributeElementDescriptor.DIRECTION.name(), "ASC")
						.e(AttributeElementDescriptor.BEHAVIOUR.name(), "NULLS_FIRST")
						.build(),
					map()
						.e(AttributeElementDescriptor.ATTRIBUTE_NAME.name(), "b")
						.e(AttributeElementDescriptor.DIRECTION.name(), "DESC")
						.e(AttributeElementDescriptor.BEHAVIOUR.name(), "NULLS_LAST")
						.build()
				))
				.build()
		);
		assertEquals(expectedMutation, convertedMutation1);

		final CreateSortableAttributeCompoundSchemaMutation convertedMutation2 = this.converter.convertFromInput(
			map()
				.e(SortableAttributeCompoundSchemaMutationDescriptor.NAME.name(), "code")
				.e(CreateSortableAttributeCompoundSchemaMutationDescriptor.DESCRIPTION.name(), "desc")
				.e(CreateSortableAttributeCompoundSchemaMutationDescriptor.DEPRECATION_NOTICE.name(), "depr")
				.e(CreateSortableAttributeCompoundSchemaMutationDescriptor.INDEXED_IN_SCOPES.name(), list()
					.i(Scope.LIVE.name()))
				.e(CreateSortableAttributeCompoundSchemaMutationDescriptor.ATTRIBUTE_ELEMENTS.name(), List.of(
					map()
						.e(AttributeElementDescriptor.ATTRIBUTE_NAME.name(), "a")
						.e(AttributeElementDescriptor.DIRECTION.name(), OrderDirection.ASC)
						.e(AttributeElementDescriptor.BEHAVIOUR.name(), OrderBehaviour.NULLS_FIRST)
						.build(),
					map()
						.e(AttributeElementDescriptor.ATTRIBUTE_NAME.name(), "b")
						.e(AttributeElementDescriptor.DIRECTION.name(), OrderDirection.DESC)
						.e(AttributeElementDescriptor.BEHAVIOUR.name(), OrderBehaviour.NULLS_LAST)
						.build()
				))
				.build()
		);
		assertEquals(expectedMutation, convertedMutation2);
	}
	@Test
	void shouldResolveInputToLocalMutationWithOnlyRequiredData() {
		final CreateSortableAttributeCompoundSchemaMutation expectedMutation = new CreateSortableAttributeCompoundSchemaMutation(
			"code",
			null,
			null,
			null,
			new AttributeElement("a", OrderDirection.ASC, OrderBehaviour.NULLS_FIRST)
		);

		final CreateSortableAttributeCompoundSchemaMutation convertedMutation1 = this.converter.convertFromInput(
			map()
				.e(SortableAttributeCompoundSchemaMutationDescriptor.NAME.name(), "code")
				.e(CreateSortableAttributeCompoundSchemaMutationDescriptor.ATTRIBUTE_ELEMENTS.name(), List.of(
					map()
						.e(AttributeElementDescriptor.ATTRIBUTE_NAME.name(), "a")
						.e(AttributeElementDescriptor.DIRECTION.name(), "ASC")
						.e(AttributeElementDescriptor.BEHAVIOUR.name(), "NULLS_FIRST")
						.build()
				))
				.build()
		);
		assertEquals(expectedMutation, convertedMutation1);
	}

	@Test
	void shouldNotResolveInputWhenMissingRequiredData() {
		assertThrows(
			EvitaInvalidUsageException.class,
			() -> this.converter.convertFromInput(
				map()
					.e(CreateSortableAttributeCompoundSchemaMutationDescriptor.ATTRIBUTE_ELEMENTS.name(), List.of(
						map()
							.e(AttributeElementDescriptor.ATTRIBUTE_NAME.name(), "a")
							.e(AttributeElementDescriptor.DIRECTION.name(), "ASC")
							.e(AttributeElementDescriptor.BEHAVIOUR.name(), "NULLS_FIRST")
							.build()
					))
					.build()
			)
		);
		assertThrows(
			EvitaInvalidUsageException.class,
			() -> this.converter.convertFromInput(
				map()
					.e(SortableAttributeCompoundSchemaMutationDescriptor.NAME.name(), "code")
					.build()
			)
		);
		assertThrows(EvitaInvalidUsageException.class, () -> this.converter.convertFromInput(Map.of()));
		assertThrows(EvitaInvalidUsageException.class, () -> this.converter.convertFromInput((Object) null));
	}

	@Test
	void shouldSerializeLocalMutationToOutput() {
		final CreateSortableAttributeCompoundSchemaMutation inputMutation = new CreateSortableAttributeCompoundSchemaMutation(
			"code",
			"desc",
			"depr",
			new Scope[] { Scope.LIVE },
			new AttributeElement("a", OrderDirection.ASC, OrderBehaviour.NULLS_FIRST),
			new AttributeElement("b", OrderDirection.DESC, OrderBehaviour.NULLS_LAST)
		);

		//noinspection unchecked
		final Map<String, Object> serializedMutation = (Map<String, Object>) this.converter.convertToOutput(inputMutation);
		assertThat(serializedMutation)
			.usingRecursiveComparison()
			.isEqualTo(
				map()
					.e(MutationDescriptor.MUTATION_TYPE.name(), CreateSortableAttributeCompoundSchemaMutation.class.getSimpleName())
					.e(SortableAttributeCompoundSchemaMutationDescriptor.NAME.name(), "code")
					.e(CreateSortableAttributeCompoundSchemaMutationDescriptor.DESCRIPTION.name(), "desc")
					.e(CreateSortableAttributeCompoundSchemaMutationDescriptor.DEPRECATION_NOTICE.name(), "depr")
					.e(CreateSortableAttributeCompoundSchemaMutationDescriptor.INDEXED_IN_SCOPES.name(), array()
						.i(Scope.LIVE.name()))
					.e(CreateSortableAttributeCompoundSchemaMutationDescriptor.ATTRIBUTE_ELEMENTS.name(), List.of(
						map()
							.e(AttributeElementDescriptor.ATTRIBUTE_NAME.name(), "a")
							.e(AttributeElementDescriptor.DIRECTION.name(), OrderDirection.ASC.name())
							.e(AttributeElementDescriptor.BEHAVIOUR.name(), OrderBehaviour.NULLS_FIRST.name())
							.build(),
						map()
							.e(AttributeElementDescriptor.ATTRIBUTE_NAME.name(), "b")
							.e(AttributeElementDescriptor.DIRECTION.name(), OrderDirection.DESC.name())
							.e(AttributeElementDescriptor.BEHAVIOUR.name(), OrderBehaviour.NULLS_LAST.name())
							.build()
					))
					.build()
			);
	}
}
