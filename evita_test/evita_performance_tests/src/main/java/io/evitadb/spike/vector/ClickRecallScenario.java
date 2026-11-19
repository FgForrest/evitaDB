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

import io.evitadb.spike.vector.JVectorFootprintScenarios.MappedRavv;
import io.evitadb.spike.vector.OsqCodeFile.MappedOsqCodes;
import io.github.jbellis.jvector.graph.GraphSearcher;
import io.github.jbellis.jvector.graph.ImmutableGraphIndex;
import io.github.jbellis.jvector.graph.SearchResult;
import io.github.jbellis.jvector.graph.similarity.DefaultSearchScoreProvider;
import io.github.jbellis.jvector.quantization.CompressedVectors;
import io.github.jbellis.jvector.vector.VectorizationProvider;
import io.github.jbellis.jvector.vector.types.VectorTypeSupport;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopKnnCollector;
import org.apache.lucene.search.knn.KnnSearchStrategy;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.VectorUtil;
import org.apache.lucene.util.hnsw.HnswGraph;
import org.apache.lucene.util.hnsw.HnswGraphSearcher;
import org.apache.lucene.util.hnsw.OnHeapHnswGraph;
import org.apache.lucene.util.hnsw.RandomVectorScorer;
import org.apache.lucene.util.quantization.QuantizedByteVectorValues.ScalarEncoding;
import org.roaringbitmap.PeekableIntIterator;
import org.roaringbitmap.RoaringBitmap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.evitadb.spike.vector.VectorMemoryProbe.line;
import static io.evitadb.spike.vector.VectorMemoryProbe.section;

/**
 * Click relevance of the seams of `p6-implementation-proposal.md`, measured with real production
 * search queries: every query a user typed into the fulltext search is embedded ({@link ClickQueryEmbedder}),
 * searched through every code and graph of the wiring, and the result is checked against the record the user then
 * clicked.
 *
 * Three numbers per configuration separate what the index loses from what the embedding loses:
 *
 * - **recall@10 against the exact scan**: how many of the exact ten nearest vectors the configuration returns,
 *   over every distinct query text; this is the index's own quality on the real query distribution;
 * - **click hit@1, hit@10 and MRR@10**: whether the clicked record is among the first ten records returned,
 *   counted per distinct (query, clicked record) pair and, beside it, per click; the exact scan gives the
 *   ceiling, and the gap between it and a configuration is the part the index is responsible for;
 * - the same click hit@10 counted **tolerant to duplicates**: a hit also when the returned record carries a vector
 *   identical to the clicked one (the corpus holds the same text under several records).
 *
 * Results are collapsed to records before the click metrics: a record with several chunks counts once, at the
 * position of its best chunk.
 *
 * Every configuration runs unfiltered and filtered to the collection the clicks came from, which is the first
 * real (correlated) filter of the spike. The filtered modes are the strategies of spec §3.4:
 *
 * - `post-filter`: the unfiltered walk, non-members dropped from its candidates afterwards; the `starved` column
 *   counts queries left with fewer than ten candidates;
 * - `in-walk`: the walk only rejects non-matching nodes from the result;
 * - `acorn`: Lucene's `FilteredHnswGraphSearcher`, forced with `KnnSearchStrategy.Hnsw(100)`;
 * - `planner`: the rule of `AbstractKnnVectorQuery` described in section 6.2 of `p6-implementation-proposal.md`: an
 *   exact scan when the filter has no more members than the walk depth, otherwise an in-walk walk with
 *   `visitLimit = members + 1` that falls back to the exact scan when it hits the limit. The fallback here scans
 *   float32; Lucene's own exact path scores with the format's scorer.
 *
 * @author Lukáš Hornych (hornych@fg.cz)
 */
public final class ClickRecallScenario {
	/** Number of records the user sees. */
	static final int TOP_K = 10;
	/** Depth of the approximate walk. */
	static final int CANDIDATES = OversamplingLadder.CANDIDATES;
	/** Oversampling factors: the rerank reads `factor × TOP_K` candidates. */
	private static final int[] FACTORS = {1, 2, 4, 8};
	/** Depth of the exact scan, deep enough to collapse to ten records. */
	static final int EXACT_DEPTH = 200;
	/** Timed passes over the query set; the first, untimed one warms the code paths. */
	private static final int TIMED_PASSES = 3;
	/** jVector vector factory. */
	private static final VectorTypeSupport VTS = VectorizationProvider.getInstance().getVectorTypeSupport();

	private ClickRecallScenario() {
		throw new UnsupportedOperationException("Utility class, not to be instantiated.");
	}

