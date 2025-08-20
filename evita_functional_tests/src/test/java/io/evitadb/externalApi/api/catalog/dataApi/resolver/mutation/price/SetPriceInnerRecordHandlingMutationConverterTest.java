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

package io.evitadb.externalApi.api.catalog.dataApi.resolver.mutation.price;

import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.data.mutation.LocalMutation;
import io.evitadb.api.requestResponse.data.mutation.price.SetPriceInnerRecordHandlingMutation;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.price.SetPriceInnerRecordHandlingMutationDescriptor;
import io.evitadb.externalApi.api.catalog.mutation.TestMutationResolvingExceptionFactory;
import io.evitadb.externalApi.api.catalog.resolver.mutation.PassThroughMutationObjectParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.evitadb.utils.MapBuilder.map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link SetPriceInnerRecordHandlingMutationConverter}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
class SetPriceInnerRecordHandlingMutationConverterTest {

	private SetPriceInnerRecordHandlingMutationConverter converter;

	@BeforeEach
	void init() {
		this.converter =  new SetPriceInnerRecordHandlingMutationConverter(new PassThroughMutationObjectParser(), new TestMutationResolvingExceptionFactory());
	}

	@Test
	void shouldResolveInputToLocalMutation() {
		final SetPriceInnerRecordHandlingMutation expectedMutation = new SetPriceInnerRecordHandlingMutation(PriceInnerRecordHandling.SUM);

		final LocalMutation<?, ?> localMutation = this.converter.convertFromInput(
			map()
				.e(SetPriceInnerRecordHandlingMutationDescriptor.PRICE_INNER_RECORD_HANDLING.name(), PriceInnerRecordHandling.SUM)
				.build()
		);
		assertEquals(expectedMutation, localMutation);

		final LocalMutation<?, ?> localMutation2 = this.converter.convertFromInput(
			map()
				.e(SetPriceInnerRecordHandlingMutationDescriptor.PRICE_INNER_RECORD_HANDLING.name(), "SUM")
				.build()
		);
		assertEquals(expectedMutation, localMutation2);
	}

	@Test
	void shouldNotResolveInputWhenMissingRequiredData() {
		assertThrows(EvitaInvalidUsageException.class, () -> this.converter.convertFromInput(Map.of()));
		assertThrows(EvitaInvalidUsageException.class, () -> this.converter.convertFromInput((Object) null));
	}

	@Test
	void shouldSerializeLocalMutationToOutput() {
		final SetPriceInnerRecordHandlingMutation inputMutation = new SetPriceInnerRecordHandlingMutation(PriceInnerRecordHandling.SUM);

		assertEquals(
			map()
				.e(SetPriceInnerRecordHandlingMutationDescriptor.PRICE_INNER_RECORD_HANDLING.name(), PriceInnerRecordHandling.SUM.name())
				.build(),
			this.converter.convertToOutput(inputMutation)
		);
	}
}
