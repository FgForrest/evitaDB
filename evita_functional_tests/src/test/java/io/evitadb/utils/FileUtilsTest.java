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

import io.evitadb.exception.UnexpectedIOException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

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

	@Test
	void shouldDeleteFile() throws IOException {
		// Create a file in the temp directory
		Path testFile = directoryTest.resolve("testFile.txt");
		Files.write(testFile, "test content".getBytes(), StandardOpenOption.CREATE);

		// Assert the file exists
		assertTrue(testFile.toFile().exists());

		// Call the method under test
		FileUtils.deleteFile(testFile);

		// Assert the file was deleted
		assertFalse(testFile.toFile().exists());
	}

	@Test
	void shouldThrowExceptionWhenDeletingNonExistentFile() {
		// Create a path for a non-existent file
		Path nonExistentFile = directoryTest.resolve("nonExistentFile.txt");

		// Assert that the file does not exist
		assertFalse(nonExistentFile.toFile().exists());

		// Attempt to delete the non-existent file and expect an exception
		assertThrows(UnexpectedIOException.class, () -> FileUtils.deleteFile(nonExistentFile));
	}

	/**
	 * Test to verify behavior when there is extension in the given file name.
	 */
	@Test
	void shouldGetFileNameWithoutExtensionWhenInputIsValid() {
		String fileNameWithExtension = "testFile.txt";
		String expectedOutput = "testFile";

		String result = FileUtils.getFileNameWithoutExtension(fileNameWithExtension);

		assertEquals(expectedOutput, result);
	}

	/**
	 * Test to verify behavior when there is no extension in the given file name.
	 */
	@Test
	void shouldGetFileNameWhenThereIsNoExtension() {
		String fileNameWithoutExtension = "testFile";
		String expectedOutput = "testFile";

		String result = FileUtils.getFileNameWithoutExtension(fileNameWithoutExtension);

		assertEquals(expectedOutput, result);
	}

	/**
	 * Test to verify behavior when there is multiple periods in the given file name.
	 */
	@Test
	void shouldGetFileNameUntilLastPeriodWhenThereAreMultiplePeriods() {
		String fileNameWithMultiplePeriods = "test.File.txt";
		String expectedOutput = "test.File";

		String result = FileUtils.getFileNameWithoutExtension(fileNameWithMultiplePeriods);

		assertEquals(expectedOutput, result);
	}
	@Test
	void shouldGetLastModifiedTimeWhenFileExists() {
		Path testFile = directoryTest.resolve("testFile.txt");
		try {
			Files.write(testFile, "test content".getBytes(), StandardOpenOption.CREATE);
			Optional<OffsetDateTime> lastModifiedTimeOpt = FileUtils.getFileLastModifiedTime(testFile);
			assertTrue(lastModifiedTimeOpt.isPresent());
			OffsetDateTime lastModifiedTime = lastModifiedTimeOpt.get();
			OffsetDateTime now = OffsetDateTime.now();
			assertTrue(now.isAfter(lastModifiedTime) || now.isEqual(lastModifiedTime));
		} catch (IOException e) {
			fail("Unexpected error occurred: " + e.getMessage());
		}
	}

	@Test
	void shouldReturnEmptyWhenFileNotExists() {
		Path nonExistentFile = directoryTest.resolve("nonExistentFile.txt");
		Optional<OffsetDateTime> lastModifiedTimeOpt = FileUtils.getFileLastModifiedTime(nonExistentFile);
		assertTrue(lastModifiedTimeOpt.isEmpty());
	}
}
