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

package io.evitadb.externalApi.grpc.services;

import com.google.protobuf.ByteString;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.server.ServiceRequestContext;
import io.evitadb.core.management.EvitaManagement;
import io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogRequest;
import io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogResponse;
import io.evitadb.externalApi.grpc.services.EvitaManagementService.RestoreCatalogUploadObserver;
import io.grpc.stub.ServerCallStreamObserver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.annotation.Nonnull;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static io.evitadb.test.TestTags.EXTERNAL_API;
import static io.evitadb.test.TestTags.GRPC;
import static io.evitadb.test.TestTags.STREAM;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Pins down what happens to a half-uploaded catalog archive when the request pool refuses the work that
 * would have written the next chunk.
 *
 * The upload's temporary file has no owner other than this observer: `createTempFile` does not reserve it,
 * nothing sweeps the work directory, and the restore step that would delete it never runs for an upload
 * that stopped half way. A hand-off the pool rejects therefore has to end the call, not merely lose the
 * step - otherwise the archive stays on disk for the lifetime of the process.
 *
 * **The rejection has to be timed to land while the chain is still running.** A pool that refuses the
 * *first* hand-off is a much weaker test: `CompletableFuture#thenRunAsync` throws on the calling thread in
 * that case, so even the original code surfaced something. The failure that hangs a client is the other
 * one - a rejection while a previous step is in flight, which the old code lost inside
 * `CompletableFuture#postComplete` on an unrelated worker. {@link RejectAfterFirstExecutor} reproduces
 * exactly that, and {@link #shouldDiscardThePartialArchiveWhenTheUploadPoolRejectsALaterChunk()} is the
 * test that separates a real fix from one that still leaks.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("A rejected upload hand-off must discard the partial archive")
@Tag(GRPC)
@Tag(EXTERNAL_API)
@Tag(STREAM)
class RestoreCatalogUploadObserverTest {
	/**
	 * Streaming budget handed to the observer. Never reached - no test here waits on a timeout - it only
	 * has to be a positive number so the re-arm path is exercised rather than skipped.
	 */
	private static final long STREAMING_BUDGET_MILLIS = 300_000L;
	/**
	 * How long a latch may wait before the test is declared broken. Generous on purpose: it is a
	 * *positive* wait, so it only ever elapses when the code under test has genuinely failed to progress.
	 */
	private static final long LATCH_TIMEOUT_SECONDS = 30L;
	/**
	 * How long the one *negative* assertion here waits before concluding nothing happened. Deliberately
	 * short: a negative wait pays its full duration on every green run, and lengthening it would not make
	 * the assertion any stronger - only slower.
	 */
	private static final long NEGATIVE_WAIT_MILLIS = 250L;

	/**
	 * Executor that accepts a fixed number of hand-offs and rejects every one after them, the way the
	 * bounded request pool does once its queue fills (`EvitaRejectingExecutorHandler` throws).
	 *
	 * Accepted work runs on a single background thread, so a step is genuinely in flight - and the chain
	 * genuinely incomplete - when the next hand-off is refused.
	 */
	private static final class RejectAfterFirstExecutor implements Executor {
		private final ExecutorService delegate = Executors.newSingleThreadExecutor();
		private final AtomicInteger accepted = new AtomicInteger();
		/**
		 * Counted down once the first accepted task has finished. Lets a test wait for the write to land
		 * rather than poll the directory for it.
		 */
		private final CountDownLatch firstTaskCompleted = new CountDownLatch(1);
		private final int acceptLimit;

		RejectAfterFirstExecutor(int acceptLimit) {
			this.acceptLimit = acceptLimit;
		}

		@Override
		public void execute(@Nonnull Runnable command) {
			if (this.accepted.incrementAndGet() > this.acceptLimit) {
				throw new RejectedExecutionException("Evita executor queue full.");
			}
			this.delegate.execute(
				() -> {
					try {
						command.run();
					} finally {
						this.firstTaskCompleted.countDown();
					}
				}
			);
		}

		/**
		 * Waits for the first accepted task to finish.
		 *
		 * @return true when it finished within {@link #LATCH_TIMEOUT_SECONDS}
		 */
		boolean awaitFirstTask() throws InterruptedException {
			return this.firstTaskCompleted.await(LATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
		}

		void shutdown() {
			this.delegate.shutdownNow();
		}
	}

	/**
	 * Captures the terminal outcome the client would observe, so a test can tell "the call ended with an
	 * error" from "the call never ended at all".
	 */
	private static final class RecordingResponseObserver extends ServerCallStreamObserver<GrpcRestoreCatalogResponse> {
		private final CountDownLatch terminated = new CountDownLatch(1);
		private final List<Throwable> errors = new ArrayList<>(2);
		private final AtomicInteger requested = new AtomicInteger();

		@Override
		public void onNext(GrpcRestoreCatalogResponse value) {
			// a successful upload is not what any test here drives
		}

		@Override
		public void onError(Throwable t) {
			this.errors.add(t);
			this.terminated.countDown();
		}

		@Override
		public void onCompleted() {
			this.terminated.countDown();
		}

		@Override
		public void request(int count) {
			this.requested.addAndGet(count);
		}

		@Override
		public boolean isReady() {
			return true;
		}

		@Override
		public boolean isCancelled() {
			return false;
		}

		@Override
		public void setOnCancelHandler(Runnable onCancelHandler) {
			// not exercised
		}

		@Override
		public void setCompression(String compression) {
			// not exercised
		}

		@Override
		public void disableAutoRequest() {
			// not exercised
		}

		@Override
		public void disableAutoInboundFlowControl() {
			// not exercised
		}

		@Override
		public void setOnReadyHandler(Runnable onReadyHandler) {
			// not exercised
		}

		@Override
		public void setMessageCompression(boolean enable) {
			// not exercised
		}

		boolean awaitTermination() throws InterruptedException {
			return this.terminated.await(LATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
		}

		boolean awaitTerminationBriefly() throws InterruptedException {
			return this.terminated.await(NEGATIVE_WAIT_MILLIS, TimeUnit.MILLISECONDS);
		}
	}

	/**
	 * Builds an observer writing into the given directory, driven by the given executor.
	 *
	 * @param workDirectory   directory the assembled archive is written into
	 * @param responseObserver observer capturing the terminal outcome
	 * @param uploadExecutor  executor the file work is handed to
	 * @return the observer under test
	 */
	@Nonnull
	private static RestoreCatalogUploadObserver observerFor(
		@Nonnull Path workDirectory,
		@Nonnull RecordingResponseObserver responseObserver,
		@Nonnull Executor uploadExecutor
	) {
		return new RestoreCatalogUploadObserver(
			responseObserver,
			ServiceRequestContext.builder(HttpRequest.of(HttpMethod.POST, "/test")).build(),
			workDirectory,
			mock(EvitaManagement.class),
			uploadExecutor,
			STREAMING_BUDGET_MILLIS
		);
	}

	/**
	 * Builds one upload chunk carrying the given payload.
	 *
	 * @param payload bytes the chunk carries
	 * @return the request message
	 */
	@Nonnull
	private static GrpcRestoreCatalogRequest chunkOf(@Nonnull byte[] payload) {
		return GrpcRestoreCatalogRequest.newBuilder()
			.setCatalogName("testCatalog")
			.setBackupFile(ByteString.copyFrom(payload))
			.build();
	}

	/**
	 * Lists the archives the observer has left in the work directory.
	 *
	 * @param workDirectory directory to inspect
	 * @return the archive files present, never null
	 */
	@Nonnull
	private static File[] archivesIn(@Nonnull Path workDirectory) {
		final File[] files = workDirectory.toFile().listFiles(
			(dir, name) -> name.startsWith("catalog_backup_for_restore-")
		);
		return files == null ? new File[0] : files;
	}

	@Test
	@DisplayName("A rejected later chunk ends the call and leaves no archive behind")
	void shouldDiscardThePartialArchiveWhenTheUploadPoolRejectsALaterChunk(@TempDir Path workDirectory)
		throws InterruptedException {
		final RecordingResponseObserver responseObserver = new RecordingResponseObserver();
		// the first chunk's write is accepted and runs; every hand-off after it is refused
		final RejectAfterFirstExecutor uploadExecutor = new RejectAfterFirstExecutor(1);
		try {
			final RestoreCatalogUploadObserver observer = observerFor(
				workDirectory, responseObserver, uploadExecutor
			);

			observer.onNext(chunkOf(new byte[]{1, 2, 3, 4}));
			observer.onNext(chunkOf(new byte[]{5, 6, 7, 8}));

			assertTrue(
				responseObserver.awaitTermination(),
				"A rejected hand-off must terminate the call - the client is otherwise left waiting for a " +
					"chunk that will never be written, until its own deadline expires."
			);
			assertEquals(
				1, responseObserver.errors.size(),
				"The client must be told exactly once why the upload ended."
			);
			assertNotNull(responseObserver.errors.get(0));

			// the point of the whole exercise: nothing may be left on disk
			assertEquals(
				0, archivesIn(workDirectory).length,
				"The partial archive must be discarded when the pool refuses the work that would have " +
					"extended it - nothing else ever deletes it."
			);

			// The chain must stay poisoned. A rejection that leaves it healthy lets this chunk through to
			// `openBackupFileIfNeeded`, which finds a closed stream and reopens the file in APPEND mode -
			// recreating on disk exactly the archive that was just deleted.
			observer.onNext(chunkOf(new byte[]{9, 10, 11, 12}));
			assertEquals(
				0, archivesIn(workDirectory).length,
				"A chunk arriving after a failed upload must not recreate the archive."
			);
		} finally {
			uploadExecutor.shutdown();
		}
	}

	@Test
	@DisplayName("A rejected first chunk also ends the call and leaves nothing behind")
	void shouldDiscardThePartialArchiveWhenTheUploadPoolRejectsTheFirstChunk(@TempDir Path workDirectory)
		throws InterruptedException {
		final RecordingResponseObserver responseObserver = new RecordingResponseObserver();
		// nothing is accepted at all - the pool is already saturated when the upload starts
		final RejectAfterFirstExecutor uploadExecutor = new RejectAfterFirstExecutor(0);
		try {
			final RestoreCatalogUploadObserver observer = observerFor(
				workDirectory, responseObserver, uploadExecutor
			);

			observer.onNext(chunkOf(new byte[]{1, 2, 3, 4}));

			assertTrue(responseObserver.awaitTermination(), "A rejected hand-off must terminate the call.");
			assertEquals(
				0, archivesIn(workDirectory).length,
				"A rejection before the file is even opened must leave the work directory untouched."
			);
		} finally {
			uploadExecutor.shutdown();
		}
	}

	@Test
	@DisplayName("A client abort discards the archive even when the pool refuses the cleanup")
	void shouldDiscardThePartialArchiveWhenTheCleanupHandOffIsRejected(@TempDir Path workDirectory)
		throws InterruptedException {
		final RecordingResponseObserver responseObserver = new RecordingResponseObserver();
		// the first chunk lands, then the client aborts and the pool refuses to run the cleanup
		final RejectAfterFirstExecutor uploadExecutor = new RejectAfterFirstExecutor(1);
		try {
			final RestoreCatalogUploadObserver observer = observerFor(
				workDirectory, responseObserver, uploadExecutor
			);

			observer.onNext(chunkOf(new byte[]{1, 2, 3, 4}));
			// let the accepted write land, so there is a real archive for the abort to discard
			assertTrue(
				uploadExecutor.awaitFirstTask(),
				"The accepted first chunk should have been written before the abort is driven."
			);
			assertEquals(
				1, archivesIn(workDirectory).length,
				"The accepted first chunk should have created the archive this test is about to abort."
			);

			observer.onError(new RuntimeException("client went away"));

			assertTrue(
				responseObserver.awaitTermination(),
				"An aborted upload must still terminate the call."
			);
			assertEquals(
				0, archivesIn(workDirectory).length,
				"Cleanup refused by the pool must fall back to the calling thread rather than be dropped."
			);
		} finally {
			uploadExecutor.shutdown();
		}
	}

	@Test
	@DisplayName("An upload the pool never refuses leaves the archive in place for the restore step")
	void shouldKeepTheArchiveWhileTheUploadIsProgressing(@TempDir Path workDirectory) throws InterruptedException {
		final RecordingResponseObserver responseObserver = new RecordingResponseObserver();
		// a pool that accepts everything - the control case, so the assertions above cannot pass merely
		// because the observer discards the archive on any code path at all
		final RejectAfterFirstExecutor uploadExecutor = new RejectAfterFirstExecutor(Integer.MAX_VALUE);
		try {
			final RestoreCatalogUploadObserver observer = observerFor(
				workDirectory, responseObserver, uploadExecutor
			);

			observer.onNext(chunkOf(new byte[]{1, 2, 3, 4}));

			assertTrue(uploadExecutor.awaitFirstTask(), "An accepted chunk must be written to the archive.");
			assertEquals(
				1, archivesIn(workDirectory).length,
				"An upload that is progressing normally must keep its archive - it is what the restore " +
					"step will read."
			);
			assertFalse(
				responseObserver.awaitTerminationBriefly(),
				"An upload that is progressing normally must not be terminated."
			);
		} finally {
			uploadExecutor.shutdown();
		}
	}

}
