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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Lists;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.exception.FileForFetchNotFoundException;
import io.evitadb.api.file.FileForFetch;
import io.evitadb.core.executor.ImmediateScheduledThreadPoolExecutor;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.core.management.FileManagementService;
import io.evitadb.dataType.PaginatedList;
import io.evitadb.export.s3.configuration.S3ExportOptions;
import io.evitadb.spi.export.model.ExportFileHandle;
import io.evitadb.utils.UUIDUtil;
import io.minio.CloseableIterator;
import io.minio.Result;
import io.minio.messages.NotificationRecords;
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
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
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

	/**
	 * Builds a {@link NotificationRecords} instance by deserializing a JSON string
	 * with the specified event type and object key.
	 *
	 * @param eventType the S3 event type string (e.g. "s3:ObjectCreated:*")
	 * @param objectKey the S3 object key
	 * @return the deserialized notification records
	 * @throws Exception if JSON parsing fails
	 */
	@Nonnull
	private static NotificationRecords buildNotificationRecords(
		@Nonnull String eventType,
		@Nonnull String objectKey
	) throws Exception {
		final String json = "{\"Records\":[{\"eventName\":\"" + eventType
			+ "\",\"s3\":{\"object\":{\"key\":\"" + objectKey + "\"}}}]}";
		final ObjectMapper mapper = new ObjectMapper();
		return mapper.readValue(json, NotificationRecords.class);
	}

	/**
	 * Injects a fake notification iterator into the service's private
	 * `notificationIterator` field via reflection.
	 *
	 * @param service  the export service instance
	 * @param iterator the fake iterator to inject
	 * @throws Exception if reflection access fails
	 */
	private static void injectNotificationIterator(
		@Nonnull ExportS3Service service,
		@Nonnull CloseableIterator<Result<NotificationRecords>> iterator
	) throws Exception {
		final Field field = ExportS3Service.class.getDeclaredField("notificationIterator");
		field.setAccessible(true);
		field.set(service, iterator);
	}

	/**
	 * Invokes the private `refreshFiles()` method on the given service via reflection.
	 *
	 * @param service the export service instance
	 * @return the return value of refreshFiles() (the iterator, or null)
	 * @throws Exception if reflection invocation fails
	 */
	@Nullable
	private static Object invokeRefreshFiles(@Nonnull ExportS3Service service) throws Exception {
		final Method method = ExportS3Service.class.getDeclaredMethod("refreshFiles");
		method.setAccessible(true);
		return method.invoke(service);
	}

	/**
	 * Invokes the private static `normalizeMetadataNames` method via reflection.
	 *
	 * @param input the metadata map to normalize
	 * @return the normalized metadata map
	 * @throws Exception if reflection invocation fails
	 */
	@Nonnull
	@SuppressWarnings("unchecked")
	private static Map<String, String> invokeNormalizeMetadataNames(
		@Nonnull Map<String, String> input
	) throws Exception {
		final Method method = ExportS3Service.class.getDeclaredMethod("normalizeMetadataNames", Map.class);
		method.setAccessible(true);
		return (Map<String, String>) method.invoke(null, input);
	}

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
		final PaginatedList<FileForFetch> files = this.exportService.listFilesToFetch(1, Integer.MAX_VALUE, Set.of());
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

		final PaginatedList<FileForFetch> fileForFetches = this.exportService.listFilesToFetch(1, 5, Set.of());
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
			this.exportService.listFilesToFetch(6, 5, Set.of())
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
			this.exportService.listFilesToFetch(1, 10, Set.of("A"))
				.getData().stream().map(FileForFetch::name).toArray(String[]::new)
		);
	}

	@Test
	@DisplayName("Should delete file from S3 bucket")
	void shouldDeleteFile() throws IOException {
		for (int i = 0; i < 5; i++) {
			writeFile("testFile" + i + ".txt", null);
		}

		final PaginatedList<FileForFetch> filesBeforeDelete = this.exportService.listFilesToFetch(1, 20, Set.of());
		assertEquals(5, filesBeforeDelete.getTotalRecordCount());

		this.exportService.deleteFile(filesBeforeDelete.getData().get(0).fileId());
		this.exportService.deleteFile(filesBeforeDelete.getData().get(3).fileId());

		assertEquals(3, this.exportService.listFilesToFetch(1, 20, Set.of()).getTotalRecordCount());
	}

	@Test
	@DisplayName("Should not fail when deleting file already removed by third party")
	void shouldNotFailWhenDeletingAlreadyDeletedObject() throws IOException {
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

		// The file must be removed from the local cache as well
		assertTrue(this.exportService.getFile(storedFile.fileId()).isEmpty());
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
		final int totalFilesBeforePurge = this.exportService.listFilesToFetch(1, 20, Set.of()).getTotalRecordCount();
		assertEquals(10, totalFilesBeforePurge);

		// Purge the files older than 2 minutes ago (should keep all since they're fresh)
		this.exportService.purgeFiles(OffsetDateTime.now().minusMinutes(2));

		// Check files after purging - should still have files since threshold is in the past
		final int totalFilesAfterPurge = this.exportService.listFilesToFetch(1, 20, Set.of()).getTotalRecordCount();
		assertEquals(10, totalFilesAfterPurge);

		// Purge files created before now (should remove all)
		this.exportService.purgeFiles(OffsetDateTime.now().plusMinutes(1));

		// Check files after purging
		final int totalFilesAfterPurge2 = this.exportService.listFilesToFetch(1, 20, Set.of()).getTotalRecordCount();
		assertEquals(0, totalFilesAfterPurge2);
	}

	@Test
	@DisplayName("Should throw exception when fetching non-existent file")
	void shouldThrowExceptionWhenFetchingNonExistentFile() {
		final UUID nonExistentId = UUIDUtil.randomUUID();
		assertThrows(
			FileForFetchNotFoundException.class,
			() -> this.exportService.fetchFile(nonExistentId)
		);
	}

	@Test
	@DisplayName("Should throw exception when deleting non-existent file")
	void shouldThrowExceptionWhenDeletingNonExistentFile() {
		final UUID nonExistentId = UUIDUtil.randomUUID();
		assertThrows(
			FileForFetchNotFoundException.class,
			() -> this.exportService.deleteFile(nonExistentId)
		);
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
		final PaginatedList<FileForFetch> remaining = this.exportService.listFilesToFetch(1, 20, Set.of());

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
			"empty.txt", "Empty file", "text/plain", null);

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

		final PaginatedList<FileForFetch> files = this.exportService.listFilesToFetch(1, 10, Set.of());

		assertEquals(1, files.getTotalRecordCount());
		// Name may be sanitized by FileUtils.convertToSupportedName()
		assertNotNull(files.getData().get(0).name());
	}

	@Test
	@DisplayName("Should handle file with unicode content")
	void shouldHandleFileWithUnicodeContent() throws IOException, InterruptedException, ExecutionException, TimeoutException {
		final String unicodeContent = "Hello \u4e16\u754c \ud83d\ude00";

		final ExportFileHandle handle = this.exportService.storeFile(
			"unicode.txt", "Description with \u00e9\u00e8", "text/plain", null);

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
			"test.txt", null, "text/plain", "origin");

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

		final PaginatedList<FileForFetch> result = this.exportService.listFilesToFetch(100, 10, Set.of());

		assertEquals(1, result.getTotalRecordCount());
		assertTrue(result.getData().isEmpty());
	}

	@Test
	@DisplayName("Should handle page size larger than total files")
	void shouldHandlePageSizeLargerThanTotalFiles() throws IOException {
		for (int i = 0; i < 3; i++) {
			writeFile("test" + i + ".txt", null);
		}

		final PaginatedList<FileForFetch> result = this.exportService.listFilesToFetch(1, 100, Set.of());

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
		newService.awaitInitialization();

		try {
			final PaginatedList<FileForFetch> files = newService.listFilesToFetch(1, 10, Set.of());
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
	@DisplayName("Should return empty list when no files match origin filter")
	void shouldReturnEmptyListWhenNoOriginTagsMatch() throws IOException {
		writeFile("test1.txt", "A,B");
		writeFile("test2.txt", "C,D");

		final PaginatedList<FileForFetch> result = this.exportService.listFilesToFetch(1, 10, Set.of("X", "Y", "Z"));

		assertEquals(0, result.getTotalRecordCount());
	}

	@Test
	@DisplayName("Should handle filtering when some files have null origin")
	void shouldHandleFileWithNullOrigin() throws IOException {
		writeFile("withOrigin.txt", "A");
		writeFile("noOrigin.txt", null);

		// Files with null origin should not match any filter
		final PaginatedList<FileForFetch> filteredResult = this.exportService.listFilesToFetch(1, 10, Set.of("A"));
		assertEquals(1, filteredResult.getTotalRecordCount());

		// But should appear when no filter is applied
		final PaginatedList<FileForFetch> unfilteredResult = this.exportService.listFilesToFetch(1, 10, Set.of());
		assertEquals(2, unfilteredResult.getTotalRecordCount());
	}

	@Test
	@DisplayName("Should handle idempotent output stream close")
	void shouldHandleIdempotentOutputStreamClose() throws IOException {
		final ExportFileHandle handle = this.exportService.storeFile(
			"test.txt", "desc", "text/plain", null);

		try (final OutputStream os = handle.outputStream()) {
			os.write("content".getBytes(StandardCharsets.UTF_8));
			os.close(); // First close
			os.close(); // Second close - should not throw or duplicate upload
		}

		handle.fileForFetchFuture().join();
		final PaginatedList<FileForFetch> files = this.exportService.listFilesToFetch(1, 10, Set.of());
		assertEquals(1, files.getTotalRecordCount());
	}

	@Test
	@DisplayName("Should remove file from cache when object-removed event is received")
	void shouldRemoveFileFromCacheWhenObjectRemovedEventReceived() throws Exception {
		final FileForFetch stored = writeFile("removable.txt", "A");
		final String objectKey = stored.fileId() + ".txt";

		final NotificationRecords records = buildNotificationRecords(
			"s3:ObjectRemoved:*", objectKey
		);
		injectNotificationIterator(
			this.exportService,
			new FakeNotificationIterator(List.of(new Result<>(records)).iterator())
		);
		invokeRefreshFiles(this.exportService);

		assertTrue(
			this.exportService.getFile(stored.fileId()).isEmpty(),
			"File should be removed from cache after OBJECT_REMOVED event"
		);
	}

	@Test
	@DisplayName("Should add file to cache when object-created event is received")
	void shouldAddFileToCacheWhenObjectCreatedEventReceived() throws Exception {
		final UUID fileId = UUIDUtil.randomUUID();
		final String objectKey = fileId + ".txt";
		final OffsetDateTime created = OffsetDateTime.now();

		uploadDirectlyToS3(
			objectKey, "external-content".getBytes(StandardCharsets.UTF_8), Map.of(
				"file-id", fileId.toString(),
				"name", "external.txt",
				"description", "Uploaded externally",
				"content-type", "text/plain",
				"created", created.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
				"origin", "EXT"
			)
		);

		final NotificationRecords records = buildNotificationRecords(
			"s3:ObjectCreated:*", objectKey
		);
		injectNotificationIterator(
			this.exportService,
			new FakeNotificationIterator(List.of(new Result<>(records)).iterator())
		);
		invokeRefreshFiles(this.exportService);

		final Optional<FileForFetch> fetched = this.exportService.getFile(fileId);
		assertTrue(fetched.isPresent(), "File should appear in cache after OBJECT_CREATED event");
		assertEquals("external.txt", fetched.get().name());
		assertEquals("Uploaded externally", fetched.get().description());
		assertArrayEquals(new String[]{"EXT"}, fetched.get().origin());
	}

	@Test
	@DisplayName("Should update existing cache entry when object-created event received for same file")
	void shouldUpdateExistingCacheEntryWhenObjectCreatedEventReceivedForSameFile() throws Exception {
		final FileForFetch stored = writeFile("original.txt", "A");
		final String objectKey = stored.fileId() + ".txt";

		// Re-upload with different name in metadata
		uploadDirectlyToS3(
			objectKey, "updated-content".getBytes(StandardCharsets.UTF_8), Map.of(
				"file-id", stored.fileId().toString(),
				"name", "updated.txt",
				"description", "Updated externally",
				"content-type", "text/plain",
				"created", stored.created().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
				"origin", "A"
			)
		);

		final NotificationRecords records = buildNotificationRecords(
			"s3:ObjectCreated:*", objectKey
		);
		injectNotificationIterator(
			this.exportService,
			new FakeNotificationIterator(List.of(new Result<>(records)).iterator())
		);
		invokeRefreshFiles(this.exportService);

		final Optional<FileForFetch> fetched = this.exportService.getFile(stored.fileId());
		assertTrue(fetched.isPresent(), "File should still be in cache");
		assertEquals("updated.txt", fetched.get().name(), "Name should be updated");

		// Verify no duplicates
		final PaginatedList<FileForFetch> all = this.exportService.listFilesToFetch(1, 100, Set.of());
		final long count = all.getData().stream()
			.filter(f -> f.fileId().equals(stored.fileId()))
			.count();
		assertEquals(1, count, "There should be no duplicate entries");
	}

	@Test
	@DisplayName("Should place new file at beginning of cache on created event")
	void shouldPlaceNewFileAtBeginningOfCacheOnCreatedEvent() throws Exception {
		writeFile("first.txt", null);
		writeFile("second.txt", null);

		final UUID newFileId = UUIDUtil.randomUUID();
		final String objectKey = newFileId + ".txt";
		uploadDirectlyToS3(
			objectKey, "new-content".getBytes(StandardCharsets.UTF_8), Map.of(
				"file-id", newFileId.toString(),
				"name", "newest.txt",
				"description", "",
				"content-type", "text/plain",
				"created", OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
				"origin", ""
			)
		);

		final NotificationRecords records = buildNotificationRecords(
			"s3:ObjectCreated:*", objectKey
		);
		injectNotificationIterator(
			this.exportService,
			new FakeNotificationIterator(List.of(new Result<>(records)).iterator())
		);
		invokeRefreshFiles(this.exportService);

		final PaginatedList<FileForFetch> files = this.exportService.listFilesToFetch(1, 10, Set.of());
		assertEquals(
			"newest.txt", files.getData().get(0).name(),
			"Newly created file should be at the beginning of the list"
		);
	}

	@Test
	@DisplayName("Should handle multiple events in single notification batch")
	void shouldHandleMultipleEventsInSingleNotificationBatch() throws Exception {
		final FileForFetch toRemove = writeFile("toRemove.txt", null);
		final String removeKey = toRemove.fileId() + ".txt";

		// Upload 2 objects directly
		final UUID id1 = UUIDUtil.randomUUID();
		final UUID id2 = UUIDUtil.randomUUID();
		uploadDirectlyToS3(
			id1 + ".txt", "c1".getBytes(StandardCharsets.UTF_8), Map.of(
				"file-id", id1.toString(),
				"name", "ext1.txt",
				"description", "",
				"content-type", "text/plain",
				"created", OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
				"origin", ""
			)
		);
		uploadDirectlyToS3(
			id2 + ".txt", "c2".getBytes(StandardCharsets.UTF_8), Map.of(
				"file-id", id2.toString(),
				"name", "ext2.txt",
				"description", "",
				"content-type", "text/plain",
				"created", OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
				"origin", ""
			)
		);

		// Build a notification with 3 events (2 created + 1 removed)
		final String json = "{\"Records\":["
			+ "{\"eventName\":\"s3:ObjectCreated:*\",\"s3\":{\"object\":{\"key\":\"" + id1 + ".txt\"}}},"
			+ "{\"eventName\":\"s3:ObjectCreated:*\",\"s3\":{\"object\":{\"key\":\"" + id2 + ".txt\"}}},"
			+ "{\"eventName\":\"s3:ObjectRemoved:*\",\"s3\":{\"object\":{\"key\":\"" + removeKey + "\"}}}"
			+ "]}";
		final ObjectMapper mapper = new ObjectMapper();
		final NotificationRecords records = mapper.readValue(json, NotificationRecords.class);

		injectNotificationIterator(
			this.exportService,
			new FakeNotificationIterator(List.of(new Result<>(records)).iterator())
		);
		invokeRefreshFiles(this.exportService);

		assertTrue(this.exportService.getFile(id1).isPresent(), "First created file should be in cache");
		assertTrue(this.exportService.getFile(id2).isPresent(), "Second created file should be in cache");
		assertTrue(
			this.exportService.getFile(toRemove.fileId()).isEmpty(),
			"Removed file should no longer be in cache"
		);
	}

	@Test
	@DisplayName("Should ignore created event when object has no metadata")
	void shouldIgnoreCreatedEventWhenObjectHasNoMetadata() throws Exception {
		final int countBefore = this.exportService.listFilesToFetch(1, 100, Set.of()).getTotalRecordCount();

		// Upload object without user metadata
		final String objectKey = UUIDUtil.randomUUID() + ".txt";
		this.s3Client.putObject(
			PutObjectRequest.builder()
				.bucket(BUCKET_NAME)
				.key(objectKey)
				.contentType("text/plain")
				.build(),
			RequestBody.fromBytes("no-meta".getBytes(StandardCharsets.UTF_8))
		);

		final NotificationRecords records = buildNotificationRecords(
			"s3:ObjectCreated:*", objectKey
		);
		injectNotificationIterator(
			this.exportService,
			new FakeNotificationIterator(List.of(new Result<>(records)).iterator())
		);
		invokeRefreshFiles(this.exportService);

		final int countAfter = this.exportService.listFilesToFetch(1, 100, Set.of()).getTotalRecordCount();
		assertEquals(
			countBefore, countAfter,
			"Cache should remain unchanged when object has no required metadata"
		);
	}

	@Test
	@DisplayName("Should ignore removed event for invalid object key")
	void shouldIgnoreRemovedEventForInvalidObjectKey() throws Exception {
		final FileForFetch stored = writeFile("keeper.txt", null);

		final NotificationRecords records = buildNotificationRecords(
			"s3:ObjectRemoved:*", "not-a-uuid.txt"
		);
		injectNotificationIterator(
			this.exportService,
			new FakeNotificationIterator(List.of(new Result<>(records)).iterator())
		);
		invokeRefreshFiles(this.exportService);

		assertTrue(
			this.exportService.getFile(stored.fileId()).isPresent(),
			"Existing file should remain in cache when invalid object key is processed"
		);
	}

	@Test
	@DisplayName("Should ignore removed event for non-existent file ID")
	void shouldIgnoreRemovedEventForNonExistentFileId() throws Exception {
		final FileForFetch stored = writeFile("existing.txt", null);

		final UUID randomId = UUIDUtil.randomUUID();
		final NotificationRecords records = buildNotificationRecords(
			"s3:ObjectRemoved:*", randomId + ".txt"
		);
		injectNotificationIterator(
			this.exportService,
			new FakeNotificationIterator(List.of(new Result<>(records)).iterator())
		);
		invokeRefreshFiles(this.exportService);

		assertTrue(
			this.exportService.getFile(stored.fileId()).isPresent(),
			"Existing file should remain in cache when event refers to non-existent file"
		);
		assertEquals(1, this.exportService.listFilesToFetch(1, 100, Set.of()).getTotalRecordCount());
	}

	@Test
	@DisplayName("Should strip x-amz-meta- prefix when normalizing metadata names")
	void shouldStripAmzPrefixWhenNormalizingMetadataNames() throws Exception {
		final Map<String, String> input = new HashMap<>();
		input.put("x-amz-meta-file-id", "v1");
		input.put("x-amz-meta-name", "v2");

		final Map<String, String> result = invokeNormalizeMetadataNames(input);

		assertEquals("v1", result.get("file-id"));
		assertEquals("v2", result.get("name"));
		assertFalse(result.containsKey("x-amz-meta-file-id"));
		assertFalse(result.containsKey("x-amz-meta-name"));
	}

	@Test
	@DisplayName("Should return same map when metadata already normalized")
	void shouldReturnSameMapWhenMetadataAlreadyNormalized() throws Exception {
		final Map<String, String> input = new HashMap<>();
		input.put("file-id", "v1");
		input.put("name", "v2");

		final Map<String, String> result = invokeNormalizeMetadataNames(input);

		assertSame(input, result, "Should return exact same map instance when no normalization needed");
	}

	// --- Fallback behavior ---

	@Test
	@DisplayName("Should lowercase and strip prefix for mixed case metadata")
	void shouldLowercaseAndStripPrefixForMixedCaseMetadata() throws Exception {
		final Map<String, String> input = new HashMap<>();
		input.put("X-Amz-Meta-File-Id", "v1");
		input.put("X-Amz-Meta-Name", "v2");

		final Map<String, String> result = invokeNormalizeMetadataNames(input);

		assertEquals("v1", result.get("file-id"));
		assertEquals("v2", result.get("name"));
		assertFalse(result.containsKey("X-Amz-Meta-File-Id"));
		assertFalse(result.containsKey("X-Amz-Meta-Name"));
	}

	@Test
	@DisplayName("Should return null from refreshFiles when notifications unsupported")
	void shouldReturnNullFromRefreshFilesWhenNotificationsUnsupported() throws Exception {
		// Do not inject an iterator — the service will try to connect to S3Mock
		// which does not support listenBucketNotification, causing an exception
		final Object result = invokeRefreshFiles(this.exportService);
		assertNull(result, "refreshFiles should return null when notification listening is unsupported");
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
			fileName, "Large file", "application/octet-stream", null);
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
	 * Uploads an object directly to S3 (bypassing the export service) with user metadata.
	 *
	 * @param objectKey the S3 object key
	 * @param content   the file content bytes
	 * @param metadata  the user metadata to attach
	 */
	private void uploadDirectlyToS3(
		@Nonnull String objectKey,
		@Nonnull byte[] content,
		@Nonnull Map<String, String> metadata
	) {
		this.s3Client.putObject(
			PutObjectRequest.builder()
				.bucket(BUCKET_NAME)
				.key(objectKey)
				.contentType("application/octet-stream")
				.metadata(metadata)
				.build(),
			RequestBody.fromBytes(content)
		);
	}

	/**
	 * A fake {@link CloseableIterator} that returns pre-built notification records
	 * for testing the `refreshFiles()` event processing loop.
	 */
	private record FakeNotificationIterator(@Nonnull Iterator<Result<NotificationRecords>> delegate)
		implements CloseableIterator<Result<NotificationRecords>> {

		@Override
		public boolean hasNext() {
			return this.delegate.hasNext();
		}

		@Override
		public Result<NotificationRecords> next() {
			return this.delegate.next();
		}

		@Override
		public void close() {
			// no-op
		}
	}

}
