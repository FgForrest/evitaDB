/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

package io.evitadb.store.catalog.task;

import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.exception.ExportFileTooLargeException;
import io.evitadb.api.file.FileForFetch;
import io.evitadb.api.task.ServerTask;
import io.evitadb.api.task.TaskStatus.TaskSimplifiedState;
import io.evitadb.core.management.FileManagementService;
import io.evitadb.export.s3.ExportS3Service;
import io.evitadb.export.s3.configuration.S3ExportOptions;
import io.evitadb.spi.export.model.ExportFileHandle;
import io.evitadb.utils.FileUtils;
import io.evitadb.utils.StringUtils;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.stream.Stream;

import static io.evitadb.test.TestTags.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Real backup tasks over S3 must refuse before uploading or publishing a too-large archive.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(STORAGE)
@Tag(EXPORT)
@Tag(TASK)
@DisplayName("Backup limits over S3 export storage")
@Testcontainers
class BackupTaskS3SizeLimitTest extends BackupTaskExportFixture {
	@Container
	static final GenericContainer<?> S3_MOCK = new GenericContainer<>(
		DockerImageName.parse("adobe/s3mock:4.12.4")
	).withExposedPorts(9090).withEnv("debug", "false")
		.waitingFor(Wait.forHttp("/").forPort(9090).forStatusCode(200));
	private static final String BUCKET = "backup-size-limit-test";
	private ExportS3Service s3;
	private S3Client client;
	private Path tempDirectory;
	private S3ExportOptions options;
	private FileManagementService fileManagement;

