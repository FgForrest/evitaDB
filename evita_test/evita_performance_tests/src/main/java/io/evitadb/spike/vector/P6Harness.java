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

package io.evitadb.spike.vector;

import io.evitadb.spike.footprint.FootprintSpikeSupport;

import javax.annotation.Nonnull;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

/**
 * The entry point of the P6 vector spike's measurement harness: runs one scenario over one dataset.
 *
 * ```
 * java <jvm flags> -cp <cp> io.evitadb.spike.vector.P6Harness <workDir> <scenario>
 * java -cp <cp> io.evitadb.spike.vector.P6Harness list
 * ```
 *
 * The work directory is a dataset ({@link SpikeDataset}, described by its `dataset.properties`). Every scenario
 * names the criterion of `p6-vector-spike-spec.md` it measures and the measurement of `p6-measurement-results.md`
 * its output is recorded in; `list` prints the catalogue. Run every scenario in its own JVM, one at a time, on
 * JDK 21 with SIMD forced: `p6-measurement-harness.md` beside the results doc has the flags, the procedure
 * for a new dataset, and the measurements that run outside this class (JMH latency, embedding the queries).
 *
 * @author Lukáš Hornych (hornych@fg.cz)
 */
public final class P6Harness {
	/** Number of out-of-corpus query vectors used by the parts 1–8 scenarios. */
	private static final int VISUAL_QUERY_COUNT = 100;

	private P6Harness() {
		throw new UnsupportedOperationException("Entry point, not to be instantiated.");
	}

	/**
	 * Runs one scenario, or prints the catalogue.
	 *
	 * @param args work directory and scenario name, or `list`
	 * @throws Exception when the dataset cannot be read or a scenario fails
	 */
	public static void main(@Nonnull String[] args) throws Exception {
		if (args.length == 1 && "list".equals(args[0])) {
			for (final Scenario scenario : Scenario.values()) {
				System.out.printf("%-18s %-34s %-10s %s%n", scenario.id, scenario.criterion, scenario.part,
					scenario.description);
			}
			return;
		}
		if (args.length != 2) {
			System.err.println("Usage: P6Harness <workDir> <scenario> | P6Harness list");
			System.exit(1);
		}
		final Scenario scenario = Scenario.of(args[1]);
		final SpikeDataset dataset = SpikeDataset.open(Path.of(args[0]));
		final MappedVectors base = dataset.base();
		System.out.println(FootprintSpikeSupport.describeLayout());
		System.out.println(
			"JVM " + Runtime.version() + ", max heap " + VectorMemoryProbe.mb(Runtime.getRuntime().maxMemory())
				+ ", Lucene " + org.apache.lucene.util.Version.LATEST + ", dataset " + dataset.label()
				+ ", corpus " + base.count() + " x " + dataset.dimension()
				+ " (raw " + VectorMemoryProbe.mb((long) base.count() * dataset.dimension() * Float.BYTES) + ")"
				+ ", scenario " + scenario.id
		);
		scenario.runner.run(dataset);
	}

	/**
	 * @param dataset the dataset
	 * @return the exact top ten of the out-of-corpus queries the parts 1–8 scenarios measure recall with: the visual
	 *         embeddings, or the query block when the export has none
	 * @throws IOException when the query block cannot be mapped
	 */
	@Nonnull
	private static VectorGroundTruth visualTruth(@Nonnull SpikeDataset dataset) throws IOException {
		final MappedVectors visual = new MappedVectors(
			dataset.workDir().resolve(VectorExportExtractor.VISUAL_FILE), dataset.dimension()
		);
		// an export without visual embeddings: the first queries of the query block stand in for them
		final MappedVectors queries = visual.count() > 0
			? visual : new MappedVectors(dataset.requireQueries(), dataset.dimension());
		final float[][] vectors = new float[Math.min(VISUAL_QUERY_COUNT, queries.count())][];
		for (int i = 0; i < vectors.length; i++) {
			vectors[i] = queries.copy(i);
		}
		return new VectorGroundTruth(dataset.base(), vectors, 10);
	}

