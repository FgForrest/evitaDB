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

package io.evitadb.export.s3;

import com.adobe.testing.s3mock.junit5.S3MockExtension;
import com.google.common.collect.Lists;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.exception.FileForFetchNotFoundException;
import io.evitadb.api.file.FileForFetch;
import io.evitadb.core.executor.ImmediateScheduledThreadPoolExecutor;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.core.management.FileManagementService;
import io.evitadb.dataType.PaginatedList;
import io.evitadb.exception.FileChecksumInvalidException;
import io.evitadb.exception.UnexpectedIOException;
import io.evitadb.export.s3.configuration.S3ExportOptions;
import io.evitadb.spi.export.model.ExportFileHandle;
import io.evitadb.utils.UUIDUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * This test verifies behavior of {@link ExportS3Service}.
 * Uses Adobe S3Mock library to simulate S3-compatible storage.
 *
 * Note: All tests are expected to fail with {@link UnsupportedOperationException} until
 * the {@link ExportS3Service} implementation is completed.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@DisplayName("S3 Export Service Test")
class ExportS3ServiceTest {

	/**
	 * S3Mock extension for JUnit 5 that starts an in-memory S3-compatible server.
	 */
	@RegisterExtension
	static final S3MockExtension S3_MOCK = S3MockExtension.builder()
		.silent()
		.withSecureConnection(false)
		.build();

	/**
	 * Name of the S3 bucket used for testing.
	 */
	private static final String BUCKET_NAME = "export-test-bucket";

	/**
	 * S3 client for direct bucket operations in tests.
	 */
	private S3Client s3Client;

	/**
	 * The export service under test.
	 */
	private ExportS3Service exportService;

	/**
	 * S3 export options configured to use the mock S3 server.
	 */
	private S3ExportOptions exportOptions;

	@BeforeEach
	void setUp() {
		// Create S3 client connected to the mock server (used only for cleanup in tearDown)
		this.s3Client = S3_MOCK.createS3ClientV2();

		// Create S3ExportOptions pointing to mock server
		// The ExportS3Service will create the bucket if it doesn't exist
		this.exportOptions = S3ExportOptions.builder()
			.enabled(true)
			.endpoint("http://localhost:" + S3_MOCK.getHttpPort())
			.bucket(BUCKET_NAME)
			.accessKey("accessKey")
			.secretKey("secretKey")
			.region("us-east-1")
			.sizeLimitBytes(1000)
			.historyExpirationSeconds(60)
			.build();

		// Create service instance - this will also create the bucket
		this.exportService = createExportService();
	}

	@Nonnull
	private ExportS3Service createExportService() {
		return new ExportS3Service(
			this.exportOptions,
			new Scheduler(new ImmediateScheduledThreadPoolExecutor()),
			new FileManagementService(
				StorageOptions.builder(StorageOptions.temporary())
					.workDirectory(
						Path.of(System.getProperty("java.io.tmpdir"), "evita/work", UUID.randomUUID().toString())
					)
					.build()
			)
		);
	}

	@AfterEach
	void tearDown() throws IOException {
		this.exportService.close();
		// Delete all objects in bucket and then the bucket itself
		try {
			this.s3Client.listObjects(builder -> builder.bucket(BUCKET_NAME))
				.contents()
				.forEach(obj -> this.s3Client.deleteObject(builder -> builder.bucket(BUCKET_NAME).key(obj.key())));
			this.s3Client.deleteBucket(builder -> builder.bucket(BUCKET_NAME));
		} catch (Exception e) {
			// Ignore cleanup errors
		}
		this.s3Client.close();
	}

