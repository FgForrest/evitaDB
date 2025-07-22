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

import io.evitadb.api.requestResponse.schema.dto.ReferenceIndexType;
import io.evitadb.api.requestResponse.schema.mutation.reference.SetReferenceSchemaIndexedMutation;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.externalApi.api.catalog.mutation.TestMutationResolvingExceptionFactory;
import io.evitadb.externalApi.api.catalog.resolver.mutation.PassThroughMutationObjectParser;
import io.evitadb.externalApi.api.catalog.schemaApi.model.ScopedReferenceIndexTypeDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.reference.ReferenceSchemaMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.reference.SetReferenceSchemaIndexedMutationDescriptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.evitadb.test.builder.ListBuilder.list;
import static io.evitadb.test.builder.MapBuilder.map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link SetReferenceSchemaIndexedMutationConverter}
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
class SetReferenceSchemaIndexedMutationConverterTest {

	private SetReferenceSchemaIndexedMutationConverter converter;

	@BeforeEach
	void init() {
		converter = new SetReferenceSchemaIndexedMutationConverter(new PassThroughMutationObjectParser(), new TestMutationResolvingExceptionFactory());
	}

	@Test
	void shouldResolveInputToLocalMutation() {
		final SetReferenceSchemaIndexedMutation expectedMutation = new SetReferenceSchemaIndexedMutation(
			"tags",
			new Scope[] { Scope.LIVE }
		);

		final SetReferenceSchemaIndexedMutation convertedMutation1 = converter.convert(
			map()
				.e(ReferenceSchemaMutationDescriptor.NAME.name(), "tags")
				.e(
					SetReferenceSchemaIndexedMutationDescriptor.INDEXED_IN_SCOPES.name(),
					list().i(
						map()
							.e(ScopedReferenceIndexTypeDescriptor.SCOPE.name(), Scope.LIVE)
							.e(ScopedReferenceIndexTypeDescriptor.INDEX_TYPE.name(), ReferenceIndexType.FOR_FILTERING.name())
					)
				)
				.build()
		);
		assertEquals(expectedMutation, convertedMutation1);

		final SetReferenceSchemaIndexedMutation convertedMutation2 = converter.convert(
			map()
				.e(ReferenceSchemaMutationDescriptor.NAME.name(), "tags")
				.e(SetReferenceSchemaIndexedMutationDescriptor.INDEXED_IN_SCOPES.name(),
					list().i(
						map()
							.e(ScopedReferenceIndexTypeDescriptor.SCOPE.name(), Scope.LIVE)
							.e(ScopedReferenceIndexTypeDescriptor.INDEX_TYPE.name(), ReferenceIndexType.FOR_FILTERING.name())
					)
				)
				.build()
		);
		assertEquals(expectedMutation, convertedMutation2);
	}

	@Test
	void shouldResolveInputToLocalMutationWithOnlyRequiredData() {
		final SetReferenceSchemaIndexedMutation expectedMutation = new SetReferenceSchemaIndexedMutation(
			"tags",
			(Scope[]) null
		);

		final SetReferenceSchemaIndexedMutation convertedMutation1 = converter.convert(
			map()
				.e(ReferenceSchemaMutationDescriptor.NAME.name(), "tags")
				.build()
		);
		assertEquals(expectedMutation, convertedMutation1);
	}

	@Test
	void shouldNotResolveInputWhenMissingRequiredData() {
		assertThrows(EvitaInvalidUsageException.class, () -> converter.convert(Map.of()));
		assertThrows(EvitaInvalidUsageException.class, () -> converter.convert((Object) null));
	}
}
