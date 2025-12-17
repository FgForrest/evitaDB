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

package io.evitadb.export.s3;

import io.evitadb.api.configuration.ExportOptions;
import io.evitadb.api.exception.FileForFetchNotFoundException;
import io.evitadb.api.file.FileForFetch;
import io.evitadb.core.executor.DelayedAsyncTask;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.core.management.FileManagementService;
import io.evitadb.dataType.PaginatedList;
import io.evitadb.exception.UnexpectedIOException;
import io.evitadb.export.s3.configuration.S3ExportOptions;
import io.evitadb.spi.export.ExportService;
import io.evitadb.spi.export.model.ExportFileHandle;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.FileUtils;
import io.evitadb.utils.IOUtils;
import io.evitadb.utils.UUIDUtil;
import io.minio.*;
import io.minio.BucketExistsArgs.Builder;
import io.minio.http.HttpUtils;
import io.minio.messages.Event;
import io.minio.messages.EventType;
import io.minio.messages.Item;
import io.minio.messages.NotificationRecords;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * Implementation of {@link ExportService} that stores exported files in S3-compatible storage
 * (such as Amazon S3 or MinIO).
 *
 * This implementation uses the MinIO Java client to interact with S3-compatible storage.
 * File metadata is stored as S3 user metadata on each object to avoid separate metadata objects
 * and reduce API calls.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@Slf4j
public class ExportS3Service implements ExportService {

	/**
	 * User metadata key for file ID.
	 */
	private static final String META_FILE_ID = "file-id";

	/**
	 * User metadata key for original file name.
	 */
	private static final String META_NAME = "name";

	/**
	 * User metadata key for file description.
	 */
	private static final String META_DESCRIPTION = "description";

	/**
	 * User metadata key for content type.
	 */
	private static final String META_CONTENT_TYPE = "content-type";

	/**
	 * User metadata key for creation timestamp.
	 */
	private static final String META_CREATED = "created";

	/**
	 * User metadata key for origin tags.
	 */
	private static final String META_ORIGIN = "origin";

	/**
	 * User metadata key for catalog name.
	 */
	private static final String META_CATALOG = "catalog";

	/**
	 * Part size for S3 multipart upload (10MB).
	 */
	private static final long PART_SIZE = 10_485_760L;
	/**
	 *
	 * Delay in seconds between automatic refreshes of the file cache from S3.
	 */
	private static final int REFRESH_DELAY_SECONDS = 600;

	/**
	 * Prefix for user metadata keys in S3 objects.
	 */
	private static final String USER_METADATA_PREFIX = "x-amz-meta-";

	/**
	 * S3-specific export configuration options containing all settings.
	 */
	private final S3ExportOptions s3Options;

	/**
	 * MinIO async client for S3 operations.
	 */
	private final MinioAsyncClient minioClient;
	/**
	 * System file management service.
	 */
	private final FileManagementService fileManagementService;
	/**
	 * Task that periodically refreshes the file cache.
	 */
	private final DelayedAsyncTask refreshTask;
	/**
	 * Task that periodically purges old files from the storage.
	 */
	private final DelayedAsyncTask purgeTask;
	/**
	 * Iterator for bucket notifications to allow explicit close on shutdown.
	 */
	@Nullable
	private volatile CloseableIterator<Result<NotificationRecords>> notificationIterator;
	/**
	 * Future that completes when the initial loading of files is done.
	 */
	private final CompletableFuture<Boolean> initializationFuture = new CompletableFuture<>();
	/**
	 * Cached list of files to fetch, sorted by creation date (newest first).
	 */
	private volatile CopyOnWriteArrayList<FileForFetch> files;

