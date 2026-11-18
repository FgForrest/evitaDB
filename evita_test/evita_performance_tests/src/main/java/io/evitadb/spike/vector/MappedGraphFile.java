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

import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.util.hnsw.HnswGraph;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

/**
 * A naive graph file of our own (section 4.4 of `p6-implementation-proposal.md`): a layered proximity graph written
 * once from any {@link GraphView} and read back mapped, so a restart opens the graph instead of rebuilding it.
 * The reader is both our {@link GraphView} (for {@link EvitaGraphSearcher}) and Lucene's {@link HnswGraph} (for
 * Lucene's own searchers), so either library's walk runs over the same bytes.
 *
 * Layout, all values little-endian 32-bit integers:
 *
 * - header: magic, format version, node ordinal bound `n`, number of levels `L`, entry node, slot degree of level 0
 *   `d0`, slot degree of the upper levels `du`, then the node count of every level 1 .. L-1;
 * - level 0: `n` fixed slots of `1 + d0` integers, the neighbour count followed by the neighbours, so the slot of
 *   a node is found by arithmetic;
 * - every upper level: its node ordinals ascending (found by binary search), then one slot of `1 + du` integers
 *   per node in the same order.
 *
 * The file is read-only: it holds a frozen graph. Insert, delete and an append-only update are out of scope of this
 * naive version. The `HnswGraph` side keeps a seek cursor and is single-threaded, like Lucene's own graphs; the
 * `GraphView` side is stateless and may be shared.
 *
 * @author Lukáš Hornych (hornych@fg.cz)
 */
public final class MappedGraphFile extends HnswGraph implements GraphView {
	/** File magic, "EVGR". */
	private static final int MAGIC = 0x45564752;
	/** Format version. */
	private static final int VERSION = 1;
	/** Integers of the fixed header before the per-level node counts. */
	private static final int FIXED_HEADER = 7;

	/** The mapped file as integers. */
	private final IntBuffer ints;
	/** Node ordinal bound. */
	private final int size;
	/** Number of levels. */
	private final int numLevels;
	/** Entry node. */
	private final int entryNode;
	/** Slot degree of level 0. */
	private final int degree0;
	/** Slot degree of the upper levels. */
	private final int degreeUpper;
	/** Integer offset of the level-0 slots. */
	private final int level0Offset;
	/** Per upper level, the integer offset of its sorted node list; index 0 unused. */
	private final int[] nodeListOffset;
	/** Per upper level, its node count; index 0 unused. */
	private final int[] levelCount;
	/** Per upper level, the integer offset of its slots; index 0 unused. */
	private final int[] slotOffset;
	/** Cursor of the `HnswGraph` side: next neighbour position. */
	private int cursor;
	/** Cursor of the `HnswGraph` side: end of the current neighbour list. */
	private int cursorEnd;
	/** Cursor of the `HnswGraph` side: start of the current neighbour list. */
	private int cursorStart;

	/**
	 * @param ints the mapped file as integers
	 */
	private MappedGraphFile(@Nonnull IntBuffer ints) {
		this.ints = ints;
		if (ints.get(0) != MAGIC || ints.get(1) != VERSION) {
			throw new IllegalStateException("Not a graph file of format version " + VERSION + ".");
		}
		this.size = ints.get(2);
		this.numLevels = ints.get(3);
		this.entryNode = ints.get(4);
		this.degree0 = ints.get(5);
		this.degreeUpper = ints.get(6);
		this.nodeListOffset = new int[this.numLevels];
		this.levelCount = new int[this.numLevels];
		this.slotOffset = new int[this.numLevels];
		this.level0Offset = FIXED_HEADER + this.numLevels - 1;
		int offset = this.level0Offset + this.size * (1 + this.degree0);
		for (int level = 1; level < this.numLevels; level++) {
			this.levelCount[level] = ints.get(FIXED_HEADER + level - 1);
			this.nodeListOffset[level] = offset;
			this.slotOffset[level] = offset + this.levelCount[level];
			offset = this.slotOffset[level] + this.levelCount[level] * (1 + this.degreeUpper);
		}
	}

	/**
	 * Writes a graph into a new file.
	 *
	 * @param graph the graph to write; it must not change while it is written
	 * @param file  the file, replaced when it exists
	 * @return the number of bytes written
	 * @throws IOException when the file cannot be written
	 */
	public static long write(@Nonnull GraphView graph, @Nonnull Path file) throws IOException {
		final int size = graph.idUpperBound();
		final int numLevels = graph.maxLevel() + 1;
		final int[] buffer = new int[graph.maxDegree()];
		final int[][] upperNodes = new int[numLevels][];
		// the slot degrees are the largest neighbour counts actually present, not the configured bounds
		int degree0 = 0;
		final int[] level0 = graph.nodesOnLevel(0);
		for (final int node : level0) {
			degree0 = Math.max(degree0, graph.neighbors(0, node, buffer));
		}
		int degreeUpper = 0;
		for (int level = 1; level < numLevels; level++) {
			upperNodes[level] = graph.nodesOnLevel(level);
			for (final int node : upperNodes[level]) {
				degreeUpper = Math.max(degreeUpper, graph.neighbors(level, node, buffer));
			}
		}
		try (
			final FileChannel channel = FileChannel.open(
				file, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE
			)
		) {
			final IntWriter out = new IntWriter(channel);
			out.put(MAGIC);
			out.put(VERSION);
			out.put(size);
			out.put(numLevels);
			out.put(graph.entryNode());
			out.put(degree0);
			out.put(degreeUpper);
			for (int level = 1; level < numLevels; level++) {
				out.put(upperNodes[level].length);
			}
			// level 0: a slot per ordinal; an ordinal absent from the graph gets an empty slot
			int next = 0;
			for (int node = 0; node < size; node++) {
				final int count;
				if (next < level0.length && level0[next] == node) {
					count = graph.neighbors(0, node, buffer);
					next++;
				} else {
					count = 0;
				}
				writeSlot(out, buffer, count, degree0);
			}
			for (int level = 1; level < numLevels; level++) {
				for (final int node : upperNodes[level]) {
					out.put(node);
				}
				for (final int node : upperNodes[level]) {
					writeSlot(out, buffer, graph.neighbors(level, node, buffer), degreeUpper);
				}
			}
			out.flush();
			return channel.size();
		}
	}

