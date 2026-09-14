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

package io.evitadb.core;

import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.configuration.TransactionOptions;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.nio.file.Path;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.OBSERVABILITY;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Verifies that a closed engine becomes collectable — that closing it releases the Flight Recorder periodic hooks
 * it registered, and nothing else retains it either.
 *
 * The hooks are why this needs a test of its own rather than a line in an existing one.
 * {@link jdk.jfr.FlightRecorder#addPeriodicEvent(java.lang.Class, Runnable)} files a hook in a registry that lives
 * for the whole JVM, and every hook this engine registers captures the engine (or one of its executors) in order
 * to read statistics off it. That registry is a static of the JDK, so a hook left in it is a GC root: an engine
 * that has closed every executor, terminated every catalog and released every buffer still cannot be collected,
 * and neither can the catalogs, persistence services and output buffers hanging off it. One engine per process
 * hides this completely; a test suite that builds thousands of them exhausts the heap.
 *
 * The assertion is deliberately made against reachability rather than against the hook registry, which has no
 * public API to count: what matters is not how many hooks were handed back but whether anything at all still
 * holds the engine, and a weak reference answers exactly that question without caring which retainer was at fault.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Engine retention after close")
@Tag(ENGINE)
@Tag(OBSERVABILITY)
class EvitaJfrHookRetentionTest implements EvitaTestSupport {
	/**
	 * How many collection attempts the assertion makes before giving up. `System.gc()` is a request rather than a
	 * command, so a single failed attempt proves nothing; a run of them does.
	 */
	private static final int COLLECTION_ATTEMPTS = 50;

	private TestPaths testPaths;
	private Path storageDirectory;

	@BeforeEach
	void setUp() throws IOException {
		this.testPaths = createTestPaths(EvitaJfrHookRetentionTest.class.getSimpleName());
		this.storageDirectory = this.testPaths.storage();
		Files.createDirectories(this.storageDirectory);
	}

	@AfterEach
	void tearDown() {
		cleanupTestPaths(this.testPaths);
	}

	@Test
	@DisplayName("should let a closed engine be collected once its Flight Recorder hooks are handed back")
	void shouldNotRetainClosedEngine() {
		final WeakReference<Evita> engineReference = bootUseAndCloseEngine();
		assertCollectable(
			engineReference,
			"A closed engine is still reachable. Every Flight Recorder periodic hook it registered is held by a " +
				"JVM-lifetime static of the JDK and released only against the very instance registered, so a hook " +
				"that was not handed back on close pins the engine - and everything it owns - for the rest of the " +
				"process."
		);
	}

	/**
	 * Boots an engine, gives it the hooks a real deployment would register, closes it, and hands back only a weak
	 * reference to it.
	 *
	 * The engine is confined to this method on purpose: a local variable in the *calling* frame would keep it
	 * strongly reachable for the whole test and the assertion could never fail, whichever way the code behaved.
	 *
	 * Every path that registers a hook is walked, because the claim under test is a quantifier - that *no* hook
	 * survives the close - and a test that only ever created one catalog would pass with three of the four
	 * registration sites still leaking.
	 *
	 * @return weak reference to the engine that has just been closed
	 */
	@Nonnull
	private WeakReference<Evita> bootUseAndCloseEngine() {
		final Evita evita = bootEvita();
		evita.waitUntilFullyInitialized();
		// a catalog is what makes the per-catalog hook exist at all - without one there is nothing to release.
		// `products` is left standing, so its hook can only ever be released by the close itself
		evita.defineCatalog("products");
		// a rename registers under the new name and retires the old one; a drop retires what is left. Both are
		// walked so that a hook stranded half-way through either would still be holding the engine at the end
		evita.defineCatalog("orders");
		evita.renameCatalog("orders", "invoices");
		dropCatalog(evita, "invoices");
		// the engine-wide hooks are normally registered by the observability API, which does not run here
		evita.emitStartObservabilityEvents();
		evita.close();
		return new WeakReference<>(evita);
	}

	/**
	 * Removes a catalog and waits for the removal to complete, so the engine mutation that retires the catalog's
	 * hook has been through the change stream before the engine is closed.
	 *
	 * @param evita       running engine to ask
	 * @param catalogName name of the catalog to remove
	 */
	private static void dropCatalog(@Nonnull Evita evita, @Nonnull String catalogName) {
		evita.deleteCatalogIfExistsWithProgress(catalogName)
			.orElseThrow()
			.onCompletion()
			.toCompletableFuture()
			.join();
	}

	/**
	 * Asserts that the referent is collectable, retrying because an explicit collection is only ever a request.
	 *
	 * @param reference reference whose referent must become unreachable
	 * @param message   what it means if it does not
	 */
	private static void assertCollectable(@Nonnull WeakReference<?> reference, @Nonnull String message) {
		for (int attempt = 0; attempt < COLLECTION_ATTEMPTS && reference.get() != null; attempt++) {
			System.gc();
			try {
				Thread.sleep(50);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			}
		}
		assertNull(reference.get(), message);
	}

	@Nonnull
	private Evita bootEvita() {
		return new Evita(
			newTestEvitaConfigurationBuilder(this.testPaths)
				.storage(
					StorageOptions.builder()
						.storageDirectory(this.storageDirectory)
						.workDirectory(this.testPaths.work())
						.build()
				)
				.transaction(
					TransactionOptions.builder()
						.transactionMemoryBufferLimitSizeBytes(1024 << 10)
						.transactionMemoryRegionCount(4)
						.build()
				)
				.build()
		);
	}

}