	/**
	 * Parses user metadata into a {@link FileForFetch} instance.
	 *
	 * @param userMetadata the S3 object user metadata
	 * @param size         the object size in bytes
	 * @return parsed FileForFetch or null if metadata is invalid
	 */
	@Nullable
	private static FileForFetch parseFileForFetch(@Nonnull Map<String, String> userMetadata, long size) {
		try {
			final String fileIdStr = userMetadata.get(META_FILE_ID);
			final String name = userMetadata.get(META_NAME);
			final String description = userMetadata.get(META_DESCRIPTION);
			final String contentType = userMetadata.get(META_CONTENT_TYPE);
			final String createdStr = userMetadata.get(META_CREATED);
			final String originStr = userMetadata.get(META_ORIGIN);
			final String catalogName = userMetadata.get(META_CATALOG);

			if (fileIdStr == null || name == null || createdStr == null) {
				return null;
			}

			final UUID fileId = UUID.fromString(fileIdStr);
			final OffsetDateTime created = OffsetDateTime.parse(createdStr, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
			final String[] origin = (originStr == null || originStr.isBlank())
				? null
				: Arrays.stream(originStr.split(",")).map(String::trim).toArray(String[]::new);

			return new FileForFetch(
				fileId,
				name,
				(description == null || description.isBlank()) ? null : description,
				contentType != null ? contentType : "application/octet-stream",
				size,
				created,
				origin,
				(catalogName == null || catalogName.isBlank()) ? null : catalogName
			);
		} catch (Exception e) {
			log.error("Failed to parse file metadata", e);
			return null;
		}
	}

	/**
	 * Validates whether the given region string is non-null and not blank.
	 *
	 * @param region the region string to validate; may be null
	 * @return {@code true} if the region is non-null and not blank, {@code false} otherwise
	 */
	private static boolean isRegionValid(@Nullable String region) {
		return region != null && !region.isBlank();
	}

	/**
	 * Closes the given {@link Closeable} object if it is not null.
	 *
	 * @param object the {@link Closeable} object to be closed; may be null
	 * @throws IOException if an I/O error occurs during the closing of the object
	 */
	private static void closeIfNotNull(@Nullable Closeable object) throws IOException {
		if (object != null) {
			object.close();
		}
	}

	/**
	 * Creates a new instance of {@link ExportS3Service}.
	 *
	 * @param exportOptions         the export configuration options
	 * @param scheduler             the scheduler for background tasks
	 * @param fileManagementService file management service
	 */
	public ExportS3Service(
		@Nonnull ExportOptions exportOptions,
		@Nonnull Scheduler scheduler,
		@Nonnull FileManagementService fileManagementService
	) {
		if (!(exportOptions instanceof S3ExportOptions)) {
			throw new IllegalArgumentException(
				"ExportS3Service requires S3ExportOptions but got: " + exportOptions.getClass().getSimpleName()
			);
		}
		this.s3Options = (S3ExportOptions) exportOptions;
		this.fileManagementService = fileManagementService;

		// Initialize MinIO async client
		final MinioAsyncClient.Builder clientBuilder = MinioAsyncClient.builder()
			.endpoint(this.s3Options.getEndpointOrThrowException())
			.credentials(
				this.s3Options.getAccessKeyOrThrowException(),
				this.s3Options.getSecretKeyOrThrowException()
			)
			.httpClient(
				HttpUtils.newDefaultHttpClient(
					this.s3Options.getRequestTimeoutInMillis(),
					this.s3Options.getRequestTimeoutInMillis(),
					this.s3Options.getRequestTimeoutInMillis()
				),
				true
			);

		if (isRegionValid(this.s3Options.getRegion())) {
			clientBuilder.region(this.s3Options.getRegion());
		}

		this.minioClient = clientBuilder.build();

		// Ensure bucket exists
		ensureBucketExists();

		// Load existing files from S3 once
		this.files = new CopyOnWriteArrayList<>();

		// Schedule automatic purging task
		this.purgeTask = new DelayedAsyncTask(
			null,
			"S3 export file service purging task",
			scheduler,
			this::purgeFiles,
			5, TimeUnit.MINUTES
		);
		this.purgeTask.schedule();

		// Schedule automatic refresh task
		this.refreshTask = new DelayedAsyncTask(
			null,
			"S3 export file service refresh task",
			scheduler,
			this::loadOrRefreshFiles,
			REFRESH_DELAY_SECONDS, TimeUnit.SECONDS
		);
		this.refreshTask.scheduleImmediately();
	}

	/**
	 * Waits for the initialization process of the service to complete.
	 * This method blocks the calling thread until the initializationFuture,
	 * representing the asynchronous initialization task, is completed.
	 */
	public void awaitInitialization() {
		this.initializationFuture.join();
	}

	@Nonnull
	@Override
	public PaginatedList<FileForFetch> listFilesToFetch(
		int page,
		int pageSize,
		@Nonnull Set<String> catalog,
		@Nonnull Set<String> origin
	) {
		final CopyOnWriteArrayList<FileForFetch> allFiles = getFiles();
		final List<FileForFetch> filteredFiles;

		if (origin.isEmpty()) {
			filteredFiles = new ArrayList<>(allFiles);
		} else {
			final int size = allFiles.size();
			final List<FileForFetch> filtered = new ArrayList<>(size);
			for (final FileForFetch file : allFiles) {
				final String[] fileOrigin = file.origin();
				if (fileOrigin != null) {
					for (final String o : fileOrigin) {
						if (origin.contains(o)) {
							filtered.add(file);
							break;
						}
					}
				}
			}
			filteredFiles = filtered;
		}

		final int firstItem = PaginatedList.getFirstItemNumberForPage(page, pageSize);
		final int totalSize = filteredFiles.size();
		final int endIndex = Math.min(firstItem + pageSize, totalSize);

		final List<FileForFetch> filePage;
		if (firstItem >= totalSize) {
			filePage = List.of();
		} else {
			filePage = filteredFiles.subList(firstItem, endIndex);
		}

		return new PaginatedList<>(
			page, pageSize,
			totalSize,
			filePage
		);
	}

	@Nonnull
	@Override
	public Optional<FileForFetch> getFile(@Nonnull UUID fileId) {
		final CopyOnWriteArrayList<FileForFetch> fileList = getFiles();
		for (final FileForFetch file : fileList) {
			if (file.fileId().equals(fileId)) {
				return Optional.of(file);
			}
		}
		return Optional.empty();
	}

	@Nonnull
	@Override
	public ExportFileHandle storeFile(
		@Nonnull String fileName,
		@Nullable String description,
		@Nonnull String contentType,
		@Nullable String catalog,
		@Nullable String origin
	) {
		final UUID fileId = UUIDUtil.randomUUID();
		final String objectKey = fileId + FileUtils.getFileExtension(fileName).map(it -> "." + it).orElse("");
		final OffsetDateTime created = OffsetDateTime.now();

		// Prepare user metadata
		final Map<String, String> userMetadata = CollectionUtils.createHashMap(8);
		userMetadata.put(META_FILE_ID, fileId.toString());
		userMetadata.put(META_NAME, fileName);
		userMetadata.put(META_DESCRIPTION, description != null ? description : "");
		userMetadata.put(META_CONTENT_TYPE, contentType);
		userMetadata.put(META_CREATED, created.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
		userMetadata.put(META_ORIGIN, origin != null ? origin : "");
		userMetadata.put(META_CATALOG, catalog != null ? catalog : "");

		// Parse origin tags
		final String[] originTags = origin == null ? null : Arrays.stream(origin.split(","))
			.map(String::trim)
			.toArray(String[]::new);

		final CompletableFuture<FileForFetch> fileForFetchFuture = new CompletableFuture<>();

		// Create output stream that buffers data and uploads to S3 when closed
		final S3UploadOutputStream uploadOutputStream = new S3UploadOutputStream(
			this.fileManagementService.createTempFile(fileId.toString()),
			this.minioClient,
			this.s3Options.getBucketOrThrowException(),
			this.s3Options.getRegion(),
			objectKey,
			contentType,
			userMetadata,
			fileId,
			fileName,
			description,
			created,
			originTags,
			catalog,
			fileForFetchFuture,
			this.s3Options.getRequestTimeoutInMillis()
		);

		return new ExportFileHandleS3(
			fileId,
			fileForFetchFuture
				.thenApply(fileForFetch -> {
					this.files.add(0, fileForFetch);
					return fileForFetch;
				}),
			objectKey,
			uploadOutputStream
		);
	}

	@Nonnull
	@Override
	public InputStream fetchFile(@Nonnull UUID fileId) throws FileForFetchNotFoundException {
		final FileForFetch file = getFile(fileId)
			.orElseThrow(() -> new FileForFetchNotFoundException(fileId));

		final String objectKey = fileId + FileUtils.getFileExtension(file.name()).map(it -> "." + it).orElse("");

		try {
			final long timeout = this.s3Options.getRequestTimeoutInMillis();
			final GetObjectArgs.Builder getObjectBuilder = GetObjectArgs.builder()
				.bucket(this.s3Options.getBucketOrThrowException())
				.object(objectKey);
			final String region = this.s3Options.getRegion();
			if (isRegionValid(region)) {
				getObjectBuilder.region(region);
			}
			return this.minioClient.getObject(
				getObjectBuilder.build()
			).get(timeout, TimeUnit.MILLISECONDS);
		} catch (Exception e) {
			throw new UnexpectedIOException(
				"Failed to fetch file from S3: " + e.getMessage(),
				"Failed to fetch file from S3.",
				e
			);
		}
	}

	@Override
	public void deleteFile(@Nonnull UUID fileId) throws FileForFetchNotFoundException {
		final FileForFetch file = getFile(fileId)
			.orElseThrow(() -> new FileForFetchNotFoundException(fileId));

		final String objectKey = fileId + FileUtils.getFileExtension(file.name()).map(it -> "." + it).orElse("");

		// Remove from cache first
		if (this.files.remove(file)) {
			try {
				final long timeout = this.s3Options.getRequestTimeoutInMillis();
				// If someone else already deleted the object, the operation should be idempotent and not fail
				final RemoveObjectArgs.Builder removeObjectArgsBuilder = RemoveObjectArgs.builder()
					.bucket(this.s3Options.getBucketOrThrowException())
					.object(objectKey);
				final String region = this.s3Options.getRegion();
				if (isRegionValid(region)) {
					removeObjectArgsBuilder.region(region);
				}
				this.minioClient.removeObject(
					removeObjectArgsBuilder.build()
				).get(timeout, TimeUnit.MILLISECONDS);
			} catch (Exception e) {
				// Ignore remote deletion errors and keep the local cache updated as the file is gone already
				log.warn(
					"Failed to delete S3 object {}, ignoring and keeping cache consistent: {}", objectKey,
					e.getMessage()
				);
			}
		}
	}

	@Override
	public long purgeFiles() {
		final OffsetDateTime thresholdDate = OffsetDateTime.now().minusSeconds(
			this.s3Options.getHistoryExpirationSeconds()
		);
		purgeFiles(thresholdDate);
		return 0L;
	}

	@Override
	public void purgeFiles(@Nonnull OffsetDateTime thresholdDate) {
		// Delete files older than threshold
		final CopyOnWriteArrayList<FileForFetch> fileList = getFiles();
		final int size = fileList.size();
		for (int i = size - 1; i >= 0; i--) {
			final FileForFetch file = fileList.get(i);
			if (file.created().isBefore(thresholdDate)) {
				log.info("Purging file from S3, because it has been created before {}: {}", thresholdDate, file);
				try {
					deleteFile(file.fileId());
				} catch (Exception e) {
					log.error("Failed to purge file from S3: {}", file, e);
				}
			}
		}

		// Check total size and delete oldest files if over limit
		try {
			long totalSize = 0L;
			for (FileForFetch fileForFetch : fileList) {
				totalSize += fileForFetch.totalSizeInBytes();
			}

			if (totalSize > this.s3Options.getSizeLimitBytes()) {
				// Sort by creation date (oldest first) for deletion
				final FileForFetch[] sortedFiles = fileList.toArray(new FileForFetch[0]);
				Arrays.sort(sortedFiles, Comparator.comparing(FileForFetch::created));

				long savedSize = 0L;
				for (final FileForFetch file : sortedFiles) {
					log.info("Purging the oldest file from S3, because the storage grew too big: {}", file);
					try {
						final long fileSize = file.totalSizeInBytes();
						deleteFile(file.fileId());
						savedSize += fileSize;
						if (totalSize - savedSize <= this.s3Options.getSizeLimitBytes()) {
							break;
						}
					} catch (Exception e) {
						log.error("Failed to purge file from S3: {}", file, e);
					}
				}
			}
		} catch (Exception e) {
			log.error("Failed to calculate total size for purging", e);
		}
	}

	@Override
	public void close() throws IOException {
		// Stop purging task
		IOUtils.closeQuietly(
			this.purgeTask::close,
			this.refreshTask::close,
			() -> {
				closeIfNotNull(this.notificationIterator);
			}
		);
		// Run final purge
		purgeFiles();
		// Close MinIO client
		IOUtils.closeQuietly(this.minioClient::close);
	}

	/**
	 * Retrieves the list of files available for fetching.
	 * The returned list is backed by a thread-safe {@link CopyOnWriteArrayList}
	 * to ensure safe iteration and modification in concurrent environments.
	 *
	 * @return a non-null {@link CopyOnWriteArrayList} of {@link FileForFetch} instances,
	 * representing the files available for fetching.
	 */
	@Nonnull
	private CopyOnWriteArrayList<FileForFetch> getFiles() {
		return this.files;
	}

	/**
	 * Ensures the configured S3 bucket exists, creating it if necessary.
	 */
	private void ensureBucketExists() {
		final String bucket = this.s3Options.getBucketOrThrowException();
		final String region = this.s3Options.getRegion();
		final boolean regionSet = isRegionValid(region);
		try {
			final long timeout = this.s3Options.getRequestTimeoutInMillis();
			final Builder bucketExistsBuilder = BucketExistsArgs.builder().bucket(bucket);
			if (regionSet) {
				bucketExistsBuilder.region(region);
			}
			final boolean bucketExists = this.minioClient.bucketExists(
				bucketExistsBuilder.build()
			).get(timeout, TimeUnit.MILLISECONDS);

			if (!bucketExists) {
				final MakeBucketArgs.Builder makeBucketBuilder = MakeBucketArgs.builder().bucket(bucket);
				if (regionSet) {
					makeBucketBuilder.region(region);
				}
				this.minioClient.makeBucket(
					makeBucketBuilder.build()
				).get(timeout, TimeUnit.MILLISECONDS);
				log.info("Created S3 bucket: {}", bucket);
			}
		} catch (Exception e) {
			throw new UnexpectedIOException(
				"Failed to ensure S3 bucket `" + bucket + "`" + (regionSet ?
					" in region `" + region + "`" :
					"") + " exists: " + e.getMessage(),
				"Failed to ensure S3 bucket exists.",
				e
			);
		}
	}

	/**
	 * Loads existing files from the S3 bucket or starts a process to refresh files depending on the state of the notification iterator.
	 * If the notification iterator is uninitialized, this method initializes it after loading existing files.
	 * Otherwise, it updates the notification iterator by refreshing files.
	 *
	 * @return a scheduling hint; implementations commonly return `0`
	 */
	private long loadOrRefreshFiles() {
		if (!this.initializationFuture.isDone()) {
			loadExistingFiles();
		}
		this.notificationIterator = refreshFiles();
		return this.notificationIterator == null ?
		    // standard delay - we will fetch all the files again
			0L :
			// notification listener is active - we may refresh much faster (each 15 seconds)
			REFRESH_DELAY_SECONDS - 15
		;
	}

	/**
	 * Loads existing files from S3 bucket by listing objects and reading their metadata.
	 */
	private void loadExistingFiles() {
		final String bucket = this.s3Options.getBucketOrThrowException();
		final String region = this.s3Options.getRegion();
		final boolean regionSet = isRegionValid(region);

		try {
			final ListObjectsArgs.Builder listObjectsBuilder = ListObjectsArgs.builder()
				.bucket(bucket)
				.includeUserMetadata(true)
				.recursive(true);
			if (regionSet) {
				listObjectsBuilder.region(region);
			}

			final Iterable<Result<Item>> objects = this.minioClient.listObjects(
				listObjectsBuilder.build()
			);

			final List<FileForFetch> result = new ArrayList<>(256);
			for (final Result<Item> itemResult : objects) {
				try {
					final Item item = itemResult.get();
					final Map<String, String> userMetadata = getItemUserMetadata(item, bucket);
					if (!userMetadata.isEmpty()) {
						final FileForFetch fileForFetch = parseFileForFetch(userMetadata, item.size());
						if (fileForFetch != null) {
							result.add(fileForFetch);
						}
					}
				} catch (Exception e) {
					log.warn("Failed to load metadata for S3 object, skipping", e);
				}
			}

			// Sort by creation date (newest first)
			result.sort(Comparator.comparing(FileForFetch::created).reversed());
			this.files = new CopyOnWriteArrayList<>(result);
			this.initializationFuture.complete(true);

		} catch (Exception e) {
			log.error("Failed to load existing files from S3", e);
			this.initializationFuture.completeExceptionally(e);
		}
	}

	/**
	 * Starts background listener that consumes S3 bucket notifications and updates local cache.
	 * The listener auto-reconnects on failures after ~1 minute.
	 */
	@Nullable
	private CloseableIterator<Result<NotificationRecords>> refreshFiles() {
		try {
			final CloseableIterator<Result<NotificationRecords>> iterator = getNotificationIterator();
			while (iterator.hasNext()) {
				final Result<NotificationRecords> res = iterator.next();
				final NotificationRecords records = res.get();
				if (records == null || records.events() == null) {
					continue;
				}
				for (final Event event : records.events()) {
					final EventType eventName = event.eventType();
					final String objectKey = event.objectName();
					if (eventName != null) {
						if (eventName == EventType.OBJECT_REMOVED_ANY) {
							onObjectRemoved(objectKey);
						} else if (eventName == EventType.OBJECT_CREATED_ANY) {
							onObjectCreatedOrUpdated(objectKey);
						}
					}
				}
			}
			return iterator;
		} catch (Exception ex) {
			// backend probably doesn't support listening to notifications
			IOUtils.closeQuietly(() -> closeIfNotNull(this.notificationIterator));
			return null;
		}
	}

	/**
	 * Retrieves an iterator for listening to S3 bucket notifications.
	 * This method returns an existing iterator if it is already initialized.
	 * If no iterator exists, a new one is created for listening to notification events
	 * such as object creation or removal within the bucket.
	 *
	 * The iterator utilizes the MinIO client to connect to the S3 bucket and
	 * listen to notification events in real-time. It supports specific
	 * configuration such as bucket name and region, which are retrieved
	 * from the associated S3 options.
	 *
	 * @return a non-null {@link CloseableIterator} of {@link Result} containing
	 * {@link NotificationRecords}. Each result corresponds to an S3 bucket
	 * notification.
	 * @throws Exception if an error occurs while initializing the iterator
	 *                   or setting up the connection to the bucket.
	 */
	@Nonnull
	private CloseableIterator<Result<NotificationRecords>> getNotificationIterator() throws Exception {
		final CloseableIterator<Result<NotificationRecords>> existingIterator = this.notificationIterator;
		if (existingIterator == null) {
			final String bucket = this.s3Options.getBucketOrThrowException();
			final String region = this.s3Options.getRegion();
			final boolean regionSet = isRegionValid(region);

			final ListenBucketNotificationArgs.Builder args = ListenBucketNotificationArgs.builder()
				.bucket(bucket)
				.events(
					new String[]{
						EventType.OBJECT_CREATED_ANY.toString(),
						EventType.OBJECT_REMOVED_ANY.toString()
					}
				);
			if (regionSet) {
				args.region(region);
			}
			final CloseableIterator<Result<NotificationRecords>> newIterator =
				this.minioClient.listenBucketNotification(args.build());
			this.notificationIterator = newIterator;
			return newIterator;
		} else {
			return existingIterator;
		}
	}

	/**
	 * Handles object removal notification by removing corresponding entry from cache.
	 */
	private void onObjectRemoved(@Nonnull String objectKey) {
		try {
			final int dot = objectKey.lastIndexOf('.');
			final String uuidStr = dot > 0 ? objectKey.substring(0, dot) : objectKey;
			final UUID fileId = UUID.fromString(uuidStr);
			final CopyOnWriteArrayList<FileForFetch> current = this.files;
			// Linear scan is acceptable due to relatively small cache size
			for (int i = 0; i < current.size(); i++) {
				final FileForFetch f = current.get(i);
				if (f.fileId().equals(fileId)) {
					current.remove(i);
					break;
				}
			}
		} catch (Exception e) {
			log.debug("Failed to remove cache entry for object key {}", objectKey, e);
		}
	}

	/**
	 * Handles the creation or update of an object in the S3 bucket. This method retrieves
	 * the object's metadata and updates a local cache to reflect the current state of the object.
	 * If metadata is invalid or an error occurs during processing, no updates are made to the cache.
	 *
	 * @param objectKey the key of the object within the S3 bucket; must not be null
	 */
	private void onObjectCreatedOrUpdated(@Nonnull String objectKey) {
		try {
			final String bucket = this.s3Options.getBucketOrThrowException();
			final String region = this.s3Options.getRegion();
			final long timeout = this.s3Options.getRequestTimeoutInMillis();

			final StatObjectArgs.Builder statObjectArgsBuilder = StatObjectArgs.builder()
				.bucket(bucket)
				.object(objectKey);
			if (isRegionValid(region)) {
				statObjectArgsBuilder.region(region);
			}
			final StatObjectResponse stat = this.minioClient.statObject(statObjectArgsBuilder.build())
				.get(timeout, TimeUnit.MILLISECONDS);
			final Map<String, String> userMetadata = normalizeMetadataNames(stat.userMetadata());
			final FileForFetch file = parseFileForFetch(userMetadata, stat.size());
			if (file == null) {
				return;
			}
			// Upsert at the beginning, remove existing if present
			final CopyOnWriteArrayList<FileForFetch> current = this.files;
			for (int i = 0; i < current.size(); i++) {
				if (current.get(i).fileId().equals(file.fileId())) {
					current.remove(i);
					break;
				}
			}
			current.add(0, file);
		} catch (Exception e) {
			log.debug("Failed to refresh cache entry for object key {}", objectKey, e);
		}
	}

	/**
	 * Retrieves the user-defined metadata for the specified S3 object. If the object already contains
	 * user metadata, it is returned directly. Otherwise, performs a metadata lookup using the S3 client.
	 *
	 * @param item   the S3 object item for which metadata is to be retrieved; must not be null
	 * @param bucket the name of the S3 bucket containing the object; must not be null
	 * @return a map containing the user-defined metadata for the specified S3 object;
	 * returns an empty map if metadata cannot be retrieved
	 */
	@Nonnull
	private Map<String, String> getItemUserMetadata(
		@Nonnull Item item,
		@Nonnull String bucket
	) {
		try {
			final String region = this.s3Options.getRegion();
			final long timeout = this.s3Options.getRequestTimeoutInMillis();
			final Map<String, String> userMetadata;
			if (item.userMetadata() != null && !item.userMetadata().isEmpty()) {
				userMetadata = normalizeMetadataNames(item.userMetadata());
			} else {
				final StatObjectArgs.Builder statObjectArgsBuilder = StatObjectArgs.builder()
					.bucket(bucket)
					.object(item.objectName());
				if (isRegionValid(region)) {
					statObjectArgsBuilder.region(region);
				}
				final StatObjectResponse statObjectResponse = this.minioClient.statObject(
					statObjectArgsBuilder.build()
				).get(timeout, TimeUnit.MILLISECONDS);
				userMetadata = normalizeMetadataNames(statObjectResponse.userMetadata());
			}
			return userMetadata;
		} catch (Exception e) {
			log.error("Failed to get user metadata for S3 object: {}", item.objectName(), e);
			return Map.of();
		}
	}

	/**
	 * Normalizes the keys of the user metadata map. This method converts keys that
	 * start with a defined prefix to lowercase and removes the prefix. If no keys
	 * match the prefix, the input map remains unchanged.
	 *
	 * @param userMetadata a map containing user-defined metadata where the keys are
	 *                     strings and the values represent metadata values; must not
	 *                     be null.
	 * @return a normalized map of metadata with processed keys, or null if no keys
	 *         required normalization.
	 */
	@Nonnull
	private static Map<String, String> normalizeMetadataNames(@Nonnull Map<String, String> userMetadata) {
		Map<String, String> normalizedMetadata = null;
		for (Map.Entry<String, String> entry : userMetadata.entrySet()) {
			final String key = entry.getKey();
			final String lowerCaseKey = key.toLowerCase(Locale.US);
			if (lowerCaseKey.startsWith(USER_METADATA_PREFIX) || !key.equals(lowerCaseKey)) {
				if (normalizedMetadata == null) {
					normalizedMetadata = CollectionUtils.createHashMap(userMetadata.size());
					// copy previous entries
					for (Map.Entry<String, String> e : userMetadata.entrySet()) {
						if (e.getKey().equals(key)) {
							break;
						}
						normalizedMetadata.put(e.getKey().toLowerCase(Locale.US), e.getValue());
					}
				}
				normalizedMetadata.put(lowerCaseKey.substring(USER_METADATA_PREFIX.length()), entry.getValue());
			}
		}
		return normalizedMetadata != null ? normalizedMetadata : userMetadata;
	}

	/**
	 * Record that contains output stream the export file can be written to and future that will be
	 * completed when the file is uploaded to S3.
	 *
	 * @param fileId             ID of the file
	 * @param fileForFetchFuture Future that will be completed when the file is uploaded
	 * @param objectKey          S3 object key
	 * @param outputStream       Output stream the file can be written to
	 */
	public record ExportFileHandleS3(
		@Nonnull UUID fileId,
		@Nonnull CompletableFuture<FileForFetch> fileForFetchFuture,
		@Nonnull String objectKey,
		@Nonnull S3UploadOutputStream outputStream
	) implements ExportFileHandle {

		@Override
		public long size() {
			return this.outputStream.size();
		}

		@Override
		public void close() throws IOException {
			this.outputStream.close();
		}

		@Nonnull
		@Override
		public String toString() {
			return "s3://" + this.objectKey + " (fileId: " + this.fileId + ")";
		}
	}

	/**
	 * An implementation of OutputStream that writes data to a temporary file and uploads it to an S3-compatible storage
	 * upon stream closure. This class is designed to facilitate stream-based file uploads while managing metadata and
	 * ensuring temporary file cleanup.
	 *
	 * This output stream writes data to a local temporary file and uploads the file to a S3 bucket on close. The class
	 * also supports metadata handling for the uploaded object and returns a `FileForFetch` object upon successful upload.
	 *
	 * Thread Safety: This class is not thread-safe, and instances should not be shared between threads.
	 */
	private static final class S3UploadOutputStream extends OutputStream {
		private final OutputStream delegate;
		private final Path path;
		private final MinioAsyncClient minioClient;
		private final String bucket;
		private final String region;
		private final String objectKey;
		private final String contentType;
		private final Map<String, String> userMetadata;
		private final UUID fileId;
		private final String fileName;
		private final String description;
		private final OffsetDateTime created;
		private final String[] originTags;
		private final String catalogName;
		private final CompletableFuture<FileForFetch> fileForFetchFuture;
		private final long requestTimeoutInMillis;
		private boolean closed;

		S3UploadOutputStream(
			@Nonnull Path tempFilePath,
			@Nonnull MinioAsyncClient minioClient,
			@Nonnull String bucket,
			@Nullable String region,
			@Nonnull String objectKey,
			@Nonnull String contentType,
			@Nonnull Map<String, String> userMetadata,
			@Nonnull UUID fileId,
			@Nonnull String fileName,
			@Nullable String description,
			@Nonnull OffsetDateTime created,
			@Nullable String[] originTags,
			@Nullable String catalogName,
			@Nonnull CompletableFuture<FileForFetch> fileForFetchFuture,
			long requestTimeoutInMillis
		) {
			try {
				final File theFile = tempFilePath.toFile();
				if (!theFile.exists()) {
					Assert.isPremiseValid(
						theFile.createNewFile(),
						"Failed to create temporary file for S3 upload."
					);
				}
				this.delegate = new BufferedOutputStream(new FileOutputStream(theFile));
			} catch (IOException e) {
				throw new UnexpectedIOException(
					"Failed to create temporary file for S3 upload: " + e.getMessage(),
					"Failed to create temporary file for S3 upload.",
					e
				);
			}
			this.path = tempFilePath;
			this.minioClient = minioClient;
			this.bucket = bucket;
			this.region = region;
			this.objectKey = objectKey;
			this.contentType = contentType;
			this.userMetadata = userMetadata;
			this.fileId = fileId;
			this.fileName = fileName;
			this.description = description;
			this.created = created;
			this.originTags = originTags;
			this.catalogName = catalogName;
			this.fileForFetchFuture = fileForFetchFuture;
			this.requestTimeoutInMillis = requestTimeoutInMillis;
			this.closed = false;
		}

		@Override
		public void write(int b) throws IOException {
			this.delegate.write(b);
		}

		@Override
		public void write(@Nonnull byte[] b) throws IOException {
			this.delegate.write(b);
		}

		@Override
		public void write(@Nonnull byte[] b, int off, int len) throws IOException {
			this.delegate.write(b, off, len);
		}

		@Override
		public void flush() throws IOException {
			this.delegate.flush();
		}

		@Override
		public void close() {
			if (this.closed) {
				return;
			}
			this.closed = true;

			try {
				// Ensure all buffered content is written to temporary file
				this.delegate.close();

				// Prepare upload resources
				final File tmpFile = this.path.toFile();
				final long tmpFileSize = tmpFile.length();
				final BufferedInputStream bis = new BufferedInputStream(new FileInputStream(tmpFile));
				final PutObjectArgs.Builder putObjectArgsBuilder = PutObjectArgs.builder()
					.bucket(this.bucket)
					.object(this.objectKey)
					.stream(bis, tmpFileSize, PART_SIZE)
					.contentType(this.contentType)
					.userMetadata(this.userMetadata);
				if (isRegionValid(this.region)) {
					putObjectArgsBuilder.region(this.region);
				}

				// Start async upload, complete the future and clean resources on completion
				this.minioClient
					.putObject(putObjectArgsBuilder.build())
					.orTimeout(this.requestTimeoutInMillis, TimeUnit.MILLISECONDS)
					.whenComplete((ignored, throwable) -> {
						try {
							if (throwable == null) {
								final FileForFetch fileForFetch = new FileForFetch(
									this.fileId,
									this.fileName,
									this.description,
									this.contentType,
									tmpFileSize,
									this.created,
									this.originTags,
									this.catalogName
								);
								this.fileForFetchFuture.complete(fileForFetch);
							} else {
								log.error("Failed to upload file to S3: {}", this.objectKey, throwable);
								final UnexpectedIOException exception = new UnexpectedIOException(
									"Failed to upload file to S3: " + throwable.getMessage(),
									"Failed to upload file to S3.",
									throwable
								);
								this.fileForFetchFuture.completeExceptionally(exception);
							}
						} finally {
							// Close the upload stream and delete temporary file
							IOUtils.closeQuietly(
								bis::close,
								() -> FileUtils.deleteFileIfExists(this.path)
							);
						}
					});
			} catch (Exception e) {
				log.error("Failed to initiate upload to S3: {}", this.objectKey, e);
				final UnexpectedIOException exception = new UnexpectedIOException(
					"Failed to upload file to S3: " + e.getMessage(),
					"Failed to upload file to S3.",
					e
				);
				this.fileForFetchFuture.completeExceptionally(exception);
				// Best-effort cleanup when async upload hasn't started
				IOUtils.closeQuietly(() -> FileUtils.deleteFileIfExists(this.path));
			}
		}

		/**
		 * Returns the size of the file represented by the output stream.
		 *
		 * @return The size of the file in bytes.
		 */
		public long size() {
			return this.path.toFile().length();
		}
	}

}
