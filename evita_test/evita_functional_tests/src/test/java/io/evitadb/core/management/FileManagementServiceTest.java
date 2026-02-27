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

package io.evitadb.core.management;

import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.exception.UnexpectedIOException;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * This test verifies behavior of {@link FileManagementService}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
class FileManagementServiceTest implements EvitaTestSupport {
	private static final String SUBDIR_NAME = "fileManagementServiceTest";
	private StorageOptions storageOptions;
	private FileManagementService fileManagementService;

	/**
	 * Method returns number files in target directory.
	 *
	 * @param path path to the directory
	 * @return number of files
	 * @throws IOException when the directory cannot be read
	 */
	private static int numberOfFiles(@Nonnull Path path) throws IOException {
		try (final Stream<Path> list = Files.list(path)) {
			return list
				.mapToInt(it -> 1)
				.sum();
		}
	}

	@BeforeEach
	void setUp() throws IOException {
		cleanTestSubDirectory(SUBDIR_NAME);
		this.storageOptions = StorageOptions.builder(StorageOptions.temporary())
			.workDirectory(getPathInTargetDirectory(SUBDIR_NAME))
			.build();
		this.fileManagementService = new FileManagementService(this.storageOptions);
	}

	@AfterEach
	void tearDown() {
		this.fileManagementService.close();
	}

	// Constructor and Initialization Tests

	@Test
	void shouldInitializeWithValidStorageOptions() {
		assertNotNull(this.fileManagementService);
		assertTrue(Files.exists(this.storageOptions.workDirectory()));
	}

	// createTempFile() Tests

	@Test
	void shouldCreateTempFile() throws IOException {
		final Path tempFile = this.fileManagementService.createTempFile("test.txt");

		assertNotNull(tempFile);
		assertTrue(Files.exists(tempFile));
		assertEquals(this.storageOptions.workDirectory().resolve("test.txt"), tempFile);
		assertEquals(1, numberOfFiles(this.storageOptions.workDirectory()) - 1); // -1 for lock file
	}

	@Test
	void shouldDeleteExistingFileBeforeCreating() throws IOException {
		// Create a file first
		final Path firstFile = this.fileManagementService.createTempFile("duplicate.txt");
		Files.writeString(firstFile, "Original content");

		// Create it again - should overwrite
		final Path secondFile = this.fileManagementService.createTempFile("duplicate.txt");

		assertNotNull(secondFile);
		assertTrue(Files.exists(secondFile));
		assertEquals(0, Files.size(secondFile), "File should be empty after recreation");
		assertEquals(firstFile, secondFile);
	}

	@Test
	void shouldCreateFileInExportDirectory() {
		final String fileName = "testInExportDir.txt";
		final Path tempFile = this.fileManagementService.createTempFile(fileName);

		assertTrue(tempFile.startsWith(this.storageOptions.workDirectory()));
		assertEquals(fileName, tempFile.getFileName().toString());
	}

	// createManagedTempFile() Tests

	@Test
	void shouldCreateManagedTempFile() {
		final Path managedFile = this.fileManagementService.createManagedTempFile("managed.txt");

		assertNotNull(managedFile);
		assertTrue(Files.exists(managedFile));
		assertEquals(this.storageOptions.workDirectory().resolve("managed.txt"), managedFile);
	}

	@Test
	void shouldNormalizePathInReservedList() {
		final Path managedFile = this.fileManagementService.createManagedTempFile("managed.txt");

		// The file should be tracked even with non-normalized path
		this.fileManagementService.purgeManagedTempFile(managedFile);

		assertFalse(Files.exists(managedFile), "File should be deleted");
	}

	@Test
	void shouldCreateMultipleManagedFiles() {
		final List<Path> managedFiles = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			final Path file = this.fileManagementService.createManagedTempFile("managed" + i + ".txt");
			managedFiles.add(file);
			assertTrue(Files.exists(file));
		}