	/**
	 * Runs every configuration over the click query set.
	 *
	 * @param base       mapped corpus vectors
	 * @param workDir    directory holding the corpus mapping and the output of {@link ClickQueryEmbedder}
	 * @param collection collection (schema) code of the filter, the clicked records' one when the dataset has clicks;
	 *                   null runs every configuration unfiltered only
	 * @throws IOException when an input cannot be read or a code file cannot be written
	 */
	public static void run(
		@Nonnull MappedVectors base,
		@Nonnull Path workDir,
		@Nullable String collection
	) throws IOException {
		section("Click relevance: production search queries against the clicked records");
		final Evaluator evaluator = evaluator(base, workDir, collection);
		final Filter filter = evaluator.filter;

		evaluator.exact("exact float32", null);
		if (filter != null) {
			evaluator.exact("exact float32", filter);
		}

		// Lucene: the graph built over the raw block, searched through each code
		final OnHeapHnswGraph luceneGraph = SpikeIndexes.luceneGraph(base);
		luceneModes(evaluator, "lucene float32", luceneGraph, filter, SpikeIndexes.luceneFloat32(base));
		for (final ScalarEncoding encoding : new ScalarEncoding[]{
			ScalarEncoding.SINGLE_BIT_QUERY_NIBBLE, ScalarEncoding.PACKED_NIBBLE, ScalarEncoding.SEVEN_BIT
		}) {
			final MappedOsqCodes codes = SpikeIndexes.osqCodes(workDir, base, encoding);
			luceneModes(evaluator, "lucene osq " + encoding.getBits() + "-bit", luceneGraph, filter,
				SpikeIndexes.luceneOsq(codes));
		}

		// jVector: its own graph over the raw block, searched through PQ and BQ
		final MappedRavv ravv = new MappedRavv(base);
		final ImmutableGraphIndex jvectorGraph = SpikeIndexes.jvectorGraph(ravv, SpikeIndexes.DEFAULT_ALPHA, true);
		jvectorModes(evaluator, "jvector pq 256", jvectorGraph, SpikeIndexes.pq(ravv), filter);
		jvectorModes(evaluator, "jvector bq", jvectorGraph, SpikeIndexes.bq(ravv), filter);
	}

	/**
	 * Loads the corpus mapping, the embedded queries, the clicks and the collection filter, prints their counts and
	 * computes the exact reference of every query.
	 *
	 * @param base       mapped corpus vectors
	 * @param workDir    directory holding the corpus mapping and the output of {@link ClickQueryEmbedder}
	 * @param collection collection (schema) code of the filter, the clicked records' one when the dataset has clicks;
	 *                   null for none
	 * @return the evaluator of the query set
	 * @throws IOException when an input cannot be read
	 */
	@Nonnull
	static Evaluator evaluator(
		@Nonnull MappedVectors base,
		@Nonnull Path workDir,
		@Nullable String collection
	) throws IOException {
		final Corpus corpus = Corpus.load(workDir.resolve(VectorExportExtractor.TEXT_ORDINALS_FILE), base);
		final float[][] queries = loadQueries(workDir, base.dimension());
		final Filter filter = collection == null ? null : Filter.of(corpus, collection);
		final Path clickFile = workDir.resolve(ClickQueryEmbedder.CLICKS_FILE);
		if (!Files.exists(clickFile)) {
			line(String.format(
				"corpus %d vectors / %d records; filter: %s; queries %d held out of the corpus, no clicks",
				base.count(), corpus.recordCount, filter == null ? "none" : String.format(
					"%d vectors (%.2f %%)", filter.count, 100.0 * filter.count / base.count()), queries.length
			));
			return new Evaluator(base, corpus, queries, null, filter);
		}
		if (filter == null) {
			throw new IllegalArgumentException("A dataset with clicks needs the clicked `collection` as its filter.");
		}
		final ClickSet clicks = ClickSet.load(clickFile, corpus, filter, base);
		line(String.format(
			"corpus %d vectors / %d records; filter: %d vectors (%.2f %%); query texts %d; clicks %d, resolved to "
				+ "a record of the collection %d, distinct (query, record) pairs %d, distinct queries among them %d",
			base.count(), corpus.recordCount, filter.count, 100.0 * filter.count / base.count(), queries.length,
			clicks.total, clicks.resolved.size(), clicks.pairs.size(), clicks.distinctQueries
		));
		return new Evaluator(base, corpus, queries, clicks, filter);
	}

	/**
	 * Runs one Lucene code unfiltered and in the three filtered modes.
	 *
	 * @param evaluator the evaluator
	 * @param label     configuration name
	 * @param graph     the Lucene graph
	 * @param filter    the collection filter, null to run unfiltered only
	 * @param scorers   for a query, the scorer of the code
	 */
	static void luceneModes(
		@Nonnull Evaluator evaluator,
		@Nonnull String label,
		@Nonnull HnswGraph graph,
		@Nullable Filter filter,
		@Nonnull IoFunction<float[], RandomVectorScorer> scorers
	) {
		// without an acceptance set the count is not read
		evaluator.approximate(label, "all", null, (q, stats) ->
			luceneWalk(scorers.apply(q), graph, null, 0, Integer.MAX_VALUE, 0, stats));
		if (filter == null) {
			return;
		}
		final Bits accept = luceneBits(filter);
		evaluator.approximate(label, "post-filter", filter, (q, stats) -> postFilter(
			luceneWalk(scorers.apply(q), graph, null, filter.count, Integer.MAX_VALUE, 0, stats), filter, stats));
		evaluator.approximate(label, "in-walk", filter, (q, stats) ->
			luceneWalk(scorers.apply(q), graph, accept, filter.count, Integer.MAX_VALUE, 0, stats));
		evaluator.approximate(label, "acorn", filter, (q, stats) ->
			luceneWalk(scorers.apply(q), graph, accept, filter.count, Integer.MAX_VALUE, 100, stats));
		evaluator.approximate(label, "planner", filter, (q, stats) ->
			plannedLuceneWalk(scorers, graph, accept, filter, evaluator, q, stats));
	}

