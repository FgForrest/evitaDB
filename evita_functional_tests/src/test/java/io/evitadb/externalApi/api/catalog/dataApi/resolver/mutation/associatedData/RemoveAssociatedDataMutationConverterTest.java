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

package io.evitadb.externalApi.api.catalog.dataApi.resolver.mutation.associatedData;

import io.evitadb.api.requestResponse.data.mutation.LocalMutation;
import io.evitadb.api.requestResponse.data.mutation.associatedData.RemoveAssociatedDataMutation;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.associatedData.AssociatedDataMutationDescriptor;
import io.evitadb.externalApi.api.catalog.mutation.TestMutationResolvingExceptionFactory;
import io.evitadb.externalApi.api.catalog.resolver.mutation.PassThroughMutationObjectParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.Map;

import static io.evitadb.utils.MapBuilder.map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link RemoveAssociatedDataMutationConverter}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
class RemoveAssociatedDataMutationConverterTest {

	private static final String ASSOCIATED_DATA_LABELS = "labels";

	private RemoveAssociatedDataMutationConverter converter;

	@BeforeEach
	void init() {
		this.converter =  new RemoveAssociatedDataMutationConverter(new PassThroughMutationObjectParser(), new TestMutationResolvingExceptionFactory());
	}

	@Test
	void shouldResolveInputToLocalMutation() {
		final RemoveAssociatedDataMutation expectedMutation = new RemoveAssociatedDataMutation(ASSOCIATED_DATA_LABELS, Locale.ENGLISH);

		final LocalMutation<?, ?> localMutation = this.converter.convertFromInput(
			map()
				.e(AssociatedDataMutationDescriptor.NAME.name(), ASSOCIATED_DATA_LABELS)
				.e(AssociatedDataMutationDescriptor.LOCALE.name(), Locale.ENGLISH)
				.build()
		);
		assertEquals(expectedMutation, localMutation);

		final LocalMutation<?, ?> localMutation2 = this.converter.convertFromInput(
			map()
				.e(AssociatedDataMutationDescriptor.NAME.name(), ASSOCIATED_DATA_LABELS)
				.e(AssociatedDataMutationDescriptor.LOCALE.name(), "en")
				.build()
		);
		assertEquals(expectedMutation, localMutation2);
	}

	@Test
	void shouldResolveInputToLocalMutationWithOnlyRequiredData() {
		final LocalMutation<?, ?> localMutation = this.converter.convertFromInput(
			map()
				.e(AssociatedDataMutationDescriptor.NAME.name(), ASSOCIATED_DATA_LABELS)
				.build()
		);

		assertEquals(
			new RemoveAssociatedDataMutation(ASSOCIATED_DATA_LABELS),
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
		final RemoveAssociatedDataMutation inputMutation = new RemoveAssociatedDataMutation(ASSOCIATED_DATA_LABELS, Locale.ENGLISH);

		//noinspection unchecked
		final Map<String, Object> serializedMutation = (Map<String, Object>) this.converter.convertToOutput(inputMutation);
		assertThat(serializedMutation)
			.usingRecursiveComparison()
			.isEqualTo(
				map()
					.e(AssociatedDataMutationDescriptor.NAME.name(), ASSOCIATED_DATA_LABELS)
					.e(AssociatedDataMutationDescriptor.LOCALE.name(), Locale.ENGLISH.toLanguageTag())
					.build()
			);
	}
}
