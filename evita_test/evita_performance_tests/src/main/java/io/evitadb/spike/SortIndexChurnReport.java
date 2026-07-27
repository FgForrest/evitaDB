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

package io.evitadb.spike;

import io.evitadb.index.attribute.OwnerSortIndex;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.SortIndexStoragePart;
import io.evitadb.spike.SortIndexBenchSupport.ValueBlock;
import org.openjdk.jol.info.GraphLayout;

import javax.annotation.Nonnull;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic, noise-free measurement of the OWNER-mode {@link OwnerSortIndex} granular persistence story on the
 * current #760 branch. For each scenario (the real `decodoma` ean anchor plus three synthetic distributions that
 * replicate the anchor's cardinality SHAPE at 10k / 100k / 1m distinct values) it reports:
 *
 * 1. N (distinct values), R (total records), and whether the owned value tree is `PAGED`.
 * 2. `fullPersistBytes` — the serialized size of the FULL initial emit (every leaf page plus the root), summed by
 *    serializing each part DIRECTLY through its Kryo serializer.
 * 3. `churnBytesPerCommit` (the headline) — the serialized size of ONE incremental commit after the index is brought to
 *    a persisted steady state (persist + reload, which restores the page-stream change-detection baseline) and a single
 *    record is added to an existing value (cardinality 1 -> 2, no split). Only the changed leaf page(s) plus the
 *    re-written root are counted; the leaf-page count and removal count of that commit are reported alongside.
 * 4. `liveHeapBytes` — the JOL deep size of the fully-owned live index graph, with the positional `sortedRecords` array
 *    broken out.
 * 5. A note confirming the PAGED load path ({@link OwnerSortIndex#fromPersistedPages} + `reconstructSortedRecords`).
 *
 * The numbers are fully deterministic so a sibling `dev`-branch mirror can be lined up cell-for-cell. The table is
 * printed to stdout and also written to `/var/tmp/decodoma-bench/report-760.txt`.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class SortIndexChurnReport {

	/**
	 * Destination of the rendered report (mirrored to stdout).
	 */
	private static final Path REPORT_FILE = Path.of("/var/tmp/decodoma-bench/report-760.txt");
	/**
	 * The scenarios measured, in order.
	 */
	private static final String[] SCENARIOS = {"anchor", "synth_10k", "synth_100k", "synth_1m"};

	/**
	 * The fully-measured result row for one scenario.
	 */
	private record ScenarioResult(
		@Nonnull String name, int distinctValues, int totalRecords, boolean paged,
		long fullPersistBytes, long churnBytes, int churnLeafPages, int churnRemovals,
		long liveHeapBytes, long sortedRecordsHeapBytes
	) {
	}

	public static void main(@Nonnull String[] args) throws Exception {
		final List<ScenarioResult> results = new ArrayList<>();
		for (final String scenario : SCENARIOS) {
			results.add(measure(scenario));
		}

		final String table = render(results);
		System.out.print(table);
		Files.createDirectories(REPORT_FILE.getParent());
		try (final PrintStream out = new PrintStream(Files.newOutputStream(REPORT_FILE), true, StandardCharsets.UTF_8)) {
			out.print(table);
		}
		System.out.println("Report written to " + REPORT_FILE);
	}

	/**
	 * Builds the owner index for one scenario and computes every reported metric.
	 */
	@Nonnull
	private static ScenarioResult measure(@Nonnull String scenario) {
		System.out.println("Measuring scenario '" + scenario + "' ...");
		final List<ValueBlock> blocks = SortIndexBenchSupport.blocksFor(scenario);
		final OwnerSortIndex owner = SortIndexBenchSupport.buildOwner(blocks);

		// 1) FULL initial emit + its serialized size + paged flag
		final List<StoragePart> fullEmission = SortIndexBenchSupport.emit(owner);
		final SortIndexStoragePart fullRoot = SortIndexBenchSupport.root(fullEmission);
		final boolean paged = fullRoot.isPaged();
		final long fullPersistBytes = SortIndexBenchSupport.serializedBytes(fullEmission);

		// 2) live heap (fully-owned graph) + sortedRecords breakdown
		final long liveHeapBytes = GraphLayout.parseInstance(owner).totalSize();
		final long sortedRecordsHeapBytes = GraphLayout.parseInstance((Object) owner.getSortedRecords()).totalSize();

		// 3) churn: persist the full emit, reload (restores the page-stream baseline), apply ONE single-record add to an
		//    existing value (cardinality 1 -> 2, no split), then measure ONLY the incremental commit
		final List<StoragePart> churnEmission = SortIndexBenchSupport.incrementalChurnParts(blocks);
		final int churnLeafPages = SortIndexBenchSupport.leafPages(churnEmission).size();
		final int churnRemovals = SortIndexBenchSupport.removals(churnEmission).size();
		final long churnBytes = SortIndexBenchSupport.serializedBytes(churnEmission);

		return new ScenarioResult(
			scenario, blocks.size(), SortIndexBenchSupport.totalRecords(blocks), paged,
			fullPersistBytes, churnBytes, churnLeafPages, churnRemovals,
			liveHeapBytes, sortedRecordsHeapBytes
		);
	}

	@Nonnull
	private static String render(@Nonnull List<ScenarioResult> results) {
		final StringBuilder sb = new StringBuilder(4_096);
		sb.append("================================================================================\n");
		sb.append(" SortIndex (OwnerSortIndex) granular-persistence report — branch #760\n");
		sb.append(" loadStructureNote: PAGED owners reload via OwnerSortIndex.fromPersistedPages +\n");
		sb.append("                    SortIndexView.reconstructSortedRecords (sortedRecords NOT persisted).\n");
		sb.append("================================================================================\n");
		sb.append(String.format(
			"%-12s %9s %9s %6s %15s %14s %6s %5s %15s %15s%n",
			"scenario", "N", "R", "paged", "fullPersistB", "churnB/commit", "lpgs", "rmv", "liveHeapB", "sortedRecB"
		));
		sb.append("------------------------------------------------------------------------------------------------------------------------\n");
		for (final ScenarioResult r : results) {
			sb.append(String.format(
				"%-12s %9d %9d %6s %15d %14d %6d %5d %15d %15d%n",
				r.name(), r.distinctValues(), r.totalRecords(), r.paged(),
				r.fullPersistBytes(), r.churnBytes(), r.churnLeafPages(), r.churnRemovals(),
				r.liveHeapBytes(), r.sortedRecordsHeapBytes()
			));
		}
		sb.append("------------------------------------------------------------------------------------------------------------------------\n");
		sb.append("Legend: fullPersistB = sum of serialized leaf pages + root (full initial emit).\n");
		sb.append("        churnB/commit = serialized bytes of ONE incremental commit (changed leaf page(s) + re-written root)\n");
		sb.append("                        after persist+reload steady state, growing one existing value 1->2 (no split).\n");
		sb.append("        lpgs = changed leaf pages in that commit; rmv = leaf-page removals in that commit.\n");
		sb.append("        liveHeapB = JOL deep size of the live OwnerSortIndex graph; sortedRecB = JOL size of getSortedRecords() int[].\n");
		return sb.toString();
	}
}