	/**
	 * The planner of section 6.2 of the implementation proposal over a Lucene walk: an exact scan when the filter
	 * has no more members than the walk depth, otherwise an in-walk walk with `visitLimit = members + 1` that falls
	 * back to the exact scan when it hits the limit.
	 *
	 * @param scorers for a query, the scorer of the code
	 * @param graph   the Lucene graph
	 * @param accept  the filter as Lucene bits
	 * @param filter  the filter
	 * @param exact   the exact scan of the fallback
	 * @param query   the query vector
	 * @param stats   receives the visited count and the fallbacks
	 * @return candidate ordinals, best first
	 * @throws IOException when the scorer fails
	 */
	@Nonnull
	static int[] plannedLuceneWalk(
		@Nonnull IoFunction<float[], RandomVectorScorer> scorers,
		@Nonnull HnswGraph graph,
		@Nonnull Bits accept,
		@Nonnull Filter filter,
		@Nonnull ExactScorer exact,
		@Nonnull float[] query,
		@Nonnull WalkStats stats
	) throws IOException {
		if (filter.count <= CANDIDATES) {
			stats.fallbacks++;
			return exact.exactScan(query, filter, CANDIDATES);
		}
		final int[] walked = luceneWalk(scorers.apply(query), graph, accept, filter.count, filter.count + 1, 0, stats);
		if (walked == null) {
			stats.fallbacks++;
			return exact.exactScan(query, filter, CANDIDATES);
		}
		return walked;
	}

	/**
	 * @param filter the filter
	 * @return the filter as Lucene's acceptance bits
	 */
	@Nonnull
	static Bits luceneBits(@Nonnull Filter filter) {
		return new Bits() {
			@Override
			public boolean get(int index) {
				return filter.contains(index);
			}

			@Override
			public int length() {
				return filter.accept.length;
			}
		};
	}

	/**
	 * One Lucene walk.
	 *
	 * @param scorer       scorer of the query over the code
	 * @param graph        the graph
	 * @param accept       the filter, null for none
	 * @param filterCount  number of accepted ordinals
	 * @param visitLimit   the visited-node budget
	 * @param acornPercent `filteredSearchThreshold` of the strategy: 0 never uses ACORN, 100 always does
	 * @param stats        receives the visited count
	 * @return candidate ordinals in approximate order, or null when the walk hit its visit limit
	 * @throws IOException when the scorer fails
	 */
	@Nullable
	static int[] luceneWalk(
		@Nonnull RandomVectorScorer scorer,
		@Nonnull HnswGraph graph,
		@Nullable Bits accept,
		int filterCount,
		int visitLimit,
		int acornPercent,
		@Nonnull WalkStats stats
	) throws IOException {
		final TopKnnCollector collector = new TopKnnCollector(
			CANDIDATES, visitLimit, new KnnSearchStrategy.Hnsw(acornPercent)
		);
		if (accept == null) {
			HnswGraphSearcher.search(scorer, collector, graph, null);
		} else {
			HnswGraphSearcher.search(scorer, collector, graph, accept, filterCount);
		}
		stats.visited += collector.visitedCount();
		if (collector.earlyTerminated() && visitLimit != Integer.MAX_VALUE) {
			return null;
		}
		final ScoreDoc[] docs = collector.topDocs().scoreDocs;
		final int[] ordinals = new int[docs.length];
		for (int i = 0; i < docs.length; i++) {
			ordinals[i] = docs[i].doc;
		}
		return ordinals;
	}

	/**
	 * Runs one jVector code unfiltered and with in-walk acceptance, the only filtered mode jVector has.
	 *
	 * @param evaluator the evaluator
	 * @param label     configuration name
	 * @param graph     the jVector graph
	 * @param codes     the codes scoring the walk
	 * @param filter    the collection filter, null to run unfiltered only
	 */
	private static void jvectorModes(
		@Nonnull Evaluator evaluator,
		@Nonnull String label,
		@Nonnull ImmutableGraphIndex graph,
		@Nonnull CompressedVectors codes,
		@Nullable Filter filter
	) {
		evaluator.approximate(label, "all", null, (q, stats) ->
			jvectorWalk(graph, codes, q, io.github.jbellis.jvector.util.Bits.ALL, stats));
		if (filter == null) {
			return;
		}
		final io.github.jbellis.jvector.util.Bits accept = filter::contains;
		evaluator.approximate(label, "post-filter", filter, (q, stats) -> postFilter(
			jvectorWalk(graph, codes, q, io.github.jbellis.jvector.util.Bits.ALL, stats), filter, stats));
		evaluator.approximate(label, "in-walk", filter, (q, stats) ->
			jvectorWalk(graph, codes, q, accept, stats));
	}

	/**
	 * Drops the candidates of an unfiltered walk that the filter rejects.
	 *
	 * @param candidates candidate ordinals of the unfiltered walk, in approximate order
	 * @param filter     the filter
	 * @param stats      counts the query as starved when fewer than ten candidates survive
	 * @return the surviving candidates in the same order
	 */
	@Nonnull
	static int[] postFilter(@Nullable int[] candidates, @Nonnull Filter filter, @Nonnull WalkStats stats) {
		if (candidates == null) {
			throw new IllegalStateException("An unlimited walk cannot stop at its visit limit.");
		}
		final int[] kept = new int[candidates.length];
		int count = 0;
		for (final int candidate : candidates) {
			if (filter.contains(candidate)) {
				kept[count++] = candidate;
			}
		}
		if (count < TOP_K) {
			stats.starved++;
		}
		return Arrays.copyOf(kept, count);
	}

