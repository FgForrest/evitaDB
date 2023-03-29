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

package io.evitadb.api.query.require;

import org.junit.jupiter.api.Test;

import static io.evitadb.api.query.QueryConstraints.statistics;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This tests verifies basic properties of {@link HierarchyStatistics} query.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class HierarchyStatisticsTest {

	@Test
	void shouldCreateViaFactoryClassWorkAsExpected() {
		assertNotNull(statistics());
		assertNotNull(statistics(StatisticsBase.COMPLETE_FILTER));
		assertNull(statistics(null));
	}

	@Test
	void shouldRecognizeApplicability() {
		assertFalse(new HierarchyStatistics(null).isApplicable());
		assertTrue(new HierarchyStatistics(StatisticsBase.COMPLETE_FILTER).isApplicable());
	}

	@Test
	void shouldToStringReturnExpectedFormat() {
		final HierarchyStatistics hierarchyStatistics = statistics();
		assertEquals("statistics()", hierarchyStatistics.toString());
	}

	@Test
	void shouldConformToEqualsAndHashContract() {
		assertNotSame(statistics(), statistics());
		assertEquals(statistics(), statistics());
		assertNotEquals(statistics(StatisticsBase.COMPLETE_FILTER), statistics(StatisticsBase.WITHOUT_USER_FILTER));
		assertEquals(statistics().hashCode(), statistics().hashCode());
		assertNotEquals(statistics(StatisticsBase.COMPLETE_FILTER).hashCode(), statistics(StatisticsBase.WITHOUT_USER_FILTER).hashCode());
	}

}
