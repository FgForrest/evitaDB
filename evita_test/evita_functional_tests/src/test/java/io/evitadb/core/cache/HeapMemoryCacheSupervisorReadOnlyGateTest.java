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

package io.evitadb.core.cache;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.configuration.CacheOptions;
import io.evitadb.api.configuration.ThreadPoolOptions;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.RangeCountFormula;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.TransactionalBitmap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.annotation.Nonnull;
import java.io.IOException;

import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.TestTags.CACHE;
import static io.evitadb.test.TestTags.ENGINE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Pins the read-only gate in {@link HeapMemoryCacheSupervisor#analyse(EvitaSessionContract, String, Formula)} - the
 * single `if` that keeps a write session out of the formula cache.
 *
 * It matters more than one line usually does. A range formula built inside an open transaction hashes identically to
 * the one built over the committed view, because a `TransactionalBitmap`'s id is a per-instance sequence value the
 * overlay does not change (`RangeFormulaCacheKeyProbeTest` demonstrates that collision). Nothing downstream of this
 * method can tell the two apart, so if a write session ever reached the cache it would be served its own
 * pre-transaction answer.
 *
 * **Calibration.** The two tests below are each other's counterfactual. The read-only test proves the visitor really
 * does wrap THIS formula - so the read-write test's `assertSame` cannot be passing because the formula was too cheap
 * or the wrong shape to be admitted. Remove the gate and the read-write test is the one that goes red.
 *
 * The subject is a {@link RangeCountFormula} on purpose: it is a cacheable formula with NO inner formulas, and the
 * admission path clones it through `getCloneWithComputationCallback`, which asserts exactly that.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(ENGINE)
@Tag(CACHE)
@DisplayName("HeapMemoryCacheSupervisor: the read-only gate on formula caching")
class HeapMemoryCacheSupervisorReadOnlyGateTest {
	/**
	 * Entity type the analysed formulas are attributed to - opaque to this test.
	 */
	private static final String SOME_ENTITY = "SomeEntity";
	/**
	 * Arbitrary non-zero index id - the staleness token a range count formula is required to carry.
	 */
	private static final long INDEX_ID = 13L;
	private Scheduler scheduler;
	private HeapMemoryCacheSupervisor supervisor;

	/**
	 * Builds the formula both tests analyse - cheap to compute, but comfortably past the complexity threshold
	 * configured below, so admission is decided by the session and nothing else.
	 *
	 * @return the formula under test
	 */
	@Nonnull
	private static RangeCountFormula rangeCountFormula() {
		return new RangeCountFormula(
			INDEX_ID,
			new Bitmap[]{new TransactionalBitmap(1, 2, 3, 4, 5), new TransactionalBitmap(2, 3, 4, 5, 6)},
			new Bitmap[]{new TransactionalBitmap(5, 6, 7)}
		);
	}

	/**
	 * Builds a session stub answering only the two questions the analysed path asks it.
	 *
	 * @param readOnly what the session reports for {@link EvitaSessionContract#isReadOnly()}
	 * @return the stubbed session
	 */
	@Nonnull
	private static EvitaSessionContract sessionThatIsReadOnly(boolean readOnly) {
		final EvitaSessionContract session = Mockito.mock(EvitaSessionContract.class);
		Mockito.when(session.isReadOnly()).thenReturn(readOnly);
		Mockito.when(session.getCatalogName()).thenReturn(TEST_CATALOG);
		return session;
	}

	@BeforeEach
	void setUp() {
		this.scheduler = new Scheduler(
			ThreadPoolOptions.requestThreadPoolBuilder()
				.minThreadCount(4)
				.maxThreadCount(4)
				.build()
		);
		this.supervisor = new HeapMemoryCacheSupervisor(
			CacheOptions.builder()
				.enabled(true)
				// long enough that the periodic re-evaluation never runs during a test method
				.reevaluateEachSeconds(3600)
				.anteroomRecordCount(10_000)
				// low enough that the subject formula is always complex enough to be admitted
				.minimalComplexityThreshold(30L)
				.minimalUsageThreshold(1)
				.cacheSizeInBytes(1_000_000L)
				.build(),
			this.scheduler
		);
	}

	@AfterEach
	void tearDown() throws IOException {
		// the supervisor owns the anteroom and the re-evaluation task; leaving either registered keeps a Flight
		// Recorder hook and a self-replanning task alive for the whole surefire fork
		this.supervisor.close();
		this.scheduler.shutdown();
	}

	@Test
	@DisplayName("A read-write session gets its formula back untouched")
	void shouldReturnTheFormulaUntouchedForAReadWriteSession() {
		final RangeCountFormula formula = rangeCountFormula();

		final Formula analysed = this.supervisor.analyse(sessionThatIsReadOnly(false), SOME_ENTITY, formula);

		assertSame(
			formula, analysed,
			"A read-write session may already carry uncommitted changes the formula key cannot see, so its formula " +
				"must never be routed through the cache"
		);
	}

	@Test
	@DisplayName("A read-only session gets an instrumented copy registered with the cache")
	void shouldWrapTheFormulaForAReadOnlySession() {
		final RangeCountFormula formula = rangeCountFormula();

		final Formula analysed = this.supervisor.analyse(sessionThatIsReadOnly(true), SOME_ENTITY, formula);

		assertNotSame(
			formula, analysed,
			"A read-only session's formula must be wrapped, or the test above proves nothing about the gate"
		);
		// the instrumented copy has to answer identically - it only adds the computation callback
		assertArrayEquals(formula.compute().getArray(), analysed.compute().getArray());
	}
}