		// All files should exist
		for (Path file : managedFiles) {
			assertTrue(Files.exists(file));
		}
	}

	// purgeManagedTempFile() Tests

	@Test
	void shouldPurgeManagedTempFile() throws IOException {
		final Path managedFile = this.fileManagementService.createManagedTempFile("toPurge.txt");
		Files.writeString(managedFile, "Delete me");

		assertTrue(Files.exists(managedFile));

		this.fileManagementService.purgeManagedTempFile(managedFile);

		assertFalse(Files.exists(managedFile));
	}

	@Test
	void shouldHandleNormalizedPath() {
		final Path managedFile = this.fileManagementService.createManagedTempFile("normalized.txt");

		// Create a non-normalized version of the same path
		final Path nonNormalizedPath = managedFile.getParent().resolve(".").resolve(managedFile.getFileName());

		this.fileManagementService.purgeManagedTempFile(nonNormalizedPath.normalize());

		assertFalse(Files.exists(managedFile));
	}

	@Test
	void shouldHandleNonExistentFile() {
		final Path nonExistentFile = this.storageOptions.workDirectory().resolve("doesNotExist.txt");

		// Should not throw exception
		assertDoesNotThrow(() -> this.fileManagementService.purgeManagedTempFile(nonExistentFile));
	}

	@Test
	void shouldRemoveFromReservedListEvenIfFileDoesNotExist() throws IOException {
		final Path managedFile = this.fileManagementService.createManagedTempFile("toDelete.txt");

		// Manually delete the file
		Files.delete(managedFile);

		// Should not throw exception and should remove from reserved list
		assertDoesNotThrow(() -> this.fileManagementService.purgeManagedTempFile(managedFile));
	}

	// getTempFile() Tests

	@Test
	void shouldGetExistingTempFile() throws IOException {
		final String fileName = "existing.txt";
		final Path createdFile = this.fileManagementService.createTempFile(fileName);
		Files.writeString(createdFile, "Test content");

		final Path retrievedFile = this.fileManagementService.getTempFile(fileName);

		assertNotNull(retrievedFile);
		assertEquals(createdFile, retrievedFile);
		assertTrue(Files.exists(retrievedFile));
		assertEquals("Test content", Files.readString(retrievedFile));
	}

	@Test
	void shouldThrowExceptionForNonExistentFile() {
		assertThrows(UnexpectedIOException.class, () -> this.fileManagementService.getTempFile("nonExistent.txt"));
	}

	@Test
	void shouldResolveFileInExportDirectory() {
		final String fileName = "resolved.txt";
		this.fileManagementService.createTempFile(fileName);

		final Path resolvedFile = this.fileManagementService.getTempFile(fileName);

		assertTrue(resolvedFile.startsWith(this.storageOptions.workDirectory()));
		assertEquals(fileName, resolvedFile.getFileName().toString());
	}

	// close() Tests

	@Test
	void shouldDeleteAllReservedFilesOnClose() throws IOException {
		final List<Path> managedFiles = new ArrayList<>();
		for (int i = 0; i < 3; i++) {
			final Path file = this.fileManagementService.createManagedTempFile("managed" + i + ".txt");
			Files.writeString(file, "Content " + i);
			managedFiles.add(file);
		}

		// Verify all files exist
		for (Path file : managedFiles) {
			assertTrue(Files.exists(file));
		}

		this.fileManagementService.close();

		// Verify all files are deleted
		for (Path file : managedFiles) {
			assertFalse(Files.exists(file), "Managed file should be deleted on close: " + file);
		}
	}

	@Test
	void shouldReleaseFolderLockOnClose() {
		final Path lockFile = this.storageOptions.workDirectory().resolve(".lock");
		assertTrue(Files.exists(lockFile));

		this.fileManagementService.close();

		// After close, we should be able to create a new service (lock should be released)
		assertDoesNotThrow(() -> {
			final FileManagementService newService = new FileManagementService(this.storageOptions);
			newService.close();
		});
	}

	@Test
	void shouldHandleMissingFilesOnClose() throws IOException {
		final Path managedFile = this.fileManagementService.createManagedTempFile("willBeDeleted.txt");

		// Manually delete the file
		Files.delete(managedFile);

		// Close should not throw exception
		assertDoesNotThrow(() -> this.fileManagementService.close());
	}

	@Test
	void shouldCloseGracefullyOnMultipleCalls() {
		// First close
		assertDoesNotThrow(() -> this.fileManagementService.close());

		// Second close should also not throw
		assertDoesNotThrow(() -> this.fileManagementService.close());
	}

	// Edge Cases and Integration Tests

	@Test
	void shouldHandleConcurrentManagedFileOperations() throws InterruptedException {
		final int threadCount = 10;
		final int filesPerThread = 5;
		final ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
		final CountDownLatch latch = new CountDownLatch(threadCount);
		final AtomicInteger successCount = new AtomicInteger(0);
		final List<Path> allFiles = new ArrayList<>();

		for (int i = 0; i < threadCount; i++) {
			final int threadId = i;
			executorService.submit(() -> {
				try {
					for (int j = 0; j < filesPerThread; j++) {
						final Path file = this.fileManagementService.createManagedTempFile(
							"thread" + threadId + "_file" + j + ".txt"
						);
						synchronized (allFiles) {
							allFiles.add(file);
						}
						Files.writeString(file, "Thread " + threadId + " File " + j);
					}
					successCount.addAndGet(filesPerThread);
				} catch (IOException e) {
					fail("IOException in thread " + threadId + ": " + e.getMessage());
				} finally {
					latch.countDown();
				}
			});
		}

		assertTrue(latch.await(10, TimeUnit.SECONDS), "Threads should complete within timeout");
		executorService.shutdown();

		assertEquals(threadCount * filesPerThread, successCount.get());
		assertEquals(threadCount * filesPerThread, allFiles.size());

		// Verify all files exist
		for (Path file : allFiles) {
			assertTrue(Files.exists(file));
		}

		// Close should delete all managed files
		this.fileManagementService.close();

		for (Path file : allFiles) {
			assertFalse(Files.exists(file));
		}
	}

	@Test
	void shouldHandleSpecialCharactersInFileName() {
		final String[] specialNames = {
			"file with spaces.txt",
			"file-with-dashes.txt",
			"file_with_underscores.txt",
			"file.multiple.dots.txt"
		};

		for (String fileName : specialNames) {
			final Path file = this.fileManagementService.createTempFile(fileName);
			assertTrue(Files.exists(file), "File with special name should be created: " + fileName);
			assertEquals(fileName, file.getFileName().toString());
		}
	}

	@Test
	void shouldHandleRelativeAndAbsolutePaths() {
		final Path managedFile = this.fileManagementService.createManagedTempFile("pathTest.txt");

		// Test with absolute path
		this.fileManagementService.purgeManagedTempFile(managedFile.toAbsolutePath());

		assertFalse(Files.exists(managedFile));
	}

	@Test
	void shouldMaintainReservedListIntegrity() {
		// Create managed files
		final Path file1 = this.fileManagementService.createManagedTempFile("file1.txt");
		final Path file2 = this.fileManagementService.createManagedTempFile("file2.txt");
		final Path file3 = this.fileManagementService.createManagedTempFile("file3.txt");

		// Purge one
		this.fileManagementService.purgeManagedTempFile(file2);

		// Create another
		final Path file4 = this.fileManagementService.createManagedTempFile("file4.txt");

		// Verify correct files exist
		assertTrue(Files.exists(file1));
		assertFalse(Files.exists(file2));
		assertTrue(Files.exists(file3));
		assertTrue(Files.exists(file4));

		// Close should delete remaining managed files
		this.fileManagementService.close();

		assertFalse(Files.exists(file1));
		assertFalse(Files.exists(file3));
		assertFalse(Files.exists(file4));
	}

	@Test
	void shouldHandleLargeNumberOfManagedFiles() {
		final int fileCount = 100;
		final List<Path> files = new ArrayList<>();

		for (int i = 0; i < fileCount; i++) {
			final Path file = this.fileManagementService.createManagedTempFile("bulk" + i + ".txt");
			files.add(file);
		}

		// All files should exist
		for (Path file : files) {
			assertTrue(Files.exists(file));
		}

		// Close should delete all
		this.fileManagementService.close();

		for (Path file : files) {
			assertFalse(Files.exists(file));
		}
	}

	@Test
	void shouldCreateTempFileWithExtension() {
		final Path txtFile = this.fileManagementService.createTempFile("test.txt");
		final Path jsonFile = this.fileManagementService.createTempFile("data.json");
		final Path noExtFile = this.fileManagementService.createTempFile("noextension");

		assertTrue(Files.exists(txtFile));
		assertTrue(Files.exists(jsonFile));
		assertTrue(Files.exists(noExtFile));

		assertEquals("test.txt", txtFile.getFileName().toString());
		assertEquals("data.json", jsonFile.getFileName().toString());
		assertEquals("noextension", noExtFile.getFileName().toString());
	}

	@Test
	void shouldHandleEmptyFileContent() throws IOException {
		final Path emptyFile = this.fileManagementService.createTempFile("empty.txt");

		assertEquals(0, Files.size(emptyFile));

		// Write and read back
		Files.writeString(emptyFile, "", StandardCharsets.UTF_8);
		assertEquals("", Files.readString(emptyFile, StandardCharsets.UTF_8));
	}

	@Test
	void shouldHandleBinaryFileContent() throws IOException {
		final Path binaryFile = this.fileManagementService.createTempFile("binary.bin");

		final byte[] binaryData = new byte[]{0x00, 0x01, 0x02, (byte) 0xFF, (byte) 0xFE};
		Files.write(binaryFile, binaryData);

		final byte[] readData = Files.readAllBytes(binaryFile);
		assertArrayEquals(binaryData, readData);
	}

	@Test
	void shouldAllowMixOfManagedAndUnmanagedFiles() throws IOException {
		// Create managed files
		final Path managed1 = this.fileManagementService.createManagedTempFile("managed1.txt");
		final Path managed2 = this.fileManagementService.createManagedTempFile("managed2.txt");

		// Create unmanaged files
		final Path unmanaged1 = this.fileManagementService.createTempFile("unmanaged1.txt");
		final Path unmanaged2 = this.fileManagementService.createTempFile("unmanaged2.txt");

		// All should exist
		assertTrue(Files.exists(managed1));
		assertTrue(Files.exists(managed2));
		assertTrue(Files.exists(unmanaged1));
		assertTrue(Files.exists(unmanaged2));

		// Close should only delete managed files
		this.fileManagementService.close();

		assertFalse(Files.exists(managed1));
		assertFalse(Files.exists(managed2));
		assertTrue(Files.exists(unmanaged1));
		assertTrue(Files.exists(unmanaged2));

		// Clean up unmanaged files
		Files.delete(unmanaged1);
		Files.delete(unmanaged2);
	}
}
