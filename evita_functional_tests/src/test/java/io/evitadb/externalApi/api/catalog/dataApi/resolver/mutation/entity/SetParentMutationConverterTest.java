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

package io.evitadb.externalApi.api.catalog.dataApi.resolver.mutation.entity;

import io.evitadb.api.requestResponse.data.mutation.LocalMutation;
import io.evitadb.api.requestResponse.data.mutation.parent.SetParentMutation;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.entity.SetParentMutationDescriptor;
import io.evitadb.externalApi.api.catalog.mutation.TestMutationResolvingExceptionFactory;
import io.evitadb.externalApi.api.catalog.resolver.mutation.PassThroughMutationObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.evitadb.utils.MapBuilder.map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link SetParentMutationConverter}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
class SetParentMutationConverterTest {

	private SetParentMutationConverter converter;

	@BeforeEach
	void init() {
		this.converter =  new SetParentMutationConverter(PassThroughMutationObjectMapper.INSTANCE, TestMutationResolvingExceptionFactory.INSTANCE);
	}

	@Test
	void shouldResolveInputToLocalMutation() {
		final SetParentMutation expectedMutation = new SetParentMutation(1);

		final LocalMutation<?, ?> localMutation = this.converter.convertFromInput(
			map()
				.e(SetParentMutationDescriptor.PARENT_PRIMARY_KEY.name(), 1)
				.build()
		);
		assertEquals(expectedMutation, localMutation);

		final LocalMutation<?, ?> localMutation2 = this.converter.convertFromInput(
			map()
				.e(SetParentMutationDescriptor.PARENT_PRIMARY_KEY.name(), "1")
				.build()
		);
		assertEquals(expectedMutation, localMutation2);
	}

	@Test
	void shouldResolveInputToLocalMutationWithOnlyRequiredData() {
		final LocalMutation<?, ?> localMutation = this.converter.convertFromInput(
			map()
				.e(SetParentMutationDescriptor.PARENT_PRIMARY_KEY.name(), 10)
				.build()
		);
		assertEquals(
			new SetParentMutation(10),
			localMutation
		);
	}

	@Test
	void shouldNotResolveInputWhenMissingRequiredData() {
		assertThrows(EvitaInvalidUsageException.class, () -> this.converter.convertFromInput(Map.of()));
		assertThrows(EvitaInvalidUsageException.class, () -> this.converter.convertFromInput((Object) null));
	}

	@Test
	void shouldSerializeLocalMutationToOutput() {
		final SetParentMutation inputMutation = new SetParentMutation(1);

		assertEquals(
			map()
				.e(SetParentMutationDescriptor.MUTATION_TYPE.name(),  SetParentMutation.class.getSimpleName())
				.e(SetParentMutationDescriptor.PARENT_PRIMARY_KEY.name(), 1)
				.build(),
			this.converter.convertToOutput(inputMutation)
		);
	}
}
