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

import org.apache.lucene.codecs.KnnVectorsFormat;
import org.apache.lucene.codecs.KnnVectorsReader;
import org.apache.lucene.codecs.KnnVectorsWriter;
import org.apache.lucene.codecs.hnsw.FlatVectorScorerUtil;
import org.apache.lucene.codecs.hnsw.FlatVectorsScorer;
import org.apache.lucene.codecs.hnsw.HnswGraphProvider;
import org.apache.lucene.codecs.perfield.PerFieldKnnVectorsFormat;
import org.apache.lucene.codecs.lucene104.Lucene104Codec;
import org.apache.lucene.codecs.lucene104.Lucene104HnswScalarQuantizedVectorsFormat;
import org.apache.lucene.codecs.lucene99.Lucene99HnswVectorsFormat;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.index.CodecReader;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.FloatVectorValues;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.SegmentReadState;
import org.apache.lucene.index.SegmentWriteState;
import org.apache.lucene.index.VectorEncoding;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.KnnFloatVectorQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.RescoreTopNQuery;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.MMapDirectory;
import org.apache.lucene.store.NoLockFactory;
import org.apache.lucene.util.hnsw.HnswGraph;
import org.apache.lucene.util.hnsw.HnswGraphBuilder;
import org.apache.lucene.util.hnsw.HnswGraphSearcher;
import org.apache.lucene.util.hnsw.OnHeapHnswGraph;
import org.apache.lucene.util.quantization.QuantizedByteVectorValues.ScalarEncoding;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static io.evitadb.spike.vector.VectorMemoryProbe.*;

/**
 * Lucene scenarios of the vector memory spike, on the version evitaDB pins (10.6.0): the `util.hnsw` package used
 * as a library over mapped vectors, and a full `IndexWriter` + `MMapDirectory` index with the float32 format and
 * every encoding of the optimized scalar quantizer (`Lucene104HnswScalarQuantizedVectorsFormat`) - 8, 7, 4 and
 * 2 bits and the RaBitQ-derived 1-bit document code scored against a 4-bit query code. Every full-index codec is
 * also run through the {@link OversamplingLadder}.
 *
 * The HNSW formats cap vectors at 1024 dimensions; {@link HighDimFormat} lifts the cap for the 2048-d corpus.
 *
 * @author Lukáš Hornych (hornych@fg.cz)
 */
public final class LuceneFootprintScenarios {
	/** Similarity used everywhere; the corpus vectors are unit length. */
	private static final VectorSimilarityFunction VSF = VectorSimilarityFunction.DOT_PRODUCT;
	/** Lucene's `M`; level 0 gets `2 * M = 32`, the same as jVector's base degree. */
	private static final int M = 16;
	/** Construction beam width. */
	private static final int BEAM = 100;
	/** Candidate queue depth of every search (the `ef` of HNSW terms), the same as in the jVector scenarios. */
	private static final int EF = 100;
	/** Name of the vector field. */
	private static final String FIELD = "v";

	private LuceneFootprintScenarios() {
		throw new UnsupportedOperationException("Utility class, not to be instantiated.");
	}

	/**
	 * Lucene `FloatVectorValues` over the mapped file with one scratch array per instance.
	 */
	 	static final class MappedFloats extends FloatVectorValues {
		/** The mapped raw block. */
		private final MappedVectors vectors;
		/** Scratch receiving the vector currently handed out. */
		private final float[] scratch;

		/**
		 * @param vectors the mapped raw block
		 */
		MappedFloats(@Nonnull MappedVectors vectors) {
			this.vectors = vectors;
			this.scratch = new float[vectors.dimension()];
		}

		@Override
		public int size() {
			return this.vectors.count();
		}

		@Override
		public int dimension() {
			return this.vectors.dimension();
		}

		@Override
		public FloatVectorValues copy() {
			return new MappedFloats(this.vectors);
		}

		@Override
		public float[] vectorValue(int targetOrd) {
			this.vectors.read(targetOrd, this.scratch);
			return this.scratch;
		}

		@Override
		public VectorEncoding getEncoding() {
			return VectorEncoding.FLOAT32;
		}
	}

	/**
	 * Delegating format lifting the 1024-dimension cap of the Lucene HNSW formats. It keeps the delegate's SPI
	 * name, so the index is read back by the stock format, which does not check the cap.
	 */
	static final class HighDimFormat extends KnnVectorsFormat {
		/** The stock format doing the work. */
		private final KnnVectorsFormat delegate;

		/**
		 * @param delegate the stock format doing the work
		 */
		HighDimFormat(@Nonnull KnnVectorsFormat delegate) {
			super(delegate.getName());
			this.delegate = delegate;
		}

