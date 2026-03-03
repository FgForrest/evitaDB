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

import io.evitadb.api.requestResponse.schema.ReferenceIndexType;
import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedReferenceIndexType;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedReferenceIndexedComponents;
import io.evitadb.api.requestResponse.schema.mutation.reference.SetReferenceSchemaIndexedMutation;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.externalApi.api.catalog.mutation.TestMutationResolvingExceptionFactory;
import io.evitadb.externalApi.api.catalog.schemaApi.model.ScopedDataDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.ScopedReferenceIndexTypeDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.ScopedReferenceIndexedComponentsDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.reference.ReferenceSchemaMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.reference.SetReferenceSchemaIndexedMutationDescriptor;
import io.evitadb.externalApi.api.model.mutation.MutationDescriptor;
import io.evitadb.externalApi.api.resolver.mutation.PassThroughMutationObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.evitadb.utils.ListBuilder.list;
import static io.evitadb.utils.MapBuilder.map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link SetReferenceSchemaIndexedMutationConverter}
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@DisplayName("SetReferenceSchemaIndexedMutation REST converter test")
class SetReferenceSchemaIndexedMutationConverterTest {

	private SetReferenceSchemaIndexedMutationConverter converter;

	@BeforeEach
	void init() {
		this.converter = new SetReferenceSchemaIndexedMutationConverter(PassThroughMutationObjectMapper.INSTANCE, TestMutationResolvingExceptionFactory.INSTANCE);
	}

	@Test
	@DisplayName("should resolve input to local mutation")
	void shouldResolveInputToLocalMutation() {
		final SetReferenceSchemaIndexedMutation expectedMutation = new SetReferenceSchemaIndexedMutation(
			"tags",
			new Scope[] { Scope.LIVE }
		);

		final SetReferenceSchemaIndexedMutation convertedMutation1 = this.converter.convertFromInput(
			map()
				.e(ReferenceSchemaMutationDescriptor.NAME.name(), "tags")
				.e(
					SetReferenceSchemaIndexedMutationDescriptor.INDEXED_IN_SCOPES.name(),
					list().i(
						map()
							.e(ScopedDataDescriptor.SCOPE.name(), Scope.LIVE)
							.e(ScopedReferenceIndexTypeDescriptor.INDEX_TYPE.name(), ReferenceIndexType.FOR_FILTERING.name())
					)
				)
				.build()
		);
		assertEquals(expectedMutation, convertedMutation1);

		final SetReferenceSchemaIndexedMutation convertedMutation2 = this.converter.convertFromInput(
			map()
				.e(ReferenceSchemaMutationDescriptor.NAME.name(), "tags")
				.e(SetReferenceSchemaIndexedMutationDescriptor.INDEXED_IN_SCOPES.name(),
					list().i(
						map()
							.e(ScopedDataDescriptor.SCOPE.name(), Scope.LIVE)
							.e(ScopedReferenceIndexTypeDescriptor.INDEX_TYPE.name(), ReferenceIndexType.FOR_FILTERING.name())
					)
				)
				.build()
		);
		assertEquals(expectedMutation, convertedMutation2);
	}

	@Test
	@DisplayName("should resolve input with indexed components to local mutation")
	void shouldResolveInputWithIndexedComponentsToLocalMutation() {
		final SetReferenceSchemaIndexedMutation expectedMutation = new SetReferenceSchemaIndexedMutation(
			"tags",
			new ScopedReferenceIndexType[]{
				new ScopedReferenceIndexType(Scope.LIVE, ReferenceIndexType.FOR_FILTERING_AND_PARTITIONING)
			},
			new ScopedReferenceIndexedComponents[]{
				new ScopedReferenceIndexedComponents(
					Scope.LIVE,
					new ReferenceIndexedComponents[]{
						ReferenceIndexedComponents.REFERENCED_ENTITY,
						ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
					}
				)
			}
		);

		final SetReferenceSchemaIndexedMutation convertedMutation = this.converter.convertFromInput(
			map()
				.e(ReferenceSchemaMutationDescriptor.NAME.name(), "tags")
				.e(
					SetReferenceSchemaIndexedMutationDescriptor.INDEXED_IN_SCOPES.name(),
					list().i(
						map()
							.e(ScopedDataDescriptor.SCOPE.name(), Scope.LIVE)
							.e(ScopedReferenceIndexTypeDescriptor.INDEX_TYPE.name(), ReferenceIndexType.FOR_FILTERING_AND_PARTITIONING.name())
					)
				)
				.e(
					SetReferenceSchemaIndexedMutationDescriptor.INDEXED_COMPONENTS_IN_SCOPES.name(),
					list().i(
						map()
							.e(ScopedDataDescriptor.SCOPE.name(), Scope.LIVE)
							.e(ScopedReferenceIndexedComponentsDescriptor.INDEXED_COMPONENTS.name(),
								new ReferenceIndexedComponents[]{
									ReferenceIndexedComponents.REFERENCED_ENTITY,
									ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
								}
							)
					)
				)
				.build()
		);
		assertEquals(expectedMutation, convertedMutation);
	}

