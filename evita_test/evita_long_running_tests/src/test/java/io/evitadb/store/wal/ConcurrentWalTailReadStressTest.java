/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2026
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

package io.evitadb.store.wal;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.util.Pool;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.configuration.TransactionOptions;
import io.evitadb.api.requestResponse.mutation.CatalogBoundMutation;
import io.evitadb.api.requestResponse.mutation.infrastructure.TransactionMutation;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.store.checksum.Crc32CChecksumFactory;
import io.evitadb.store.kryo.ObservableOutputKeeper;
import io.evitadb.store.model.reference.LogFileRecordReference;
import io.evitadb.store.offsetIndex.io.CatalogOffHeapMemoryManager;
import io.evitadb.store.settings.StorageSettings;
import io.evitadb.store.shared.kryo.KryoFactory;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.utils.FileUtils;
import lombok.extern.slf4j.Slf4j;
import net.bytebuddy.agent.ByteBuddyAgent;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;
import net.bytebuddy.asm.Advice;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.instrument.Instrumentation;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static io.evitadb.spi.store.catalog.persistence.CatalogPersistenceService.getWalFileName;
import static io.evitadb.test.TestTags.SLOW;
import static io.evitadb.test.TestTags.STORAGE;
import static io.evitadb.test.TestTags.WAL;
import static net.bytebuddy.matcher.ElementMatchers.is;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.nameStartsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Drives a real writer and real readers against the same {@link CatalogWriteAheadLog} at the same time - the
 * condition change-data-capture is in permanently, and the one no other test in this repository reproduces.
 *
 * Every other torn-tail test manufactures the damage on a quiesced file: it falsifies a length prefix or truncates
 * the file and then reads. That reproduces the *signature* of the production failure but not its *mechanism*, and
 * the two can diverge - a reader that is genuinely racing an appender may reach the failing consistency check by a
 * path that deliberate file surgery never takes, with different values in hand. Since the fix for issue #1551
 * decides what to do from those values, a synthetic reproduction cannot by itself show that the production error is
 * the one being fixed. This test is what closes that gap.
 *
 * What it asserts is deliberately not "no exception was thrown". A torn tail read is handled internally and
 * reported as a graceful end-of-stream, so a purely thrown-exception assertion passes while the defect is
 * happening. It asserts instead that no internal error was ever **constructed**, which is what the observability
 * agent counts and therefore what put a production pod's health probe into `EVITA_DB_INTERNAL_ERRORS` for 57.6 % of
 * its probe cycles.
 *
 * It lives in the long-running module because it is a thread-scheduling test: it costs ~9 s alone but was measured
 * at 28.9 s inside a full functional run, where it competes with the rest of the suite for cores. Tagging it `slow`
 * in the functional module would have meant it never ran at all, since the default `unitAndFunctional` profile
 * excludes that tag.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@Slf4j
@DisplayName("Concurrent WAL tail read: live writer against readers tailing the same log")
@Tag(STORAGE)
@Tag(WAL)
public class ConcurrentWalTailReadStressTest implements EvitaTestSupport {
	/**
	 * Transaction sizes cycled through by the writer. Deliberately uneven and mostly small, so that records land at
	 * awkward offsets and a reader's buffer is routinely filled only partially - the condition
	 * {@link AbstractMutationLog}'s javadoc blamed for misaligned reader state for two years.
	 */
	private static final int[] TRANSACTION_SIZES = {1, 2, 1, 5, 1, 3, 8, 1, 2, 13, 1, 1, 21, 2, 1};

	/**
	 * Number of transactions the writer appends during the run.
	 */
	private static final int TRANSACTION_COUNT = 400;

	/**
	 * Number of readers tailing concurrently.
	 */
	private static final int READER_COUNT = 3;

	/**
	 * Upper bound on the run, so a hang fails the test rather than stalling the build.
	 */
	private static final long JOIN_TIMEOUT_MS = 180_000L;

	/**
	 * Kryo instances currently handed out by {@link #catalogKryoPool}, by identity.
	 */
	private static final Set<Kryo> KRYO_IN_FLIGHT = Collections.newSetFromMap(new IdentityHashMap<>());

	/**
	 * Every time {@link #catalogKryoPool} handed the same Kryo to two callers at once, or took back one it had not
	 * handed out. A Kryo is not thread-safe, so either of those means the *fixture* let two threads share a
	 * deserializer - and the resulting garbage would look exactly like a WAL read defect while proving nothing
	 * about the code under test.
	 */
	public static final Queue<String> KRYO_POOL_VIOLATIONS = new ConcurrentLinkedQueue<>();

