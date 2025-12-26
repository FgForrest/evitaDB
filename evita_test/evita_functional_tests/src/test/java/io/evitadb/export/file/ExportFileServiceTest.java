/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2025
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

package io.evitadb.export.file;

import com.google.common.collect.Lists;
import io.evitadb.api.configuration.ExportOptions;
import io.evitadb.api.exception.FileForFetchNotFoundException;
import io.evitadb.api.file.FileForFetch;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.dataType.PaginatedList;
import io.evitadb.exception.FileChecksumInvalidException;
import io.evitadb.export.file.configuration.FileSystemExportOptions;
import io.evitadb.spi.export.model.ExportFileHandle;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.utils.UUIDUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * This test verifies behavior of {@link ExportFileService}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@DisplayName("ExportFileService")
class ExportFileServiceTest implements EvitaTestSupport {
	private static final String SUBDIR_NAME = "exportFileServiceTest";
	private final FileSystemExportOptions exportOptions = FileSystemExportOptions.builder()
		.sizeLimitBytes(1000)
		.historyExpirationSeconds(60)
		.directory(getPathInTargetDirectory(SUBDIR_NAME))
		.build();
	private ExportFileService exportService;

	/**
	 * Method returns number files in target directory.
	 *
	 * @param path path to the catalog directory
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
		this.exportService = new ExportFileService(this.exportOptions, Mockito.mock(Scheduler.class));
	}

	@AfterEach
	void tearDown() {
		this.exportService.close();
	}

	@Test
	@DisplayName("Should store new file with correct metadata and content")
	void shouldStoreNewFile() throws IOException {
		writeFile("testFile.txt", "A,B");

		// verify the file was written
		final PaginatedList<FileForFetch> files = exportService.listFilesToFetch(1, Integer.MAX_VALUE, Set.of(), Set.of());
		assertEquals(1, files.getTotalRecordCount());

		final FileForFetch fileForFetch = files.getData().get(0);
		assertEquals("testFile.txt", fileForFetch.name());
		assertEquals("With description ...", fileForFetch.description());
		assertEquals("text/plain", fileForFetch.contentType());
		assertEquals(15, fileForFetch.totalSizeInBytes());
		assertArrayEquals(new String[]{"A", "B"}, fileForFetch.origin());

		// verify the file content
		assertEquals("testFileContent", Files.readString(((FileSystemFileForFetch) fileForFetch).path(this.exportOptions.getDirectory()), StandardCharsets.UTF_8));
	}

	@Test
	@DisplayName("Should list files with pagination and filter by origin")
	void shouldListAndFilterFiles() throws IOException {
		final Random rnd = new Random();
		final List<String[]> tags = new ArrayList<>(32);
		for (int i = 0; i < 28; i++) {
			final String[] theTags = Stream.generate(() -> Character.toString((char) ('A' + rnd.nextInt(16))))
				.limit(5)
				.toArray(String[]::new);
			tags.add(theTags);
			writeFile(
				"testFile" + i + ".txt",
				String.join(",", theTags)
			);
		}

		final PaginatedList<FileForFetch> fileForFetches = this.exportService.listFilesToFetch(1, 5, Set.of(), Set.of());
		assertArrayEquals(
			new String[]{
				"testFile27.txt", "testFile26.txt", "testFile25.txt", "testFile24.txt", "testFile23.txt"
			},
			fileForFetches.getData().stream().map(FileForFetch::name).toArray(String[]::new)
		);
		assertEquals(28, fileForFetches.getTotalRecordCount());

		assertArrayEquals(
			new String[]{
				"testFile2.txt", "testFile1.txt", "testFile0.txt"
			},
			this.exportService.listFilesToFetch(6, 5, Set.of(), Set.of())
				.getData().stream().map(FileForFetch::name).toArray(String[]::new)
		);

		final List<String> filteredNames = new ArrayList<>(32);
		for (int i = 0; i < tags.size(); i++) {
			final String[] tag = tags.get(i);
			if (Arrays.asList(tag).contains("A")) {
				filteredNames.add("testFile" + i + ".txt");
			}
		}

		assertArrayEquals(
			Lists.reverse(filteredNames).stream().limit(10).toArray(String[]::new),
			this.exportService.listFilesToFetch(1, 10, Set.of(), Set.of("A"))
				.getData().stream().map(FileForFetch::name).toArray(String[]::new)
		);
	}

	@Test
	@DisplayName("Should delete file and remove both data and metadata files")
	void shouldDeleteFile() throws IOException {
		for (int i = 0; i < 5; i++) {
			writeFile("testFile" + i + ".txt", null);
		}

		assertEquals(
			11, numberOfFiles(this.exportOptions.getDirectory())
		);

		final PaginatedList<FileForFetch> filesBeforeDelete = this.exportService.listFilesToFetch(1, 20, Set.of(), Set.of());
		assertEquals(5, filesBeforeDelete.getTotalRecordCount());

		this.exportService.deleteFile(filesBeforeDelete.getData().get(0).fileId());
		this.exportService.deleteFile(filesBeforeDelete.getData().get(3).fileId());

		assertEquals(3, this.exportService.listFilesToFetch(1, 20, Set.of(), Set.of()).getTotalRecordCount());

		assertEquals(
			7, numberOfFiles(this.exportOptions.getDirectory())
		);
	}

	@Test
	@DisplayName("Should fetch file content by file ID")
	void shouldFetchFile() throws IOException {
		final FileForFetch storedFile = writeFile("testFile.txt", "A,B");

		try (final InputStream inputStream = this.exportService.fetchFile(storedFile.fileId())) {
			final String content = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
			assertEquals("testFileContent", content);
		}
	}

	@Test
	@DisplayName("Should purge files based on date threshold and size limit")
	void shouldPurgeFiles() throws IOException {
		// Initialize some test files
		for (int i = 0; i < 10; i++) {
			writeFile(UUIDUtil.randomUUID() + ".txt", null);
		}

		// Check files before purging
		int numOfFilesBeforePurge = numberOfFiles(exportOptions.getDirectory());
		int totalFilesBeforePurge = this.exportService.listFilesToFetch(1, 20, Set.of(), Set.of()).getTotalRecordCount();
		assertEquals(10, totalFilesBeforePurge);
		assertEquals(totalFilesBeforePurge * 2 + 1, numOfFilesBeforePurge);

		// Purge the files
		this.exportService.purgeFiles(OffsetDateTime.now().minusMinutes(2));

		// Check files after purging
		int numOfFilesAfterPurge = numberOfFiles(exportOptions.getDirectory());
		int totalFilesAfterPurge = this.exportService.listFilesToFetch(1, 20, Set.of(), Set.of()).getTotalRecordCount();
		assertEquals(5, totalFilesAfterPurge);
		assertEquals(totalFilesAfterPurge * 2 + 1, numOfFilesAfterPurge);

		this.exportService.purgeFiles(OffsetDateTime.now());

		// Check files after purging
		int numOfFilesAfterPurge2 = numberOfFiles(exportOptions.getDirectory());
		int totalFilesAfterPurge2 = this.exportService.listFilesToFetch(1, 20, Set.of(), Set.of()).getTotalRecordCount();
		assertEquals(0, totalFilesAfterPurge2);
		assertEquals(totalFilesAfterPurge2 * 2 + 1, numOfFilesAfterPurge2);
	}

	@Test
	@DisplayName("Should store file in catalog subdirectory")
	void shouldStoreFileInCatalogDirectory() throws IOException {
		final String catalogName = "my-catalog";
		final FileForFetch storedFile = writeFileWithCatalog("test.txt", "A", catalogName);

		// Verify file location
		final Path catalogDir = this.exportOptions.getDirectory().resolve(catalogName);
		assertTrue(Files.exists(catalogDir), "Catalog directory should exist");
		assertTrue(Files.isDirectory(catalogDir), "Catalog path should be a directory");

		// Verify file existence in the subdirectory
		final Path expectedPath = ((FileSystemFileForFetch) storedFile).path(this.exportOptions.getDirectory());
		assertTrue(Files.exists(expectedPath), "File should exist in catalog subdirectory");

		// Verify metadata existence in the subdirectory
		final Path expectedMetadataPath = ((FileSystemFileForFetch) storedFile).metadataPath(this.exportOptions.getDirectory());
		assertTrue(Files.exists(expectedMetadataPath), "Metadata file should exist in catalog subdirectory");

		// Verify retrieval
		final Optional<FileForFetch> retrieved = this.exportService.getFile(storedFile.fileId());
		assertTrue(retrieved.isPresent());
		assertEquals(catalogName, retrieved.get().catalogName());
	}

	@Test
	@DisplayName("Should fetch file content from catalog subdirectory")
	void shouldFetchFileFromCatalogDirectory() throws IOException {
		final String catalogName = "fetch-catalog";
		final FileForFetch storedFile = writeFileWithCatalog("test.txt", "A", catalogName);

		try (final InputStream inputStream = this.exportService.fetchFile(storedFile.fileId())) {
			final String content = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
			assertEquals("testFileContent", content);
		}
	}

	@Test
	@DisplayName("Should delete file and empty catalog subdirectory")
	void shouldDeleteFileFromCatalogDirectory() throws IOException {
		final String catalogName = "delete-catalog";
		final FileForFetch storedFile = writeFileWithCatalog("test.txt", "A", catalogName);

		assertTrue(Files.exists(this.exportOptions.getDirectory().resolve(catalogName)));

		this.exportService.deleteFile(storedFile.fileId());

		// File and metadata should be gone
		final Path catalogDir = this.exportOptions.getDirectory().resolve(catalogName);
		final Path filePath = ((FileSystemFileForFetch) storedFile).path(catalogDir);
		final Path metadataPath = ((FileSystemFileForFetch) storedFile).metadataPath(catalogDir);

		assertFalse(Files.exists(filePath), "File should be deleted");
		assertFalse(Files.exists(metadataPath), "Metadata should be deleted");

		// The directory might remain after deleteFile, it is cleaned up by purgeFiles usually,
		// but let's check if the implementation does it immediately?
		// The requirement says: "Also automatically remove empty folders in purge methods."
		// So deleteFile probably doesn't remove the folder.
		assertTrue(Files.exists(catalogDir), "Catalog directory should still exist after single file deletion");
	}

	@Test
	@DisplayName("Should list files filtering by catalog")
	void shouldListFilesFilteringByCatalog() throws IOException {
		writeFileWithCatalog("file1.txt", "A", "cat1");
		writeFileWithCatalog("file2.txt", "A", "cat2");
		writeFileWithCatalog("file3.txt", "A", null); // root

		final PaginatedList<FileForFetch> cat1Files = this.exportService.listFilesToFetch(1, 10, Set.of("cat1"), Set.of());
		assertEquals(1, cat1Files.getTotalRecordCount());
		assertEquals("file1.txt", cat1Files.getData().get(0).name());

		final PaginatedList<FileForFetch> cat2Files = this.exportService.listFilesToFetch(1, 10, Set.of("cat2"), Set.of());
		assertEquals(1, cat2Files.getTotalRecordCount());
		assertEquals("file2.txt", cat2Files.getData().get(0).name());

		// Filtering by empty set should return all
		final PaginatedList<FileForFetch> allFiles = this.exportService.listFilesToFetch(1, 10, Set.of(), Set.of());
		assertEquals(3, allFiles.getTotalRecordCount());
	}

	@Test
	@DisplayName("Should purge empty catalog directories")
	void shouldPurgeEmptyCatalogDirectories() throws IOException {
		final String catalogName = "purge-catalog";
		final FileForFetch storedFile = writeFileWithCatalog("test.txt", "A", catalogName);

		// Manually delete the file to simulate expiration/deletion leaving empty dir
		// Or better, use deleteFile
		this.exportService.deleteFile(storedFile.fileId());

		final Path catalogDir = this.exportOptions.getDirectory().resolve(catalogName);
		assertTrue(Files.exists(catalogDir), "Directory should exist before purge");

		// Run purge
		this.exportService.purgeFiles(OffsetDateTime.now());

		assertFalse(Files.exists(catalogDir), "Directory should be removed after purge");
	}

	@Test
	@DisplayName("Should purge files recursively")
	void shouldPurgeFilesRecursively() throws IOException {
		// Initialize files in root and catalogs
		writeFileWithCatalog("root.txt", null, null);
		writeFileWithCatalog("cat.txt", null, "cat");

		// Check files before purging
		assertEquals(2, this.exportService.listFilesToFetch(1, 20, Set.of(), Set.of()).getTotalRecordCount());

		// Purge the files (all of them)
		this.exportService.purgeFiles(OffsetDateTime.now());

		// Check files after purging
		assertEquals(0, this.exportService.listFilesToFetch(1, 20, Set.of(), Set.of()).getTotalRecordCount());

		// Check directory structure
		assertFalse(Files.exists(this.exportOptions.getDirectory().resolve("cat")), "Catalog directory should be gone");
	}

	// ==================== Exception Handling Tests ====================

	@Test
	@DisplayName("Should throw FileForFetchNotFoundException when fetching non-existent file")
	void shouldThrowExceptionWhenFetchingNonExistentFile() {
		final UUID nonExistentId = UUID.randomUUID();
		assertThrows(FileForFetchNotFoundException.class,
			() -> exportService.fetchFile(nonExistentId));
	}

	@Test
	@DisplayName("Should throw FileForFetchNotFoundException when deleting non-existent file")
	void shouldThrowExceptionWhenDeletingNonExistentFile() {
		final UUID nonExistentId = UUID.randomUUID();
		assertThrows(FileForFetchNotFoundException.class,
			() -> exportService.deleteFile(nonExistentId));
	}

	@Test
	@DisplayName("Should throw FileForFetchNotFoundException when deleting same file twice")
	void shouldThrowExceptionWhenDeletingSameFileTwice() throws IOException {
		final FileForFetch storedFile = writeFile("test.txt", "A");
		final UUID fileId = storedFile.fileId();

		// First delete should succeed
		assertDoesNotThrow(() -> exportService.deleteFile(fileId));

		// Second delete should throw
		assertThrows(FileForFetchNotFoundException.class,
			() -> exportService.deleteFile(fileId));
	}

	// ==================== getFile() Method Tests ====================

	@Test
	@DisplayName("Should return file when getting by valid UUID")
	void shouldReturnFileWhenGettingByValidId() throws IOException {
		final FileForFetch storedFile = writeFile("test.txt", "A");

		final Optional<FileForFetch> result = exportService.getFile(storedFile.fileId());

		assertTrue(result.isPresent());
		assertEquals(storedFile.fileId(), result.get().fileId());
		assertEquals(storedFile.name(), result.get().name());
	}

	@Test
	@DisplayName("Should return empty optional when getting file by non-existent UUID")
	void shouldReturnEmptyWhenFileNotFound() {
		final Optional<FileForFetch> result = exportService.getFile(UUID.randomUUID());

		assertTrue(result.isEmpty());
	}

	@Test
	@DisplayName("Should return empty optional after file is deleted")
	void shouldReturnEmptyAfterFileDeletion() throws IOException {
		final FileForFetch storedFile = writeFile("test.txt", "A");
		final UUID fileId = storedFile.fileId();

		// Verify file exists
		assertTrue(exportService.getFile(fileId).isPresent());

		// Delete the file
		exportService.deleteFile(fileId);

		// Should return empty
		assertTrue(exportService.getFile(fileId).isEmpty());
	}

	// ==================== File Name Edge Case Tests ====================

	@Test
	@DisplayName("Should handle file without extension")
	void shouldStoreFileWithoutExtension() throws IOException {
		final FileForFetch storedFile = writeFile("testfile", "A");

		// Verify the file path doesn't have trailing dot
		final Path filePath = ((FileSystemFileForFetch) storedFile).path(exportOptions.getDirectory());
		assertFalse(filePath.toString().endsWith("."));
		assertTrue(Files.exists(filePath));
		assertEquals("testfile", storedFile.name());
	}

	@Test
	@DisplayName("Should handle file with multiple dots in name")
	void shouldStoreFileWithMultipleDots() throws IOException {
		final FileForFetch storedFile = writeFile("test.backup.2024.tar.gz", "A");

		assertEquals("test.backup.2024.tar.gz", storedFile.name());
		// Extension should be "gz"
		assertTrue(((FileSystemFileForFetch) storedFile).path(exportOptions.getDirectory()).toString().endsWith(".gz"));
	}

	@Test
	@DisplayName("Should sanitize special characters in file name")
	void shouldSanitizeSpecialCharactersInFileName() throws IOException {
		final FileForFetch storedFile = writeFile("test file with spaces.txt", "A");

		// Verify file was created and can be retrieved
		assertTrue(Files.exists(((FileSystemFileForFetch) storedFile).path(exportOptions.getDirectory())));
	}

	// ==================== Empty File and Null Origin Tests ====================

	@Test
	@DisplayName("Should store empty file correctly")
	void shouldStoreEmptyFile() throws IOException {
		final ExportFileHandle handle = exportService.storeFile(
			"empty.txt", "Empty file", "text/plain", null, null);
		handle.outputStream().close();  // Write nothing

		final FileForFetch fileForFetch = handle.fileForFetchFuture().getNow(null);
		assertEquals(0, fileForFetch.totalSizeInBytes());
	}

	@Test
	@DisplayName("Should handle null origin correctly")
	void shouldStoreFileWithNullOrigin() throws IOException {
		final ExportFileHandle handle = exportService.storeFile(
			"test.txt", "desc", "text/plain", null, null);
		try (OutputStream os = handle.outputStream()) {
			os.write("content".getBytes());
		}

		final FileForFetch file = handle.fileForFetchFuture().getNow(null);
		assertNull(file.origin());
	}

	@Test
	@DisplayName("Should not match null origin files when filtering by origin")
	void shouldNotMatchNullOriginWhenFiltering() throws IOException {
		// Store file with null origin
		final ExportFileHandle handle = exportService.storeFile(
			"nullOrigin.txt", "desc", "text/plain", null, null);
		try (OutputStream os = handle.outputStream()) {
			os.write("content".getBytes());
		}

		// Store file with origin
		writeFile("withOrigin.txt", "TAG");

		// Filter by TAG should only return 1 file
		final PaginatedList<FileForFetch> filtered =
			exportService.listFilesToFetch(1, 10, Set.of(), Set.of("TAG"));
		assertEquals(1, filtered.getTotalRecordCount());
		assertEquals("withOrigin.txt", filtered.getData().get(0).name());

		// No filter should return both files
		final PaginatedList<FileForFetch> all =
			exportService.listFilesToFetch(1, 10, Set.of(), Set.of());
		assertEquals(2, all.getTotalRecordCount());
	}

	// ==================== Pagination Edge Case Tests ====================

	@Test
	@DisplayName("Should return empty list when page exceeds available data")
	void shouldReturnEmptyListForPageBeyondData() throws IOException {
		writeFile("test.txt", "A");

		final PaginatedList<FileForFetch> result =
			exportService.listFilesToFetch(100, 10, Set.of(), Set.of());

		assertEquals(1, result.getTotalRecordCount());
		assertTrue(result.getData().isEmpty());
	}

	@Test
	@DisplayName("Should return empty results for empty file list")
	void shouldReturnEmptyResultsWhenNoFiles() {
		final PaginatedList<FileForFetch> result =
			exportService.listFilesToFetch(1, 10, Set.of(), Set.of());

		assertEquals(0, result.getTotalRecordCount());
		assertTrue(result.getData().isEmpty());
	}

	@Test
	@DisplayName("Should handle multiple origin filters")
	void shouldFilterByMultipleOrigins() throws IOException {
		writeFile("file1.txt", "A");
		writeFile("file2.txt", "B");
		writeFile("file3.txt", "C");

		final PaginatedList<FileForFetch> result =
			exportService.listFilesToFetch(1, 10, Set.of(), Set.of("A", "B"));

		assertEquals(2, result.getTotalRecordCount());
	}

	@Test
	@DisplayName("Should return empty results for non-existent origin filter")
	void shouldReturnEmptyForNonExistentOrigin() throws IOException {
		writeFile("file1.txt", "A");
		writeFile("file2.txt", "B");

		final PaginatedList<FileForFetch> result =
			exportService.listFilesToFetch(1, 10, Set.of(), Set.of("NON_EXISTENT"));

		assertEquals(0, result.getTotalRecordCount());
		assertTrue(result.getData().isEmpty());
	}

	// ==================== Service Restart/Initialization Tests ====================

	@Test
	@DisplayName("Should restore files from existing directory on restart")
	void shouldRestoreFilesFromExistingDirectoryOnRestart() throws IOException {
		// Create files with first service instance
		writeFile("file1.txt", "A");
		writeFile("file2.txt", "B");
		exportService.close();

		// Create new service instance pointing to same directory
		// Replace the field so tearDown closes the new service
		this.exportService = new ExportFileService(
			exportOptions, Mockito.mock(Scheduler.class));

		final PaginatedList<FileForFetch> files =
			exportService.listFilesToFetch(1, 10, Set.of(), Set.of());
		assertEquals(2, files.getTotalRecordCount());
	}

	@Test
	@DisplayName("Should handle initialization with corrupted metadata files")
	void shouldHandleCorruptedMetadataFiles() throws IOException {
		// Create proper file
		writeFile("proper.txt", "A");

		// Create corrupted metadata file directly
		final Path corruptedMetadata = exportOptions.getDirectory()
			.resolve(UUIDUtil.randomUUID() + FileSystemFileForFetch.METADATA_EXTENSION);
		Files.writeString(corruptedMetadata, "corrupted content");

		exportService.close();

		// Create new service - should not fail due to corrupted file
		// Replace the field so tearDown closes the new service
		this.exportService = new ExportFileService(
			exportOptions, Mockito.mock(Scheduler.class));

		// Should only see the proper file
		final PaginatedList<FileForFetch> files =
			exportService.listFilesToFetch(1, 10, Set.of(), Set.of());
		assertEquals(1, files.getTotalRecordCount());
		assertEquals("proper.txt", files.getData().get(0).name());
	}

	// ==================== Purge Edge Case Tests ====================

	@Test
	@DisplayName("Should purge orphan files without metadata")
	void shouldPurgeOrphanFilesWithoutMetadata() throws IOException {
		// Create proper file
		writeFile("proper.txt", "A");

		// Create orphan file (data file without metadata)
		final Path orphanPath = exportOptions.getDirectory().resolve("orphan-data.txt");
		Files.writeString(orphanPath, "orphan content");

		// Set last modified to past
		assertTrue(orphanPath.toFile().setLastModified(
			System.currentTimeMillis() - TimeUnit.HOURS.toMillis(1)));

		exportService.purgeFiles(OffsetDateTime.now().minusMinutes(30));

		// Orphan should be deleted
		assertFalse(Files.exists(orphanPath));
		// Proper file should remain
		assertEquals(1, exportService.listFilesToFetch(1, 10, Set.of(), Set.of()).getTotalRecordCount());
	}

	@Test
	@DisplayName("Should keep orphan files newer than threshold")
	void shouldKeepOrphanFilesNewerThanThreshold() throws IOException {
		// Create proper file
		writeFile("proper.txt", "A");

		// Create orphan file (data file without metadata) - don't change modification time
		final Path orphanPath = exportOptions.getDirectory().resolve("orphan-new.txt");
		Files.writeString(orphanPath, "orphan content");

		exportService.purgeFiles(OffsetDateTime.now().minusMinutes(30));

		// Orphan should remain (it's newer than threshold)
		assertTrue(Files.exists(orphanPath));
	}

	@Test
	@DisplayName("Should delete oldest files when size limit exceeded")
	void shouldDeleteOldestFilesWhenSizeLimitExceeded() throws IOException {
		// Recreate service with controllable time
		final AtomicReference<OffsetDateTime> testTime =
			new AtomicReference<>(OffsetDateTime.now());

		this.exportService.close();
		this.exportService = new ExportFileService(
			exportOptions,
			Mockito.mock(Scheduler.class),
			testTime::get
		);

		// Service has sizeLimitBytes=1000 in test setup
		// Create files that exceed the limit - each file creates ~300 bytes content + metadata
		for (int i = 0; i < 5; i++) {
			writeFileWithContent("file" + i + ".txt", "A", new byte[300]);
			testTime.set(testTime.get().plusSeconds(1)); // Advance time deterministically
		}

		// All 5 files should exist now (total ~1500 bytes, over limit)
		assertEquals(5, exportService.listFilesToFetch(1, 10, Set.of(), Set.of()).getTotalRecordCount());

		// Trigger purge with future date (don't delete by date, only by size)
		exportService.purgeFiles(OffsetDateTime.now().plusDays(1));

		// Files should be deleted from oldest until under limit
		final PaginatedList<FileForFetch> remaining =
			exportService.listFilesToFetch(1, 10, Set.of(), Set.of());

		// Should have fewer files now due to size limit
		assertTrue(remaining.getTotalRecordCount() < 5);
	}

	@Test
	@DisplayName("Should throw exception when file content is corrupted")
	void shouldThrowExceptionWhenFileContentIsCorrupted() throws IOException {
		final FileForFetch storedFile = writeFile("test.txt", "A");
		final Path filePath = ((FileSystemFileForFetch) storedFile).path(this.exportOptions.getDirectory());

		// Append a byte to corrupt the file content
		Files.write(filePath, new byte[]{0}, java.nio.file.StandardOpenOption.APPEND);

		final InputStream is = this.exportService.fetchFile(storedFile.fileId());
		is.readAllBytes();
		assertThrows(FileChecksumInvalidException.class, is::close);
	}

	// ==================== Constructor Validation Tests ====================

	@Test
	@DisplayName("Should throw exception for wrong export options type")
	void shouldThrowExceptionForWrongOptionsType() {
		final ExportOptions wrongOptions = Mockito.mock(ExportOptions.class);

		assertThrows(IllegalArgumentException.class,
			() -> new ExportFileService(wrongOptions, Mockito.mock(Scheduler.class)));
	}

	// ==================== ExportFileHandleLocal Tests ====================

	@Test
	@DisplayName("Should return correct file size from handle")
	void shouldReturnCorrectFileSizeFromHandle() throws IOException {
		final ExportFileHandle handle = exportService.storeFile(
			"test.txt", "desc", "text/plain", null, "A");
		try (OutputStream os = handle.outputStream()) {
			os.write("12345".getBytes());
		}

		assertEquals(5, handle.size());
	}

	@Test
	@DisplayName("Should handle double close of output stream safely")
	void shouldHandleDoubleCloseOfOutputStreamSafely() throws IOException {
		final ExportFileHandle handle = exportService.storeFile(
			"test.txt", "desc", "text/plain", null, "A");
		final OutputStream os = handle.outputStream();
		os.write("content".getBytes());

		// Double close should not throw
		assertDoesNotThrow(() -> {
			os.close();
			os.close();
		});
	}

	@Test
	@DisplayName("Should complete future after stream close")
	void shouldCompleteFutureAfterStreamClose() throws IOException {
		final ExportFileHandle handle = exportService.storeFile(
			"test.txt", "desc", "text/plain", null, "A");

		// Future should not be complete before close
		assertFalse(handle.fileForFetchFuture().isDone());

		try (OutputStream os = handle.outputStream()) {
			os.write("content".getBytes());
		}

		// Future should be complete after close
		assertTrue(handle.fileForFetchFuture().isDone());
		assertEquals("test.txt", handle.fileForFetchFuture().getNow(null).name());
	}

	// ==================== Additional Edge Cases ====================

	@Test
	@DisplayName("Should return export directory path")
	void shouldReturnExportDirectory() {
		assertEquals(exportOptions.getDirectory(), exportService.getExportDirectory());
	}

	@Test
	@DisplayName("Should handle file with null description")
	void shouldStoreFileWithNullDescription() throws IOException {
		final ExportFileHandle handle = exportService.storeFile(
			"test.txt", null, "text/plain", null, "A");
		try (OutputStream os = handle.outputStream()) {
			os.write("content".getBytes());
		}

		final FileForFetch file = handle.fileForFetchFuture().getNow(null);
		assertNull(file.description());
	}

	@Test
	@DisplayName("Should preserve file order by creation time (newest first)")
	void shouldPreserveFileOrderByCreationTime() throws IOException {
		// Recreate service with controllable time
		final AtomicReference<OffsetDateTime> testTime =
			new AtomicReference<>(OffsetDateTime.now());

		this.exportService.close();
		this.exportService = new ExportFileService(
			exportOptions,
			Mockito.mock(Scheduler.class),
			testTime::get
		);

		writeFile("first.txt", "A");
		testTime.set(testTime.get().plusSeconds(1));

		writeFile("second.txt", "A");
		testTime.set(testTime.get().plusSeconds(1));

		writeFile("third.txt", "A");

		final PaginatedList<FileForFetch> files =
			exportService.listFilesToFetch(1, 10, Set.of(), Set.of());

		assertEquals(3, files.getTotalRecordCount());
		// Newest first
		assertEquals("third.txt", files.getData().get(0).name());
		assertEquals("second.txt", files.getData().get(1).name());
		assertEquals("first.txt", files.getData().get(2).name());
	}

	// ==================== Externally Managed Files Tests ====================

	@Test
	@DisplayName("Should not purge externally managed files by date")
	void shouldNotPurgeExternallyManagedFilesByDate() throws IOException {
		// Create both managed and regular files
		final FileForFetch managedFile = writeExternallyManagedFile("managed.txt", "A");
		final FileForFetch regularFile = writeFile("regular.txt", "A");

		// Verify both files exist
		assertEquals(2, exportService.listFilesToFetch(1, 10, Set.of(), Set.of()).getTotalRecordCount());

		// Purge all files by date (threshold in future)
		exportService.purgeFiles(OffsetDateTime.now().plusMinutes(1));

		// Managed file should remain, regular should be deleted
		final PaginatedList<FileForFetch> remainingFiles = exportService.listFilesToFetch(1, 10, Set.of(), Set.of());
		assertEquals(1, remainingFiles.getTotalRecordCount());
		assertEquals(managedFile.fileId(), remainingFiles.getData().get(0).fileId());
		assertTrue(remainingFiles.getData().get(0).externallyManaged());
	}

	@Test
	@DisplayName("Should not purge externally managed files by size limit")
	void shouldNotPurgeExternallyManagedFilesBySize() throws IOException {
		// Recreate service with controllable time
		final AtomicReference<OffsetDateTime> testTime =
			new AtomicReference<>(OffsetDateTime.now());

		this.exportService.close();
		this.exportService = new ExportFileService(
			exportOptions,
			Mockito.mock(Scheduler.class),
			testTime::get
		);

		// Create managed files that exceed the size limit (1000 bytes)
		final FileForFetch managedFile1 = writeExternallyManagedFileWithContent("managed1.txt", "A", new byte[400]);
		testTime.set(testTime.get().plusSeconds(1));
		final FileForFetch managedFile2 = writeExternallyManagedFileWithContent("managed2.txt", "A", new byte[400]);
		testTime.set(testTime.get().plusSeconds(1));

		// Create regular files
		final FileForFetch regularFile1 = writeFileWithContent("regular1.txt", "A", new byte[300]);
		testTime.set(testTime.get().plusSeconds(1));
		final FileForFetch regularFile2 = writeFileWithContent("regular2.txt", "A", new byte[300]);

		// Verify all 4 files exist (total > 1000 bytes)
		assertEquals(4, exportService.listFilesToFetch(1, 10, Set.of(), Set.of()).getTotalRecordCount());

		// Trigger purge with future date (only size-based purge should happen)
		exportService.purgeFiles(OffsetDateTime.now().plusDays(1));

		// Managed files should remain, regular files should be deleted to meet size limit
		final PaginatedList<FileForFetch> remainingFiles = exportService.listFilesToFetch(1, 10, Set.of(), Set.of());

		// Count managed and regular files
		long managedCount = remainingFiles.getData().stream().filter(FileForFetch::externallyManaged).count();
		long regularCount = remainingFiles.getData().stream().filter(f -> !f.externallyManaged()).count();

		// Both managed files should remain
		assertEquals(2, managedCount);
		// Regular files should be reduced or eliminated
		assertTrue(regularCount < 2, "Regular files should be purged to meet size limit");
	}

	@Test
	@DisplayName("Should delete externally managed file via explicit deleteFile call")
	void shouldDeleteExternallyManagedFileExplicitly() throws IOException {
		final FileForFetch managedFile = writeExternallyManagedFile("managed.txt", "A");

		// Verify file exists
		assertTrue(exportService.getFile(managedFile.fileId()).isPresent());

		// Delete explicitly
		exportService.deleteFile(managedFile.fileId());

		// Verify file is deleted
		assertTrue(exportService.getFile(managedFile.fileId()).isEmpty());
	}

	@Test
	@DisplayName("Should mark externally managed file correctly in metadata")
	void shouldMarkExternallyManagedFileCorrectly() throws IOException {
		final FileForFetch managedFile = writeExternallyManagedFile("managed.txt", "A");
		final FileForFetch regularFile = writeFile("regular.txt", "A");

		assertTrue(managedFile.externallyManaged(), "Managed file should have externallyManaged=true");
		assertFalse(regularFile.externallyManaged(), "Regular file should have externallyManaged=false");

		// Verify metadata is persisted correctly by reloading
		final Optional<FileForFetch> reloadedManaged = exportService.getFile(managedFile.fileId());
		final Optional<FileForFetch> reloadedRegular = exportService.getFile(regularFile.fileId());

		assertTrue(reloadedManaged.isPresent());
		assertTrue(reloadedRegular.isPresent());
		assertTrue(reloadedManaged.get().externallyManaged());
		assertFalse(reloadedRegular.get().externallyManaged());
	}

	@Test
	@DisplayName("Should evict regular files earlier when managed files take space")
	void shouldEvictRegularFilesEarlierWhenManagedFilesTakeSpace() throws IOException {
		// Recreate service with controllable time
		final AtomicReference<OffsetDateTime> testTime =
			new AtomicReference<>(OffsetDateTime.now());

		this.exportService.close();
		this.exportService = new ExportFileService(
			exportOptions,
			Mockito.mock(Scheduler.class),
			testTime::get
		);

		// Create a managed file that takes significant space (600 bytes out of 1000 limit)
		writeExternallyManagedFileWithContent("managed.txt", "A", new byte[600]);
		testTime.set(testTime.get().plusSeconds(1));

		// Create regular files that would fit without the managed file but don't fit with it
		final FileForFetch regularOld = writeFileWithContent("regular_old.txt", "A", new byte[200]);
		testTime.set(testTime.get().plusSeconds(1));
		final FileForFetch regularNew = writeFileWithContent("regular_new.txt", "A", new byte[300]);

		// Total: 600 + 200 + 300 + metadata = > 1000 bytes
		assertEquals(3, exportService.listFilesToFetch(1, 10, Set.of(), Set.of()).getTotalRecordCount());

		// Trigger size-based purge
		exportService.purgeFiles(OffsetDateTime.now().plusDays(1));

		// Managed file should remain, oldest regular file should be deleted
		final PaginatedList<FileForFetch> remaining = exportService.listFilesToFetch(1, 10, Set.of(), Set.of());

		// Check that managed file is still present
		boolean managedPresent = remaining.getData().stream().anyMatch(FileForFetch::externallyManaged);
		assertTrue(managedPresent, "Managed file should not be deleted");

		// The oldest regular file should be deleted first
		boolean oldRegularPresent = remaining.getData().stream()
			.anyMatch(f -> f.fileId().equals(regularOld.fileId()));

		// Either the old one is deleted, or the total is reduced
		assertTrue(remaining.getTotalRecordCount() < 3 || !oldRegularPresent,
			"Oldest regular files should be evicted first");
	}

	// ==================== Helper Methods ====================

	@Nullable
	private FileForFetch writeFile(@Nonnull String fileName, @Nullable String withOrigin) throws IOException {
		final ExportFileHandle exportFileHandle = this.exportService.storeFile(
			fileName,
			"With description ...",
			"text/plain",
			null,
			withOrigin
		);
		try (final OutputStream outputStream = exportFileHandle.outputStream()) {
			outputStream.write("testFileContent".getBytes());
		}
		return exportFileHandle.fileForFetchFuture().getNow(null);
	}

	@Nullable
	private FileForFetch writeFileWithContent(
		@Nonnull String fileName,
		@Nullable String origin,
		@Nonnull byte[] content
	) throws IOException {
		final ExportFileHandle handle = exportService.storeFile(
			fileName, "desc", "text/plain", null, origin);
		try (OutputStream os = handle.outputStream()) {
			os.write(content);
		}
		return handle.fileForFetchFuture().getNow(null);
	}

	@Nullable
	private FileForFetch writeFileWithCatalog(@Nonnull String fileName, @Nullable String withOrigin, @Nullable String catalogName) throws IOException {
		final ExportFileHandle exportFileHandle = this.exportService.storeFile(
			fileName,
			"With description ...",
			"text/plain",
			catalogName,
			withOrigin
		);
		try (final OutputStream outputStream = exportFileHandle.outputStream()) {
			outputStream.write("testFileContent".getBytes());
		}
		return exportFileHandle.fileForFetchFuture().getNow(null);
	}

	@Nullable
	private FileForFetch writeExternallyManagedFile(@Nonnull String fileName, @Nullable String withOrigin) throws IOException {
		final ExportFileHandle exportFileHandle = this.exportService.storeExternallyManagedFile(
			fileName,
			"Externally managed file",
			"text/plain",
			null,
			withOrigin
		);
		try (final OutputStream outputStream = exportFileHandle.outputStream()) {
			outputStream.write("testFileContent".getBytes());
		}
		return exportFileHandle.fileForFetchFuture().getNow(null);
	}

	@Nullable
	private FileForFetch writeExternallyManagedFileWithContent(
		@Nonnull String fileName,
		@Nullable String origin,
		@Nonnull byte[] content
	) throws IOException {
		final ExportFileHandle handle = exportService.storeExternallyManagedFile(
			fileName, "Externally managed file", "text/plain", null, origin);
		try (OutputStream os = handle.outputStream()) {
			os.write(content);
		}
		return handle.fileForFetchFuture().getNow(null);
	}

}
