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

package io.evitadb.utils;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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
	private Path directoryTest;

	@BeforeEach
	void setUp() {
		directoryTest = tmpFolder.resolve("directoryTest");
		directoryTest.toFile().mkdirs();
	}

	@AfterEach
	void tearDown() throws IOException {
		FileUtils.deleteDirectory(directoryTest);
	}

	@Test
	void shouldListDirectories() throws IOException {
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

	@Test
	void shouldCalculateDirectorySizeIncludingSubdirectories() throws IOException {
		// Create some files in the temp directory
		Path file1 = directoryTest.resolve("file1.txt");
		Files.write(file1, "Hello".getBytes(), StandardOpenOption.CREATE);

		Path file2 = directoryTest.resolve("file2.txt");
		Files.write(file2, "World".getBytes(), StandardOpenOption.CREATE);

		// Create a subdirectory and a file in it
		Path subDir = directoryTest.resolve("subdir");
		Files.createDirectory(subDir);
		Path file3 = subDir.resolve("file3.txt");
		Files.write(file3, "Hello, Subdirectory".getBytes(), StandardOpenOption.CREATE);

		// Calculate the expected size
		long expectedSize = Files.size(file1) + Files.size(file2) + Files.size(file3);

		// Call the method under test
		long actualSize = FileUtils.getDirectorySize(directoryTest);

		// Assert that the actual size matches the expected size
		assertEquals(expectedSize, actualSize);
	}

	@Test
	void shouldConvertToSafeFileName() {
		assertEquals("Abc-d784_2.z1p", FileUtils.convertToSupportedName("Abc   +-_ d784 2 .z1p"));
	}
}
