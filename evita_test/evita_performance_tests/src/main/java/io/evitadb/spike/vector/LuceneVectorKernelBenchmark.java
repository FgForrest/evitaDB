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

import org.apache.lucene.util.VectorUtil;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import javax.annotation.Nonnull;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * The distance kernels Lucene's `VectorUtil` dispatches to its vectorization provider, one per code of the
 * specification's §3.5 ladder: float32 (the exact reranker), 8-bit, 7-bit, 4-bit (packed), the 2-bit document ×
 * 4-bit query and the 1-bit document × 4-bit query kernels of the optimized scalar quantizer, and the XOR
 * popcount of symmetric binary quantization.
 *
 * The benchmark carries no JVM flags of its own, so the forked JVM inherits the host's arguments, and the
 * vectorization provider is chosen by them: run it once **with** `--add-modules jdk.incubator.vector` (Lucene
 * selects `PanamaVectorizationProvider`) and once **without** it (Lucene falls back to the scalar
 * `DefaultVectorizationProvider`). Lucene logs the provider it selected on first use; confirm it in each run's
 * output, otherwise both runs may have measured the same code.
 *
 * Each invocation scores one query against {@link #DOCUMENTS} documents, small enough to stay in the L2 cache, so
 * the figure is the arithmetic of the kernel, not memory bandwidth.
 *
 * @author Lukáš Hornych (hornych@fg.cz)
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class LuceneVectorKernelBenchmark {
	/** Number of documents scored per invocation. */
	private static final int DOCUMENTS = 64;

	/**
	 * Runs the benchmark from the command line.
	 *
	 * @param args JMH arguments
	 * @throws Exception when JMH fails
	 */
	public static void main(String[] args) throws Exception {
		org.openjdk.jmh.Main.main(args);
	}

	/**
	 * Random query and documents in every representation the kernels consume.
	 */
	@State(Scope.Benchmark)
	public static class Vectors {
		/** Vector dimension: the specification's 768 and the measured corpus's 2048. */
		@Param({"768", "2048"})
		public int dimension;

		/** float32 query. */
		float[] floatQuery;
		/** float32 documents. */
		float[][] floatDocuments;
		/** Unsigned 8-bit query. */
		byte[] byteQuery;
		/** Unsigned 8-bit documents. */
		byte[][] byteDocuments;
		/** 7-bit query, values 0..127. */
		byte[] sevenBitQuery;
		/** 7-bit documents, values 0..127. */
		byte[][] sevenBitDocuments;
		/** Unpacked 4-bit query, one nibble per byte. */
		byte[] nibbleQuery;
		/** Packed 4-bit query, two nibbles per byte. */
		byte[] packedNibbleQuery;
		/** Packed 4-bit documents, two nibbles per byte. */
		byte[][] packedNibbleDocuments;
		/** 4-bit query transposed into four bit planes, as the asymmetric kernels expect it. */
		byte[] transposedNibbleQuery;
		/** 2-bit documents as two bit planes. */
		byte[][] dibitDocuments;
		/** 1-bit documents. */
		byte[][] bitDocuments;
		/** 1-bit query for the symmetric XOR popcount. */
		byte[] bitQuery;

		/**
		 * Fills every representation from one fixed seed.
		 */
		@Setup
		public void setUp() {
			final Random random = new Random(42L);
			final int dim = this.dimension;
			this.floatQuery = randomFloats(random, dim);
			this.floatDocuments = new float[DOCUMENTS][];
			this.byteQuery = randomBytes(random, dim, 256);
			this.byteDocuments = new byte[DOCUMENTS][];
			this.sevenBitQuery = randomBytes(random, dim, 128);
			this.sevenBitDocuments = new byte[DOCUMENTS][];
			this.nibbleQuery = randomBytes(random, dim, 16);
			this.packedNibbleQuery = randomBytes(random, dim / 2, 256);
			this.packedNibbleDocuments = new byte[DOCUMENTS][];
			this.transposedNibbleQuery = randomBytes(random, 4 * dim / 8, 256);
			this.dibitDocuments = new byte[DOCUMENTS][];
			this.bitDocuments = new byte[DOCUMENTS][];
			this.bitQuery = randomBytes(random, dim / 8, 256);
			for (int i = 0; i < DOCUMENTS; i++) {
				this.floatDocuments[i] = randomFloats(random, dim);
				this.byteDocuments[i] = randomBytes(random, dim, 256);
				this.sevenBitDocuments[i] = randomBytes(random, dim, 128);
				this.packedNibbleDocuments[i] = randomBytes(random, dim / 2, 256);
				this.dibitDocuments[i] = randomBytes(random, 2 * dim / 8, 256);
				this.bitDocuments[i] = randomBytes(random, dim / 8, 256);
			}
		}

		/**
		 * @param random source of randomness
		 * @param length number of components
		 * @return components uniform in `[-1, 1)`
		 */
		@Nonnull
		private static float[] randomFloats(@Nonnull Random random, int length) {
			final float[] result = new float[length];
			for (int i = 0; i < length; i++) {
				result[i] = random.nextFloat() * 2 - 1;
			}
			return result;
		}

		/**
		 * @param random source of randomness
		 * @param length number of bytes
		 * @param bound  exclusive upper bound of each unsigned value
		 * @return bytes holding values uniform in `[0, bound)`
		 */
		@Nonnull
		private static byte[] randomBytes(@Nonnull Random random, int length, int bound) {
			final byte[] result = new byte[length];
			for (int i = 0; i < length; i++) {
				result[i] = (byte) random.nextInt(bound);
			}
			return result;
		}
	}

	/**
	 * float32 dot product: the exact reranker and the float32 graph walk.
	 *
	 * @param vectors the fixture
	 * @return the summed scores, so nothing is optimised away
	 */
	@Benchmark
	@OperationsPerInvocation(DOCUMENTS)
	public float float32Dot(@Nonnull Vectors vectors) {
		float total = 0;
		for (int i = 0; i < DOCUMENTS; i++) {
			total += VectorUtil.dotProduct(vectors.floatQuery, vectors.floatDocuments[i]);
		}
		return total;
	}

	/**
	 * Unsigned 8-bit dot product: `UNSIGNED_BYTE`.
	 *
	 * @param vectors the fixture
	 * @return the summed scores
	 */
	@Benchmark
	@OperationsPerInvocation(DOCUMENTS)
	public long uint8Dot(@Nonnull Vectors vectors) {
		long total = 0;
		for (int i = 0; i < DOCUMENTS; i++) {
			total += VectorUtil.uint8DotProduct(vectors.byteQuery, vectors.byteDocuments[i]);
		}
		return total;
	}

	/**
	 * Signed byte dot product over 7-bit values: `SEVEN_BIT`.
	 *
	 * @param vectors the fixture
	 * @return the summed scores
	 */
	@Benchmark
	@OperationsPerInvocation(DOCUMENTS)
	public long int7Dot(@Nonnull Vectors vectors) {
		long total = 0;
		for (int i = 0; i < DOCUMENTS; i++) {
			total += VectorUtil.dotProduct(vectors.sevenBitQuery, vectors.sevenBitDocuments[i]);
		}
		return total;
	}

	/**
	 * 4-bit dot product, unpacked query against a packed document: `PACKED_NIBBLE` at query time.
	 *
	 * @param vectors the fixture
	 * @return the summed scores
	 */
	@Benchmark
	@OperationsPerInvocation(DOCUMENTS)
	public long int4DotSinglePacked(@Nonnull Vectors vectors) {
		long total = 0;
		for (int i = 0; i < DOCUMENTS; i++) {
			total += VectorUtil.int4DotProductSinglePacked(vectors.nibbleQuery, vectors.packedNibbleDocuments[i]);
		}
		return total;
	}

	/**
	 * 4-bit dot product, both sides packed: `PACKED_NIBBLE` between two documents during graph construction.
	 *
	 * @param vectors the fixture
	 * @return the summed scores
	 */
	@Benchmark
	@OperationsPerInvocation(DOCUMENTS)
	public long int4DotBothPacked(@Nonnull Vectors vectors) {
		long total = 0;
		for (int i = 0; i < DOCUMENTS; i++) {
			total += VectorUtil.int4DotProductBothPacked(vectors.packedNibbleQuery, vectors.packedNibbleDocuments[i]);
		}
		return total;
	}

	/**
	 * 2-bit document × 4-bit query: `DIBIT_QUERY_NIBBLE`.
	 *
	 * @param vectors the fixture
	 * @return the summed scores
	 */
	@Benchmark
	@OperationsPerInvocation(DOCUMENTS)
	public long dibitQueryNibble(@Nonnull Vectors vectors) {
		long total = 0;
		for (int i = 0; i < DOCUMENTS; i++) {
			total += VectorUtil.int4DibitDotProduct(vectors.transposedNibbleQuery, vectors.dibitDocuments[i]);
		}
		return total;
	}

	/**
	 * 1-bit document × 4-bit query, the RaBitQ-derived asymmetric kernel: `SINGLE_BIT_QUERY_NIBBLE`.
	 *
	 * @param vectors the fixture
	 * @return the summed scores
	 */
	@Benchmark
	@OperationsPerInvocation(DOCUMENTS)
	public long singleBitQueryNibble(@Nonnull Vectors vectors) {
		long total = 0;
		for (int i = 0; i < DOCUMENTS; i++) {
			total += VectorUtil.int4BitDotProduct(vectors.transposedNibbleQuery, vectors.bitDocuments[i]);
		}
		return total;
	}

	/**
	 * Symmetric 1-bit XOR popcount (Hamming distance), the kernel of jVector-style binary quantization.
	 *
	 * @param vectors the fixture
	 * @return the summed distances
	 */
	@Benchmark
	@OperationsPerInvocation(DOCUMENTS)
	public long hamming(@Nonnull Vectors vectors) {
		long total = 0;
		for (int i = 0; i < DOCUMENTS; i++) {
			total += VectorUtil.xorBitCount(vectors.bitQuery, vectors.bitDocuments[i]);
		}
		return total;
	}
}
