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

import static io.evitadb.api.query.QueryConstraints.page;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * This tests verifies basic properties of {@link Page} query.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class PageTest {

	@Test
	void shouldRecognizeApplicability() {
		assertTrue(page(null, null).isApplicable());
		assertTrue(page(1, 20).isApplicable());
		assertTrue(page(null, 20).isApplicable());
		assertTrue(page(1, null).isApplicable());
	}

	@Test
	void shouldConformToEqualsAndHashContract() {
		assertNotSame(page(1, 1), page(1, 1));
		assertEquals(page(1, 1), page(1, 1));
		assertNotEquals(page(1, 1), page(2, 1));
		assertEquals(page(1, 1).hashCode(), page(1, 1).hashCode());
		assertNotEquals(page(1, 1).hashCode(), page(1, 2).hashCode());
	}

	@Test
	void shouldReplaceNullValuesWithDefaults() {
		assertEquals(page(null, null), page(1, 20));
		assertEquals(page(null, 2), page(1, 2));
		assertEquals(page(1, null), page(1, 20));
	}

}