	/**
	 * Every {@link EvitaInternalError} constructed while {@link #RECORDING_ERRORS} is set, as its `toString()`.
	 *
	 * The construction is what has to be observed rather than the throw: the WAL suppliers handle a torn tail read
	 * by catching the exception and reporting a graceful end-of-stream, so nothing escapes to a caller and nothing
	 * reaches a log - yet the observability agent has already counted it, because it instruments the constructors
	 * of the error hierarchy roots. A reader watching only for thrown exceptions would therefore see a clean run
	 * while `io_evitadb_errors_total` climbed. Public because the advice below is inlined into a class in another
	 * package and is subject to ordinary access control from there.
	 */
	public static final Queue<String> CONSTRUCTED_ERRORS = new ConcurrentLinkedQueue<>();

	/**
	 * Whether {@link ErrorConstructionRecordingAdvice} should record. Kept off outside the measured window so that
	 * unrelated errors constructed elsewhere in the surefire fork cannot inflate the result.
	 */
	public static volatile boolean RECORDING_ERRORS;

	/**
	 * Prefix shared by the writer and reader threads this test starts.
	 *
	 * The recorder is necessarily JVM-wide - it is a retransformation of the error hierarchy root, and the fork
	 * runs other classes alongside this one - so the measured window is not enough on its own to isolate it.
	 * Filtering by the constructing thread is: a sweep of the whole `wal | cdc` tag set was observed failing this
	 * test with three `Invalid WAL file name ...` errors that belong to a sibling class's deliberate negative
	 * tests, which says nothing whatsoever about reading a WAL tail.
	 */
	public static final String STRESS_THREAD_PREFIX = "wal-tail-stress-";

	private final Path walDirectory = getTestDirectory().resolve(getClass().getSimpleName());

	/**
	 * Built with the same shape production uses - `new Pool<>(true, false, 16)`, a
	 * {@link java.util.concurrent.LinkedBlockingQueue} ({@code DefaultCatalogPersistenceService:505},
	 * {@code DefaultEnginePersistenceService:134}) - and not the `new Pool<>(false, false, 1)` the WAL tests
	 * carried before, which is a bare {@link java.util.ArrayDeque} with unsynchronized `poll()`/`offer()`.
	 *
	 * The writer and every reader draw their Kryo from this one pool ({@code AbstractMutationLog:1345},
	 * {@code AbstractMutationSupplier:233}), so under the unsynchronized shape a racing obtain hands ONE Kryo to two
	 * threads - and a Kryo is not thread-safe. The garbage that follows is indistinguishable from a WAL read defect
	 * while proving nothing about one: it cost this line of work a day of misattributed evidence, with the
	 * {@link #KRYO_POOL_VIOLATIONS} probe below added to catch it if it ever comes back. Nothing here needs the
	 * single-threaded shape, so there is no way to opt into it.
	 */
	private final Pool<Kryo> catalogKryoPool = new Pool<>(true, false, 16) {
		@Override
		protected Kryo create() {
			return KryoFactory.createKryo(WalKryoConfigurer.INSTANCE);
		}

		@Override
		public Kryo obtain() {
			final Kryo kryo = super.obtain();
			synchronized (KRYO_IN_FLIGHT) {
				if (!KRYO_IN_FLIGHT.add(kryo)) {
					KRYO_POOL_VIOLATIONS.add(
						"DOUBLE OBTAIN of Kryo@" + System.identityHashCode(kryo) +
							" by " + Thread.currentThread().getName()
					);
				}
			}
			return kryo;
		}

		@Override
		public void free(Kryo object) {
			synchronized (KRYO_IN_FLIGHT) {
				if (!KRYO_IN_FLIGHT.remove(object)) {
					KRYO_POOL_VIOLATIONS.add(
						"FREE OF UNOBTAINED Kryo@" + System.identityHashCode(object) +
							" by " + Thread.currentThread().getName()
					);
				}
			}
			super.free(object);
		}
	};

	private final Path isolatedWalFilePath = this.walDirectory.resolve("isolatedWal.tmp");
	private final ObservableOutputKeeper observableOutputKeeper = ObservableOutputKeeper._internalBuild(
		Mockito.mock(Scheduler.class)
	);
	private final CatalogOffHeapMemoryManager bigOffHeapMemoryManager = new CatalogOffHeapMemoryManager(
		TEST_CATALOG, 10_000_000, 4, Crc32CChecksumFactory.INSTANCE
	);
	private CatalogWriteAheadLog wal;

	/**
	 * Records every construction of an evitaDB internal error. Bound to the hierarchy root only - the same class the
	 * production agent instruments - so each error is counted exactly once whatever its depth.
	 */
	public static class ErrorConstructionRecordingAdvice {

