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

package io.evitadb.externalApi.api.catalog.dataApi.resolver.mutation.reference;

import io.evitadb.api.requestResponse.data.mutation.LocalMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.mutation.reference.SetReferenceGroupMutation;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.reference.SetReferenceGroupMutationDescriptor;
import io.evitadb.externalApi.api.catalog.mutation.TestMutationResolvingExceptionFactory;
import io.evitadb.externalApi.api.catalog.resolver.mutation.PassThroughMutationObjectParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.evitadb.utils.MapBuilder.map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link SetReferenceGroupMutationConverter}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
class SetReferenceGroupMutationConverterTest {

	public static final String REFERENCE_TAGS = "tags";
	private SetReferenceGroupMutationConverter converter;

	@BeforeEach
	void init() {
		this.converter =  new SetReferenceGroupMutationConverter(new PassThroughMutationObjectParser(), new TestMutationResolvingExceptionFactory());
	}

	@Test
	void shouldResolveInputToLocalMutation() {
		final SetReferenceGroupMutation expectedMutation = new SetReferenceGroupMutation(new ReferenceKey(REFERENCE_TAGS, 1), "TagsGroup", 2);

		final LocalMutation<?, ?> localMutation = this.converter.convertFromInput(
			map()
				.e(SetReferenceGroupMutationDescriptor.NAME.name(), REFERENCE_TAGS)
				.e(SetReferenceGroupMutationDescriptor.PRIMARY_KEY.name(), 1)
				.e(SetReferenceGroupMutationDescriptor.GROUP_TYPE.name(), "TagsGroup")
				.e(SetReferenceGroupMutationDescriptor.GROUP_PRIMARY_KEY.name(), 2)
				.build()
		);
		assertEquals(expectedMutation, localMutation);
	}

	@Test
	void shouldResolveInputToLocalMutationWithOnlyRequiredData() {
		final SetReferenceGroupMutation expectedMutation = new SetReferenceGroupMutation(new ReferenceKey(REFERENCE_TAGS, 1), null, 2);

		final LocalMutation<?, ?> localMutation = this.converter.convertFromInput(
			map()
				.e(SetReferenceGroupMutationDescriptor.NAME.name(), REFERENCE_TAGS)
				.e(SetReferenceGroupMutationDescriptor.PRIMARY_KEY.name(), 1)
				.e(SetReferenceGroupMutationDescriptor.GROUP_PRIMARY_KEY.name(), 2)
				.build()
		);
		assertEquals(expectedMutation, localMutation);
	}

	@Test
	void shouldNotResolveInputWhenMissingRequiredData() {
		assertThrows(
			EvitaInvalidUsageException.class,
			() -> this.converter.convertFromInput(
				map()
					.e(SetReferenceGroupMutationDescriptor.NAME.name(), REFERENCE_TAGS)
					.e(SetReferenceGroupMutationDescriptor.PRIMARY_KEY.name(), 1)
					.build()
			)
		);
		assertThrows(
			EvitaInvalidUsageException.class,
			() -> this.converter.convertFromInput(
				map()
					.e(SetReferenceGroupMutationDescriptor.NAME.name(), REFERENCE_TAGS)
					.e(SetReferenceGroupMutationDescriptor.GROUP_PRIMARY_KEY.name(), 2)
					.build()
			)
		);
		assertThrows(
			EvitaInvalidUsageException.class,
			() -> this.converter.convertFromInput(
				map()
					.e(SetReferenceGroupMutationDescriptor.PRIMARY_KEY.name(), 1)
					.e(SetReferenceGroupMutationDescriptor.GROUP_PRIMARY_KEY.name(), 2)
					.build()
			)
		);
		assertThrows(EvitaInvalidUsageException.class, () -> this.converter.convertFromInput(Map.of()));
		assertThrows(EvitaInvalidUsageException.class, () -> this.converter.convertFromInput((Object) null));
	}

	@Test
	void shouldSerializeLocalMutationToOutput() {
		final SetReferenceGroupMutation inputMutation = new SetReferenceGroupMutation(new ReferenceKey(REFERENCE_TAGS, 1), "TagsGroup", 2);

		//noinspection unchecked
		final Map<String, Object> serializedMutation = (Map<String, Object>) this.converter.convertToOutput(inputMutation);
		assertThat(serializedMutation)
			.usingRecursiveComparison()
			.isEqualTo(
				map()
					.e(SetReferenceGroupMutationDescriptor.NAME.name(), REFERENCE_TAGS)
					.e(SetReferenceGroupMutationDescriptor.PRIMARY_KEY.name(), 1)
					.e(SetReferenceGroupMutationDescriptor.GROUP_TYPE.name(), "TagsGroup")
					.e(SetReferenceGroupMutationDescriptor.GROUP_PRIMARY_KEY.name(), 2)
					.build()
			);
	}
}