	/** Uses the real service and a separate SDK client to inspect physical bucket contents. */
	@BeforeEach
	void setUpS3() {
		final String endpoint = "http://" + S3_MOCK.getHost() + ":" + S3_MOCK.getMappedPort(9090);
		this.client = S3Client.builder().endpointOverride(URI.create(endpoint)).region(Region.US_EAST_1)
			.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("accessKey", "secretKey")))
			.serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build()).build();
		this.tempDirectory = this.paths.work().resolve("s3-temp");
		this.options = S3ExportOptions.builder().enabled(true).endpoint(endpoint).bucket(BUCKET)
				.accessKey("accessKey").secretKey("secretKey").region("us-east-1").sizeLimitBytes(20000).build();
		this.fileManagement = new FileManagementService(StorageOptions.builder(StorageOptions.temporary())
			.workDirectory(this.tempDirectory).build());
		this.s3 = new ExportS3Service(this.options, this.scheduler, this.fileManagement);
	}

	/** Removes all test objects and closes the SDK client before the shared fixture deletes its paths. */
	@AfterEach
	void tearDownS3() throws IOException {
		this.s3.close();
		this.fileManagement.close();
		this.client.listObjects(builder -> builder.bucket(BUCKET)).contents().forEach(
			object -> this.client.deleteObject(builder -> builder.bucket(BUCKET).key(object.key()))
		);
		this.client.deleteBucket(builder -> builder.bucket(BUCKET));
		this.client.close();
	}

	@Nested
	@DisplayName("S3 payload allowance")
	class Allowance {
		@Test
		@DisplayName("should report the full configured limit without sidecar reserve")
		void shouldReportS3Allowance() {
			final ExportFileHandle handle = s3.storeFile("limit.zip", null, "application/zip", null);
			assertEquals(20000, handle.sizeLimit().maxFileSizeInBytes());
			assertEquals(20000, handle.sizeLimit().configuredLimitInBytes());
			assertEquals("export.s3.sizeLimitBytes", handle.sizeLimit().configurationProperty());
			assertTrue(handle.discard());
		}
	}

	@Nested
	@DisplayName("Backup task refusals")
	class Refusals {
		@Test
		@DisplayName("should refuse a point-in-time backup without leaving an object or temp file")
		void shouldRefusePointInTimeBackup() throws IOException {
			assertRefused(new BackupTask(TEST_CATALOG, null, null, false, bootstrap(), s3, persistence, null));
		}

		@Test
		@DisplayName("should refuse a full backup without leaving an object or temp file")
		void shouldRefuseFullBackup() throws IOException {
			assertRefused(new FullBackupTask(TEST_CATALOG, s3, persistence, null));
		}
	}

	@Nested
	@DisplayName("Admitted backups")
	class Admitted {
		@Test
		@DisplayName("should upload and publish a backup that fits")
		void shouldUploadAdmittedBackup() throws IOException {
			s3.close();
			s3 = new ExportS3Service(
				S3ExportOptions.builder(options).sizeLimitBytes(10000000).build(), scheduler, fileManagement
			);
			final BackupTask task = new BackupTask(
				TEST_CATALOG, null, null, false, bootstrap(), s3, persistence, null
			);
			task.transitionToIssued();
			task.execute();
			assertEquals(TaskSimplifiedState.FINISHED, task.getStatus().simplifiedState());
			final FileForFetch file = task.getFutureResult().join();
			assertEquals(1, s3.listFilesToFetch(1, 10, Set.of()).getTotalRecordCount());
			assertEquals(1, client.listObjects(builder -> builder.bucket(BUCKET)).contents().size());
			try (final InputStream input = s3.fetchFile(file.fileId())) {
				assertEquals(file.totalSizeInBytes(), input.readAllBytes().length);
			}
		}

		/** A full backup must finish only after its asynchronous S3 upload makes the archive available. */
		@Test
		@DisplayName("should upload and publish a full backup that fits")
		void shouldUploadAdmittedFullBackup() throws IOException {
			s3.close();
			s3 = new ExportS3Service(
				S3ExportOptions.builder(options).sizeLimitBytes(10000000).build(), scheduler, fileManagement
			);
			final FullBackupTask task = new FullBackupTask(TEST_CATALOG, s3, persistence, null);
			task.transitionToIssued();
			task.execute();
			assertEquals(TaskSimplifiedState.FINISHED, task.getStatus().simplifiedState());
			final FileForFetch file = task.getFutureResult().join();
			assertEquals(1, s3.listFilesToFetch(1, 10, Set.of()).getTotalRecordCount());
			assertEquals(file, s3.listFilesToFetch(1, 10, Set.of()).getData().getFirst());
			assertEquals(1, client.listObjects(builder -> builder.bucket(BUCKET)).contents().size());
			assertEquals(file.fileId() + ".zip",
				client.listObjects(builder -> builder.bucket(BUCKET)).contents().getFirst().key());
			final byte[] objectBytes = client.getObjectAsBytes(
				builder -> builder.bucket(BUCKET).key(file.fileId() + ".zip")
			).asByteArray();
			assertTrue(objectBytes.length > 0);
			assertEquals(file.totalSizeInBytes(), objectBytes.length);
			try (final InputStream input = s3.fetchFile(file.fileId())) {
				assertArrayEquals(objectBytes, input.readAllBytes());
			}
		}
	}

	/** Checks S3-specific operator wording, task state, bucket contents and the local staging directory. */
	private void assertRefused(@Nonnull ServerTask<?, FileForFetch> task) throws IOException {
		final long onDisk = FileUtils.getDirectorySize(this.persistence.getCatalogStoragePath());
		task.transitionToIssued();
		assertThrows(RuntimeException.class, task::execute);
		assertEquals(TaskSimplifiedState.FAILED, task.getStatus().simplifiedState());
		final String expected = "The backup of catalog `" + TEST_CATALOG +
			"` was stopped because it exceeded the export storage limit of " + StringUtils.formatByteSize(20000) +
			"; the server would delete a larger file. No backup was created. Increase `export.s3.sizeLimitBytes` " +
			"in the server configuration (or `EVITADB_EXPORT_S3_SIZELIMITBYTES`) and restart the server. " +
			"The catalog currently occupies " + StringUtils.formatByteSize(onDisk) + " on disk.";
		assertEquals(expected, task.getStatus().publicExceptionMessage());
		final ExportFileTooLargeException failure = assertInstanceOf(ExportFileTooLargeException.class,
			assertThrows(CompletionException.class, () -> task.getFutureResult().join()).getCause());
		assertEquals(expected, failure.getPublicMessage());
		assertEquals(0, this.s3.listFilesToFetch(1, 10, Set.of()).getTotalRecordCount());
		assertTrue(this.client.listObjects(builder -> builder.bucket(BUCKET)).contents().isEmpty());
		try (final Stream<Path> files = Files.walk(this.tempDirectory)) {
			assertEquals(
				0,
				files.filter(Files::isRegularFile)
					.filter(it -> !it.getFileName().toString().equals(".lock"))
					.count(),
				"refusal must remove every temp file"
			);
		}
	}
}
