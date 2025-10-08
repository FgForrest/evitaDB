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

import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.ReflectedReferenceSchemaContract.AttributeInheritanceBehavior;
import io.evitadb.api.requestResponse.schema.dto.ReferenceIndexType;
import io.evitadb.api.requestResponse.schema.mutation.reference.CreateReflectedReferenceSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedReferenceIndexType;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.externalApi.api.catalog.mutation.TestMutationResolvingExceptionFactory;
import io.evitadb.externalApi.api.catalog.resolver.mutation.PassThroughMutationObjectMapper;
import io.evitadb.externalApi.api.catalog.schemaApi.model.ScopedDataDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.ScopedReferenceIndexTypeDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.reference.CreateReflectedReferenceSchemaMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.reference.ReferenceSchemaMutationDescriptor;
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
 * Tests for {@link CreateReflectedReferenceSchemaMutationConverter}
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
class CreateReflectedReferenceSchemaMutationConverterTest {

	private CreateReflectedReferenceSchemaMutationConverter converter;

	@BeforeEach
	void init() {
		this.converter = new CreateReflectedReferenceSchemaMutationConverter(PassThroughMutationObjectMapper.INSTANCE, TestMutationResolvingExceptionFactory.INSTANCE);
	}

	@Test
	void shouldResolveInputToLocalMutation() {
		final CreateReflectedReferenceSchemaMutation expectedMutation = new CreateReflectedReferenceSchemaMutation(
			"tags",
			"desc",
			"depr",
			Cardinality.ZERO_OR_MORE,
			"tag",
			"tags",
			new ScopedReferenceIndexType[] { new ScopedReferenceIndexType(Scope.DEFAULT_SCOPE, ReferenceIndexType.FOR_FILTERING) },
			new Scope[] { Scope.LIVE },
			AttributeInheritanceBehavior.INHERIT_ALL_EXCEPT,
			new String[] { "order" }
		);

		final CreateReflectedReferenceSchemaMutation convertedMutation1 = this.converter.convertFromInput(
			map()
				.e(ReferenceSchemaMutationDescriptor.NAME.name(), "tags")
				.e(CreateReflectedReferenceSchemaMutationDescriptor.DESCRIPTION.name(), "desc")
				.e(CreateReflectedReferenceSchemaMutationDescriptor.DEPRECATION_NOTICE.name(), "depr")
				.e(CreateReflectedReferenceSchemaMutationDescriptor.CARDINALITY.name(), Cardinality.ZERO_OR_MORE)
				.e(CreateReflectedReferenceSchemaMutationDescriptor.REFERENCED_ENTITY_TYPE.name(), "tag")
				.e(CreateReflectedReferenceSchemaMutationDescriptor.REFLECTED_REFERENCE_NAME.name(), "tags")
				.e(
					CreateReflectedReferenceSchemaMutationDescriptor.INDEXED_IN_SCOPES.name(),
					list().i(
						map()
							.e(ScopedReferenceIndexTypeDescriptor.SCOPE.name(), Scope.LIVE)
							.e(ScopedReferenceIndexTypeDescriptor.INDEX_TYPE.name(), ReferenceIndexType.FOR_FILTERING.name())
					)
				)
				.e(CreateReflectedReferenceSchemaMutationDescriptor.FACETED_IN_SCOPES.name(), list()
					.i(Scope.LIVE))
				.e(CreateReflectedReferenceSchemaMutationDescriptor.ATTRIBUTES_INHERITANCE_BEHAVIOR.name(), AttributeInheritanceBehavior.INHERIT_ALL_EXCEPT)
				.e(CreateReflectedReferenceSchemaMutationDescriptor.ATTRIBUTE_INHERITANCE_FILTER.name(), list()
					.i("order"))
				.build()
		);
		assertEquals(expectedMutation, convertedMutation1);

		final CreateReflectedReferenceSchemaMutation convertedMutation2 = this.converter.convertFromInput(
			map()
				.e(ReferenceSchemaMutationDescriptor.NAME.name(), "tags")
				.e(CreateReflectedReferenceSchemaMutationDescriptor.DESCRIPTION.name(), "desc")
				.e(CreateReflectedReferenceSchemaMutationDescriptor.DEPRECATION_NOTICE.name(), "depr")
				.e(CreateReflectedReferenceSchemaMutationDescriptor.CARDINALITY.name(), "ZERO_OR_MORE")
				.e(CreateReflectedReferenceSchemaMutationDescriptor.REFERENCED_ENTITY_TYPE.name(), "tag")
				.e(CreateReflectedReferenceSchemaMutationDescriptor.REFLECTED_REFERENCE_NAME.name(), "tags")
				.e(
					CreateReflectedReferenceSchemaMutationDescriptor.INDEXED_IN_SCOPES.name(),
					list().i(
						map()
							.e(ScopedReferenceIndexTypeDescriptor.SCOPE.name(), Scope.LIVE)
							.e(ScopedReferenceIndexTypeDescriptor.INDEX_TYPE.name(), ReferenceIndexType.FOR_FILTERING.name())
					)
				)
				.e(CreateReflectedReferenceSchemaMutationDescriptor.FACETED_IN_SCOPES.name(), list()
					.i(Scope.LIVE.name()))
				.e(CreateReflectedReferenceSchemaMutationDescriptor.ATTRIBUTES_INHERITANCE_BEHAVIOR.name(), AttributeInheritanceBehavior.INHERIT_ALL_EXCEPT.name())
				.e(CreateReflectedReferenceSchemaMutationDescriptor.ATTRIBUTE_INHERITANCE_FILTER.name(), list()
					.i("order"))
				.build()
		);
		assertEquals(expectedMutation, convertedMutation2);
	}

