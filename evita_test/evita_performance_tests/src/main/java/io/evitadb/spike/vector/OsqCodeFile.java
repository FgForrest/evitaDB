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

import org.apache.lucene.codecs.lucene104.OffHeapScalarQuantizedVectorValues;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.util.VectorUtil;
import org.apache.lucene.util.quantization.OptimizedScalarQuantizer;
import org.apache.lucene.util.quantization.OptimizedScalarQuantizer.QuantizationResult;
import org.apache.lucene.util.quantization.QuantizedByteVectorValues;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * A code file in a layout of the spike's own, holding one optimized-scalar-quantizer code per ordinal, and the
 * mapped reader that hands it to Lucene's scorers. This is the "vector store" seam of
 * `p6-implementation-proposal.md` for the codes: Lucene computes the bytes, the file and its reading are ours.
 *
 * Layout, little-endian: a header of magic, encoding wire number, dimension, count, the centroid's dot product
 * with itself and the centroid itself; then one fixed-size record per ordinal, the packed code followed by the
 * four correction terms in the order of `QuantizationResult` (three floats and an int, 16 B). The packing and
 * the correction order mirror `Lucene104ScalarQuantizedVectorsWriter`, so Lucene's scorer reads our records the
 * way it reads its own `.veq`.
 *
 * @author Lukáš Hornych (hornych@fg.cz)
 */
public final class OsqCodeFile {
	/** File magic, "OSQ1". */
	private static final int MAGIC = 0x4F535131;
	/** Header bytes before the centroid: magic, encoding, dimension, count, centroid dot product. */
	private static final int FIXED_HEADER = 5 * Integer.BYTES;
	/** Bytes of the correction terms following every packed code. */
	private static final int CORRECTIONS = 4 * Integer.BYTES;
	/** Similarity the codes are made for; the corpus vectors are unit length. */
	private static final VectorSimilarityFunction VSF = VectorSimilarityFunction.DOT_PRODUCT;

	private OsqCodeFile() {
		throw new UnsupportedOperationException("Utility class, not to be instantiated.");
	}

	/**
	 * Computes the centroid of the raw block, quantizes every vector against it and appends the codes to a new
	 * file, one record per ordinal. Only one vector and one record are on the heap at a time.
	 *
	 * @param file     the code file to create; an existing one is replaced
	 * @param base     the mapped raw block
	 * @param encoding the code to write
	 * @throws IOException when the file cannot be written
	 */
	public static void write(
		@Nonnull Path file,
		@Nonnull MappedVectors base,
		@Nonnull QuantizedByteVectorValues.ScalarEncoding encoding
	) throws IOException {
		final int dimension = base.dimension();
		final int count = base.count();
		final float[] vector = new float[dimension];
		final float[] centroid = new float[dimension];
		for (int ord = 0; ord < count; ord++) {
			base.read(ord, vector);
			for (int i = 0; i < dimension; i++) {
				centroid[i] += vector[i];
			}
		}
		for (int i = 0; i < dimension; i++) {
			centroid[i] /= count;
		}
		final float centroidDp = VectorUtil.dotProduct(centroid, centroid);

		final OptimizedScalarQuantizer quantizer = new OptimizedScalarQuantizer(VSF);
		final byte[] scratch = new byte[encoding.getDiscreteDimensions(dimension)];
		final byte[] packed = switch (encoding) {
			case UNSIGNED_BYTE, SEVEN_BIT -> scratch;
			case PACKED_NIBBLE, SINGLE_BIT_QUERY_NIBBLE, DIBIT_QUERY_NIBBLE ->
				new byte[encoding.getDocPackedLength(scratch.length)];
		};
		try (final FileChannel channel = FileChannel.open(
			file, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE
		)) {
			final ByteBuffer header = ByteBuffer.allocate(FIXED_HEADER + dimension * Float.BYTES)
				.order(ByteOrder.LITTLE_ENDIAN);
			header.putInt(MAGIC).putInt(encoding.getWireNumber()).putInt(dimension).putInt(count).putFloat(centroidDp);
			for (final float component : centroid) {
				header.putFloat(component);
			}
			writeFully(channel, header.flip());

			final ByteBuffer record = ByteBuffer.allocate(packed.length + CORRECTIONS).order(ByteOrder.LITTLE_ENDIAN);
			for (int ord = 0; ord < count; ord++) {
				// scalarQuantize mutates its input, so it gets the scratch copy read from the mapping
				base.read(ord, vector);
				final QuantizationResult corrections =
					quantizer.scalarQuantize(vector, scratch, encoding.getBits(), centroid);
				pack(encoding, scratch, packed);
				record.clear();
				record.put(packed)
					.putFloat(corrections.lowerInterval())
					.putFloat(corrections.upperInterval())
					.putFloat(corrections.additionalCorrection())
					.putInt(corrections.quantizedComponentSum());
				writeFully(channel, record.flip());
			}
		}
	}

