/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.utils;


import io.evitadb.utils.VersionUtils.SemVer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * This test verifies contract of  the {@link VersionUtils} class.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public class VersionUtilsTest {

	@Test
	void shouldReturnExpectedSemVerComparisonResults() {
		assertEquals(0, SemVer.fromString("1.0.0").compareTo(SemVer.fromString("1.0.0")));
		assertEquals(0, SemVer.fromString("1.0.1").compareTo(SemVer.fromString("1.0.0")));
		assertEquals(1, SemVer.fromString("1.1.0").compareTo(SemVer.fromString("1.0.0")));
		assertEquals(-1, SemVer.fromString("1.1.0").compareTo(SemVer.fromString("1.2.0")));
		assertEquals(1, SemVer.fromString("2025.1.0").compareTo(SemVer.fromString("2024.12.0")));
		assertEquals(-1, SemVer.fromString("2024.12.0").compareTo(SemVer.fromString("2025.1.0")));
	}
}
