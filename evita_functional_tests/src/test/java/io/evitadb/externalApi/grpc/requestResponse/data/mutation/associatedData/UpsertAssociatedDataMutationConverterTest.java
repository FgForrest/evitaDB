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

package io.evitadb.externalApi.grpc.requestResponse.data.mutation.associatedData;

import io.evitadb.api.requestResponse.data.mutation.associatedData.UpsertAssociatedDataMutation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UpsertAssociatedDataMutationConverterTest {

	private static UpsertAssociatedDataMutationConverter converter;

	@BeforeAll
	static void setup() {
		converter = new UpsertAssociatedDataMutationConverter();
	}

	@Test
	void shouldConvertMutation() {
		final UpsertAssociatedDataMutation mutation1 = new UpsertAssociatedDataMutation("labels", Locale.US, "someLabel");
		assertEquals(mutation1, converter.convert(converter.convert(mutation1)));

		final UpsertAssociatedDataMutation mutation2 = new UpsertAssociatedDataMutation("labels", "someLabel");
		assertEquals(mutation2, converter.convert(converter.convert(mutation2)));
	}

}