	/**
	 * Maps a code file written by {@link #write}.
	 *
	 * @param file the code file
	 * @return Lucene's view of the codes over the mapping
	 * @throws IOException when the file cannot be mapped or is not a code file
	 */
	@Nonnull
	public static MappedOsqCodes open(@Nonnull Path file) throws IOException {
		try (final FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
			if (channel.size() > Integer.MAX_VALUE) {
				// the spike addresses the mapping with int offsets; production code maps in chunks
				throw new IllegalArgumentException("Code file " + file + " exceeds 2 GB.");
			}
			final ByteBuffer map = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())
				.order(ByteOrder.LITTLE_ENDIAN);
			if (map.getInt(0) != MAGIC) {
				throw new IllegalArgumentException("File " + file + " is not an OSQ code file.");
			}
			final QuantizedByteVectorValues.ScalarEncoding encoding =
				QuantizedByteVectorValues.ScalarEncoding.fromWireNumber(map.getInt(4))
					.orElseThrow(() -> new IllegalArgumentException("Unknown encoding in " + file + "."));
			final int dimension = map.getInt(8);
			final int count = map.getInt(12);
			final float centroidDp = map.getFloat(16);
			final float[] centroid = new float[dimension];
			for (int i = 0; i < dimension; i++) {
				centroid[i] = map.getFloat(FIXED_HEADER + i * Float.BYTES);
			}
			return new MappedOsqCodes(
				map, encoding, dimension, count, centroid, centroidDp, FIXED_HEADER + dimension * Float.BYTES
			);
		}
	}

	/**
	 * Packs a quantized index-side record the way `Lucene104ScalarQuantizedVectorsWriter#packIndexRecord` does.
	 *
	 * @param encoding the code
	 * @param scratch  one quantized value per byte
	 * @param packed   receives the packed record; the same array as `scratch` for the byte-wide codes
	 */
	private static void pack(
		@Nonnull QuantizedByteVectorValues.ScalarEncoding encoding,
		@Nonnull byte[] scratch,
		@Nonnull byte[] packed
	) {
		switch (encoding) {
			case PACKED_NIBBLE -> OffHeapScalarQuantizedVectorValues.packNibbles(scratch, packed);
			case SINGLE_BIT_QUERY_NIBBLE -> OptimizedScalarQuantizer.packAsBinary(scratch, packed);
			case DIBIT_QUERY_NIBBLE -> OptimizedScalarQuantizer.transposeDibit(scratch, packed);
			case UNSIGNED_BYTE, SEVEN_BIT -> {
				// the quantized bytes are the record
			}
		}
	}

	/**
	 * @param channel the channel to write to
	 * @param buffer  the bytes to write, all of them
	 * @throws IOException when the write fails
	 */
	private static void writeFully(@Nonnull FileChannel channel, @Nonnull ByteBuffer buffer) throws IOException {
		while (buffer.hasRemaining()) {
			channel.write(buffer);
		}
	}

	/**
	 * Lucene's `QuantizedByteVectorValues` over a mapped code file. Lucene's scorer asks it for the packed code
	 * and the correction terms of an ordinal; both are read from the mapping, the code into a per-instance
	 * scratch array, as Lucene's own off-heap reader does. Not thread-safe: {@link #copy()} per thread.
	 */
	public static final class MappedOsqCodes extends QuantizedByteVectorValues {
		/** The whole file, read-only and little-endian. */
		private final ByteBuffer map;
		/** The code in the file. */
		private final ScalarEncoding encoding;
		/** Dimension of the vectors the codes were made from, not the packed length. */
		private final int dimension;
		/** Number of codes. */
		private final int count;
		/** The centroid the codes are relative to. */
		private final float[] centroid;
		/** The centroid's dot product with itself. */
		private final float centroidDp;
		/** File offset of the first record. */
		private final int firstRecord;
		/** Bytes of one record: packed code plus corrections. */
		private final int recordSize;
		/** Receives the code handed out last. */
		private final byte[] scratch;
		/** Quantizer Lucene's scorer uses to quantize the query. */
		private final OptimizedScalarQuantizer quantizer = new OptimizedScalarQuantizer(VSF);

		/**
		 * @param map         the mapped file
		 * @param encoding    the code in the file
		 * @param dimension   dimension of the source vectors
		 * @param count       number of codes
		 * @param centroid    the centroid the codes are relative to
		 * @param centroidDp  the centroid's dot product with itself
		 * @param firstRecord file offset of the first record
		 */
		MappedOsqCodes(
			@Nonnull ByteBuffer map,
			@Nonnull ScalarEncoding encoding,
			int dimension,
			int count,
			@Nonnull float[] centroid,
			float centroidDp,
			int firstRecord
		) {
			this.map = map;
			this.encoding = encoding;
			this.dimension = dimension;
			this.count = count;
			this.centroid = centroid;
			this.centroidDp = centroidDp;
			this.firstRecord = firstRecord;
			this.scratch = new byte[encoding.getDocPackedLength(encoding.getDiscreteDimensions(dimension))];
			this.recordSize = this.scratch.length + CORRECTIONS;
		}

		@Override
		public int dimension() {
			return this.dimension;
		}

		@Override
		public int size() {
			return this.count;
		}

		@Override
		public byte[] vectorValue(int ord) {
			this.map.get(this.firstRecord + ord * this.recordSize, this.scratch, 0, this.scratch.length);
			return this.scratch;
		}

		@Override
		public QuantizationResult getCorrectiveTerms(int ord) {
			final int offset = this.firstRecord + ord * this.recordSize + this.scratch.length;
			return new QuantizationResult(
				this.map.getFloat(offset),
				this.map.getFloat(offset + Float.BYTES),
				this.map.getFloat(offset + 2 * Float.BYTES),
				this.map.getInt(offset + 3 * Float.BYTES)
			);
		}

		@Override
		public OptimizedScalarQuantizer getQuantizer() {
			return this.quantizer;
		}

		@Override
		public ScalarEncoding getScalarEncoding() {
			return this.encoding;
		}

		@Override
		public float[] getCentroid() {
			return this.centroid;
		}

		@Override
		public float getCentroidDP() {
			return this.centroidDp;
		}

		@Override
		public MappedOsqCodes copy() {
			return new MappedOsqCodes(
				this.map, this.encoding, this.dimension, this.count, this.centroid, this.centroidDp, this.firstRecord
			);
		}
	}
}