	@Test
	@DisplayName("should not resolve input when missing required data")
	void shouldNotResolveInputWhenMissingRequiredData() {
		assertThrows(
			EvitaInvalidUsageException.class,
			() -> this.converter.convertFromInput(
				map()
					.e(SetReferenceSchemaIndexedMutationDescriptor.INDEXED_IN_SCOPES.name(), true)
					.build()
			)
		);
		assertThrows(EvitaInvalidUsageException.class, () -> this.converter.convertFromInput(Map.of()));
		assertThrows(EvitaInvalidUsageException.class, () -> this.converter.convertFromInput((Object) null));
	}

	@Test
	@DisplayName("should serialize local mutation to output")
	void shouldSerializeLocalMutationToOutput() {
		final SetReferenceSchemaIndexedMutation inputMutation = new SetReferenceSchemaIndexedMutation(
			"tags",
			new ScopedReferenceIndexType[] { new ScopedReferenceIndexType(Scope.LIVE, ReferenceIndexType.FOR_FILTERING_AND_PARTITIONING) }
		);

		//noinspection unchecked
		final Map<String, Object> serializedMutation = (Map<String, Object>) this.converter.convertToOutput(inputMutation);
		assertThat(serializedMutation)
			.usingRecursiveComparison()
			.isEqualTo(
				map()
					.e(MutationDescriptor.MUTATION_TYPE.name(), SetReferenceSchemaIndexedMutation.class.getSimpleName())
					.e(ReferenceSchemaMutationDescriptor.NAME.name(), "tags")
					.e(SetReferenceSchemaIndexedMutationDescriptor.INDEXED_IN_SCOPES.name(),
					   list().i(
						   map()
							   .e(ScopedDataDescriptor.SCOPE.name(), Scope.LIVE.name())
							   .e(ScopedReferenceIndexTypeDescriptor.INDEX_TYPE.name(), ReferenceIndexType.FOR_FILTERING_AND_PARTITIONING.name())
					   )
					)
					.build()
			);
	}

	@Test
	@DisplayName("should serialize local mutation with indexed components to output")
	void shouldSerializeLocalMutationWithIndexedComponentsToOutput() {
		final SetReferenceSchemaIndexedMutation inputMutation = new SetReferenceSchemaIndexedMutation(
			"tags",
			new ScopedReferenceIndexType[]{
				new ScopedReferenceIndexType(Scope.LIVE, ReferenceIndexType.FOR_FILTERING_AND_PARTITIONING)
			},
			new ScopedReferenceIndexedComponents[]{
				new ScopedReferenceIndexedComponents(
					Scope.LIVE,
					new ReferenceIndexedComponents[]{
						ReferenceIndexedComponents.REFERENCED_ENTITY,
						ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
					}
				)
			}
		);

		//noinspection unchecked
		final Map<String, Object> serializedMutation = (Map<String, Object>) this.converter.convertToOutput(inputMutation);
		assertThat(serializedMutation)
			.usingRecursiveComparison()
			.isEqualTo(
				map()
					.e(MutationDescriptor.MUTATION_TYPE.name(), SetReferenceSchemaIndexedMutation.class.getSimpleName())
					.e(ReferenceSchemaMutationDescriptor.NAME.name(), "tags")
					.e(SetReferenceSchemaIndexedMutationDescriptor.INDEXED_IN_SCOPES.name(),
					   list().i(
						   map()
							   .e(ScopedDataDescriptor.SCOPE.name(), Scope.LIVE.name())
							   .e(ScopedReferenceIndexTypeDescriptor.INDEX_TYPE.name(), ReferenceIndexType.FOR_FILTERING_AND_PARTITIONING.name())
					   )
					)
					.e(SetReferenceSchemaIndexedMutationDescriptor.INDEXED_COMPONENTS_IN_SCOPES.name(),
					   list().i(
						   map()
							   .e(ScopedDataDescriptor.SCOPE.name(), Scope.LIVE.name())
							   .e(ScopedReferenceIndexedComponentsDescriptor.INDEXED_COMPONENTS.name(),
								  new String[]{
									  ReferenceIndexedComponents.REFERENCED_ENTITY.name(),
									  ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY.name()
								  }
							   )
					   )
					)
					.build()
			);
	}
}
