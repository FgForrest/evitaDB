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

package io.evitadb.externalApi.rest.api.resolver.serializer;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for {@link DataTypeSerializer}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
class DataTypeSerializerTest {

	@Test
	void shouldSerializePrimitiveType() {
		assertEquals("String", DataTypeSerializer.serialize(String.class));
		assertEquals("BigDecimal", DataTypeSerializer.serialize(BigDecimal.class));
	}

	@Test
	void shouldSerializeArrayType() {
		assertEquals("StringArray", DataTypeSerializer.serialize(String[].class));
		assertEquals("BigDecimalArray", DataTypeSerializer.serialize(BigDecimal[].class));
	}
}