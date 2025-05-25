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

import io.evitadb.exception.FolderAlreadyUsedException;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * This test verifies behavior of {@link FolderLock} class.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
class FolderLockTest implements EvitaTestSupport {
	private Path tempFolder;

	@BeforeEach
	void setUp() {
		this.tempFolder = getTestDirectory().resolve("folderLockTest");
		FileUtils.deleteDirectory(this.tempFolder);
		assertTrue(this.tempFolder.toFile().mkdirs());
	}

	@AfterEach
	void tearDown() {
		FileUtils.deleteDirectory(this.tempFolder);
	}

	@Test
	void shouldRefuseToLockSameDirectoryTwice() throws IOException {
		try (FolderLock firstLock = new FolderLock(this.tempFolder)) {
			assertNotNull(firstLock, "FolderLock should not be null.");
			assertThrows(
				FolderAlreadyUsedException.class,
				() -> {
					try (final FolderLock secondLock = new FolderLock(this.tempFolder)) {
						fail("Should not be able to lock the same directory twice.");
					}
				},
				"Should refuse to lock the same directory twice."
			);
		}
	}

	@Test
	void shouldFailToGetLock() {
		assertThrows(
			FolderAlreadyUsedException.class,
			() -> {
				try (FolderLock firstLock = new FolderLock(this.tempFolder)) {
					assertNotNull(firstLock, "FolderLock should not be null.");
					try (FolderLock secondLock = new FolderLock(this.tempFolder)) {
						fail("Should not be able to lock the same directory twice.");
					}
				}
			},
			"Should refuse to lock the same directory twice."
		);
	}

}