	/**
	 * Prints how the corpus divides into collections and locales, the counts a filter `collection` is chosen by.
	 *
	 * @param dataset the dataset, prepared by opening it
	 * @throws IOException when the ordinal mapping cannot be read
	 */
	private static void describe(@Nonnull SpikeDataset dataset) throws IOException {
		final Map<String, int[]> counts = new TreeMap<>();
		try (final BufferedReader reader = Files.newBufferedReader(
			dataset.workDir().resolve(VectorExportExtractor.TEXT_ORDINALS_FILE), StandardCharsets.UTF_8)) {
			String line;
			String previous = null;
			while ((line = reader.readLine()) != null) {
				final String[] parts = line.split("\t", -1);
				final int[] count = counts.computeIfAbsent(parts[0] + " / " + parts[2], k -> new int[2]);
				count[0]++;
				final String record = parts[0] + '\t' + parts[1] + '\t' + parts[2];
				if (!record.equals(previous)) {
					count[1]++;
				}
				previous = record;
			}
		}
		for (final Map.Entry<String, int[]> entry : counts.entrySet()) {
			System.out.printf("%-48s %9d vectors %8d records%n", entry.getKey(), entry.getValue()[0],
				entry.getValue()[1]);
		}
		final Path queries = dataset.workDir().resolve(ClickQueryEmbedder.QUERY_VECTORS_FILE);
		System.out.println("query block: " + (Files.exists(queries)
			? new MappedVectors(queries, dataset.dimension()).count() + " vectors" : "none"));
	}

	/**
	 * Runs a scenario over a dataset.
	 */
	@FunctionalInterface
	private interface Runner {
		/**
		 * @param dataset the dataset
		 * @throws Exception when the scenario fails
		 */
		void run(@Nonnull SpikeDataset dataset) throws Exception;
	}

