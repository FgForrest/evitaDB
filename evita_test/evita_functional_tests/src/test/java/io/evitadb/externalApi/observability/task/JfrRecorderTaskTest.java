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

package io.evitadb.externalApi.observability.task;

import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.exception.ExportFileTooLargeException;
import io.evitadb.api.file.FileForFetch;
import io.evitadb.core.executor.ImmediateScheduledThreadPoolExecutor;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.core.management.FileManagementService;
import io.evitadb.export.file.ExportFileService;
import io.evitadb.export.file.configuration.FileSystemExportOptions;
import io.evitadb.export.s3.ExportS3Service;
import io.evitadb.export.s3.configuration.S3ExportOptions;
import io.evitadb.spi.export.ExportService;
import io.evitadb.spi.export.model.ExportFileHandle;
import io.evitadb.test.EvitaTestSupport;
import lombok.experimental.Delegate;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static io.evitadb.test.TestTags.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs real JFR recordings on a task thread and checks publication only after archive finalization.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(OBSERVABILITY_API)
@Tag(EXPORT)
@Tag(TASK)
@DisplayName("JFR recording export")
@Testcontainers
class JfrRecorderTaskTest implements EvitaTestSupport {
	@Container
	static final GenericContainer<?> S3_MOCK = new GenericContainer<>(
		DockerImageName.parse("adobe/s3mock:4.12.4")
	).withExposedPorts(9090).withEnv("debug", "false")
		.waitingFor(Wait.forHttp("/").forPort(9090).forStatusCode(200));
	private TestPaths paths;
	private Scheduler scheduler;
	private FileManagementService management;

	/** Creates real work storage and a daemon scheduler owned by this test. */
	@BeforeEach
	void setUp() {
		this.paths = createTestPaths(getClass().getSimpleName());
		final ImmediateScheduledThreadPoolExecutor executor = new ImmediateScheduledThreadPoolExecutor();
		executor.setThreadFactory(Thread.ofPlatform().daemon(true).factory());
		this.scheduler = new Scheduler(executor);
		this.management = new FileManagementService(StorageOptions.builder()
			.workDirectory(this.paths.work()).build());
	}

	/** Releases services before deleting their directories. */
	@AfterEach
	void tearDown() {
		this.management.close();
		this.scheduler.shutdownNow();
		cleanupTestPaths(this.paths);
	}

	/** A stop publishes only after the zip closes, so the returned descriptor must exist. */
	@Test
	@DisplayName("should return and list the finished recording after stop")
	void shouldReturnFinishedRecordingAfterStop() throws Exception {
		try (final ExportFileService service = fileService(10000000)) {
			final JfrRecorderTask task = task(service);
			final Thread worker = start(task);
			try {
				task.stop();
				final FileForFetch result = task.getFutureResult().get(1, TimeUnit.MINUTES);
				assertNotNull(result, "a stopped JFR task must return its published file");
				assertEquals(List.of(result), service.listFilesToFetch(1, 10, Set.of()).getData());
				assertNoTemporaryRecording();
			} finally {
				worker.join(60000);
				assertFalse(worker.isAlive());
			}
		}
	}

	/** Even a short recording exceeds this allowance and must leave no archive or temporary recording. */
	@Test
	@DisplayName("should refuse an oversized recording and remove its temporary file")
	void shouldRefuseOversizedRecording() throws Exception {
		try (final ExportFileService service = fileService(512)) {
			final JfrRecorderTask task = task(service);
			final Thread worker = start(task);
			try {
				task.stop();
				final ExecutionException failure = assertThrows(ExecutionException.class,
					() -> task.getFutureResult().get(1, TimeUnit.MINUTES));
				assertInstanceOf(ExportFileTooLargeException.class, failure.getCause());
				assertEquals("The JFR recording was stopped because it exceeded the export storage limit of " +
					"512 B; the server would delete a larger file. No recording was created. Increase " +
					"`export.fileSystem.sizeLimitBytes` in the server configuration " +
					"(or `EVITADB_EXPORT_FILESYSTEM_SIZELIMITBYTES`) and restart the server.",
					task.getStatus().publicExceptionMessage());
				assertTrue(service.listFilesToFetch(1, 10, Set.of()).getData().isEmpty());
				try (final Stream<Path> files = Files.list(this.paths.export())) {
					assertEquals(0, files.filter(Files::isRegularFile)
						.filter(it -> !it.getFileName().toString().equals(".lock")).count());
				}
				assertNoTemporaryRecording();
			} finally {
				worker.join(60000);
				assertFalse(worker.isAlive());
			}
		}
	}

