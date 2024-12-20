/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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

package io.evitadb.api.query.order;

import io.evitadb.exception.EvitaInvalidUsageException;
import org.junit.jupiter.api.Test;

import static io.evitadb.api.query.QueryConstraints.limit;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link SegmentLimit} constraint.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
class SegmentLimitTest {

	@Test
	void shouldCreateViaFactoryClassWorkAsExpected() {
		final SegmentLimit segmentLimit = limit(5);

		assertNull(limit(null));
		assertEquals(segmentLimit.getLimit(), 5);
	}

	@Test
	void shouldFailToCreateLimitWithNegativeNumber() {
		assertThrows(EvitaInvalidUsageException.class, () -> limit(-1));
	}

	@Test
	void shouldRecognizeApplicability() {
		assertTrue(new SegmentLimit(5).isApplicable());
	}

	@Test
	void shouldToStringReturnExpectedFormat() {
		final SegmentLimit segmentLimit = limit(5);
		assertEquals("limit(5)", segmentLimit.toString());

		final SegmentLimit segmentLimit2 = limit(10);
		assertEquals("limit(10)", segmentLimit2.toString());
	}

	@Test
	void shouldConformToEqualsAndHashContract() {
		assertNotSame(limit(5), limit(5));
		assertEquals(limit(5), limit(5));
		assertNotEquals(limit(10), limit(5));
		assertEquals(limit(5).hashCode(), limit(5).hashCode());
		assertNotEquals(limit(10).hashCode(), limit(5).hashCode());
	}

}