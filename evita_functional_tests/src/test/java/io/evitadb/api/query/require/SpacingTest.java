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

import io.evitadb.dataType.expression.Expression;
import org.junit.jupiter.api.Test;

import static io.evitadb.api.query.QueryConstraints.gap;
import static io.evitadb.api.query.QueryConstraints.spacing;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This tests verifies basic properties of {@link Page} query.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class SpacingTest {

	@Test
	void shouldCreateViaFactoryClassWorkAsExpected() {
		assertNotNull(spacing(gap(1, "true")));
		assertNull(spacing(gap(1, (String) null)));
		assertNull(spacing(gap(1, (Expression) null)));
		assertNotNull(spacing(gap(1, (Expression) null), gap(1, "true")));
	}

	@Test
	void shouldRecognizeApplicability() {
		assertFalse(new Spacing().isApplicable());
		assertTrue(spacing(gap(1, "true")).isApplicable());
		assertTrue(spacing(gap(1, "true"), gap(1, "false")).isApplicable());
	}

	@Test
	void shouldToStringReturnExpectedFormat() {
		assertEquals("spacing(gap(1,'true'))", spacing(gap(1, "true")).toString());
	}

	@Test
	void shouldConformToEqualsAndHashContract() {
		assertNotSame(spacing(gap(1, "true")), spacing(gap(1, "true")));
		assertEquals(spacing(gap(1, "true")), spacing(gap(1, "true")));
		assertNotEquals(spacing(gap(1, "true")), spacing(gap(2, "true")));
		assertNotEquals(spacing(gap(1, "true")), spacing(gap(1, "false")));
		assertEquals(spacing(gap(1, "true")).hashCode(), spacing(gap(1, "true")).hashCode());
		assertNotEquals(spacing(gap(1, "true")).hashCode(), spacing(gap(2, "true")).hashCode());
		assertNotEquals(spacing(gap(1, "true")).hashCode(), spacing(gap(1, "false")).hashCode());
	}

}
