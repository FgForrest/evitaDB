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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
@DisplayName("FileUtils contract tests")
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

	@Nested
	@DisplayName("Directory operations tests")
	class DirectoryOperationsTests {

		@Test
		@DisplayName("Should list directories")
		void shouldListDirectories() throws IOException {
			org.apache.commons.io.FileUtils.deleteDirectory(FileUtilsTest.this.directoryTest.toFile());

			assertTrue(FileUtilsTest.this.directoryTest.toFile().mkdirs());
			assertTrue(FileUtilsTest.this.directoryTest.resolve("A").toFile().mkdirs());
			assertTrue(FileUtilsTest.this.directoryTest.resolve("B").toFile().mkdirs());
			assertTrue(FileUtilsTest.this.directoryTest.resolve("C").toFile().mkdirs());

			final Path[] paths = FileUtils.listDirectories(FileUtilsTest.this.directoryTest);
			assertEquals(3, paths.length);

			assertArrayEquals(
				new String[]{"A", "B", "C"},
				Arrays.stream(paths).map(Path::toFile).map(File::getName).sorted().toArray(String[]::new)
			);

			org.apache.commons.io.FileUtils.deleteDirectory(FileUtilsTest.this.directoryTest.toFile());
		}

		@Test
		@DisplayName("Should rename folder successfully")
		void shouldRenameFolderSuccessfully() throws IOException {
			Path sourceDir = FileUtilsTest.this.directoryTest.resolve("sourceDir");
			Path targetDir = FileUtilsTest.this.directoryTest.resolve("targetDir");

			Files.createDirectory(sourceDir);
			Files.write(sourceDir.resolve("file1.txt"), "File 1 content".getBytes());
			Files.createDirectory(sourceDir.resolve("subDir"));
			Files.write(sourceDir.resolve("subDir").resolve("file2.txt"), "File 2 content".getBytes());

			assertTrue(Files.exists(sourceDir));
			assertFalse(Files.exists(targetDir));

			FileUtils.renameFolder(sourceDir, targetDir);

			assertFalse(Files.exists(sourceDir));
			assertTrue(Files.exists(targetDir));
			assertTrue(Files.exists(targetDir.resolve("file1.txt")));
			assertTrue(Files.exists(targetDir.resolve("subDir").resolve("file2.txt")));
		}

		@Test
		@DisplayName("Should throw exception when source does not exist")
		void shouldThrowExceptionWhenSourceDoesNotExist() {
			Path nonExistentSource = FileUtilsTest.this.directoryTest.resolve("nonExistentSource");
			Path targetDir = FileUtilsTest.this.directoryTest.resolve("targetDir");

			assertFalse(Files.exists(nonExistentSource));

			assertThrows(UnexpectedIOException.class, () -> FileUtils.renameFolder(nonExistentSource, targetDir));

			assertFalse(Files.exists(targetDir));
		}

		@Test
		@DisplayName("Should rename folder with nested structure successfully")
		void shouldRenameFolderWithNestedStructureSuccessfully() throws IOException {
			Path sourceDir = FileUtilsTest.this.directoryTest.resolve("nestedSource");
			Path targetDir = FileUtilsTest.this.directoryTest.resolve("nestedTarget");

			Files.createDirectory(sourceDir);
			Files.createDirectory(sourceDir.resolve("nested1"));
			Files.createDirectory(sourceDir.resolve("nested1").resolve("nested2"));
			Files.write(
				sourceDir.resolve("nested1").resolve("nested2").resolve("file.txt"), "Nested file content".getBytes());

			assertTrue(Files.exists(sourceDir));

			FileUtils.renameFolder(sourceDir, targetDir);

			assertFalse(Files.exists(sourceDir));
			assertTrue(Files.exists(targetDir));
			assertTrue(Files.exists(targetDir.resolve("nested1").resolve("nested2").resolve("file.txt")));
		}
	}

	@Nested
	@DisplayName("File size tests")
	class FileSizeTests {

		@Test
		@DisplayName("Should calculate directory size including subdirectories")
		void shouldCalculateDirectorySizeIncludingSubdirectories() throws IOException {
			Path file1 = FileUtilsTest.this.directoryTest.resolve("file1.txt");
			Files.write(file1, "Hello".getBytes(), StandardOpenOption.CREATE);

			Path file2 = FileUtilsTest.this.directoryTest.resolve("file2.txt");
			Files.write(file2, "World".getBytes(), StandardOpenOption.CREATE);

			Path subDir = FileUtilsTest.this.directoryTest.resolve("subdir");
			Files.createDirectory(subDir);
			Path file3 = subDir.resolve("file3.txt");
			Files.write(file3, "Hello, Subdirectory".getBytes(), StandardOpenOption.CREATE);

			long expectedSize = Files.size(file1) + Files.size(file2) + Files.size(file3);

			long actualSize = FileUtils.getDirectorySize(FileUtilsTest.this.directoryTest);

			assertEquals(expectedSize, actualSize);
		}
	}

	@Nested
	@DisplayName("File naming tests")
	class FileNamingTests {

		@Test
		@DisplayName("Should convert to safe file name")
		void shouldConvertToSafeFileName() {
			assertEquals("Abc-d784_2.z1p", FileUtils.convertToSupportedName("Abc   +-_ d784 2 .z1p"));
		}

		@Test
		@DisplayName("Should get file name without extension when input is valid")
		void shouldGetFileNameWithoutExtensionWhenInputIsValid() {
			String fileNameWithExtension = "testFile.txt";
			String expectedOutput = "testFile";

			String result = FileUtils.getFileNameWithoutExtension(fileNameWithExtension);

			assertEquals(expectedOutput, result);
		}

		@Test
		@DisplayName("Should get file name when there is no extension")
		void shouldGetFileNameWhenThereIsNoExtension() {
			String fileNameWithoutExtension = "testFile";
			String expectedOutput = "testFile";

			String result = FileUtils.getFileNameWithoutExtension(fileNameWithoutExtension);

			assertEquals(expectedOutput, result);
		}

		@Test
		@DisplayName("Should get file name until last period when there are multiple periods")
		void shouldGetFileNameUntilLastPeriodWhenThereAreMultiplePeriods() {
			String fileNameWithMultiplePeriods = "test.File.txt";
			String expectedOutput = "test.File";

			String result = FileUtils.getFileNameWithoutExtension(fileNameWithMultiplePeriods);

			assertEquals(expectedOutput, result);
		}
	}

	@Nested
	@DisplayName("Deletion tests")
	class DeletionTests {

		@Test
		@DisplayName("Should delete file if exists")
		void shouldDeleteFileIfExists() throws IOException {
			Path testFile = FileUtilsTest.this.directoryTest.resolve("testFile.txt");
			Files.write(testFile, "test content".getBytes(), StandardOpenOption.CREATE);

			assertTrue(testFile.toFile().exists());

			FileUtils.deleteFileIfExists(testFile);

			assertFalse(testFile.toFile().exists());
		}
	}

	@Nested
	@DisplayName("File rename and replace tests")
	class FileRenameAndReplaceTests {

		@Test
		@DisplayName("Should rename file successfully when target does not exist")
		void shouldRenameFileSuccessfullyWhenTargetDoesNotExist() throws IOException {
			Path sourceFile = FileUtilsTest.this.directoryTest.resolve("sourceFile.txt");
			Path targetFile = FileUtilsTest.this.directoryTest.resolve("targetFile.txt");
			Files.write(sourceFile, "Source content".getBytes(), StandardOpenOption.CREATE);

			FileUtils.renameOrReplaceFile(sourceFile, targetFile);

			assertFalse(Files.exists(sourceFile));
			assertTrue(Files.exists(targetFile));
			assertEquals("Source content", Files.readString(targetFile));
		}

		@Test
		@DisplayName("Should replace existing target file")
		void shouldReplaceExistingTargetFile() throws IOException {
			Path sourceFile = FileUtilsTest.this.directoryTest.resolve("sourceFile.txt");
			Path targetFile = FileUtilsTest.this.directoryTest.resolve("targetFile.txt");
			Files.write(sourceFile, "Source content".getBytes(), StandardOpenOption.CREATE);
			Files.write(targetFile, "Target content".getBytes(), StandardOpenOption.CREATE);

			FileUtils.renameOrReplaceFile(sourceFile, targetFile);

			assertFalse(Files.exists(sourceFile));
			assertTrue(Files.exists(targetFile));
			assertEquals("Source content", Files.readString(targetFile));
		}

		@Test
		@DisplayName("Should handle source file not existing")
		void shouldHandleSourceFileNotExisting() {
			Path sourceFile = FileUtilsTest.this.directoryTest.resolve("nonExistentSourceFile.txt");
			Path targetFile = FileUtilsTest.this.directoryTest.resolve("targetFile.txt");

			assertThrows(UnexpectedIOException.class, () -> FileUtils.renameOrReplaceFile(sourceFile, targetFile));
		}
	}

	@Nested
	@DisplayName("File metadata tests")
	class FileMetadataTests {

		@Test
		@DisplayName("Should get last modified time when file exists")
		void shouldGetLastModifiedTimeWhenFileExists() {
			Path testFile = FileUtilsTest.this.directoryTest.resolve("testFile.txt");
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
		@DisplayName("Should return empty when file not exists")
		void shouldReturnEmptyWhenFileNotExists() {
			Path nonExistentFile = FileUtilsTest.this.directoryTest.resolve("nonExistentFile.txt");
			Optional<OffsetDateTime> lastModifiedTimeOpt = FileUtils.getFileLastModifiedTime(nonExistentFile);
			assertTrue(lastModifiedTimeOpt.isEmpty());
		}
	}

	@Nested
	@DisplayName("Compression tests")
	class CompressionTests {

		@Test
		@DisplayName("Should compress directory successfully")
		void shouldCompressDirectorySuccessfully() throws IOException {
			Path subDir = FileUtilsTest.this.directoryTest.resolve("subDir");
			Files.createDirectory(subDir);
			Path file1 = FileUtilsTest.this.directoryTest.resolve("file1.txt");
			Path file2 = subDir.resolve("file2.txt");
			Files.write(file1, "File1 Content".getBytes(), StandardOpenOption.CREATE);
			Files.write(file2, "File2 Content".getBytes(), StandardOpenOption.CREATE);

			Path zipFile = FileUtilsTest.this.tmpFolder.resolve("output.zip");

			try (OutputStream outputStream = Files.newOutputStream(zipFile)) {
				FileUtils.compressDirectory(FileUtilsTest.this.directoryTest, outputStream);
			}

			assertTrue(Files.exists(zipFile));
			try (java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(Files.newInputStream(zipFile))) {
				java.util.List<String> entryNames = new java.util.ArrayList<>();
				java.util.zip.ZipEntry entry;
				while ((entry = zis.getNextEntry()) != null) {
					entryNames.add(entry.getName());
				}

				assertEquals(3, entryNames.size(), "Expected 3 entries in the ZIP file");
				assertTrue(entryNames.contains("file1.txt"), "ZIP should contain file1.txt");
				assertTrue(entryNames.contains("subDir/"), "ZIP should contain subDir/");
				assertTrue(entryNames.contains("subDir" + File.separatorChar + "file2.txt"), "ZIP should contain subDir/file2.txt");
			}
		}

		@Test
		@DisplayName("Should handle compression of empty directory")
		void shouldHandleCompressionOfEmptyDirectory() throws IOException {
			Path zipFile = tmpFolder.resolve("output_empty.zip");

			try (OutputStream outputStream = Files.newOutputStream(zipFile)) {
				FileUtils.compressDirectory(directoryTest, outputStream);
			}

			assertTrue(Files.exists(zipFile));
			try (java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(Files.newInputStream(zipFile))) {
				assertNull(zis.getNextEntry());
			}
		}

		@Test
		@DisplayName("Should throw exception when directory does not exist")
		void shouldThrowExceptionWhenDirectoryDoesNotExist() {
			Path nonExistentDirectory = tmpFolder.resolve("nonExistentDir");
			Path zipFile = tmpFolder.resolve("output_error.zip");

			try (OutputStream outputStream = Files.newOutputStream(zipFile)) {
				assertThrows(
					UnexpectedIOException.class, () -> FileUtils.compressDirectory(nonExistentDirectory, outputStream));
			} catch (IOException e) {
				fail("Unexpected error occurred: " + e.getMessage());
			}
		}
	}
}
