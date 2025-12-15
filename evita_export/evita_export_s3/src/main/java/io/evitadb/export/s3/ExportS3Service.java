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
import io.minio.messages.Item;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
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
	 * Part size for S3 multipart upload (10MB).
	 */
	private static final long PART_SIZE = 10_485_760L;

	/**
	 * S3-specific export configuration options containing all settings.
	 */
	private final S3ExportOptions s3Options;

	/**
	 * MinIO async client for S3 operations.
	 */
	private final MinioAsyncClient minioClient;

	/**
	 * Cached list of files to fetch, sorted by creation date (newest first).
	 */
	private final CopyOnWriteArrayList<FileForFetch> files;

	/**
	 * System file management service.
	 */
	private final FileManagementService fileManagementService;

	/**
	 * Task that periodically purges old files from the storage.
	 */
	private final DelayedAsyncTask purgeTask;

	/**
	 * Gets a value from the metadata map using case-insensitive key lookup.
	 * S3-compatible storages may normalize metadata keys differently.
	 *
	 * @param metadata the metadata map
	 * @param key      the key to look up (case-insensitive)
	 * @return the value or null if not found
	 */
	@Nullable
	private static String getMetadataValue(@Nonnull Map<String, String> metadata, @Nonnull String key) {
		// Try exact match first
		final String value = metadata.get(key);
		if (value != null) {
			return value;
		}
		// Try case-insensitive match
		for (Map.Entry<String, String> entry : metadata.entrySet()) {
			if (entry.getKey().equalsIgnoreCase(key)) {
				return entry.getValue();
			}
		}
		return null;
	}

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
			final String fileIdStr = getMetadataValue(userMetadata, META_FILE_ID);
			final String name = getMetadataValue(userMetadata, META_NAME);
			final String description = getMetadataValue(userMetadata, META_DESCRIPTION);
			final String contentType = getMetadataValue(userMetadata, META_CONTENT_TYPE);
			final String createdStr = getMetadataValue(userMetadata, META_CREATED);
			final String originStr = getMetadataValue(userMetadata, META_ORIGIN);

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
				origin
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
			);

		if (isRegionValid(this.s3Options.getRegion())) {
			clientBuilder.region(this.s3Options.getRegion());
		}

		this.minioClient = clientBuilder.build();

		// Ensure bucket exists
		ensureBucketExists();

		// Load existing files from S3
		/* TODO JNO - we need to load files asynchronously */
		this.files = loadExistingFiles();

		// Schedule automatic purging task
		this.purgeTask = new DelayedAsyncTask(
			null,
			"S3 export file service purging task",
			scheduler,
			this::purgeFiles,
			5, TimeUnit.MINUTES
		);
		this.purgeTask.schedule();
	}

	@Nonnull
	@Override
	public PaginatedList<FileForFetch> listFilesToFetch(int page, int pageSize, @Nonnull Set<String> origin) {
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
				// TODO JNO - handle situation when someone else already deleted the object - in such case we should not throw exception and just remove the file from cache
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
				// Re-add to cache if deletion failed
				this.files.add(0, file);
				throw new UnexpectedIOException(
					"Failed to delete file from S3: " + e.getMessage(),
					"Failed to delete file from S3.",
					e
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
		IOUtils.closeQuietly(this.purgeTask::close);
		// Run final purge
		purgeFiles();
		// MinIO client doesn't require explicit closing
	}

	@Nonnull
	private CopyOnWriteArrayList<FileForFetch> getFiles() {
		/* TODO JNO - check there are no new files on the server side */
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
	 * Loads existing files from S3 bucket by listing objects and reading their metadata.
	 *
	 * @return list of files sorted by creation date (newest first)
	 */
	@Nonnull
	private CopyOnWriteArrayList<FileForFetch> loadExistingFiles() {
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
			return new CopyOnWriteArrayList<>(result);

		} catch (Exception e) {
			log.error("Failed to load existing files from S3", e);
			return new CopyOnWriteArrayList<>();
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
				userMetadata = item.userMetadata();
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
				userMetadata = statObjectResponse.userMetadata();
			}
			return userMetadata;
		} catch (Exception e) {
			log.error("Failed to get user metadata for S3 object: {}", item.objectName(), e);
			return Map.of();
		}
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
									this.originTags
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