	/**
	 * One jVector walk.
	 *
	 * @param graph  the graph
	 * @param codes  the codes scoring the walk
	 * @param query  the query vector
	 * @param accept the filter
	 * @param stats  receives the visited count
	 * @return candidate ordinals in approximate order
	 * @throws IOException when the searcher cannot be closed
	 */
	@Nonnull
	static int[] jvectorWalk(
		@Nonnull ImmutableGraphIndex graph,
		@Nonnull CompressedVectors codes,
		@Nonnull float[] query,
		@Nonnull io.github.jbellis.jvector.util.Bits accept,
		@Nonnull WalkStats stats
	) throws IOException {
		try (final GraphSearcher searcher = new GraphSearcher(graph)) {
			final SearchResult result = searcher.search(
				new DefaultSearchScoreProvider(codes.precomputedScoreFunctionFor(
					VTS.createFloatVector(query), io.github.jbellis.jvector.vector.VectorSimilarityFunction.DOT_PRODUCT
				)),
				CANDIDATES, CANDIDATES, 0.0f, 0.0f, accept
			);
			stats.visited += result.getVisitedCount();
			final SearchResult.NodeScore[] nodes = result.getNodes();
			final int[] ordinals = new int[nodes.length];
			for (int i = 0; i < nodes.length; i++) {
				ordinals[i] = nodes[i].node;
			}
			return ordinals;
		}
	}

	/**
	 * @param workDir   directory holding the output of {@link ClickQueryEmbedder}
	 * @param dimension the corpus dimension, which the queries must share
	 * @return the query vectors in row order
	 * @throws IOException when the block cannot be read
	 */
	@Nonnull
	static float[][] loadQueries(@Nonnull Path workDir, int dimension) throws IOException {
		final Path file = workDir.resolve(ClickQueryEmbedder.QUERY_VECTORS_FILE);
		if (Files.size(file) % ((long) dimension * Float.BYTES) != 0) {
			throw new IllegalStateException(
				"The query block is not a whole number of " + dimension + "-dimensional vectors; was it embedded "
					+ "with the corpus model?"
			);
		}
		final MappedVectors mapped = new MappedVectors(file, dimension);
		final float[][] queries = new float[mapped.count()][];
		for (int i = 0; i < queries.length; i++) {
			queries[i] = mapped.copy(i);
		}
		return queries;
	}

	/**
	 * A function that may throw `IOException`, as Lucene's scorer factories do.
	 *
	 * @param <T> input type
	 * @param <R> result type
	 */
	@FunctionalInterface
	interface IoFunction<T, R> {
		/**
		 * @param input the input
		 * @return the result
		 * @throws IOException when the underlying read fails
		 */
		@Nonnull
		R apply(@Nonnull T input) throws IOException;
	}

	/**
	 * An approximate search: the candidate ordinals of a query in approximate order.
	 */
	@FunctionalInterface
	interface Walk {
		/**
		 * @param query the query vector
		 * @param stats receives the walk's counters
		 * @return candidate ordinals, best first by the code's score
		 * @throws IOException when a scorer or searcher fails
		 */
		@Nonnull
		int[] search(@Nonnull float[] query, @Nonnull WalkStats stats) throws IOException;
	}

	/**
	 * Counters of one configuration over the whole query set.
	 */
	static final class WalkStats {
		/** Nodes visited, summed over the queries. */
		long visited;
		/** Queries answered by the exact scan instead of the walk. */
		int fallbacks;
		/** Queries left with fewer than ten candidates after the post-filter. */
		int starved;
	}

	/**
	 * The ordinal-to-record mapping of the corpus.
	 *
	 * @param schema      collection code of every ordinal
	 * @param locale      embedding locale of every ordinal
	 * @param url         `url` field of every ordinal's record, empty when it has none
	 * @param record      a dense record number of every ordinal (collection, id and locale together)
	 * @param recordCount number of distinct records
	 */
	record Corpus(
		@Nonnull String[] schema,
		@Nonnull String[] locale,
		@Nonnull String[] url,
		@Nonnull int[] record,
		int recordCount
	) {
		/**
		 * @param file the mapping written by {@link VectorExportExtractor}
		 * @param base the corpus block, whose size the mapping must match
		 * @return the mapping
		 * @throws IOException when the file cannot be read
		 */
		@Nonnull
		static Corpus load(@Nonnull Path file, @Nonnull MappedVectors base) throws IOException {
			final List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
			if (lines.size() != base.count()) {
				throw new IllegalStateException(
					"The mapping has " + lines.size() + " lines, the corpus " + base.count() + " vectors."
				);
			}
			final int n = lines.size();
			final String[] schema = new String[n];
			final String[] locale = new String[n];
			final String[] url = new String[n];
			final int[] record = new int[n];
			final Map<String, Integer> records = new HashMap<>(n * 2);
			for (int i = 0; i < n; i++) {
				final String[] parts = lines.get(i).split("\t", -1);
				schema[i] = parts[0];
				locale[i] = parts[2];
				url[i] = parts[3];
				final Integer known = records.putIfAbsent(parts[0] + '\t' + parts[1] + '\t' + parts[2], records.size());
				record[i] = known == null ? records.size() - 1 : known;
			}
			return new Corpus(schema, locale, url, record, records.size());
		}
	}