		@Advice.OnMethodExit
		public static void after(@Advice.This Object thiz) {
			if (ConcurrentWalTailReadStressTest.RECORDING_ERRORS
				&& Thread.currentThread().getName()
				.startsWith(ConcurrentWalTailReadStressTest.STRESS_THREAD_PREFIX)) {
				ConcurrentWalTailReadStressTest.CONSTRUCTED_ERRORS.add(String.valueOf(thiz));
			}
		}

	}

	@BeforeEach
	void setUp() throws IOException {
		cleanTestSubDirectory(getClass().getSimpleName());
		this.walDirectory.toFile().mkdirs();
		this.wal = createCatalogWriteAheadLogOfLargeEnoughSize();
	}

	@AfterEach
	void tearDown() throws IOException {
		this.observableOutputKeeper.close();
		this.wal.close();
		FileUtils.deleteDirectory(this.walDirectory);
	}

	@Test
	@Tag(SLOW)
	@DisplayName("tailing a WAL while it is being appended must not construct an internal error")
	void shouldNotConstructInternalErrorsWhileTailingAConcurrentlyAppendedWal() throws InterruptedException {
		final Instrumentation instrumentation = ByteBuddyAgent.install();
		// Only the error hierarchy root is retransformed, and types are described from the type pool rather
		// than by loading them. Both are required: Byte Buddy's defaults enumerate and load every class in a
		// surefire fork that already holds tens of thousands of them, which has been observed to wedge the
		// fork entirely rather than merely slow it down.
		final AgentBuilder.RedefinitionStrategy.DiscoveryStrategy errorRootOnly =
			new AgentBuilder.RedefinitionStrategy.DiscoveryStrategy.Explicit(EvitaInternalError.class);
		final ResettableClassFileTransformer transformer = new AgentBuilder.Default()
			.disableClassFormatChanges()
			.with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
			.with(errorRootOnly)
			.with(AgentBuilder.DescriptionStrategy.Default.POOL_ONLY)
			.ignore(
				nameStartsWith("net.bytebuddy.")
					.or(nameStartsWith("java."))
					.or(nameStartsWith("jdk."))
					.or(nameStartsWith("sun."))
			)
			.type(is(EvitaInternalError.class))
			.transform((builder, type, loader, module, domain) -> builder
				.visit(Advice.to(ErrorConstructionRecordingAdvice.class).on(isConstructor())))
			.installOn(instrumentation);

		final AtomicBoolean writerFinished = new AtomicBoolean();
		final List<Throwable> escapedFailures = Collections.synchronizedList(new ArrayList<>());
		final Set<Long> versionsSeenWhileRacing = ConcurrentHashMap.newKeySet();

		CONSTRUCTED_ERRORS.clear();
		KRYO_POOL_VIOLATIONS.clear();
		RECORDING_ERRORS = true;
		try {
			final Thread writer = new Thread(
				() -> {
					try {
						for (int i = 0; i < TRANSACTION_COUNT; i++) {
							CatalogWriteAheadLogIntegrationTest.writeWal(
								this.bigOffHeapMemoryManager,
								new int[]{TRANSACTION_SIZES[i % TRANSACTION_SIZES.length]},
								null,
								this.isolatedWalFilePath,
								this.observableOutputKeeper,
								this.wal
							);
						}
					} catch (Throwable ex) {
						escapedFailures.add(ex);
					} finally {
						writerFinished.set(true);
					}
				},
				STRESS_THREAD_PREFIX + "writer"
			);

			final List<Thread> readers = new ArrayList<>(READER_COUNT);
			for (int readerIndex = 0; readerIndex < READER_COUNT; readerIndex++) {
				readers.add(
					new Thread(
						() -> {
							// Each reader keeps its own pointer and re-opens the stream from it, which is
							// exactly how ChangeCatalogCaptureSharedPublisher#readWal behaves on every
							// notification - and it keeps the reader pinned near the moving tail, which is
							// where the race lives.
							long nextVersion = 1L;
							while (!writerFinished.get()) {
								try (
									final Stream<CatalogBoundMutation> stream =
										this.wal.getCommittedMutationStream(nextVersion)
								) {
									final Iterator<CatalogBoundMutation> iterator = stream.iterator();
									while (iterator.hasNext()) {
										final CatalogBoundMutation mutation = iterator.next();
										if (mutation instanceof TransactionMutation transactionMutation) {
											final long version = transactionMutation.getVersion();
											versionsSeenWhileRacing.add(version);
											nextVersion = Math.max(nextVersion, version);
										}
									}
								} catch (Throwable ex) {
									escapedFailures.add(ex);
									return;
								}
							}
						},
						STRESS_THREAD_PREFIX + "reader-" + readerIndex
					)
				);
			}

			writer.start();
			for (final Thread reader : readers) {
				reader.start();
			}
			writer.join(JOIN_TIMEOUT_MS);
			for (final Thread reader : readers) {
				reader.join(JOIN_TIMEOUT_MS);
			}

			assertTrue(writerFinished.get(), "The writer did not finish within " + JOIN_TIMEOUT_MS + " ms.");
		} finally {
			log.info(
				"[stress] kryoPoolViolations={}; escapedFailures={}; constructedErrors={}",
				KRYO_POOL_VIOLATIONS.size(), escapedFailures.size(), CONSTRUCTED_ERRORS.size()
			);
			RECORDING_ERRORS = false;
			transformer.reset(
				instrumentation, AgentBuilder.RedefinitionStrategy.RETRANSFORMATION, errorRootOnly
			);
		}

		if (!escapedFailures.isEmpty()) {
			final StringBuilder detail = new StringBuilder(2048);
			detail.append("Reading a WAL while it was being appended to surfaced ")
				.append(escapedFailures.size()).append(" failure(s) to the caller. Stack of the first:\n");
			final StringWriter stackWriter = new StringWriter();
			escapedFailures.get(0).printStackTrace(new PrintWriter(stackWriter));
			detail.append(stackWriter);
			detail.append("\nInternal errors constructed during the same run: ")
				.append(Set.copyOf(CONSTRUCTED_ERRORS));
			detail.append("\nKryo pool concurrency violations: ").append(KRYO_POOL_VIOLATIONS.size())
				.append(' ').append(KRYO_POOL_VIOLATIONS.stream().limit(10).toList());
			fail(detail.toString());
		}

		assertTrue(
			CONSTRUCTED_ERRORS.isEmpty(),
			"Tailing a WAL that was being appended to constructed " + CONSTRUCTED_ERRORS.size() +
				" evitaDB internal error(s). Nothing was thrown to a caller and nothing was logged - the " +
				"supplier handles this as a graceful end-of-stream - but the observability agent counts the " +
				"construction itself, so each one moves io_evitadb_errors_total and raises the " +
				"EVITA_DB_INTERNAL_ERRORS health signal for a condition that is by design recoverable. " +
				"Distinct errors observed: " + Set.copyOf(CONSTRUCTED_ERRORS)
		);

		// Finally, with the writer stopped, a single quiet read must still see every committed transaction in
		// ascending order - a run that stayed silent by losing data would otherwise pass the assertions above.
		final List<Long> versionsAfterQuiesce = new ArrayList<>(TRANSACTION_COUNT);
		try (
			final Stream<CatalogBoundMutation> stream = this.wal.getCommittedMutationStream(1L)
		) {
			stream.forEach(
				mutation -> {
					if (mutation instanceof TransactionMutation transactionMutation) {
						versionsAfterQuiesce.add(transactionMutation.getVersion());
					}
				}
			);
		}

		final List<Long> expectedVersions = new ArrayList<>(TRANSACTION_COUNT);
		for (long version = 1L; version <= TRANSACTION_COUNT; version++) {
			expectedVersions.add(version);
		}
		assertEquals(
			expectedVersions,
			versionsAfterQuiesce,
			"After the writer stopped, a quiet read of the WAL did not return exactly the transactions that " +
				"were committed, in order. Concurrent tailing must not cost a transaction or reorder one."
		);
	}

	/**
	 * Creates a WAL whose rotation threshold is high enough that the whole run stays in a single file - rotation is
	 * covered elsewhere, and a rotation here would make the quiesced verification at the end ambiguous.
	 *
	 * @return the write-ahead log under test
	 */
	@Nonnull
	private CatalogWriteAheadLog createCatalogWriteAheadLogOfLargeEnoughSize() {
		return new CatalogWriteAheadLog(
			0L,
			TEST_CATALOG,
			new LogFileRecordReference(index -> getWalFileName(TEST_CATALOG, index)),
			this.walDirectory,
			this.catalogKryoPool,
			new StorageSettings(
				StorageOptions.builder()
					.compress(false)
					.build(),
				TransactionOptions.builder()
					.walFileSizeBytes(Long.MAX_VALUE)
					.build()
			),
			Mockito.mock(Scheduler.class),
			// the consumer is only notified of versions becoming eligible for trimming, which this test neither
			// triggers nor inspects
			version -> {
			}
		);
	}

}
