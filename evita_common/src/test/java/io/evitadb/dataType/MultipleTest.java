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

package io.evitadb.dataType;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * This test verifies contract of {@link Multiple} class.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class MultipleTest {

	@Test
	void shouldConvertToString() {
		final Multiple multiple = new Multiple(
			1,
			new BigDecimal("1.2"),
			OffsetDateTime.of(2020, 1, 1, 0, 0, 0, 0, ZoneId.systemDefault().getRules().getOffset(LocalDateTime.of(2022, 12, 1, 0, 0)))
		);
		assertEquals("{1,1.2,2020-01-01T00:00:00+01:00}", multiple.toString());
	}

	@Test
	void shouldCompareAndEqualsWorkForSimpleValue() {
		assertEquals(-1, new Multiple(1, 1).compareTo(new Multiple(2, 2)));
		assertEquals(-1, new Multiple(1, 1).compareTo(new Multiple(1, 2)));
		assertEquals(1, new Multiple(2, 2).compareTo(new Multiple(1, 1)));
		assertEquals(1, new Multiple(1, 2).compareTo(new Multiple(1, 1)));
		assertEquals(0, new Multiple(1, 1).compareTo(new Multiple(1, 1)));
		assertEquals(new Multiple(1, 1), new Multiple(1, 1));
		assertEquals(new Multiple(1, 1).hashCode(), new Multiple(1, 1).hashCode());
	}

	@Test
	void shouldCompareAndEqualsWorkForValuesWithDifferentLengths() {
		assertEquals(-1, new Multiple(1, 1).compareTo(new Multiple(1, 1, 1)));
		assertEquals(1, new Multiple(1, 1, 1).compareTo(new Multiple(1, 1)));
	}

	@Test
	void shouldCompareAndEqualsWorkForValuesWithDifferentTypes() {
		assertEquals(-1, new Multiple(1, "a").compareTo(new Multiple(1, "b")));
		assertEquals(1, new Multiple(1, "b").compareTo(new Multiple(1, "a")));
		assertEquals(0, new Multiple(1, "a").compareTo(new Multiple(1, "a")));
	}

}
