/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.externalApi.api.catalog.dataApi.resolver.mutation.entity;

import io.evitadb.api.requestResponse.data.mutation.LocalMutation;
import io.evitadb.api.requestResponse.data.mutation.entity.SetHierarchicalPlacementMutation;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.entity.SetHierarchicalPlacementMutationDescriptor;
import io.evitadb.externalApi.api.catalog.mutation.TestMutationResolvingExceptionFactory;
import io.evitadb.externalApi.api.catalog.resolver.mutation.PassThroughMutationObjectParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.evitadb.test.builder.MapBuilder.map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link SetHierarchicalPlacementMutationConverter}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
class SetHierarchicalPlacementMutationConverterTest {

	private SetHierarchicalPlacementMutationConverter converter;

	@BeforeEach
	void init() {
		converter =  new SetHierarchicalPlacementMutationConverter(new PassThroughMutationObjectParser(), new TestMutationResolvingExceptionFactory());
	}

	@Test
	void shouldResolveInputToLocalMutation() {
		final SetHierarchicalPlacementMutation expectedMutation = new SetHierarchicalPlacementMutation(1, 10);

		final LocalMutation<?, ?> localMutation = converter.convert(
			map()
				.e(SetHierarchicalPlacementMutationDescriptor.PARENT_PRIMARY_KEY.name(), 1)
				.e(SetHierarchicalPlacementMutationDescriptor.ORDER_AMONG_SIBLINGS.name(), 10)
				.build()
		);
		assertEquals(expectedMutation, localMutation);

		final LocalMutation<?, ?> localMutation2 = converter.convert(
			map()
				.e(SetHierarchicalPlacementMutationDescriptor.PARENT_PRIMARY_KEY.name(), "1")
				.e(SetHierarchicalPlacementMutationDescriptor.ORDER_AMONG_SIBLINGS.name(), "10")
				.build()
		);
		assertEquals(expectedMutation, localMutation2);
	}

	@Test
	void shouldResolveInputToLocalMutationWithOnlyRequiredData() {
		final LocalMutation<?, ?> localMutation = converter.convert(
			map()
				.e(SetHierarchicalPlacementMutationDescriptor.ORDER_AMONG_SIBLINGS.name(), 10)
				.build()
		);
		assertEquals(
			new SetHierarchicalPlacementMutation(10),
			localMutation
		);
	}

	@Test
	void shouldNotResolveInputWhenMissingRequiredData() {
		assertThrows(EvitaInvalidUsageException.class, () -> converter.convert(Map.of()));
		assertThrows(EvitaInvalidUsageException.class, () -> converter.convert((Object) null));
	}
}