		@Override
		public KnnVectorsWriter fieldsWriter(SegmentWriteState state) throws IOException {
			return this.delegate.fieldsWriter(state);
		}

		@Override
		public KnnVectorsReader fieldsReader(SegmentReadState state) throws IOException {
			return this.delegate.fieldsReader(state);
		}

		@Override
		public int getMaxDimensions(String fieldName) {
			return 4096;
		}
	}

	/**
	 * Builds an `OnHeapHnswGraph` directly through `HnswGraphBuilder`, scoring vectors read from the mapping.
	 *
	 * @param base  mapped raw vectors
	 * @param truth ground truth for the sanity recall check
	 * @throws IOException when the builder fails to read vectors
	 */
	public static void hnswLibrary(@Nonnull MappedVectors base, @Nonnull VectorGroundTruth truth) throws IOException {
		section("Lucene util.hnsw as a library, raw vectors MAPPED");
		// the selected vectorization provider is logged by Lucene itself (JUL); its accessor is caller-restricted
		line("HnswGraphBuilder(M=" + M + ", beam=" + BEAM + ")");
		final int n = base.count();
		final FlatVectorsScorer scorer = FlatVectorScorerUtil.getLucene99FlatVectorsScorer();
		final MappedFloats values = new MappedFloats(base);
		final long heapBefore = heapUsedAfterGc();
		resetHeapPeak();
		final long start = System.nanoTime();
		final HnswGraphBuilder builder = HnswGraphBuilder.create(
			scorer.getRandomVectorScorerSupplier(VSF, values), M, BEAM, 42L
		);
		final OnHeapHnswGraph graph = builder.build(n);
		line(
			"build " + (System.nanoTime() - start) / 1_000_000 + " ms (single thread), heap pool peak incl. garbage "
				+ mb(heapPeak())
		);
		size("OnHeapHnswGraph.ramBytesUsed() (library estimate)", graph.ramBytesUsed(), n);
		size("JOL owned size of graph", ownedSize(graph), n);
		printGraphStats(graph);
		size("heap delta of build, builder alive", heapUsedAfterGc() - heapBefore, n);
		line(bufferPools());
		printMappings(base.path().getParent());
		final double recall = truth.recall(q -> {
			try {
				final ScoreDoc[] docs = HnswGraphSearcher.search(
					scorer.getRandomVectorScorer(VSF, new MappedFloats(base), truth.queries()[q]),
					EF, graph, null, Integer.MAX_VALUE
				).topDocs().scoreDocs;
				return toOrdinals(docs);
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		});
		line(String.format("sanity recall@10 (ef=%d) = %.4f", EF, recall));
	}

	/**
	 * Indexes the corpus through `IndexWriter` into an `MMapDirectory`, merges it into one segment, reopens it and
	 * measures heap, buffer pools and page-cache residency before and after queries.
	 *
	 * @param base    mapped raw vectors, the documents' source
	 * @param truth   ground truth for the sanity recall check
	 * @param workDir parent directory of the index
	 * @param codec   one of `float32`, `osq8`, `osq7`, `osq4`, `osq2`, `osq1`
	 * @throws IOException when indexing or reading fails
	 */
	public static void fullIndex(
		@Nonnull MappedVectors base,
		@Nonnull VectorGroundTruth truth,
		@Nonnull Path workDir,
		@Nonnull String codec
	) throws IOException {
		final KnnVectorsFormat format = switch (codec) {
			case "float32" -> new Lucene99HnswVectorsFormat(M, BEAM);
			case "osq8" -> new Lucene104HnswScalarQuantizedVectorsFormat(ScalarEncoding.UNSIGNED_BYTE, M, BEAM);
			case "osq7" -> new Lucene104HnswScalarQuantizedVectorsFormat(ScalarEncoding.SEVEN_BIT, M, BEAM);
			case "osq4" -> new Lucene104HnswScalarQuantizedVectorsFormat(ScalarEncoding.PACKED_NIBBLE, M, BEAM);
			case "osq2" -> new Lucene104HnswScalarQuantizedVectorsFormat(ScalarEncoding.DIBIT_QUERY_NIBBLE, M, BEAM);
			case "osq1" ->
				new Lucene104HnswScalarQuantizedVectorsFormat(ScalarEncoding.SINGLE_BIT_QUERY_NIBBLE, M, BEAM);
			default -> throw new IllegalArgumentException("Unknown codec `" + codec + "`.");
		};
		section("Lucene IndexWriter + MMapDirectory, " + format);
		final int n = base.count();
		final Path indexDir = workDir.resolve("lucene-" + codec);
		deleteRecursively(indexDir);
		Files.createDirectories(indexDir);
		final KnnVectorsFormat highDim = new HighDimFormat(format);
		final IndexWriterConfig config = new IndexWriterConfig()
			.setCodec(
				new Lucene104Codec() {
					@Override
					public KnnVectorsFormat getKnnVectorsFormatForField(String field) {
						return highDim;
					}
				}
			)
			.setRAMBufferSizeMB(2048)
			.setUseCompoundFile(false);
		final long heapBefore = heapUsedAfterGc();
		resetHeapPeak();
		final long start = System.nanoTime();
		try (
			// one writer, nothing else opens the directory; the shared file system the work directory may sit on
			// touches the lock file's timestamp, which the native lock reports as "changed by an external force"
			final Directory directory = new MMapDirectory(indexDir, NoLockFactory.INSTANCE);
			final IndexWriter writer = new IndexWriter(directory, config)
		) {
			final float[] vector = new float[base.dimension()];
			for (int i = 0; i < n; i++) {
				base.read(i, vector);
				final Document document = new Document();
				document.add(new KnnFloatVectorField(FIELD, vector.clone(), VSF));
				writer.addDocument(document);
			}
			size("IndexWriter.ramBytesUsed() before flush (buffered vectors + graph)", writer.ramBytesUsed(), n);
			writer.forceMerge(1);
			writer.commit();
		}
		line(
			"index " + (System.nanoTime() - start) / 1_000_000 + " ms, heap pool peak incl. garbage " + mb(heapPeak())
		);
		long total = 0;
		for (final Map.Entry<String, Long> entry : sizesByExtension(indexDir).entrySet()) {
			size("file *." + entry.getKey(), entry.getValue(), n);
			total += entry.getValue();
		}
		size("index total on disk", total, n);
		final long heapAfterIndexing = heapUsedAfterGc();
		size("heap delta after writer closed", heapAfterIndexing - heapBefore, n);

		try (
			final Directory directory = new MMapDirectory(indexDir);
			final DirectoryReader reader = DirectoryReader.open(directory)
		) {
			line("segments=" + reader.leaves().size());
			size("heap delta after DirectoryReader.open", heapUsedAfterGc() - heapAfterIndexing, n);
			for (final LeafReaderContext context : reader.leaves()) {
				final KnnVectorsReader vectorReader = ((CodecReader) context.reader()).getVectorReader();
				// Lucene 10 dropped KnnVectorsReader#ramBytesUsed; it reports the mapped bytes per file extension
				final Map<String, Long> offHeap = vectorReader.getOffHeapByteSize(
					context.reader().getFieldInfos().fieldInfo(FIELD)
				);
				for (final Map.Entry<String, Long> entry : offHeap.entrySet()) {
					size("KnnVectorsReader.getOffHeapByteSize() ." + entry.getKey(), entry.getValue(), n);
				}
				final KnnVectorsReader fieldReader =
					((PerFieldKnnVectorsFormat.FieldsReader) vectorReader).getFieldReader(FIELD);
				line("field reader " + fieldReader.getClass().getSimpleName());
				if (fieldReader instanceof HnswGraphProvider provider) {
					printGraphStats(provider.getGraph(FIELD));
				}
			}
			size("JOL owned size of DirectoryReader", ownedSize(reader), n);
			line("after open: " + bufferPools());
			printMappings(indexDir);
			final IndexSearcher searcher = new IndexSearcher(reader);
			final double recall = truth.recall(q -> {
				try {
					return toOrdinals(
						searcher.search(new KnnFloatVectorQuery(FIELD, truth.queries()[q], EF), 10).scoreDocs
					);
				} catch (IOException e) {
					throw new UncheckedIOException(e);
				}
			});
			line(String.format("sanity recall@10 (ef=%d) = %.4f", EF, recall));
			OversamplingLadder.run(codec, base, truth, q -> {
				try {
					return toOrdinals(
						searcher.search(
							new KnnFloatVectorQuery(FIELD, truth.queries()[q], OversamplingLadder.CANDIDATES),
							OversamplingLadder.CANDIDATES
						).scoreDocs
					);
				} catch (IOException e) {
					throw new UncheckedIOException(e);
				}
			});
			luceneRescore(codec, searcher, truth);
			size("heap delta after queries, reader open", heapUsedAfterGc() - heapAfterIndexing, n);
			line("after queries: " + bufferPools() + ", process RSS " + mb(processRss()));
			printMappings(indexDir);
			printNativeMemory();
		}
	}

	/**
	 * Measures what Lucene does out of the box: `RescoreTopNQuery#createFullPrecisionRescorerQuery` rescoring the
	 * {@link OversamplingLadder#CANDIDATES} approximate candidates against the float32 vectors in `.vec`, read
	 * through Lucene's own mapping rather than the harness's.
	 *
	 * @param codec    codec name printed in the output
	 * @param searcher searcher over the open index
	 * @param truth    ground truth of the corpus
	 * @throws IOException when the search fails
	 */
	private static void luceneRescore(
		@Nonnull String codec,
		@Nonnull IndexSearcher searcher,
		@Nonnull VectorGroundTruth truth
	) throws IOException {
		final float[][] queries = truth.queries();
		final Query[] rescoreQueries = new Query[queries.length];
		for (int q = 0; q < queries.length; q++) {
			rescoreQueries[q] = RescoreTopNQuery.createFullPrecisionRescorerQuery(
				new KnnFloatVectorQuery(FIELD, queries[q], OversamplingLadder.CANDIDATES), queries[q], FIELD, 10
			);
		}
		final double recall = truth.recall(q -> {
			try {
				return toOrdinals(searcher.search(rescoreQueries[q], 10).scoreDocs);
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		});
		final int passes = 5;
		final long[] latencies = new long[queries.length * passes];
		// the first pass (pass -1) warms the code paths and is not recorded
		for (int pass = -1; pass < passes; pass++) {
			for (int q = 0; q < queries.length; q++) {
				final long start = System.nanoTime();
				searcher.search(rescoreQueries[q], 10);
				if (pass >= 0) {
					latencies[pass * queries.length + q] = System.nanoTime() - start;
				}
			}
		}
		Arrays.sort(latencies);
		line(String.format(
			"%s: Lucene RescoreTopNQuery over %d candidates, recall@10 = %.4f, latency p50 %.3f ms, p95 %.3f ms",
			codec, OversamplingLadder.CANDIDATES, recall,
			OversamplingLadder.percentile(latencies, 0.50), OversamplingLadder.percentile(latencies, 0.95)
		));
	}

	/**
	 * Prints the number of nodes per level and the mean neighbour count on level 0, read through the graph
	 * contract, so the on-heap and the on-disk graph are compared on the same terms.
	 *
	 * @param graph the graph to inspect
	 * @throws IOException when an on-disk graph cannot be read
	 */
	private static void printGraphStats(@Nonnull HnswGraph graph) throws IOException {
		final StringBuilder levels = new StringBuilder(64);
		for (int level = 0; level < graph.numLevels(); level++) {
			levels.append(level == 0 ? "" : ", ").append(graph.getNodesOnLevel(level).size());
		}
		long edges = 0;
		final HnswGraph.NodesIterator nodes = graph.getNodesOnLevel(0);
		int nodeCount = 0;
		while (nodes.hasNext()) {
			graph.seek(0, nodes.nextInt());
			while (graph.nextNeighbor() != Integer.MAX_VALUE) {
				edges++;
			}
			nodeCount++;
		}
		line(String.format(
			"graph: %d levels, nodes per level [%s], mean level-0 degree %.1f",
			graph.numLevels(), levels, edges / (double) nodeCount
		));
	}

	/**
	 * @param docs search hits; doc ids equal ordinals in a single segment without deletions
	 * @return the doc ids in hit order
	 */
	@Nonnull
	private static int[] toOrdinals(@Nonnull ScoreDoc[] docs) {
		final int[] ordinals = new int[docs.length];
		for (int i = 0; i < docs.length; i++) {
			ordinals[i] = docs[i].doc;
		}
		return ordinals;
	}

	/**
	 * @param directory the index directory
	 * @return total bytes per file extension
	 * @throws IOException when the directory cannot be listed
	 */
	@Nonnull
	private static Map<String, Long> sizesByExtension(@Nonnull Path directory) throws IOException {
		final Map<String, Long> result = new TreeMap<>();
		try (final DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
			for (final Path file : stream) {
				final String name = file.getFileName().toString();
				final int dot = name.lastIndexOf('.');
				final String extension = dot < 0 ? name.replaceAll("_.*", "") : name.substring(dot + 1);
				result.merge(extension, Files.size(file), Long::sum);
			}
		}
		return result;
	}

	/**
	 * @param directory directory to delete with its content, if it exists
	 * @throws IOException when a file cannot be deleted
	 */
	private static void deleteRecursively(@Nonnull Path directory) throws IOException {
		if (Files.exists(directory)) {
			final List<Path> paths;
			try (final Stream<Path> walk = Files.walk(directory)) {
				paths = walk.sorted(Comparator.reverseOrder()).toList();
			}
			for (final Path path : paths) {
				Files.delete(path);
			}
		}
	}
}