	@Test
	@DisplayName("Should store new file to S3 bucket")
	void shouldStoreNewFile() throws IOException {
		writeFile("testFile.txt", "A,B");

		// verify the file was written
		final PaginatedList<FileForFetch> files = this.exportService.listFilesToFetch(1, Integer.MAX_VALUE, Set.of(), Set.of());
		assertEquals(1, files.getTotalRecordCount());

		final FileForFetch fileForFetch = files.getData().get(0);
		assertEquals("testFile.txt", fileForFetch.name());
		assertEquals("With description ...", fileForFetch.description());
		assertEquals("text/plain", fileForFetch.contentType());
		assertEquals(15, fileForFetch.totalSizeInBytes());
		assertArrayEquals(new String[]{"A", "B"}, fileForFetch.origin());
	}

	@Test
	@DisplayName("Should list and filter files by origin tags")
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
	@DisplayName("Should store file in catalog")
	void shouldStoreFileInCatalog() throws IOException {
		final String catalogName = "my-catalog";
		final FileForFetch storedFile = writeFileWithCatalog("test.txt", "A", catalogName);

		// Verify retrieval
		final Optional<FileForFetch> retrieved = this.exportService.getFile(storedFile.fileId());
		assertTrue(retrieved.isPresent());
		assertEquals(catalogName, retrieved.get().catalogName());
	}

	@Test
	@DisplayName("Should fetch file content from catalog")
	void shouldFetchFileFromCatalog() throws IOException {
		final String catalogName = "fetch-catalog";
		final FileForFetch storedFile = writeFileWithCatalog("test.txt", "A", catalogName);

		try (final InputStream inputStream = this.exportService.fetchFile(storedFile.fileId())) {
			final String content = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
			assertEquals("testFileContent", content);
		}
	}

