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

// TODO JNO - remove at the end of #1615 (the whole file)
package io.evitadb.index.virtual;

import io.evitadb.core.catalog.VirtualReferencePartitionMaintenance;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.TestSource;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The non-vacuity listing of the forced run of the functional suite (see the forced mode of
 * {@link VirtualReferencePartitionMaintenance}): one line per finished test, appended to
 * {@link #LISTING_FILE}, saying whether the test was **eligible** - whether some query it planned ran over a catalog
 * holding registered single-owner partitions of a collection read virtually - and how many virtual partition handles
 * and virtual rows its queries read:
 *
 * ```
 * <class>#<method>[<invocation>]	eligible=yes|no	reached=yes|no	handles=<n>	rows=<n>
 *     	ineligibleLoads=<n>	concurrent=yes|no	<status>
 * ```
 *
 * (one line in the file, wrapped here).
 *
 * `reached` narrows `eligible` down to the queries that resolved partitions of a collection holding registered
 * partitions - those that asked a reference route of a collection holding something to read virtually. A reached
 * test reporting `rows=0` resolved partitions of such a collection without reading any of them virtually - the
 * listing to triage first; an eligible test that was never reached merely ran queries the virtual reading has no part
 * in, or touched only references whose partitions all have several owners.
 * `ineligibleLoads` counts the catalogs the test loaded from disk that the forced mode could not trust (see
 * {@link VirtualReferencePartitionMaintenance#getForcedIneligibleLoads()}).
 *
 * The counters are global to the JVM, so a line is exact only when the tests run one at a time - the forced run
 * disables the parallel execution of JUnit for that reason. A line written while another test was running too says
 * `concurrent=yes` and must not be trusted.
 *
 * Registered as a service for every run, it does nothing at all unless the JVM runs in the forced mode.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class ForcedVirtualReadNonVacuityListener implements TestExecutionListener {
	/**
	 * The listing, relative to the working directory of the test JVM - the module directory under surefire.
	 */
	public static final Path LISTING_FILE = Path.of("target", "virtual-partition-non-vacuity.txt");
	/**
	 * How many tests are running right now.
	 */
	private final AtomicInteger runningTests = new AtomicInteger();
	/**
	 * Virtual handles resolved before the running test started.
	 */
	private volatile long handlesAtStart;
	/**
	 * Virtual rows read before the running test started.
	 */
	private volatile long rowsAtStart;
	/**
	 * Ineligible catalog loads before the running test started.
	 */
	private volatile long ineligibleLoadsAtStart;
	/**
	 * TRUE when another test overlapped the running one.
	 */
	private volatile boolean overlapped;

	@Override
	public void executionStarted(@Nonnull TestIdentifier testIdentifier) {
		if (!VirtualReferencePartitionMaintenance.FORCED || !testIdentifier.isTest()) {
			return;
		}
		if (this.runningTests.incrementAndGet() > 1) {
			this.overlapped = true;
		} else {
			this.overlapped = false;
			VirtualReferencePartitionMaintenance.resetForcedQueryEligibility();
			this.handlesAtStart = VirtualReferencePartitionMaintenance.getForcedHandlesResolved();
			this.rowsAtStart = VirtualReferencePartitionMaintenance.getForcedRowsRead();
			this.ineligibleLoadsAtStart = VirtualReferencePartitionMaintenance.getForcedIneligibleLoads();
		}
	}

	@Override
	public void executionFinished(
		@Nonnull TestIdentifier testIdentifier,
		@Nonnull TestExecutionResult testExecutionResult
	) {
		if (!VirtualReferencePartitionMaintenance.FORCED || !testIdentifier.isTest()) {
			return;
		}
		final boolean concurrent = this.overlapped || this.runningTests.get() > 1;
		final String line = describe(testIdentifier) +
			"\teligible=" + (VirtualReferencePartitionMaintenance.isForcedQueryEligible() ? "yes" : "no") +
			"\treached=" + (VirtualReferencePartitionMaintenance.isForcedQueryReached() ? "yes" : "no") +
			"\thandles=" + (VirtualReferencePartitionMaintenance.getForcedHandlesResolved() - this.handlesAtStart) +
			"\trows=" + (VirtualReferencePartitionMaintenance.getForcedRowsRead() - this.rowsAtStart) +
			"\tineligibleLoads=" +
			(VirtualReferencePartitionMaintenance.getForcedIneligibleLoads() - this.ineligibleLoadsAtStart) +
			"\tconcurrent=" + (concurrent ? "yes" : "no") +
			"\t" + testExecutionResult.getStatus() + "\n";
		this.runningTests.decrementAndGet();
		try {
			// one small write in append mode, so lines of several forks never interleave
			Files.createDirectories(LISTING_FILE.getParent());
			Files.writeString(
				LISTING_FILE, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND
			);
		} catch (IOException ex) {
			throw new UncheckedIOException("Cannot append to `" + LISTING_FILE.toAbsolutePath() + "`!", ex);
		}
	}

	/**
	 * Names the test: `class#method` with the invocation display name for a method source, the unique id otherwise.
	 *
	 * @param testIdentifier the test
	 * @return its name, free of tabs and line breaks
	 */
	@Nonnull
	private static String describe(@Nonnull TestIdentifier testIdentifier) {
		final TestSource source = testIdentifier.getSource().orElse(null);
		final String name = source instanceof final MethodSource methodSource ?
			methodSource.getClassName() + "#" + methodSource.getMethodName() + "[" +
				testIdentifier.getDisplayName() + "]" :
			testIdentifier.getUniqueId();
		return name.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
	}
}