	/**
	 * A candidate set: the filter a search runs over.
	 *
	 * It reaches the search the way evitaDB would hand it over (spec §2.4, §2.5): a `RoaringBitmap` of the members'
	 * primary keys, the output of the rest of the query, behind the ordinal map. The strategies use only that path:
	 * a walk tests a node with {@link #contains(int)}, `bitmap.contains(ordToPk[ord])`, and the exact scan iterates
	 * the bitmap. The `boolean[]` over ordinals is the same set for the measurement itself (the ground truth's
	 * checks, the click resolution) and for the membership-structure comparison of the filtered matrix.
	 *
	 * A filter always holds whole records: every vector of a member record is a member.
	 *
	 * @param accept      whether an ordinal is a member
	 * @param count       number of member ordinals
	 * @param primaryKeys the primary keys of the member records
	 * @param ordinals    the map between ordinals and primary keys
	 */
	record Filter(
		@Nonnull boolean[] accept,
		int count,
		@Nonnull RoaringBitmap primaryKeys,
		@Nonnull PrimaryKeyOrdinals ordinals
	) {
		/**
		 * @param corpus     the corpus mapping
		 * @param collection the collection code
		 * @return the filter of the collection
		 */
		@Nonnull
		static Filter of(@Nonnull Corpus corpus, @Nonnull String collection) {
			final boolean[] accept = new boolean[corpus.schema.length];
			for (int i = 0; i < accept.length; i++) {
				accept[i] = collection.equals(corpus.schema[i]);
			}
			final Filter filter = of(accept, PrimaryKeyOrdinals.of(corpus.record, corpus.recordCount));
			if (filter.count == 0) {
				throw new IllegalArgumentException("No corpus vector belongs to the given collection.");
			}
			return filter;
		}

		/**
		 * Builds the filter of the records that have at least one accepted ordinal.
		 *
		 * @param accept   whether an ordinal is selected; extended in place to whole records
		 * @param ordinals the map between ordinals and primary keys
		 * @return the filter
		 */
		@Nonnull
		static Filter of(@Nonnull boolean[] accept, @Nonnull PrimaryKeyOrdinals ordinals) {
			final RoaringBitmap primaryKeys = new RoaringBitmap();
			for (int i = 0; i < accept.length; i++) {
				if (accept[i]) {
					primaryKeys.add(ordinals.ordToPk()[i]);
				}
			}
			primaryKeys.runOptimize();
			int count = 0;
			final PeekableIntIterator keys = primaryKeys.getIntIterator();
			while (keys.hasNext()) {
				final int pk = keys.next();
				for (int ord = ordinals.pkStart()[pk]; ord < ordinals.pkStart()[pk + 1]; ord++) {
					accept[ord] = true;
					count++;
				}
			}
			return new Filter(accept, count, primaryKeys, ordinals);
		}

		/**
		 * The membership test of a walk, through the ordinal map and the bitmap of primary keys.
		 *
		 * @param ordinal a vector ordinal
		 * @return whether the ordinal's record is a member
		 */
		boolean contains(int ordinal) {
			return this.primaryKeys.contains(this.ordinals.ordToPk()[ordinal]);
		}
	}

	/**
	 * One click resolved to the clicked record.
	 *
	 * @param query    row of the query vector
	 * @param strict   record numbers of the clicked record
	 * @param tolerant the clicked record plus every record carrying a vector identical to one of its vectors
	 */
	private record Click(int query, @Nonnull int[] strict, @Nonnull int[] tolerant) {
	}

	/**
	 * The clicks of the log, resolved to corpus records.
	 *
	 * @param total           clicks in the joined log
	 * @param resolved        clicks whose URL names a record of the collection, one entry per click
	 * @param pairs           the resolved clicks deduplicated by (query, clicked record)
	 * @param distinctQueries distinct queries among the pairs
	 */
	private record ClickSet(int total, @Nonnull List<Click> resolved, @Nonnull List<Click> pairs, int distinctQueries) {
		/**
		 * @param file   the joined click log written by {@link ClickQueryEmbedder}
		 * @param corpus the corpus mapping
		 * @param filter the collection the clicked records must belong to
		 * @param base   the corpus vectors, for the duplicate check
		 * @return the resolved clicks
		 * @throws IOException when the file cannot be read
		 */
		@Nonnull
		static ClickSet load(
			@Nonnull Path file,
			@Nonnull Corpus corpus,
			@Nonnull Filter filter,
			@Nonnull MappedVectors base
		) throws IOException {
			// URL and locale -> ordinals of the clicked record within the collection
			final Map<String, List<Integer>> byUrl = new HashMap<>(filter.count * 2);
			for (int i = 0; i < corpus.url.length; i++) {
				if (filter.accept[i] && !corpus.url[i].isEmpty()) {
					byUrl.computeIfAbsent(corpus.locale[i] + '\t' + corpus.url[i], k -> new ArrayList<>(4)).add(i);
				}
			}
			final int[] vectorHash = new int[base.count()];
			final float[] scratch = new float[base.dimension()];
			for (int i = 0; i < vectorHash.length; i++) {
				base.read(i, scratch);
				vectorHash[i] = Arrays.hashCode(scratch);
			}
			final List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
			final List<Click> resolved = new ArrayList<>(lines.size());
			final Map<String, Click> pairs = new LinkedHashMap<>(lines.size() * 2);
			final Map<String, int[][]> targets = new HashMap<>(64);
			for (final String line : lines) {
				final String[] parts = line.split("\t", -1);
				final List<Integer> ordinals = byUrl.get(parts[1] + '\t' + parts[2]);
				if (ordinals == null) {
					continue;
				}
				final int[][] target = targets.computeIfAbsent(
					parts[1] + '\t' + parts[2], k -> resolveTargets(ordinals, corpus, base, vectorHash, scratch)
				);
				final Click click = new Click(Integer.parseInt(parts[0]), target[0], target[1]);
				resolved.add(click);
				pairs.putIfAbsent(click.query + "\t" + parts[1] + '\t' + parts[2], click);
			}
			if (resolved.isEmpty()) {
				throw new IllegalArgumentException(
					"No click names a record of the given collection; check the collection code argument (it is "
						+ "the schema `code` of the export, not the `section` of the click log)."
				);
			}
			final long distinctQueries = pairs.values().stream().mapToInt(Click::query).distinct().count();
			return new ClickSet(lines.size(), resolved, new ArrayList<>(pairs.values()), (int) distinctQueries);
		}

