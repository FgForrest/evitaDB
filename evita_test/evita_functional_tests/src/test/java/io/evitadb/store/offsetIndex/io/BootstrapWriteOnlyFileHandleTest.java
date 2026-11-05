/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

package io.evitadb.store.offsetIndex.io;

import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.store.checksum.Crc32CChecksumFactory;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.utils.UUIDUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static io.evitadb.test.TestTags.STORAGE;
import static io.evitadb.test.TestTags.MANAGEMENT;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies interrupted closure of a real bootstrap write handle.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(STORAGE)
@Tag(MANAGEMENT)
@DisplayName("Bootstrap write handle closure")
class BootstrapWriteOnlyFileHandleTest implements EvitaTestSupport {

	/** Verifies that an interrupted lock acquisition preserves its cause and permits a later close. */
	@Test
	@DisplayName("Should preserve interruption when closing and allow a later close")
	void shouldPreserveInterruptionWhenClosing() throws IOException {
		final Path file = getPathInTargetDirectory("BootstrapWriteOnlyFileHandle")
			.resolve(UUIDUtil.randomUUID() + ".boot");
		try (final BootstrapWriteOnlyFileHandle handle = new BootstrapWriteOnlyFileHandle(
			file, StorageOptions.DEFAULT_OUTPUT_BUFFER_SIZE, false, 5, Crc32CChecksumFactory.INSTANCE
		)) {
			handle.checkAndExecuteAndSync("write data", () -> {}, output -> output.writeString("data"));
			try {
				Thread.currentThread().interrupt();
				final GenericEvitaInternalError failure = assertThrows(GenericEvitaInternalError.class, handle::close);
				assertInstanceOf(InterruptedException.class, failure.getCause());
				assertEquals("Failed to close file due to interrupt!", failure.getMessage());
				assertTrue(Thread.currentThread().isInterrupted());
			} finally {
				Thread.interrupted();
			}
			assertDoesNotThrow(handle::close);
		}
		Files.delete(file);
		assertFalse(Files.exists(file));
	}
}
