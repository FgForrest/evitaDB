/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

import io.evitadb.api.requestResponse.schema.mutation.sortableAttributeCompound.SetSortableAttributeCompoundIndexedMutation;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.externalApi.api.catalog.mutation.TestMutationResolvingExceptionFactory;
import io.evitadb.externalApi.api.catalog.resolver.mutation.PassThroughMutationObjectParser;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.sortableAttributeCompound.SetSortableAttributeCompoundIndexedMutationDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.sortableAttributeCompound.SortableAttributeCompoundSchemaMutationDescriptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.evitadb.test.builder.MapBuilder.map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link SetSortableAttributeCompoundIndexedMutationConverter}
 * TODO LHO - tohle bude potřeba doladit
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
class SetSortableAttributeCompoundIndexedMutationConverterTest {

	private SetSortableAttributeCompoundIndexedMutationConverter converter;

	@BeforeEach
	void init() {
		converter = new SetSortableAttributeCompoundIndexedMutationConverter(new PassThroughMutationObjectParser(), new TestMutationResolvingExceptionFactory());
	}

	@Test
	void shouldResolveInputToLocalMutation() {
		final SetSortableAttributeCompoundIndexedMutation expectedMutation = new SetSortableAttributeCompoundIndexedMutation(
			"code",
			Scope.values()
		);
		final SetSortableAttributeCompoundIndexedMutation convertedMutation = converter.convert(
			map()
				.e(SortableAttributeCompoundSchemaMutationDescriptor.NAME.name(), "code")
				.e(SetSortableAttributeCompoundIndexedMutationDescriptor.INDEXED_IN_SCOPES.name(), Scope.values())
				.build()
		);
		assertEquals(expectedMutation, convertedMutation);
	}

	@Test
	void shouldNotResolveInputWhenMissingRequiredData() {
		assertThrows(
			EvitaInvalidUsageException.class,
			() -> converter.convert(
				map()
					.e(SortableAttributeCompoundSchemaMutationDescriptor.NAME.name(), "code")
					.build()
			)
		);
		assertThrows(
			EvitaInvalidUsageException.class,
			() -> converter.convert(
				map()
					.e(SetSortableAttributeCompoundIndexedMutationDescriptor.INDEXED_IN_SCOPES.name(), Scope.values())
					.build()
			)
		);
		assertThrows(EvitaInvalidUsageException.class, () -> converter.convert(Map.of()));
		assertThrows(EvitaInvalidUsageException.class, () -> converter.convert((Object) null));
	}
}