		/**
		 * @param ordinals   ordinals of the clicked record
		 * @param corpus     the corpus mapping
		 * @param base       the corpus vectors
		 * @param vectorHash hash of every corpus vector
		 * @param scratch    buffer of one vector
		 * @return the strict record numbers at index 0, the duplicate-tolerant ones at index 1
		 */
		@Nonnull
		private static int[][] resolveTargets(
			@Nonnull List<Integer> ordinals,
			@Nonnull Corpus corpus,
			@Nonnull MappedVectors base,
			@Nonnull int[] vectorHash,
			@Nonnull float[] scratch
		) {
			final int[] strict = ordinals.stream().mapToInt(o -> corpus.record[o]).distinct().toArray();
			final List<Integer> tolerant = new ArrayList<>(8);
			for (final int ordinal : ordinals) {
				final float[] clicked = base.copy(ordinal);
				final int hash = vectorHash[ordinal];
				for (int i = 0; i < vectorHash.length; i++) {
					if (vectorHash[i] == hash) {
						base.read(i, scratch);
						if (Arrays.equals(clicked, scratch)) {
							tolerant.add(corpus.record[i]);
						}
					}
				}
			}
			return new int[][]{strict, tolerant.stream().mapToInt(Integer::intValue).distinct().toArray()};
		}
	}

	/**
	 * Runs configurations and prints one line per configuration, mode and oversampling factor.
	 */
	static final class Evaluator extends ExactScorer {
		/** The corpus mapping. */
		private final Corpus corpus;
		/** The query vectors. */
		private final float[][] queries;
		/** The resolved clicks, null when the queries are held out of the corpus. */
		@Nullable private final ClickSet clicks;
		/** Exact score of the tenth nearest vector per query, unfiltered. */
		private final float[] kthAll;
		/** Exact score of the tenth nearest vector per query, within the filter. */
		private final float[] kthFiltered;
		/** The collection filter the reference was computed for, null when the dataset declares none. */
		@Nullable final Filter filter;

		/**
		 * Computes the exact reference of every query, unfiltered and filtered.
		 *
		 * @param base    the corpus vectors
		 * @param corpus  the corpus mapping
		 * @param queries the query vectors
		 * @param clicks  the resolved clicks, null when the queries are held out of the corpus
		 * @param filter  the collection filter, null for none
		 */
		Evaluator(
			@Nonnull MappedVectors base,
			@Nonnull Corpus corpus,
			@Nonnull float[][] queries,
			@Nullable ClickSet clicks,
			@Nullable Filter filter
		) {
			super(base);
			this.filter = filter;
			this.corpus = corpus;
			this.queries = queries;
			this.clicks = clicks;
			this.kthAll = new float[queries.length];
			this.kthFiltered = new float[queries.length];
			for (int q = 0; q < queries.length; q++) {
				this.kthAll[q] = score(queries[q], exactScan(queries[q], null, TOP_K)[TOP_K - 1]);
				if (filter != null) {
					this.kthFiltered[q] = score(queries[q], exactScan(queries[q], filter, TOP_K)[TOP_K - 1]);
				}
			}
		}

		/**
		 * Prints the exact scan, the ceiling of every click metric.
		 *
		 * @param label  configuration name
		 * @param filter the filter, null for none
		 */
		void exact(@Nonnull String label, @Nullable Filter filter) {
			final int[][] ranked = new int[this.queries.length][];
			final long[] latencies = new long[this.queries.length * TIMED_PASSES];
			for (int pass = -1; pass < TIMED_PASSES; pass++) {
				for (int q = 0; q < this.queries.length; q++) {
					final long start = System.nanoTime();
					ranked[q] = exactScan(this.queries[q], filter, EXACT_DEPTH);
					if (pass >= 0) {
						latencies[pass * this.queries.length + q] = System.nanoTime() - start;
					}
				}
			}
			print(label, filter == null ? "all" : "filtered", 0, ranked, filter != null, latencies, null);
		}

		/**
		 * @return the query vectors in row order
		 */
		@Nonnull
		float[][] queries() {
			return this.queries;
		}

