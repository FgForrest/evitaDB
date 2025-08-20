/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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
import java.io.OutputStream;
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
		this.directoryTest = this.tmpFolder.resolve("directoryTest");
		this.directoryTest.toFile().mkdirs();
	}

	@AfterEach
	void tearDown() throws IOException {
		FileUtils.deleteDirectory(this.directoryTest);
	}

	@Test
	void shouldListDirectories() throws IOException {
		org.apache.commons.io.FileUtils.deleteDirectory(this.directoryTest.toFile());

		assertTrue(this.directoryTest.toFile().mkdirs());
		assertTrue(this.directoryTest.resolve("A").toFile().mkdirs());
		assertTrue(this.directoryTest.resolve("B").toFile().mkdirs());
		assertTrue(this.directoryTest.resolve("C").toFile().mkdirs());

		final Path[] paths = FileUtils.listDirectories(this.directoryTest);
		assertEquals(3, paths.length);

		assertArrayEquals(
			new String[]{"A", "B", "C"},
			Arrays.stream(paths).map(Path::toFile).map(File::getName).sorted().toArray(String[]::new)
		);

		org.apache.commons.io.FileUtils.deleteDirectory(this.directoryTest.toFile());
	}

	@Test
	void shouldCalculateDirectorySizeIncludingSubdirectories() throws IOException {
		// Create some files in the temp directory
		Path file1 = this.directoryTest.resolve("file1.txt");
		Files.write(file1, "Hello".getBytes(), StandardOpenOption.CREATE);

		Path file2 = this.directoryTest.resolve("file2.txt");
		Files.write(file2, "World".getBytes(), StandardOpenOption.CREATE);

		// Create a subdirectory and a file in it
		Path subDir = this.directoryTest.resolve("subdir");
		Files.createDirectory(subDir);
		Path file3 = subDir.resolve("file3.txt");
		Files.write(file3, "Hello, Subdirectory".getBytes(), StandardOpenOption.CREATE);

		// Calculate the expected size
		long expectedSize = Files.size(file1) + Files.size(file2) + Files.size(file3);

		// Call the method under test
		long actualSize = FileUtils.getDirectorySize(this.directoryTest);

		// Assert that the actual size matches the expected size
		assertEquals(expectedSize, actualSize);
	}

	@Test
	void shouldConvertToSafeFileName() {
		assertEquals("Abc-d784_2.z1p", FileUtils.convertToSupportedName("Abc   +-_ d784 2 .z1p"));
	}

	@Test
	void shouldDeleteFileIfExists() throws IOException {
		// Create a file in the temp directory
		Path testFile = this.directoryTest.resolve("testFile.txt");
		Files.write(testFile, "test content".getBytes(), StandardOpenOption.CREATE);

		// Assert the file exists
		assertTrue(testFile.toFile().exists());

		// Call the method under test
		FileUtils.deleteFileIfExists(testFile);

		// Assert the file was deleted
		assertFalse(testFile.toFile().exists());
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
	void shouldRenameFolderSuccessfully() throws IOException {
		// Set up source and target directories
		Path sourceDir = this.directoryTest.resolve("sourceDir");
		Path targetDir = this.directoryTest.resolve("targetDir");

		Files.createDirectory(sourceDir);
		Files.write(sourceDir.resolve("file1.txt"), "File 1 content".getBytes());
		Files.createDirectory(sourceDir.resolve("subDir"));
		Files.write(sourceDir.resolve("subDir").resolve("file2.txt"), "File 2 content".getBytes());

		// Verify source directory exists and target does not
		assertTrue(Files.exists(sourceDir));
		assertFalse(Files.exists(targetDir));

		// Perform the folder rename operation
		FileUtils.renameFolder(sourceDir, targetDir);

		// Verify source directory is deleted and target exists with same structure
		assertFalse(Files.exists(sourceDir));
		assertTrue(Files.exists(targetDir));
		assertTrue(Files.exists(targetDir.resolve("file1.txt")));
		assertTrue(Files.exists(targetDir.resolve("subDir").resolve("file2.txt")));
	}

	@Test
	void shouldThrowExceptionWhenSourceDoesNotExist() {
		// Set up source and target directories
		Path nonExistentSource = this.directoryTest.resolve("nonExistentSource");
		Path targetDir = this.directoryTest.resolve("targetDir");

		// Verify source does not exist
		assertFalse(Files.exists(nonExistentSource));

		// Perform the folder rename operation and expect exception
		assertThrows(UnexpectedIOException.class, () -> FileUtils.renameFolder(nonExistentSource, targetDir));

		// Verify target directory does not exist
		assertFalse(Files.exists(targetDir));
	}

	@Test
	void shouldRenameFolderWithNestedStructureSuccessfully() throws IOException {
		// Set up source and target directories with nested structure
		Path sourceDir = this.directoryTest.resolve("nestedSource");
		Path targetDir = this.directoryTest.resolve("nestedTarget");

		Files.createDirectory(sourceDir);
		Files.createDirectory(sourceDir.resolve("nested1"));
		Files.createDirectory(sourceDir.resolve("nested1").resolve("nested2"));
		Files.write(
			sourceDir.resolve("nested1").resolve("nested2").resolve("file.txt"), "Nested file content".getBytes());

		// Verify source directory exists
		assertTrue(Files.exists(sourceDir));

		// Perform the folder rename operation
		FileUtils.renameFolder(sourceDir, targetDir);

		// Verify source directory is deleted and target exists with same structure
		assertFalse(Files.exists(sourceDir));
		assertTrue(Files.exists(targetDir));
		assertTrue(Files.exists(targetDir.resolve("nested1").resolve("nested2").resolve("file.txt")));
	}

	@Test
	void shouldRenameFileSuccessfullyWhenTargetDoesNotExist() throws IOException {
		// Arrange
		Path sourceFile = this.directoryTest.resolve("sourceFile.txt");
		Path targetFile = this.directoryTest.resolve("targetFile.txt");
		Files.write(sourceFile, "Source content".getBytes(), StandardOpenOption.CREATE);

		// Act
		FileUtils.renameOrReplaceFile(sourceFile, targetFile);

		// Assert
		assertFalse(Files.exists(sourceFile));
		assertTrue(Files.exists(targetFile));
		assertEquals("Source content", Files.readString(targetFile));
	}

	@Test
	void shouldReplaceExistingTargetFile() throws IOException {
		// Arrange
		Path sourceFile = this.directoryTest.resolve("sourceFile.txt");
		Path targetFile = this.directoryTest.resolve("targetFile.txt");
		Files.write(sourceFile, "Source content".getBytes(), StandardOpenOption.CREATE);
		Files.write(targetFile, "Target content".getBytes(), StandardOpenOption.CREATE);

		// Act
		FileUtils.renameOrReplaceFile(sourceFile, targetFile);

		// Assert
		assertFalse(Files.exists(sourceFile));
		assertTrue(Files.exists(targetFile));
		assertEquals("Source content", Files.readString(targetFile));
	}

	@Test
	void shouldHandleSourceFileNotExisting() {
		// Arrange
		Path sourceFile = this.directoryTest.resolve("nonExistentSourceFile.txt");
		Path targetFile = this.directoryTest.resolve("targetFile.txt");

		// Act & Assert
		assertThrows(UnexpectedIOException.class, () -> FileUtils.renameOrReplaceFile(sourceFile, targetFile));
	}

	@Test
	void shouldGetLastModifiedTimeWhenFileExists() {
		Path testFile = this.directoryTest.resolve("testFile.txt");
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
		Path nonExistentFile = this.directoryTest.resolve("nonExistentFile.txt");
		Optional<OffsetDateTime> lastModifiedTimeOpt = FileUtils.getFileLastModifiedTime(nonExistentFile);
		assertTrue(lastModifiedTimeOpt.isEmpty());
	}

	@Test
	void shouldCompressDirectorySuccessfully() throws IOException {
		// Arrange
		Path subDir = this.directoryTest.resolve("subDir");
		Files.createDirectory(subDir);
		Path file1 = this.directoryTest.resolve("file1.txt");
		Path file2 = subDir.resolve("file2.txt");
		Files.write(file1, "File1 Content".getBytes(), StandardOpenOption.CREATE);
		Files.write(file2, "File2 Content".getBytes(), StandardOpenOption.CREATE);

		Path zipFile = this.tmpFolder.resolve("output.zip");

		// Act
		try (OutputStream outputStream = Files.newOutputStream(zipFile)) {
			FileUtils.compressDirectory(this.directoryTest, outputStream);
		}

		// Assert
		assertTrue(Files.exists(zipFile));
		try (java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(Files.newInputStream(zipFile))) {
			// Collect all entries from the ZIP file
			java.util.List<String> entryNames = new java.util.ArrayList<>();
			java.util.zip.ZipEntry entry;
			while ((entry = zis.getNextEntry()) != null) {
				entryNames.add(entry.getName());
			}

			// Verify that all expected entries are present, regardless of their order
			assertEquals(3, entryNames.size(), "Expected 3 entries in the ZIP file");
			assertTrue(entryNames.contains("file1.txt"), "ZIP should contain file1.txt");
			assertTrue(entryNames.contains("subDir/"), "ZIP should contain subDir/");
			assertTrue(entryNames.contains("subDir" + File.separatorChar + "file2.txt"), "ZIP should contain subDir/file2.txt");
		}
	}

	@Test
	void shouldHandleCompressionOfEmptyDirectory() throws IOException {
		// Arrange
		Path zipFile = this.tmpFolder.resolve("output_empty.zip");

		// Act
		try (OutputStream outputStream = Files.newOutputStream(zipFile)) {
			FileUtils.compressDirectory(this.directoryTest, outputStream);
		}

		// Assert
		assertTrue(Files.exists(zipFile));
		try (java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(Files.newInputStream(zipFile))) {
			assertNull(zis.getNextEntry());
		}
	}

	@Test
	void shouldThrowExceptionWhenDirectoryDoesNotExist() {
		// Arrange
		Path nonExistentDirectory = this.tmpFolder.resolve("nonExistentDir");
		Path zipFile = this.tmpFolder.resolve("output_error.zip");

		// Act & Assert
		try (OutputStream outputStream = Files.newOutputStream(zipFile)) {
			assertThrows(
				UnexpectedIOException.class, () -> FileUtils.compressDirectory(nonExistentDirectory, outputStream));
		} catch (IOException e) {
			fail("Unexpected error occurred: " + e.getMessage());
		}
	}
}
