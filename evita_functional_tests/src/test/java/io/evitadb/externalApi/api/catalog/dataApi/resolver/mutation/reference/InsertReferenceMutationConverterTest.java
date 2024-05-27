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
import io.evitadb.api.requestResponse.data.mutation.reference.InsertReferenceMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.reference.InsertReferenceMutationDescriptor;
import io.evitadb.externalApi.api.catalog.mutation.TestMutationResolvingExceptionFactory;
import io.evitadb.externalApi.api.catalog.resolver.mutation.PassThroughMutationObjectParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.evitadb.test.builder.MapBuilder.map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link InsertReferenceMutationConverter}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
class InsertReferenceMutationConverterTest {

	private static final String REFERENCE_TAGS = "tags";
	private InsertReferenceMutationConverter converter;

	@BeforeEach
	void init() {
		converter =  new InsertReferenceMutationConverter(new PassThroughMutationObjectParser(), new TestMutationResolvingExceptionFactory());
	}

	@Test
	void shouldResolveInputToLocalMutation() {
		final InsertReferenceMutation expectedMutation = new InsertReferenceMutation(new ReferenceKey(REFERENCE_TAGS, 1), Cardinality.ONE_OR_MORE, "Tag");

		final LocalMutation<?, ?> localMutation = converter.convert(
			map()
				.e(InsertReferenceMutationDescriptor.NAME.name(), REFERENCE_TAGS)
				.e(InsertReferenceMutationDescriptor.PRIMARY_KEY.name(), 1)
				.e(InsertReferenceMutationDescriptor.CARDINALITY.name(), Cardinality.ONE_OR_MORE)
				.e(InsertReferenceMutationDescriptor.REFERENCED_ENTITY_TYPE.name(), "Tag")
				.build()
		);
		assertEquals(expectedMutation, localMutation);

		final LocalMutation<?, ?> localMutation2 = converter.convert(
			map()
				.e(InsertReferenceMutationDescriptor.NAME.name(), REFERENCE_TAGS)
				.e(InsertReferenceMutationDescriptor.PRIMARY_KEY.name(), 1)
				.e(InsertReferenceMutationDescriptor.CARDINALITY.name(), "ONE_OR_MORE")
				.e(InsertReferenceMutationDescriptor.REFERENCED_ENTITY_TYPE.name(), "Tag")
				.build()
		);
		assertEquals(expectedMutation, localMutation2);
	}

	@Test
	void shouldResolveInputToLocalMutationWithOnlyRequiredData() {
		final LocalMutation<?, ?> localMutation = converter.convert(
			map()
				.e(InsertReferenceMutationDescriptor.NAME.name(), REFERENCE_TAGS)
				.e(InsertReferenceMutationDescriptor.PRIMARY_KEY.name(), 1)
				.build()
		);
		assertEquals(
			new InsertReferenceMutation(new ReferenceKey(REFERENCE_TAGS, 1), null, null),
			localMutation
		);
	}

	@Test
	void shouldNotResolveInputWhenMissingRequiredData() {
		assertThrows(
			EvitaInvalidUsageException.class,
			() -> converter.convert(
				map()
					.e(InsertReferenceMutationDescriptor.NAME.name(), REFERENCE_TAGS)
					.build()
			)
		);
		assertThrows(
			EvitaInvalidUsageException.class,
			() -> converter.convert(
				map()
					.e(InsertReferenceMutationDescriptor.PRIMARY_KEY.name(), 1)
					.build()
			)
		);
		assertThrows(EvitaInvalidUsageException.class, () -> converter.convert(Map.of()));
		assertThrows(EvitaInvalidUsageException.class, () -> converter.convert((Object) null));
	}
}