		/**
		 * Runs an approximate configuration through the oversampling factors.
		 *
		 * @param label  configuration name
		 * @param mode   filter mode name
		 * @param filter the filter, null for none
		 * @param walk   the approximate search
		 */
		void approximate(@Nonnull String label, @Nonnull String mode, @Nullable Filter filter, @Nonnull Walk walk) {
			try {
				final WalkStats stats = new WalkStats();
				final int[][] candidates = new int[this.queries.length][];
				for (int q = 0; q < this.queries.length; q++) {
					candidates[q] = walk.search(this.queries[q], stats);
				}
				for (final int factor : FACTORS) {
					final int depth = factor * TOP_K;
					final int[][] ranked = new int[this.queries.length][];
					final long[] latencies = new long[this.queries.length * TIMED_PASSES];
					final WalkStats ignored = new WalkStats();
					for (int pass = -1; pass < TIMED_PASSES; pass++) {
						for (int q = 0; q < this.queries.length; q++) {
							final long start = System.nanoTime();
							ranked[q] = rerank(this.queries[q], walk.search(this.queries[q], ignored), depth);
							if (pass >= 0) {
								latencies[pass * this.queries.length + q] = System.nanoTime() - start;
							}
						}
					}
					print(label, mode, factor, ranked, filter != null, latencies, stats);
				}
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		}

		/**
		 * Prints one result line.
		 *
		 * @param label     configuration name
		 * @param mode      filter mode name
		 * @param factor    oversampling factor, 0 for the exact scan
		 * @param ranked    per query, ordinals ordered by exact score
		 * @param filtered  whether the recall reference is the filtered exact scan
		 * @param latencies per query and pass, nanoseconds
		 * @param stats     walk counters, null for the exact scan
		 */
		private void print(
			@Nonnull String label,
			@Nonnull String mode,
			int factor,
			@Nonnull int[][] ranked,
			boolean filtered,
			@Nonnull long[] latencies,
			@Nullable WalkStats stats
		) {
			// recall@10 against the exact scan, tie-robust: a returned vector scoring at least the tenth exact
			// score is a hit
			long hits = 0;
			final int[][] records = new int[ranked.length][];
			for (int q = 0; q < ranked.length; q++) {
				final float kth = filtered ? this.kthFiltered[q] : this.kthAll[q];
				for (int i = 0; i < Math.min(TOP_K, ranked[q].length); i++) {
					if (score(this.queries[q], ranked[q][i]) >= kth - 1e-5f) {
						hits++;
					}
				}
				records[q] = collapse(ranked[q]);
			}
			final double recall = hits / (double) (ranked.length * TOP_K);
			Arrays.sort(latencies);
			if (this.clicks == null) {
				line(String.format(
					"%-18s %-8s %3s | recall@10 %.3f | visited %6.0f fallback %3d starved %3d"
						+ " | p50 %.3f ms p95 %.3f ms",
					label, mode, factor == 0 ? "-" : factor + "x", recall,
					stats == null ? 0.0 : stats.visited / (double) this.queries.length,
					stats == null ? 0 : stats.fallbacks, stats == null ? 0 : stats.starved,
					OversamplingLadder.percentile(latencies, 0.50), OversamplingLadder.percentile(latencies, 0.95)
				));
				return;
			}
			final double[] pairs = clickMetrics(this.clicks.pairs, records);
			final double[] all = clickMetrics(this.clicks.resolved, records);
			line(String.format(
				"%-18s %-8s %3s | recall@10 %.3f | pairs hit@1 %.3f hit@10 %.3f MRR %.3f dup-tol hit@10 %.3f"
					+ " | clicks hit@10 %.3f | visited %6.0f fallback %3d starved %3d | p50 %.3f ms p95 %.3f ms",
				label, mode, factor == 0 ? "-" : factor + "x", recall, pairs[0], pairs[1], pairs[2], pairs[3], all[1],
				stats == null ? 0.0 : stats.visited / (double) this.queries.length,
				stats == null ? 0 : stats.fallbacks, stats == null ? 0 : stats.starved,
				OversamplingLadder.percentile(latencies, 0.50), OversamplingLadder.percentile(latencies, 0.95)
			));
		}

		/**
		 * @param clicks  the clicks to score
		 * @param records per query, the first ten record numbers returned
		 * @return hit@1, hit@10, MRR@10 and duplicate-tolerant hit@10, each averaged over the clicks
		 */
		@Nonnull
		private static double[] clickMetrics(@Nonnull List<Click> clicks, @Nonnull int[][] records) {
			double hit1 = 0;
			double hit10 = 0;
			double mrr = 0;
			double tolerant10 = 0;
			for (final Click click : clicks) {
				final int rank = rankOf(records[click.query], click.strict);
				if (rank == 1) {
					hit1++;
				}
				if (rank > 0) {
					hit10++;
					mrr += 1.0 / rank;
				}
				if (rankOf(records[click.query], click.tolerant) > 0) {
					tolerant10++;
				}
			}
			final int n = clicks.size();
			return new double[]{hit1 / n, hit10 / n, mrr / n, tolerant10 / n};
		}

		/**
		 * @param records returned record numbers, best first
		 * @param targets record numbers that count as the clicked record
		 * @return the 1-based position of the first target, 0 when none is returned
		 */
		private static int rankOf(@Nonnull int[] records, @Nonnull int[] targets) {
			for (int i = 0; i < records.length; i++) {
				for (final int target : targets) {
					if (records[i] == target) {
						return i + 1;
					}
				}
			}
			return 0;
		}

		/**
		 * @param ranked ordinals ordered by exact score
		 * @return the first ten distinct records, each at the position of its best chunk
		 */
		@Nonnull
		private int[] collapse(@Nonnull int[] ranked) {
			final int[] records = new int[TOP_K];
			int count = 0;
			for (int i = 0; i < ranked.length && count < TOP_K; i++) {
				final int record = this.corpus.record[ranked[i]];
				boolean seen = false;
				for (int j = 0; j < count && !seen; j++) {
					seen = records[j] == record;
				}
				if (!seen) {
					records[count++] = record;
				}
			}
			return Arrays.copyOf(records, count);
		}
	}

	/**
	 * Exact scoring over the mapped corpus: the rerank of a walk's candidates and the exact scan. Not thread-safe,
	 * it reads every vector into one buffer.
	 */
	static class ExactScorer {
		/** The corpus vectors, read by the rerank and the exact scan. */
		private final MappedVectors base;
		/** Buffer of one vector. */
		private final float[] scratch;

		/**
		 * @param base the corpus vectors
		 */
		ExactScorer(@Nonnull MappedVectors base) {
			this.base = base;
			this.scratch = new float[base.dimension()];
		}