	@Test
	void shouldResolveInputToLocalMutationWithOnlyRequiredData() {
		final CreateReflectedReferenceSchemaMutation expectedMutation = new CreateReflectedReferenceSchemaMutation(
			"tags",
			null,
			null,
			null,
			"tag",
			"tags",
			null,
			null,
			AttributeInheritanceBehavior.INHERIT_ALL_EXCEPT,
			null
		);

		final CreateReflectedReferenceSchemaMutation convertedMutation1 = this.converter.convertFromInput(
			map()
				.e(ReferenceSchemaMutationDescriptor.NAME.name(), "tags")
				.e(CreateReflectedReferenceSchemaMutationDescriptor.REFERENCED_ENTITY_TYPE.name(), "tag")
				.e(CreateReflectedReferenceSchemaMutationDescriptor.REFLECTED_REFERENCE_NAME.name(), "tags")
				.e(CreateReflectedReferenceSchemaMutationDescriptor.ATTRIBUTES_INHERITANCE_BEHAVIOR.name(), AttributeInheritanceBehavior.INHERIT_ALL_EXCEPT.name())
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
					.e(CreateReflectedReferenceSchemaMutationDescriptor.REFERENCED_ENTITY_TYPE.name(), "tag")
					.e(CreateReflectedReferenceSchemaMutationDescriptor.REFLECTED_REFERENCE_NAME.name(), "tags")
					.e(CreateReflectedReferenceSchemaMutationDescriptor.ATTRIBUTES_INHERITANCE_BEHAVIOR.name(), AttributeInheritanceBehavior.INHERIT_ALL_EXCEPT.name())
					.build()
			)
		);
		assertThrows(
			EvitaInvalidUsageException.class,
			() -> this.converter.convertFromInput(
				map()
					.e(ReferenceSchemaMutationDescriptor.NAME.name(), "tags")
					.e(CreateReflectedReferenceSchemaMutationDescriptor.REFLECTED_REFERENCE_NAME.name(), "tags")
					.e(CreateReflectedReferenceSchemaMutationDescriptor.ATTRIBUTES_INHERITANCE_BEHAVIOR.name(), AttributeInheritanceBehavior.INHERIT_ALL_EXCEPT.name())
					.build()
			)
		);
		assertThrows(
			EvitaInvalidUsageException.class,
			() -> this.converter.convertFromInput(
				map()
					.e(ReferenceSchemaMutationDescriptor.NAME.name(), "tags")
					.e(CreateReflectedReferenceSchemaMutationDescriptor.REFERENCED_ENTITY_TYPE.name(), "tag")
					.e(CreateReflectedReferenceSchemaMutationDescriptor.ATTRIBUTES_INHERITANCE_BEHAVIOR.name(), AttributeInheritanceBehavior.INHERIT_ALL_EXCEPT.name())
					.build()
			)
		);
		assertThrows(
			EvitaInvalidUsageException.class,
			() -> this.converter.convertFromInput(
				map()
					.e(ReferenceSchemaMutationDescriptor.NAME.name(), "tags")
					.e(CreateReflectedReferenceSchemaMutationDescriptor.REFERENCED_ENTITY_TYPE.name(), "tag")
					.e(CreateReflectedReferenceSchemaMutationDescriptor.REFLECTED_REFERENCE_NAME.name(), "tags")
					.build()
			)
		);
		assertThrows(EvitaInvalidUsageException.class, () -> this.converter.convertFromInput(Map.of()));
		assertThrows(EvitaInvalidUsageException.class, () -> this.converter.convertFromInput((Object) null));
	}

	@Test
	void shouldSerializeLocalMutationToOutput() {
		final CreateReflectedReferenceSchemaMutation inputMutation = new CreateReflectedReferenceSchemaMutation(
			"tags",
			"desc",
			"depr",
			Cardinality.ZERO_OR_MORE,
			"tag",
			"tags",
			new ScopedReferenceIndexType[] { new ScopedReferenceIndexType(Scope.LIVE, ReferenceIndexType.FOR_FILTERING_AND_PARTITIONING) },
			new Scope[] { Scope.LIVE },
			AttributeInheritanceBehavior.INHERIT_ALL_EXCEPT,
			new String[] { "order" }
		);

		//noinspection unchecked
		final Map<String, Object> serializedMutation = (Map<String, Object>) this.converter.convertToOutput(inputMutation);
		assertThat(serializedMutation)
			.usingRecursiveComparison()
			.isEqualTo(
				map()
					.e(MutationDescriptor.MUTATION_TYPE.name(), CreateReflectedReferenceSchemaMutation.class.getSimpleName())
					.e(ReferenceSchemaMutationDescriptor.NAME.name(), "tags")
					.e(CreateReflectedReferenceSchemaMutationDescriptor.DESCRIPTION.name(), "desc")
					.e(CreateReflectedReferenceSchemaMutationDescriptor.DEPRECATION_NOTICE.name(), "depr")
					.e(CreateReflectedReferenceSchemaMutationDescriptor.CARDINALITY.name(), Cardinality.ZERO_OR_MORE.name())
					.e(CreateReflectedReferenceSchemaMutationDescriptor.REFERENCED_ENTITY_TYPE.name(), "tag")
					.e(CreateReflectedReferenceSchemaMutationDescriptor.REFLECTED_REFERENCE_NAME.name(), "tags")
					.e(CreateReflectedReferenceSchemaMutationDescriptor.INDEXED_IN_SCOPES.name(),
					   list().i(
						   map()
							   .e(ScopedDataDescriptor.SCOPE.name(), Scope.LIVE.name())
							   .e(ScopedReferenceIndexTypeDescriptor.INDEX_TYPE.name(), ReferenceIndexType.FOR_FILTERING_AND_PARTITIONING.name())
					   )
					)
					.e(CreateReflectedReferenceSchemaMutationDescriptor.FACETED_IN_SCOPES.name(), array()
						.i(Scope.LIVE.name()))
					.e(CreateReflectedReferenceSchemaMutationDescriptor.ATTRIBUTES_INHERITANCE_BEHAVIOR.name(), AttributeInheritanceBehavior.INHERIT_ALL_EXCEPT.name())
					.e(CreateReflectedReferenceSchemaMutationDescriptor.ATTRIBUTE_INHERITANCE_FILTER.name(), array()
						.i("order"))
					.build()
			);
	}
}
