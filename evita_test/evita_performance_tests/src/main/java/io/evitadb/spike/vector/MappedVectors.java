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

import javax.annotation.Nonnull;
import java.io.IOException;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * A dense block of little-endian float32 vectors mapped read-only via `FileChannel#map`.
 *
 * The mapping lives outside the Java heap; only the `MappedByteBuffer` handle is a heap object. Readers copy one
 * vector at a time into a scratch array they own, so no raw vector is retained on the heap beyond one scratch of
 * `dimension * 4` bytes per reader. This is the shape the P6 specification (§2.3) requires from the mapped
 * `RandomAccessVectorValues` evitaDB would write over its own vector file. On JDK 21 a `MappedByteBuffer` is the
 * only non-preview way to map a file; it is reported by the JVM's `mapped` buffer pool. One `MappedByteBuffer` spans
 * at most 2 GB, so a larger file is mapped in segments of whole vectors; a file under 2 GB is one segment.
 *
 * @author Lukáš Hornych (hornych@fg.cz)
 */
public final class MappedVectors {
	/** File the vectors were mapped from. */
	private final Path path;
	/** Number of float components of every vector. */
	private final int dimension;
	/** Number of vectors in the file. */
	private final int count;
	/** Number of vectors in every segment but the last. */
	private final int vectorsPerSegment;
	/** Float views of the read-only mapping segments, little-endian. */
	private final FloatBuffer[] segments;

	/**
	 * Maps the whole file.
	 *
	 * @param path      file holding `count * dimension` little-endian float32 values and nothing else
	 * @param dimension number of components of every vector
	 * @throws IOException when the file cannot be opened or mapped
	 */
	public MappedVectors(@Nonnull Path path, int dimension) throws IOException {
		this.path = path;
		this.dimension = dimension;
		try (final FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
			final long size = channel.size();
			if (size % (4L * dimension) != 0) {
				throw new IllegalArgumentException(
					"File " + path + " of " + size + " B is not a multiple of " + dimension + " floats."
				);
			}
			this.count = Math.toIntExact(size / (4L * dimension));
			this.vectorsPerSegment = Math.max(1, Integer.MAX_VALUE / (4 * dimension));
			final long segmentBytes = 4L * dimension * this.vectorsPerSegment;
			this.segments = new FloatBuffer[(int) Math.max(1, (size + segmentBytes - 1) / segmentBytes)];
			for (int i = 0; i < this.segments.length; i++) {
				final long offset = i * segmentBytes;
				final MappedByteBuffer buffer = channel.map(
					FileChannel.MapMode.READ_ONLY, offset, Math.min(segmentBytes, size - offset)
				);
				this.segments[i] = buffer.order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
			}
		}
	}

	/**
	 * @return the mapped file
	 */
	@Nonnull
	public Path path() {
		return this.path;
	}

	/**
	 * @return number of vectors in the file
	 */
	public int count() {
		return this.count;
	}

	/**
	 * @return number of components of every vector
	 */
	public int dimension() {
		return this.dimension;
	}

	/**
	 * Copies one vector into the caller's array using an absolute bulk get, which is safe to call concurrently.
	 *
	 * @param ordinal dense position of the vector in the file
	 * @param target  array of at least `dimension` floats that receives the vector
	 */
	public void read(int ordinal, @Nonnull float[] target) {
		this.segments[ordinal / this.vectorsPerSegment]
			.get((ordinal % this.vectorsPerSegment) * this.dimension, target, 0, this.dimension);
	}

	/**
	 * Allocates a heap copy of one vector — used only by the heap-baseline scenario and for query vectors.
	 *
	 * @param ordinal dense position of the vector in the file
	 * @return a new array holding the vector
	 */
	@Nonnull
	public float[] copy(int ordinal) {
		final float[] vector = new float[this.dimension];
		read(ordinal, vector);
		return vector;
	}
}