		/**
		 * Rescores the first `depth` candidates exactly.
		 *
		 * @param query      the query vector
		 * @param candidates candidate ordinals in approximate order
		 * @param depth      how many candidates to rescore
		 * @return the rescored ordinals, best first
		 */
		@Nonnull
		int[] rerank(@Nonnull float[] query, @Nonnull int[] candidates, int depth) {
			final int count = Math.min(depth, candidates.length);
			final float[] scores = new float[count];
			for (int i = 0; i < count; i++) {
				scores[i] = score(query, candidates[i]);
			}
			return sortByScore(Arrays.copyOf(candidates, count), scores);
		}

		/**
		 * Exact scan over the corpus or the filter. A filter is scanned the way spec §3.4 describes it: the bitmap of
		 * primary keys is iterated, and each member's vectors are reached through the ordinal map, so the cost is
		 * linear in the members, not in the corpus.
		 *
		 * @param query  the query vector
		 * @param filter the filter, null for the whole corpus
		 * @param depth  number of ordinals to return
		 * @return the best `depth` ordinals by exact score, best first
		 */
		@Nonnull
		int[] exactScan(@Nonnull float[] query, @Nullable Filter filter, int depth) {
			// a bounded min-heap of the best `depth` ordinals
			final int[] heap = new int[depth];
			final float[] heapScores = new float[depth];
			int size = 0;
			if (filter == null) {
				for (int i = 0; i < this.base.count(); i++) {
					size = offer(heap, heapScores, size, i, score(query, i));
				}
			} else {
				final int[] pkStart = filter.ordinals().pkStart();
				final PeekableIntIterator keys = filter.primaryKeys().getIntIterator();
				while (keys.hasNext()) {
					final int pk = keys.next();
					for (int i = pkStart[pk]; i < pkStart[pk + 1]; i++) {
						size = offer(heap, heapScores, size, i, score(query, i));
					}
				}
			}
			return sortByScore(Arrays.copyOf(heap, size), Arrays.copyOf(heapScores, size));
		}

		/**
		 * Offers one scored ordinal to the bounded min-heap of the exact scan.
		 *
		 * @param heap       ordinals of the heap, the worst at the root
		 * @param heapScores scores of the heap's ordinals
		 * @param size       the number of ordinals in the heap
		 * @param ordinal    the scored ordinal
		 * @param score      its exact score
		 * @return the number of ordinals in the heap afterwards
		 */
		private static int offer(@Nonnull int[] heap, @Nonnull float[] heapScores, int size, int ordinal, float score) {
			if (size < heap.length) {
				heap[size] = ordinal;
				heapScores[size] = score;
				siftUp(heap, heapScores, size);
				return size + 1;
			}
			if (score > heapScores[0]) {
				heap[0] = ordinal;
				heapScores[0] = score;
				siftDown(heap, heapScores, size);
			}
			return size;
		}

		/**
		 * @param query   the query vector
		 * @param ordinal a corpus ordinal
		 * @return the exact dot product
		 */
		float score(@Nonnull float[] query, int ordinal) {
			this.base.read(ordinal, this.scratch);
			return VectorUtil.dotProduct(query, this.scratch);
		}

		/**
		 * @param ordinals ordinals, reordered in place
		 * @param scores   their scores, reordered in place
		 * @return the ordinals, best score first
		 */
		@Nonnull
		private static int[] sortByScore(@Nonnull int[] ordinals, @Nonnull float[] scores) {
			// insertion sort: the arrays hold at most a few hundred entries
			for (int i = 1; i < ordinals.length; i++) {
				final int ordinal = ordinals[i];
				final float s = scores[i];
				int j = i - 1;
				while (j >= 0 && scores[j] < s) {
					ordinals[j + 1] = ordinals[j];
					scores[j + 1] = scores[j];
					j--;
				}
				ordinals[j + 1] = ordinal;
				scores[j + 1] = s;
			}
			return ordinals;
		}

		/**
		 * @param heap   ordinals of the min-heap
		 * @param scores their scores
		 * @param index  the position of the entry just added
		 */
		private static void siftUp(@Nonnull int[] heap, @Nonnull float[] scores, int index) {
			int i = index;
			while (i > 0) {
				final int parent = (i - 1) / 2;
				if (scores[parent] <= scores[i]) {
					return;
				}
				swap(heap, scores, i, parent);
				i = parent;
			}
		}

		/**
		 * @param heap   ordinals of the min-heap
		 * @param scores their scores
		 * @param size   number of entries in the heap
		 */
		private static void siftDown(@Nonnull int[] heap, @Nonnull float[] scores, int size) {
			int i = 0;
			while (true) {
				final int left = 2 * i + 1;
				final int right = left + 1;
				int smallest = i;
				if (left < size && scores[left] < scores[smallest]) {
					smallest = left;
				}
				if (right < size && scores[right] < scores[smallest]) {
					smallest = right;
				}
				if (smallest == i) {
					return;
				}
				swap(heap, scores, i, smallest);
				i = smallest;
			}
		}

		/**
		 * @param heap   ordinals
		 * @param scores their scores
		 * @param a      one position
		 * @param b      the other position
		 */
		private static void swap(@Nonnull int[] heap, @Nonnull float[] scores, int a, int b) {
			final int ordinal = heap[a];
			heap[a] = heap[b];
			heap[b] = ordinal;
			final float s = scores[a];
			scores[a] = scores[b];
			scores[b] = s;
		}
	}
}
