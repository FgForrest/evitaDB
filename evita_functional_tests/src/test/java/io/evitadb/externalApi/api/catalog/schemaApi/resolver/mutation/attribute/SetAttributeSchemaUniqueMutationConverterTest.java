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

import io.evitadb.api.requestResponse.schema.dto.AttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.mutation.attribute.ScopedAttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.mutation.attribute.SetAttributeSchemaUniqueMutation;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.externalApi.api.catalog.mutation.TestMutationResolvingExceptionFactory;
import io.evitadb.externalApi.api.catalog.resolver.mutation.PassThroughMutationObjectMapper;
import io.evitadb.externalApi.api.catalog.schemaApi.model.ScopedAttributeUniquenessTypeDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.ScopedDataDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.attribute.AttributeSchemaMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.attribute.SetAttributeSchemaUniqueMutationDescriptor;
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
 * Tests for {@link SetAttributeSchemaUniqueMutationConverter}
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
class SetAttributeSchemaUniqueMutationConverterTest {

	private SetAttributeSchemaUniqueMutationConverter converter;

	@BeforeEach
	void init() {
		this.converter = new SetAttributeSchemaUniqueMutationConverter(PassThroughMutationObjectMapper.INSTANCE, TestMutationResolvingExceptionFactory.INSTANCE);
	}

	@Test
	void shouldResolveInputToLocalMutation() {
		final SetAttributeSchemaUniqueMutation expectedMutation = new SetAttributeSchemaUniqueMutation(
			"code",
			new ScopedAttributeUniquenessType[] {
				new ScopedAttributeUniquenessType(Scope.LIVE, AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION)
			}
		);

		final SetAttributeSchemaUniqueMutation convertedMutation1 = this.converter.convertFromInput(
			map()
				.e(AttributeSchemaMutationDescriptor.NAME.name(), "code")
				.e(SetAttributeSchemaUniqueMutationDescriptor.UNIQUE_IN_SCOPES.name(), list()
					.i(map()
						.e(ScopedDataDescriptor.SCOPE.name(), Scope.LIVE)
						.e(ScopedAttributeUniquenessTypeDescriptor.UNIQUENESS_TYPE.name(), AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION)))
				.build()
		);
		assertEquals(expectedMutation, convertedMutation1);

		final SetAttributeSchemaUniqueMutation convertedMutation2 = this.converter.convertFromInput(
			map()
				.e(AttributeSchemaMutationDescriptor.NAME.name(), "code")
				.e(SetAttributeSchemaUniqueMutationDescriptor.UNIQUE_IN_SCOPES.name(), list()
					.i(map()
						.e(ScopedDataDescriptor.SCOPE.name(), Scope.LIVE.name())
						.e(ScopedAttributeUniquenessTypeDescriptor.UNIQUENESS_TYPE.name(), AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION.name())))
				.build()
		);
		assertEquals(expectedMutation, convertedMutation2);
	}

	@Test
	void shouldResolveInputToLocalMutationWithOnlyRequiredData() {
		final SetAttributeSchemaUniqueMutation expectedMutation = new SetAttributeSchemaUniqueMutation(
			"code",
			(ScopedAttributeUniquenessType[]) null
		);

		final SetAttributeSchemaUniqueMutation convertedMutation1 = this.converter.convertFromInput(
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
					.e(SetAttributeSchemaUniqueMutationDescriptor.UNIQUE_IN_SCOPES.name(), true)
					.build()
			)
		);
		assertThrows(EvitaInvalidUsageException.class, () -> this.converter.convertFromInput(Map.of()));
		assertThrows(EvitaInvalidUsageException.class, () -> this.converter.convertFromInput((Object) null));
	}

	@Test
	void shouldSerializeLocalMutationToOutput() {
		final SetAttributeSchemaUniqueMutation inputMutation = new SetAttributeSchemaUniqueMutation(
			"code",
			new ScopedAttributeUniquenessType[] {
				new ScopedAttributeUniquenessType(Scope.LIVE, AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION)
			}
		);

		//noinspection unchecked
		final Map<String, Object> serializedMutation = (Map<String, Object>) this.converter.convertToOutput(inputMutation);
		assertThat(serializedMutation)
			.usingRecursiveComparison()
			.isEqualTo(
				map()
					.e(MutationDescriptor.MUTATION_TYPE.name(), SetAttributeSchemaUniqueMutation.class.getSimpleName())
					.e(AttributeSchemaMutationDescriptor.NAME.name(), "code")
					.e(SetAttributeSchemaUniqueMutationDescriptor.UNIQUE_IN_SCOPES.name(), list()
						.i(map()
							.e(ScopedDataDescriptor.SCOPE.name(), Scope.LIVE.name())
							.e(ScopedAttributeUniquenessTypeDescriptor.UNIQUENESS_TYPE.name(), AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION.name())))
					.build()
			);
	}
}
