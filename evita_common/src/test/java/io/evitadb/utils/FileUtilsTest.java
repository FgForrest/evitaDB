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

package io.evitadb.utils;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test verifies behaviour of {@link FileUtils}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
class FileUtilsTest {
	private final Path tmpFolder = Path.of(System.getProperty("java.io.tmpdir") + File.separator);

	@Test
	void shouldListDirectories() throws IOException {
		final Path directoryTest = tmpFolder.resolve("directoryTest");
		org.apache.commons.io.FileUtils.deleteDirectory(directoryTest.toFile());

		assertTrue(directoryTest.toFile().mkdirs());
		assertTrue(directoryTest.resolve("A").toFile().mkdirs());
		assertTrue(directoryTest.resolve("B").toFile().mkdirs());
		assertTrue(directoryTest.resolve("C").toFile().mkdirs());

		final Path[] paths = FileUtils.listDirectories(directoryTest);
		assertEquals(3, paths.length);

		assertArrayEquals(
			new String[] {"A", "B", "C"},
			Arrays.stream(paths).map(Path::toFile).map(File::getName).sorted().toArray(String[]::new)
		);

		org.apache.commons.io.FileUtils.deleteDirectory(directoryTest.toFile());
	}

}