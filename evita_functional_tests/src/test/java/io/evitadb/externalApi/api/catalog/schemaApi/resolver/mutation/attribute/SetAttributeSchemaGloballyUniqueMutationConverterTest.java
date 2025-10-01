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

package io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.attribute;

import io.evitadb.api.requestResponse.schema.dto.GlobalAttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.mutation.attribute.ScopedGlobalAttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.mutation.attribute.SetAttributeSchemaGloballyUniqueMutation;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.externalApi.api.catalog.mutation.TestMutationResolvingExceptionFactory;
import io.evitadb.externalApi.api.catalog.resolver.mutation.PassThroughMutationObjectMapper;
import io.evitadb.externalApi.api.catalog.schemaApi.model.ScopedDataDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.ScopedGlobalAttributeUniquenessTypeDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.attribute.AttributeSchemaMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.attribute.SetAttributeSchemaGloballyUniqueMutationDescriptor;
import io.evitadb.externalApi.api.model.mutation.MutationDescriptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.evitadb.utils.ListBuilder.list;
import static io.evitadb.utils.MapBuilder.map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link SetAttributeSchemaGloballyUniqueMutationConverter}
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
class SetAttributeSchemaGloballyUniqueMutationConverterTest {

	private SetAttributeSchemaGloballyUniqueMutationConverter converter;

	@BeforeEach
	void init() {
		this.converter = new SetAttributeSchemaGloballyUniqueMutationConverter(PassThroughMutationObjectMapper.INSTANCE, TestMutationResolvingExceptionFactory.INSTANCE);
	}

	@Test
	void shouldResolveInputToLocalMutation() {
		final SetAttributeSchemaGloballyUniqueMutation expectedMutation = new SetAttributeSchemaGloballyUniqueMutation(
			"code",
			new ScopedGlobalAttributeUniquenessType[] {
				new ScopedGlobalAttributeUniquenessType(Scope.LIVE, GlobalAttributeUniquenessType.UNIQUE_WITHIN_CATALOG)
			}
		);

		final SetAttributeSchemaGloballyUniqueMutation convertedMutation1 = this.converter.convertFromInput(
			map()
				.e(AttributeSchemaMutationDescriptor.NAME.name(), "code")
				.e(SetAttributeSchemaGloballyUniqueMutationDescriptor.UNIQUE_GLOBALLY_IN_SCOPES.name(), list()
					.i(map()
						.e(ScopedDataDescriptor.SCOPE.name(), Scope.LIVE)
						.e(ScopedGlobalAttributeUniquenessTypeDescriptor.UNIQUENESS_TYPE.name(), GlobalAttributeUniquenessType.UNIQUE_WITHIN_CATALOG)))
				.build()
		);
		assertEquals(expectedMutation, convertedMutation1);

		final SetAttributeSchemaGloballyUniqueMutation convertedMutation2 = this.converter.convertFromInput(
			map()
				.e(AttributeSchemaMutationDescriptor.NAME.name(), "code")
				.e(SetAttributeSchemaGloballyUniqueMutationDescriptor.UNIQUE_GLOBALLY_IN_SCOPES.name(), list()
					.i(map()
						.e(ScopedDataDescriptor.SCOPE.name(), Scope.LIVE.name())
						.e(ScopedGlobalAttributeUniquenessTypeDescriptor.UNIQUENESS_TYPE.name(), GlobalAttributeUniquenessType.UNIQUE_WITHIN_CATALOG.name())))
				.build()
		);
		assertEquals(expectedMutation, convertedMutation2);
	}

	@Test
	void shouldResolveInputToLocalMutationWithOnlyRequiredData() {
		final SetAttributeSchemaGloballyUniqueMutation expectedMutation = new SetAttributeSchemaGloballyUniqueMutation(
			"code",
			(ScopedGlobalAttributeUniquenessType[]) null
		);

		final SetAttributeSchemaGloballyUniqueMutation convertedMutation1 = this.converter.convertFromInput(
			map()
				.e(AttributeSchemaMutationDescriptor.NAME.name(), "code")
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
					.e(SetAttributeSchemaGloballyUniqueMutationDescriptor.UNIQUE_GLOBALLY_IN_SCOPES.name(), true)
					.build()
			)
		);
		assertThrows(EvitaInvalidUsageException.class, () -> this.converter.convertFromInput(Map.of()));
		assertThrows(EvitaInvalidUsageException.class, () -> this.converter.convertFromInput((Object) null));
	}

	@Test
	void shouldSerializeLocalMutationToOutput() {
		final SetAttributeSchemaGloballyUniqueMutation inputMutation = new SetAttributeSchemaGloballyUniqueMutation(
			"code",
			new ScopedGlobalAttributeUniquenessType[] {
				new ScopedGlobalAttributeUniquenessType(Scope.LIVE, GlobalAttributeUniquenessType.UNIQUE_WITHIN_CATALOG)
			}
		);

		//noinspection unchecked
		final Map<String, Object> serializedMutation = (Map<String, Object>) this.converter.convertToOutput(inputMutation);
		assertThat(serializedMutation)
			.usingRecursiveComparison()
			.isEqualTo(
				map()
					.e(MutationDescriptor.MUTATION_TYPE.name(), SetAttributeSchemaGloballyUniqueMutation.class.getSimpleName())
					.e(AttributeSchemaMutationDescriptor.NAME.name(), "code")
					.e(SetAttributeSchemaGloballyUniqueMutationDescriptor.UNIQUE_GLOBALLY_IN_SCOPES.name(), list()
						.i(map()
							.e(ScopedDataDescriptor.SCOPE.name(), Scope.LIVE.name())
							.e(ScopedGlobalAttributeUniquenessTypeDescriptor.UNIQUENESS_TYPE.name(), GlobalAttributeUniquenessType.UNIQUE_WITHIN_CATALOG.name())))
					.build()
			);
	}
}
