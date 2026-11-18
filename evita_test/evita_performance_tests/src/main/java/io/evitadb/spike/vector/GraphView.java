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

import io.github.jbellis.jvector.graph.ImmutableGraphIndex;
import io.github.jbellis.jvector.graph.NodesIterator;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.util.hnsw.HnswGraph;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;

/**
 * The graph contract of spec §3.1 that evitaDB's own searcher ({@link EvitaGraphSearcher}) walks: a layered
 * proximity graph addressed by vector ordinals. Level 0 holds every node; a higher level holds a subset. Adapters
 * exist for jVector's `View`, for Lucene's `HnswGraph` and for the graph file of our own ({@link MappedGraphFile}).
 *
 * @author Lukáš Hornych (hornych@fg.cz)
 */
public interface GraphView {

	/**
	 * @return the exclusive upper bound of node ordinals
	 */
	int idUpperBound();

	/**
	 * @return the highest level, 0 for a flat graph
	 */
	int maxLevel();

	/**
	 * @return the node the walk starts from, present on {@link #maxLevel()}
	 */
	int entryNode();

	/**
	 * @return the largest neighbour count of any node on any level, the size `neighbors` needs for its buffer
	 */
	int maxDegree();

	/**
	 * @return Lucene's `M`: half the configured level-0 degree, the scale of the ACORN exploration constants
	 */
	int maxConn();

	/**
	 * Copies the neighbours of a node on a level into a buffer.
	 *
	 * @param level the level
	 * @param node  the node ordinal, present on the level
	 * @param dest  receives the neighbour ordinals; at least {@link #maxDegree()} long
	 * @return the number of neighbours copied
	 */
	int neighbors(int level, int node, @Nonnull int[] dest);

	/**
	 * @param node a node ordinal
	 * @return false when the node was deleted and only stays in the graph to keep it connected
	 */
	boolean isLive(int node);

	/**
	 * @param level the level
	 * @return the ordinals of every node on the level, ascending
	 */
	@Nonnull
	int[] nodesOnLevel(int level);

	/**
	 * @param graph the jVector graph, for the degree bound
	 * @param view  a view of it; the adapter is as thread-safe as the view
	 * @return the jVector graph as our contract
	 */
	@Nonnull
	static GraphView ofJVector(@Nonnull ImmutableGraphIndex graph, @Nonnull ImmutableGraphIndex.View view) {
		return new JVectorGraphView(graph, view);
	}

	/**
	 * @param graph the Lucene graph; `HnswGraph` keeps a seek cursor, so the adapter is single-threaded
	 * @return the Lucene graph as our contract
	 */
	@Nonnull
	static GraphView ofLucene(@Nonnull HnswGraph graph) {
		return new LuceneGraphView(graph);
	}

	/**
	 * jVector's `View` as our contract.
	 *
	 * @param graph the graph
	 * @param view  its view, which carries the live nodes and the entry node
	 */
	record JVectorGraphView(@Nonnull ImmutableGraphIndex graph, @Nonnull ImmutableGraphIndex.View view)
		implements GraphView {

		@Override
		public int idUpperBound() {
			return this.view.getIdUpperBound();
		}

		@Override
		public int maxLevel() {
			return this.view.entryNode().level;
		}

		@Override
		public int entryNode() {
			return this.view.entryNode().node;
		}

		@Override
		public int maxDegree() {
			int max = 0;
			for (final int degree : this.graph.maxDegrees()) {
				max = Math.max(max, degree);
			}
			// while inserts run, a node may hold up to `neighborOverflow` times its degree before it is pruned
			return 2 * max;
		}

		@Override
		public int maxConn() {
			return Math.max(1, this.graph.getDegree(0) / 2);
		}

		@Override
		public int neighbors(int level, int node, @Nonnull int[] dest) {
			final NodesIterator iterator = this.view.getNeighborsIterator(level, node);
			int count = 0;
			while (iterator.hasNext()) {
				dest[count++] = iterator.nextInt();
			}
			return count;
		}

		@Override
		public boolean isLive(int node) {
			return this.view.liveNodes().get(node);
		}

		@Nonnull
		@Override
		public int[] nodesOnLevel(int level) {
			final NodesIterator iterator = this.graph.getNodes(level);
			final int[] nodes = new int[iterator.size()];
			int count = 0;
			while (iterator.hasNext()) {
				nodes[count++] = iterator.nextInt();
			}
			Arrays.sort(nodes, 0, count);
			return count == nodes.length ? nodes : Arrays.copyOf(nodes, count);
		}
	}

	/**
	 * Lucene's `HnswGraph` as our contract. Lucene's graph has no deletes, so every node is live.
	 *
	 * @param graph the graph
	 */
	record LuceneGraphView(@Nonnull HnswGraph graph) implements GraphView {

		@Override
		public int idUpperBound() {
			// a graph built by inserting arbitrary ordinals is sparse: its size counts nodes, not ordinals
			return this.graph.maxNodeId() + 1;
		}

		@Override
		public int maxLevel() {
			try {
				return this.graph.numLevels() - 1;
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		}

		@Override
		public int entryNode() {
			try {
				return this.graph.entryNode();
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		}

		@Override
		public int maxDegree() {
			// Lucene's level 0 holds up to 2 × M neighbours, the upper levels M
			return 2 * this.graph.maxConn();
		}

		@Override
		public int maxConn() {
			return this.graph.maxConn();
		}

		@Override
		public int neighbors(int level, int node, @Nonnull int[] dest) {
			try {
				this.graph.seek(level, node);
				int count = 0;
				int n = this.graph.nextNeighbor();
				while (n != DocIdSetIterator.NO_MORE_DOCS) {
					dest[count++] = n;
					n = this.graph.nextNeighbor();
				}
				return count;
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		}

		@Override
		public boolean isLive(int node) {
			return true;
		}

		@Nonnull
		@Override
		public int[] nodesOnLevel(int level) {
			try {
				final HnswGraph.NodesIterator iterator = this.graph.getNodesOnLevel(level);
				final int[] nodes = new int[iterator.size()];
				int count = 0;
				while (iterator.hasNext()) {
					nodes[count++] = iterator.nextInt();
				}
				Arrays.sort(nodes, 0, count);
				return count == nodes.length ? nodes : Arrays.copyOf(nodes, count);
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		}
	}
}
