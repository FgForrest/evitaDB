/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025-2026
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.DATA_TYPE;

/**
 * This test verifies contract of  the {@link VersionUtils} class.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025-2026
 */
@DisplayName("VersionUtils contract tests")
@Tag(ENGINE)
@Tag(DATA_TYPE)
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

	@Test
	void shouldReturnExpectedIsAtLeastResults() {
		// client is newer than the compared major.minor
		assertTrue(VersionUtils.isAtLeast(SemVer.fromString("2026.2.0"), 2025, 4));
		// client is on the same major, newer minor
		assertTrue(VersionUtils.isAtLeast(SemVer.fromString("2025.5.0"), 2025, 4));
		// client is exactly on the compared major.minor
		assertTrue(VersionUtils.isAtLeast(SemVer.fromString("2025.4.0"), 2025, 4));
		// client is on the same major, older minor
		assertFalse(VersionUtils.isAtLeast(SemVer.fromString("2025.3.0"), 2025, 4));
		// client is on an older major
		assertFalse(VersionUtils.isAtLeast(SemVer.fromString("2024.12.0"), 2025, 4));
		// client declared no version at all - treated as older than any major.minor
		assertFalse(VersionUtils.isAtLeast(null, 2025, 4));
	}

	@Test
	void shouldReturnNonNullCommitHash() {
		// Whether or not the evita_server / java_driver jar happens to be on the test classpath
		// (depends on local maven cache state), the contract is the same: never null, never blank.
		// When the manifest cannot be resolved the fallback is the literal "?".
		final String commitHash = VersionUtils.readCommitHash();
		assertNotNull(commitHash);
		assertFalse(commitHash.isBlank(), "commit hash must not be blank");
	}
}