	/**
	 * Maps a graph file.
	 *
	 * @param file the file written by {@link #write(GraphView, Path)}
	 * @return the mapped graph
	 * @throws IOException when the file cannot be mapped
	 */
	@Nonnull
	public static MappedGraphFile open(@Nonnull Path file) throws IOException {
		try (final FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
			final MappedByteBuffer mapped = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size());
			return new MappedGraphFile(mapped.order(ByteOrder.LITTLE_ENDIAN).asIntBuffer());
		}
	}

	/**
	 * @param out    the writer
	 * @param buffer the neighbours
	 * @param count  number of neighbours
	 * @param degree slot degree; the slot is padded to it
	 * @throws IOException when the file cannot be written
	 */
	private static void writeSlot(@Nonnull IntWriter out, @Nonnull int[] buffer, int count, int degree)
		throws IOException {
		out.put(count);
		for (int i = 0; i < degree; i++) {
			out.put(i < count ? buffer[i] : -1);
		}
	}

	/**
	 * @param level the level
	 * @param node  a node ordinal present on the level
	 * @return integer offset of the node's slot
	 */
	private int slot(int level, int node) {
		if (level == 0) {
			return this.level0Offset + node * (1 + this.degree0);
		}
		final int index = binarySearch(this.nodeListOffset[level], this.levelCount[level], node);
		if (index < 0) {
			throw new IllegalArgumentException("Node " + node + " is not on level " + level + ".");
		}
		return this.slotOffset[level] + index * (1 + this.degreeUpper);
	}

	/**
	 * @param offset integer offset of a sorted node list
	 * @param count  its length
	 * @param node   the node to find
	 * @return its index, or a negative number when absent
	 */
	private int binarySearch(int offset, int count, int node) {
		int low = 0;
		int high = count - 1;
		while (low <= high) {
			final int mid = (low + high) >>> 1;
			final int value = this.ints.get(offset + mid);
			if (value < node) {
				low = mid + 1;
			} else if (value > node) {
				high = mid - 1;
			} else {
				return mid;
			}
		}
		return -1;
	}

	@Override
	public int idUpperBound() {
		return this.size;
	}

	@Override
	public int maxLevel() {
		return this.numLevels - 1;
	}

	@Override
	public int entryNode() {
		return this.entryNode;
	}

	@Override
	public int maxDegree() {
		return Math.max(this.degree0, this.degreeUpper);
	}

	@Override
	public int neighbors(int level, int node, @Nonnull int[] dest) {
		final int slot = slot(level, node);
		final int count = this.ints.get(slot);
		this.ints.get(slot + 1, dest, 0, count);
		return count;
	}

	@Override
	public boolean isLive(int node) {
		return true;
	}

	@Nonnull
	@Override
	public int[] nodesOnLevel(int level) {
		if (level == 0) {
			final int[] nodes = new int[this.size];
			Arrays.setAll(nodes, i -> i);
			return nodes;
		}
		final int[] nodes = new int[this.levelCount[level]];
		this.ints.get(this.nodeListOffset[level], nodes, 0, nodes.length);
		return nodes;
	}

	@Override
	public void seek(int level, int target) {
		final int slot = slot(level, target);
		this.cursorStart = slot + 1;
		this.cursor = this.cursorStart;
		this.cursorEnd = this.cursorStart + this.ints.get(slot);
	}

	@Override
	public int size() {
		return this.size;
	}

	@Override
	public int nextNeighbor() {
		return this.cursor < this.cursorEnd ? this.ints.get(this.cursor++) : DocIdSetIterator.NO_MORE_DOCS;
	}

	@Override
	public int numLevels() {
		return this.numLevels;
	}

	@Override
	public int maxConn() {
		// Lucene's M: level 0 holds up to 2 × M neighbours
		return Math.max(1, this.degree0 / 2);
	}

	@Override
	public int neighborCount() {
		return this.cursorEnd - this.cursorStart;
	}

	@Override
	public NodesIterator getNodesOnLevel(int level) {
		if (level == 0) {
			return new DenseNodesIterator(this.size);
		}
		return new ArrayNodesIterator(nodesOnLevel(level));
	}

	/**
	 * Buffered little-endian integer output to a channel.
	 */
	private static final class IntWriter {
		/** The channel written to. */
		private final FileChannel channel;
		/** The buffer, flushed when full. */
		private final ByteBuffer buffer = ByteBuffer.allocateDirect(1 << 20).order(ByteOrder.LITTLE_ENDIAN);

		/**
		 * @param channel the channel written to
		 */
		IntWriter(@Nonnull FileChannel channel) {
			this.channel = channel;
		}

		/**
		 * @param value the integer to write
		 * @throws IOException when the channel cannot be written
		 */
		void put(int value) throws IOException {
			if (this.buffer.remaining() < Integer.BYTES) {
				flush();
			}
			this.buffer.putInt(value);
		}

		/**
		 * Writes the buffered integers.
		 *
		 * @throws IOException when the channel cannot be written
		 */
		void flush() throws IOException {
			this.buffer.flip();
			while (this.buffer.hasRemaining()) {
				this.channel.write(this.buffer);
			}
			this.buffer.clear();
		}
	}
}