	/** Real S3 publication completes asynchronously after the zip and its staging stream close. */
	@Test
	@DisplayName("should await the recording uploaded to S3")
	void shouldAwaitS3Recording() throws Exception {
		final String endpoint = "http://" + S3_MOCK.getHost() + ":" + S3_MOCK.getMappedPort(9090);
		try (final ExportS3Service service = new ExportS3Service(S3ExportOptions.builder()
			.enabled(true).endpoint(endpoint).bucket("jfr-publication").region("us-east-1")
			.accessKey("accessKey").secretKey("secretKey").sizeLimitBytes(10000000).build(),
			this.scheduler, this.management)) {
			final JfrRecorderTask task = task(service);
			final Thread worker = start(task);
			try {
				task.stop();
				final FileForFetch result = task.getFutureResult().get(1, TimeUnit.MINUTES);
				assertNotNull(result, "a JFR task must wait for S3 publication");
				assertEquals(List.of(result), service.listFilesToFetch(1, 10, Set.of()).getData());
				service.deleteFile(result.fileId());
				assertNoTemporaryRecording();
			} finally {
				worker.join(60000);
				assertFalse(worker.isAlive());
			}
		}
	}

	/**
	 * Blocks a write made by Files.copy, then interrupts it through the task's execution handle. The write is the only
	 * step a cancel can stop: Files.copy reads through an uninterruptible channel.
	 */
	@Test
	@DisplayName("should discard a recording when a cancel interrupts a write of its copy")
	void shouldDiscardRecordingWhenCancelInterruptsCopyWrite() throws Exception {
		try (final ExportFileService storage = fileService(10000000)) {
			final BlockingExportService service = new BlockingExportService(storage);
			final JfrRecorderTask task = task(service);
			final Thread worker = start(task);
			try {
				task.stop();
				assertTrue(service.copyStarted.await(1, TimeUnit.MINUTES), "the copy must reach the export stream");
				assertTrue(service.copying, "the blocked write must originate inside Files.copy");
				assertTrue(task.cancel());
				worker.join(60000);
				assertFalse(worker.isAlive());
				assertTrue(storage.listFilesToFetch(1, 10, Set.of()).getData().isEmpty(),
					"a cancelled copy must never publish a truncated archive");
				assertNoTemporaryRecording();
			} finally {
				task.cancel();
				worker.join(60000);
			}
		}
	}

	/** Keeps the real handle while exposing a deterministic copy boundary to the test. */
	private static class BlockingExportService implements ExportService {
		@Delegate private final ExportService delegate;
		private final CountDownLatch copyStarted = new CountDownLatch(1);
		private boolean copying;

		/** Delegates every storage operation except the first output write. */
		private BlockingExportService(@Nonnull ExportService delegate) {
			this.delegate = delegate;
		}

		/** {@inheritDoc} */
		@Override
		@Nonnull
		public ExportFileHandle storeFile(
			@Nonnull String name, @Nullable String description, @Nonnull String type, @Nullable String origin
		) {
			return new BlockingHandle(this.delegate.storeFile(name, description, type, origin), this);
		}
	}

	/** Fails just the blocked copy write on interrupt, so the zip closes around a body that did not finish. */
	private static class BlockingHandle implements ExportFileHandle {
		@Delegate private final ExportFileHandle delegate;
		private final BlockingExportService service;

		/** Shares the copy-boundary latch with the test's service. */
		private BlockingHandle(@Nonnull ExportFileHandle delegate, @Nonnull BlockingExportService service) {
			this.delegate = delegate;
			this.service = service;
		}

		/** {@inheritDoc} */
		@Override
		@Nonnull
		public OutputStream outputStream() {
			return new FilterOutputStream(this.delegate.outputStream()) {
				private boolean first = true;

				/** {@inheritDoc} */
				@Override
				public void write(@Nonnull byte[] bytes, int offset, int length) throws IOException {
					if (this.first) {
						this.first = false;
						BlockingHandle.this.service.copying = StackWalker.getInstance().walk(frames ->
							frames.anyMatch(frame -> frame.getClassName().equals(Files.class.getName()) &&
								frame.getMethodName().equals("copy")));
						BlockingHandle.this.service.copyStarted.countDown();
						try {
							new CountDownLatch(1).await();
						} catch (InterruptedException exception) {
							Thread.currentThread().interrupt();
							throw new IOException("copy interrupted", exception);
						}
					}
					this.out.write(bytes, offset, length);
				}
			};
		}
	}

	/** Allocates local storage with the requested configured limit. */
	@Nonnull
	private ExportFileService fileService(long limit) {
		return new ExportFileService(FileSystemExportOptions.builder().directory(this.paths.export())
			.sizeLimitBytes(limit).build(), this.scheduler);
	}

	/** Creates the real task without requiring a complete evita server. */
	@Nonnull
	private JfrRecorderTask task(@Nonnull ExportService service) {
		return new JfrRecorderTask(new String[0], null, null, service, this.management);
	}

	/** Runs the task on its own daemon thread; failures are available from the task future. */
	@Nonnull
	private static Thread start(@Nonnull JfrRecorderTask task) {
		task.transitionToIssued();
		final FutureTask<Void> execution = new FutureTask<>(() -> {
			try {
				task.call();
				return null;
			} finally {
				Thread.interrupted();
			}
		});
		task.attachExecutionHandle(execution);
		return Thread.ofPlatform().daemon(true).start(execution);
	}

	/** The JFR temporary file must be removed on both success and refusal. */
	private void assertNoTemporaryRecording() throws Exception {
		try (final Stream<Path> files = Files.list(this.paths.work())) {
			assertEquals(0, files.filter(it -> it.toString().endsWith(".jfr")).count());
		}
	}
}