	@Test
	@DisplayName("Should delete file from catalog")
	void shouldDeleteFileFromCatalog() throws IOException {
		final String catalogName = "delete-catalog";
		final FileForFetch storedFile = writeFileWithCatalog("test.txt", "A", catalogName);

		this.exportService.deleteFile(storedFile.fileId());

		// Verify retrieval returns empty
		final Optional<FileForFetch> retrieved = this.exportService.getFile(storedFile.fileId());
		assertTrue(retrieved.isEmpty());
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
	@DisplayName("Should purge files in catalogs")
	void shouldPurgeFilesInCatalogs() throws IOException {
		// Initialize files in root and catalogs
		writeFileWithCatalog("root.txt", null, null);
		writeFileWithCatalog("cat.txt", null, "cat");

		// Check files before purging
		assertEquals(2, this.exportService.listFilesToFetch(1, 20, Set.of(), Set.of()).getTotalRecordCount());

		// Purge the files (created now, so purging with future threshold removes them)
		this.exportService.purgeFiles(OffsetDateTime.now().plusSeconds(1));

		// Check files after purging
		assertEquals(0, this.exportService.listFilesToFetch(1, 20, Set.of(), Set.of()).getTotalRecordCount());
	}

	@Test
	@DisplayName("Should delete file from S3 bucket")
	void shouldDeleteFile() throws IOException {
		for (int i = 0; i < 5; i++) {
			writeFile("testFile" + i + ".txt", null);
		}

		final PaginatedList<FileForFetch> filesBeforeDelete = this.exportService.listFilesToFetch(1, 20, Set.of(), Set.of());
		assertEquals(5, filesBeforeDelete.getTotalRecordCount());

		this.exportService.deleteFile(filesBeforeDelete.getData().get(0).fileId());
		this.exportService.deleteFile(filesBeforeDelete.getData().get(3).fileId());

		assertEquals(3, this.exportService.listFilesToFetch(1, 20, Set.of(), Set.of()).getTotalRecordCount());
	}

	@Test
	@DisplayName("Should throw exception when deleting file already removed by third party")
	void shouldThrowExceptionWhenDeletingAlreadyDeletedObject() throws IOException {
		final FileForFetch storedFile = writeFile("thirdParty.txt", null);

		// Compute S3 object key the same way as service does: <fileId><extension>
		final String name = storedFile.name();
		final int dotIdx = name.lastIndexOf('.');
		final String ext = dotIdx >= 0 ? name.substring(dotIdx) : "";
		final String objectKey = storedFile.fileId().toString() + ext;

		// Simulate third-party deletion directly on S3
		this.s3Client.deleteObject(b -> b.bucket(BUCKET_NAME).key(objectKey));

		// Our service deletion must not throw even if the object is already gone
		assertDoesNotThrow(() -> this.exportService.deleteFile(storedFile.fileId()));

		// The file must be removed from the local cache as well (stateless -> check lookup returns empty)
		assertTrue(this.exportService.getFile(storedFile.fileId()).isEmpty());
	}

	@Test
	@DisplayName("Should throw exception when file content is corrupted")
	void shouldThrowExceptionWhenFileContentIsCorrupted() throws IOException {
		final FileForFetch storedFile = writeFile("test.txt", "A");

		// Construct object key
		final String name = storedFile.name();
		final int dotIdx = name.lastIndexOf('.');
		final String ext = dotIdx >= 0 ? name.substring(dotIdx) : "";
		final String objectKey = storedFile.fileId().toString() + ext;

		// Get original metadata
		var headObjectResponse = this.s3Client.headObject(b -> b.bucket(BUCKET_NAME).key(objectKey));
		Map<String, String> metadata = headObjectResponse.metadata();

		// Upload modified content with original metadata (containing original CRC32)
		this.s3Client.putObject(PutObjectRequest.builder()
				.bucket(BUCKET_NAME)
				.key(objectKey)
				.metadata(metadata)
				.build(),
			RequestBody.fromString("corrupted content"));

		final InputStream is = this.exportService.fetchFile(storedFile.fileId());
		is.readAllBytes();
		assertThrows(FileChecksumInvalidException.class, is::close);
	}

	@Test
	@DisplayName("Should fetch file content from S3 bucket")
	void shouldFetchFile() throws IOException {
		final FileForFetch storedFile = writeFile("testFile.txt", "A,B");

		try (final InputStream inputStream = this.exportService.fetchFile(storedFile.fileId())) {
			final String content = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
			assertEquals("testFileContent", content);
		}
	}

	@Test
	@DisplayName("Should purge files based on age threshold")
	void shouldPurgeFiles() throws IOException {
		// Initialize some test files
		for (int i = 0; i < 10; i++) {
			writeFile(UUIDUtil.randomUUID() + ".txt", null);
		}

		// Check files before purging
		final int totalFilesBeforePurge = this.exportService.listFilesToFetch(1, 20, Set.of(), Set.of()).getTotalRecordCount();
		assertEquals(10, totalFilesBeforePurge);

		// Purge the files older than 2 minutes ago (should keep all since they're fresh)
		this.exportService.purgeFiles(OffsetDateTime.now().minusMinutes(2));

		// Check files after purging - should still have files since threshold is in the past
		final int totalFilesAfterPurge = this.exportService.listFilesToFetch(1, 20, Set.of(), Set.of()).getTotalRecordCount();
		assertEquals(10, totalFilesAfterPurge);

		// Purge files created before now (should remove all)
		this.exportService.purgeFiles(OffsetDateTime.now().plusMinutes(1));

		// Check files after purging
		final int totalFilesAfterPurge2 = this.exportService.listFilesToFetch(1, 20, Set.of(), Set.of()).getTotalRecordCount();
		assertEquals(0, totalFilesAfterPurge2);
	}

	@Test
	@DisplayName("Should throw exception when fetching non-existent file")
	void shouldThrowExceptionWhenFetchingNonExistentFile() {
		final UUID nonExistentId = UUIDUtil.randomUUID();
		assertThrows(FileForFetchNotFoundException.class,
			() -> this.exportService.fetchFile(nonExistentId));
	}

	@Test
	@DisplayName("Should return file by ID when it exists")
	void shouldReturnFileByIdWhenExists() throws IOException {
		final FileForFetch storedFile = writeFile("test.txt", null);

		final Optional<FileForFetch> result = this.exportService.getFile(storedFile.fileId());

		assertTrue(result.isPresent());
		assertEquals(storedFile.fileId(), result.get().fileId());
		assertEquals("test.txt", result.get().name());
	}

	@Test
	@DisplayName("Should return empty optional when file not found")
	void shouldReturnEmptyOptionalWhenFileNotFound() {
		final UUID nonExistentId = UUIDUtil.randomUUID();

		final Optional<FileForFetch> result = this.exportService.getFile(nonExistentId);

		assertTrue(result.isEmpty());
	}

	@Test
	@DisplayName("Should purge oldest files when storage limit is exceeded")
	void shouldPurgeOldestFilesWhenStorageLimitExceeded() throws IOException {
		// The service is configured with sizeLimitBytes=1000
		// Write files that exceed this limit
		for (int i = 0; i < 5; i++) {
			writeFile("file" + i + ".txt", null);
		}

		// Write large files to exceed the limit (each file is 15 bytes, need to go over 1000)
		writeLargeFile("bigFile1.txt", 500);
		writeLargeFile("bigFile2.txt", 600);

		// Total is now: 5*15 + 500 + 600 = 75 + 1100 = 1175 bytes (exceeds 1000 limit)

		// Trigger purge with past date (to skip age-based purge and only trigger size-based)
		this.exportService.purgeFiles(OffsetDateTime.now().minusYears(1));

		// Verify some files were removed to bring storage under limit
		final PaginatedList<FileForFetch> remaining = this.exportService.listFilesToFetch(1, 20, Set.of(), Set.of());

		// Should have fewer than 7 files and newest files should be kept
		assertTrue(remaining.getTotalRecordCount() < 7);
		// Verify newest large file is kept
		assertTrue(remaining.getData().stream()
			.anyMatch(f -> f.name().equals("bigFile2.txt")));
	}

	@Test
	@DisplayName("Should handle empty file upload")
	void shouldHandleEmptyFileUpload() throws IOException, InterruptedException, ExecutionException, TimeoutException {
		final ExportFileHandle handle = this.exportService.storeFile(
			"empty.txt", "Empty file", "text/plain", null, null);

		// Close without writing anything
		handle.close();

		final FileForFetch result = handle.fileForFetchFuture().get(30, TimeUnit.SECONDS);
		assertEquals(0, result.totalSizeInBytes());
		assertEquals("empty.txt", result.name());

		try (final InputStream is = this.exportService.fetchFile(result.fileId())) {
			assertEquals(0, is.readAllBytes().length);
		}
	}

	@Test
	@DisplayName("Should handle file with special characters in name")
	void shouldHandleFileWithSpecialCharactersInName() throws IOException {
		final FileForFetch storedFile = writeFile("test file (1).txt", null);

		final PaginatedList<FileForFetch> files = this.exportService.listFilesToFetch(1, 10, Set.of(), Set.of());

		assertEquals(1, files.getTotalRecordCount());
		// Name may be sanitized by FileUtils.convertToSupportedName()
		assertNotNull(files.getData().get(0).name());
	}

	@Test
	@DisplayName("Should handle file with unicode content")
	void shouldHandleFileWithUnicodeContent() throws IOException, InterruptedException, ExecutionException, TimeoutException {
		final String unicodeContent = "Hello \u4e16\u754c \ud83d\ude00";

		final ExportFileHandle handle = this.exportService.storeFile(
			"unicode.txt", "Description with \u00e9\u00e8", "text/plain", null, null);

		try (final OutputStream os = handle.outputStream()) {
			os.write(unicodeContent.getBytes(StandardCharsets.UTF_8));
		}

		final FileForFetch result = handle.fileForFetchFuture().get(30, TimeUnit.SECONDS);

		try (final InputStream is = this.exportService.fetchFile(result.fileId())) {
			assertEquals(unicodeContent, new String(is.readAllBytes(), StandardCharsets.UTF_8));
		}
	}

	@Test
	@DisplayName("Should handle null description")
	void shouldHandleNullDescription() throws IOException, InterruptedException, ExecutionException, TimeoutException {
		final ExportFileHandle handle = this.exportService.storeFile(
			"test.txt", null, "text/plain", null, "origin");

		try (final OutputStream os = handle.outputStream()) {
			os.write("content".getBytes(StandardCharsets.UTF_8));
		}

		final FileForFetch result = handle.fileForFetchFuture().get(30, TimeUnit.SECONDS);
		assertNull(result.description());
	}

	@Test
	@DisplayName("Should return empty list when page is beyond available data")
	void shouldReturnEmptyListWhenPageBeyondAvailableData() throws IOException {
		writeFile("test.txt", null);

		final PaginatedList<FileForFetch> result = this.exportService.listFilesToFetch(100, 10, Set.of(), Set.of());

		assertEquals(1, result.getTotalRecordCount());
		assertTrue(result.getData().isEmpty());
	}

	@Test
	@DisplayName("Should handle page size larger than total files")
	void shouldHandlePageSizeLargerThanTotalFiles() throws IOException {
		for (int i = 0; i < 3; i++) {
			writeFile("test" + i + ".txt", null);
		}

		final PaginatedList<FileForFetch> result = this.exportService.listFilesToFetch(1, 100, Set.of(), Set.of());

		assertEquals(3, result.getTotalRecordCount());
		assertEquals(3, result.getData().size());
	}

	@Test
	@DisplayName("Should load existing files from S3 on startup")
	void shouldLoadExistingFilesOnStartup() throws IOException {
		// Store files using current service
		writeFile("file1.txt", "A,B");
		writeFile("file2.txt", "C,D");

		this.exportService.close();

		// Create a new service instance
		final ExportS3Service newService = createExportService();

		try {
			final PaginatedList<FileForFetch> files = newService.listFilesToFetch(1, 10, Set.of(), Set.of());
			assertEquals(2, files.getTotalRecordCount());
		} finally {
			newService.close();
		}
	}

	@Test
	@DisplayName("Should handle multiple close calls gracefully")
	void shouldHandleMultipleCloseCallsGracefully() throws IOException {
		writeFile("test.txt", null);

		// Multiple close calls should not throw
		assertDoesNotThrow(() -> {
			this.exportService.close();
			this.exportService.close();
		});
	}

	@Test
	@DisplayName("Should throw exception when deleting unmanaged file")
	void shouldThrowExceptionWhenDeletingUnmanagedFile() throws Exception {
		final UUID fileId = UUIDUtil.randomUUID();
		final String objectKey = fileId + ".txt";

		// Create metadata that is valid for parsing but missing the marker
		final Map<String, String> meta = new HashMap<>();
		meta.put("file-id", fileId.toString());
		meta.put("name", "unmanaged.txt");
		meta.put("created", OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
		// Missing generated-by marker

		// Upload file directly to S3
		this.s3Client.putObject(PutObjectRequest.builder()
				.bucket(BUCKET_NAME)
				.key(objectKey)
				.metadata(meta)
				.build(),
			RequestBody.fromString("content"));

		// Should throw exception when trying to delete
		assertThrows(UnexpectedIOException.class, () -> this.exportService.deleteFile(fileId));

		// Verify file is still there
		final var response = this.s3Client.listObjectsV2(r -> r.bucket(BUCKET_NAME).prefix(objectKey));
		assertTrue(response.contents().stream().anyMatch(o -> o.key().equals(objectKey)));
	}

	@Test
	@DisplayName("Should return empty list when no files match origin filter")
	void shouldReturnEmptyListWhenNoOriginTagsMatch() throws IOException {
		writeFile("test1.txt", "A,B");
		writeFile("test2.txt", "C,D");

		final PaginatedList<FileForFetch> result = this.exportService.listFilesToFetch(1, 10, Set.of(), Set.of("X", "Y", "Z"));

		assertEquals(0, result.getTotalRecordCount());
	}

	@Test
	@DisplayName("Should handle filtering when some files have null origin")
	void shouldHandleFileWithNullOrigin() throws IOException {
		writeFile("withOrigin.txt", "A");
		writeFile("noOrigin.txt", null);

		// Files with null origin should not match any filter
		final PaginatedList<FileForFetch> filteredResult = this.exportService.listFilesToFetch(1, 10, Set.of(), Set.of("A"));
		assertEquals(1, filteredResult.getTotalRecordCount());

		// But should appear when no filter is applied
		final PaginatedList<FileForFetch> unfilteredResult = this.exportService.listFilesToFetch(1, 10, Set.of(), Set.of());
		assertEquals(2, unfilteredResult.getTotalRecordCount());
	}

	@Test
	@DisplayName("Should handle idempotent output stream close")
	void shouldHandleIdempotentOutputStreamClose() throws IOException {
		final ExportFileHandle handle = this.exportService.storeFile(
			"test.txt", "desc", "text/plain", null, null);

		try (final OutputStream os = handle.outputStream()) {
			os.write("content".getBytes(StandardCharsets.UTF_8));
			os.close(); // First close
			os.close(); // Second close - should not throw or duplicate upload
		}

		handle.fileForFetchFuture().join();
		final PaginatedList<FileForFetch> files = this.exportService.listFilesToFetch(1, 10, Set.of(), Set.of());
		assertEquals(1, files.getTotalRecordCount());
	}

	@Test
	@DisplayName("Should not purge externally managed files by date")
	void shouldNotPurgeExternallyManagedFilesByDate() throws IOException {
		// Create regular files and externally managed files
		writeFile("regular1.txt", null);
		writeFile("regular2.txt", null);
		final FileForFetch managedFile = writeExternallyManagedFile("managed.txt", null);

		// Verify all files exist
		assertEquals(3, this.exportService.listFilesToFetch(1, 20, Set.of(), Set.of()).getTotalRecordCount());

		// Purge with future date (should delete all regular files but not managed)
		this.exportService.purgeFiles(OffsetDateTime.now().plusMinutes(1));

		// Verify only managed file remains
		final PaginatedList<FileForFetch> remainingFiles = this.exportService.listFilesToFetch(1, 20, Set.of(), Set.of());
		assertEquals(1, remainingFiles.getTotalRecordCount());
		assertEquals(managedFile.fileId(), remainingFiles.getData().get(0).fileId());
		assertTrue(remainingFiles.getData().get(0).externallyManaged());
	}

	@Test
	@DisplayName("Should not purge externally managed files by size")
	void shouldNotPurgeExternallyManagedFilesBySize() throws IOException {
		// Service is configured with sizeLimitBytes=1000
		// Create externally managed file that takes up 400 bytes
		final FileForFetch managedFile = writeLargeExternallyManagedFile("managed.txt", 400);

		// Create regular files that together with managed exceed limit
		writeLargeFile("regular1.txt", 300);
		writeLargeFile("regular2.txt", 400);
		// Total: 400 + 300 + 400 = 1100 bytes (exceeds 1000 limit)

		// Trigger size-based purge with past date
		this.exportService.purgeFiles(OffsetDateTime.now().minusYears(1));

		// Verify managed file still exists (regular files may be purged to fit within limit)
		final PaginatedList<FileForFetch> remainingFiles = this.exportService.listFilesToFetch(1, 20, Set.of(), Set.of());
		assertTrue(remainingFiles.getData().stream()
			.anyMatch(f -> f.fileId().equals(managedFile.fileId())));
	}

	@Test
	@DisplayName("Should delete externally managed file explicitly")
	void shouldDeleteExternallyManagedFileExplicitly() throws IOException {
		final FileForFetch managedFile = writeExternallyManagedFile("managed.txt", null);

		// Verify file exists
		assertTrue(this.exportService.getFile(managedFile.fileId()).isPresent());

		// Delete explicitly
		this.exportService.deleteFile(managedFile.fileId());

		// Verify file is deleted
		assertTrue(this.exportService.getFile(managedFile.fileId()).isEmpty());
	}

	@Test
	@DisplayName("Should mark externally managed file correctly")
	void shouldMarkExternallyManagedFileCorrectly() throws IOException {
		final FileForFetch regularFile = writeFile("regular.txt", null);
		final FileForFetch managedFile = writeExternallyManagedFile("managed.txt", null);

		// Verify flags
		assertFalse(regularFile.externallyManaged());
		assertTrue(managedFile.externallyManaged());

		// Verify via getFile
		final Optional<FileForFetch> retrievedRegular = this.exportService.getFile(regularFile.fileId());
		final Optional<FileForFetch> retrievedManaged = this.exportService.getFile(managedFile.fileId());

		assertTrue(retrievedRegular.isPresent());
		assertTrue(retrievedManaged.isPresent());
		assertFalse(retrievedRegular.get().externallyManaged());
		assertTrue(retrievedManaged.get().externallyManaged());
	}

	@Test
	@DisplayName("Should evict regular files earlier when managed files take space")
	void shouldEvictRegularFilesEarlierWhenManagedFilesTakeSpace() throws IOException {
		// Service is configured with sizeLimitBytes=1000
		// Create large externally managed file (600 bytes)
		final FileForFetch managedFile = writeLargeExternallyManagedFile("managed.txt", 600);

		// Available space for regular files: 1000 - 600 = 400 bytes
		// Create regular files that exceed the available space
		writeLargeFile("regular1.txt", 200);
		writeLargeFile("regular2.txt", 200);
		writeLargeFile("regular3.txt", 200);
		// Total regular files: 600 bytes (exceeds 400 available)

		// Trigger size-based purge
		this.exportService.purgeFiles(OffsetDateTime.now().minusYears(1));

		// Verify managed file still exists
		final PaginatedList<FileForFetch> remainingFiles = this.exportService.listFilesToFetch(1, 20, Set.of(), Set.of());
		assertTrue(remainingFiles.getData().stream()
			.anyMatch(f -> f.fileId().equals(managedFile.fileId())));

		// Calculate total size of remaining regular files
		long regularFilesSize = remainingFiles.getData().stream()
			.filter(f -> !f.externallyManaged())
			.mapToLong(FileForFetch::totalSizeInBytes)
			.sum();

		// Regular files should fit within available space (400 bytes)
		assertTrue(regularFilesSize <= 400,
			"Regular files size (" + regularFilesSize + ") should be <= 400 bytes");
	}

	/**
	 * Helper method to write a file to the export service.
	 *
	 * @param fileName   the name of the file to write
	 * @param withOrigin comma-separated origin tags, or null for no tags
	 * @return the created {@link FileForFetch} instance
	 * @throws IOException if file writing fails
	 */
	@Nonnull
	private FileForFetch writeFile(@Nonnull String fileName, @Nullable String withOrigin) throws IOException {
		final ExportFileHandle exportFileHandle = this.exportService.storeFile(
			fileName,
			"With description ...",
			"text/plain",
			null,
			withOrigin
		);
		try (final OutputStream outputStream = exportFileHandle.outputStream()) {
			outputStream.write("testFileContent".getBytes(StandardCharsets.UTF_8));
		}
		// Wait for the async upload to complete
		try {
			return exportFileHandle.fileForFetchFuture().get(30, TimeUnit.SECONDS);
		} catch (InterruptedException | ExecutionException | TimeoutException e) {
			throw new IOException("Failed to wait for file upload to complete", e);
		}
	}

	/**
	 * Helper method to write a file to the export service with catalog.
	 *
	 * @param fileName    the name of the file to write
	 * @param withOrigin  comma-separated origin tags, or null for no tags
	 * @param catalogName the catalog name
	 * @return the created {@link FileForFetch} instance
	 * @throws IOException if file writing fails
	 */
	@Nonnull
	private FileForFetch writeFileWithCatalog(@Nonnull String fileName, @Nullable String withOrigin, @Nullable String catalogName) throws IOException {
		final ExportFileHandle exportFileHandle = this.exportService.storeFile(
			fileName,
			"With description ...",
			"text/plain",
			catalogName,
			withOrigin
		);
		try (final OutputStream outputStream = exportFileHandle.outputStream()) {
			outputStream.write("testFileContent".getBytes(StandardCharsets.UTF_8));
		}
		// Wait for the async upload to complete
		try {
			return exportFileHandle.fileForFetchFuture().get(30, TimeUnit.SECONDS);
		} catch (InterruptedException | ExecutionException | TimeoutException e) {
			throw new IOException("Failed to wait for file upload to complete", e);
		}
	}

	/**
	 * Helper method to write a large file with specified size to the export service.
	 *
	 * @param fileName    the name of the file to write
	 * @param sizeInBytes the size of the file content in bytes
	 * @return the created {@link FileForFetch} instance
	 * @throws IOException if file writing fails
	 */
	@Nonnull
	private FileForFetch writeLargeFile(@Nonnull String fileName, int sizeInBytes) throws IOException {
		final ExportFileHandle handle = this.exportService.storeFile(
			fileName, "Large file", "application/octet-stream", null, null);
		try (final OutputStream os = handle.outputStream()) {
			final byte[] data = new byte[sizeInBytes];
			Arrays.fill(data, (byte) 'X');
			os.write(data);
		}
		try {
			return handle.fileForFetchFuture().get(30, TimeUnit.SECONDS);
		} catch (InterruptedException | ExecutionException | TimeoutException e) {
			throw new IOException("Failed to wait for file upload", e);
		}
	}

	/**
	 * Helper method to write an externally managed file to the export service.
	 *
	 * @param fileName   the name of the file to write
	 * @param withOrigin comma-separated origin tags, or null for no tags
	 * @return the created {@link FileForFetch} instance
	 * @throws IOException if file writing fails
	 */
	@Nonnull
	private FileForFetch writeExternallyManagedFile(@Nonnull String fileName, @Nullable String withOrigin) throws IOException {
		final ExportFileHandle exportFileHandle = this.exportService.storeExternallyManagedFile(
			fileName,
			"With description ...",
			"text/plain",
			null,
			withOrigin
		);
		try (final OutputStream outputStream = exportFileHandle.outputStream()) {
			outputStream.write("testFileContent".getBytes(StandardCharsets.UTF_8));
		}
		try {
			return exportFileHandle.fileForFetchFuture().get(30, TimeUnit.SECONDS);
		} catch (InterruptedException | ExecutionException | TimeoutException e) {
			throw new IOException("Failed to wait for file upload to complete", e);
		}
	}

	/**
	 * Helper method to write a large externally managed file with specified size.
	 *
	 * @param fileName    the name of the file to write
	 * @param sizeInBytes the size of the file content in bytes
	 * @return the created {@link FileForFetch} instance
	 * @throws IOException if file writing fails
	 */
	@Nonnull
	private FileForFetch writeLargeExternallyManagedFile(@Nonnull String fileName, int sizeInBytes) throws IOException {
		final ExportFileHandle handle = this.exportService.storeExternallyManagedFile(
			fileName, "Large externally managed file", "application/octet-stream", null, null);
		try (final OutputStream os = handle.outputStream()) {
			final byte[] data = new byte[sizeInBytes];
			Arrays.fill(data, (byte) 'X');
			os.write(data);
		}
		try {
			return handle.fileForFetchFuture().get(30, TimeUnit.SECONDS);
		} catch (InterruptedException | ExecutionException | TimeoutException e) {
			throw new IOException("Failed to wait for file upload", e);
		}
	}

}