	/**
	 * The catalogue: every scenario with the spec criterion it measures and the doc part that records it.
	 */
	private enum Scenario {
		PREPARE("prepare", "§4.3 dataset", "-",
			"extracts the blocks, holds out the queries, prints the collections and locales",
			P6Harness::describe),
		JVECTOR_HEAP("jvector-heap", "§2.8 memory", "results 1", "jVector graph with raw vectors on the heap",
			d -> JVectorFootprintScenarios.build(d.base(), visualTruth(d), d.workDir(), false)),
		JVECTOR_MMAP("jvector-mmap", "§2.3 off-heap, §2.8 memory", "results 1",
			"jVector graph over mapped raw vectors; writes the on-disk index",
			d -> JVectorFootprintScenarios.build(d.base(), visualTruth(d), d.workDir(), true)),
		JVECTOR_ONDISK("jvector-ondisk", "§2.3 off-heap", "results 1", "jVector's OnDiskGraphIndex reopened",
			d -> JVectorFootprintScenarios.openOnDisk(visualTruth(d), d.workDir(), d.base().count())),
		LUCENE_HNSW("lucene-hnsw", "§2.8 memory", "results 1", "Lucene util.hnsw as a library over mapped vectors",
			d -> LuceneFootprintScenarios.hnswLibrary(d.base(), visualTruth(d))),
		LUCENE_FLOAT32("lucene-float32", "§2.8 memory, §3.5 codecs", "results 1, 3", "full Lucene index, float32",
			d -> LuceneFootprintScenarios.fullIndex(d.base(), visualTruth(d), d.workDir(), "float32")),
		LUCENE_OSQ8("lucene-osq8", "§3.5 codecs", "results 1, 3", "full Lucene index, OSQ 8-bit",
			d -> LuceneFootprintScenarios.fullIndex(d.base(), visualTruth(d), d.workDir(), "osq8")),
		LUCENE_OSQ7("lucene-osq7", "§3.5 codecs", "results 1, 3", "full Lucene index, OSQ 7-bit",
			d -> LuceneFootprintScenarios.fullIndex(d.base(), visualTruth(d), d.workDir(), "osq7")),
		LUCENE_OSQ4("lucene-osq4", "§3.5 codecs", "results 1, 3", "full Lucene index, OSQ 4-bit",
			d -> LuceneFootprintScenarios.fullIndex(d.base(), visualTruth(d), d.workDir(), "osq4")),
		LUCENE_OSQ2("lucene-osq2", "§3.5 codecs", "results 1, 3", "full Lucene index, OSQ 2-bit",
			d -> LuceneFootprintScenarios.fullIndex(d.base(), visualTruth(d), d.workDir(), "osq2")),
		LUCENE_OSQ1("lucene-osq1", "§3.5 codecs", "results 1, 3", "full Lucene index, OSQ 1-bit (RaBitQ-derived)",
			d -> LuceneFootprintScenarios.fullIndex(d.base(), visualTruth(d), d.workDir(), "osq1")),
		LUCENE_WIRED_OSQ1("lucene-wired-osq1", "§2.6 seams", "results 2", "Lucene graph + our OSQ 1-bit code file",
			d -> LuceneWiringScenario.run(d.base(), visualTruth(d), d.workDir(), "osq1")),
		LUCENE_WIRED_OSQ4("lucene-wired-osq4", "§2.6 seams", "results 2", "Lucene graph + our OSQ 4-bit code file",
			d -> LuceneWiringScenario.run(d.base(), visualTruth(d), d.workDir(), "osq4")),
		LUCENE_WIRED_OSQ7("lucene-wired-osq7", "§2.6 seams", "results 2", "Lucene graph + our OSQ 7-bit code file",
			d -> LuceneWiringScenario.run(d.base(), visualTruth(d), d.workDir(), "osq7")),
		JVECTOR_WIRED("jvector-wired", "§2.6 seams", "results 2", "jVector graph + PQ/BQ codes written and reloaded",
			d -> JVectorWiringScenario.run(d.base(), visualTruth(d), d.workDir())),
		CLICK_RECALL("click-recall", "§1.2 recall, §3.4 filtered", "results 5, 7",
			"every code and strategy on the production queries, against the exact scan and the clicks",
			d -> ClickRecallScenario.run(d.base(), d.workDir(), d.collection())),
		OWN_SEARCHER("own-searcher", "§2.7 searcher, §3.5 codecs", "results 8",
			"our searcher over the jVector graph, every code on one graph, cancellation",
			d -> GraphSeamScenarios.ownSearcher(d.base(), d.workDir(), d.collection())),
		GRAPH_FAMILY("graph-family", "§3.2 graph family (step 4)", "results 9",
			"jVector alpha 1.0 / 1.2 x hierarchy on / off",
			d -> GraphSeamScenarios.graphFamily(d.base(), d.workDir(), d.collection())),
		GRAPH_FILE("graph-file", "§2.3 storage", "results 10", "the graph written into our file and walked mapped",
			d -> GraphSeamScenarios.graphFile(d.base(), d.workDir(), d.collection())),
		FILTER_MATRIX("filter-matrix", "§4.4 filtered matrix", "results 6",
			"selectivity x predicate shape x strategy, Roaring vs flat bitset",
			d -> FilteredMatrixScenario.run(d.base(), d.workDir())),
		CHURN("churn", "§4.7 churn", "results 11", "inserts, deletes, cleanup and replacement under concurrent readers",
			d -> ChurnScenario.run(d.base(), d.workDir())),
		CHURN_COMBINED("churn-combined", "§4.7 churn", "results 11",
			"the jVector churn protocol on the jVector graph searched by our searcher with Lucene OSQ codes",
			d -> ChurnScenario.runCombined(d.base(), d.workDir())),
		GRAPH_SWAP("graph-swap", "§4.7 churn", "results 12",
			"a graph copied from the live one, changed by a batch and swapped in: time, memory, allocation, GC",
			d -> GraphSwapScenario.run(d.base(), d.workDir())),
		ACORN_DENSITY("acorn-density", "§3.4 filtered", "results 6",
			"Lucene graphs M 16 / 32 / 64, in-walk against ACORN under the real and random filters",
			d -> GraphDensityScenarios.acornDensity(d.base(), d.workDir(), d.collection())),
		PQ_CENTER("pq-center", "§3.5 codecs", "results 3",
			"jVector PQ with and without global centering, BQ alongside, on one graph",
			d -> GraphDensityScenarios.pqCentering(d.base(), d.workDir(), d.collection())),
		TX_OVERLAY("tx-overlay", "§4.7 churn", "results 13",
			"a transaction's own vectors scanned exactly and its deletes as a bitmap over one shared jVector graph",
			d -> TransactionOverlayScenario.overlay(d.base(), d.workDir())),
		JV_DELETE_REPAIR("jv-delete-repair", "§4.7 churn", "results 12",
			"jVector deletes on a copy: cleanup() with and without refinement, removeDeletedNodes() alone",
			d -> TransactionOverlayScenario.deleteRepair(d.base(), d.workDir()));

		/** The scenario's name on the command line. */
		private final String id;
		/** The spec criterion it measures. */
		private final String criterion;
		/** The measurement of the results doc that records it. */
		private final String part;
		/** What it does. */
		private final String description;
		/** How it runs. */
		private final Runner runner;

		/**
		 * @param id          the scenario's name on the command line
		 * @param criterion   the spec criterion it measures
		 * @param part        the measurement of the results doc that records it
		 * @param description what it does
		 * @param runner      how it runs
		 */
		Scenario(
			@Nonnull String id,
			@Nonnull String criterion,
			@Nonnull String part,
			@Nonnull String description,
			@Nonnull Runner runner
		) {
			this.id = id;
			this.criterion = criterion;
			this.part = part;
			this.description = description;
			this.runner = runner;
		}

		/**
		 * @param id a scenario name
		 * @return the scenario
		 * @throws IllegalArgumentException when no scenario has the name
		 */
		@Nonnull
		static Scenario of(@Nonnull String id) {
			for (final Scenario scenario : values()) {
				if (scenario.id.equals(id)) {
					return scenario;
				}
			}
			throw new IllegalArgumentException("Unknown scenario `" + id + "`; `P6Harness list` prints them.");
		}
	}
}
