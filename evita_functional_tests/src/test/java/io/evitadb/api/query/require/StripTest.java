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

package io.evitadb.api.query.require;

import org.junit.jupiter.api.Test;

import static io.evitadb.api.query.QueryConstraints.strip;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * This tests verifies basic properties of {@link Strip} query.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class StripTest {

	@Test
	void shouldRecognizeApplicability() {
		assertTrue(strip(null, null).isApplicable());
		assertTrue(strip(1, null).isApplicable());
		assertTrue(strip(null, 20).isApplicable());
		assertTrue(strip(1, 20).isApplicable());
	}

	@Test
	void shouldConformToEqualsAndHashContract() {
		assertNotSame(strip(1, 1), strip(1, 1));
		assertEquals(strip(1, 1), strip(1, 1));
		assertNotEquals(strip(1, 1), strip(2, 1));
		assertEquals(strip(1, 1).hashCode(), strip(1, 1).hashCode());
		assertNotEquals(strip(1, 1).hashCode(), strip(1, 2).hashCode());
	}

	@Test
	void shouldReplaceNullValuesWithDefaults() {
		assertEquals(strip(null, null), strip(0, 20));
		assertEquals(strip(null, 2), strip(0, 2));
		assertEquals(strip(1, null), strip(1, 20));
	}

}
