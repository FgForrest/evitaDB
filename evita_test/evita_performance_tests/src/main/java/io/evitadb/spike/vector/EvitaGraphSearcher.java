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

import io.github.jbellis.jvector.graph.similarity.ScoreFunction;
import org.apache.lucene.util.hnsw.RandomVectorScorer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/**
 * The evitaDB-owned searcher of spec §2.7, written over the graph contract {@link GraphView} and a node scorer, so
 * that it walks a jVector graph, a Lucene graph or our own graph file alike, through any code. It adds what
 * jVector's `GraphSearcher` lacks:
 *
 * - **a visit budget**: the walk gives up and returns `null` once it has scored more distinct nodes than allowed,
 *   the unit Lucene's `visitLimit` counts in, so the planner rule of section 6.2 of `p6-implementation-proposal.md`
 *   (`visitLimit = members + 1`) stays the same rule;
 * - **cancellation**: a flag consulted on every iteration of the walk;
 * - **in-walk acceptance**: rejected nodes are walked through but never returned;
 * - **predicate-aware widening (ACORN-1)**: rejected neighbours are not scored, and when too many of a node's
 *   neighbours are rejected their own neighbours are explored instead; a port of Lucene 10.6's
 *   `FilteredHnswGraphSearcher#searchLevel`, constants included;
 * - deleted nodes (`GraphView#isLive`) are walked through and never returned, as jVector does with `liveNodes()`.
 *
 * The upper levels are descended greedily (a beam of one) without the filter, then level 0 is walked best-first to
 * the requested depth: the walk stops when the best remaining candidate scores below the worst of `depth` accepted
 * results. Not thread-safe: an instance owns scratch arrays and serves one thread.
 *
 * @author Lukáš Hornych (hornych@fg.cz)
 */
public final class EvitaGraphSearcher {
	/** Share of rejected neighbours above which the ACORN walk explores the second hop; Lucene's constant. */
	private static final float EXPANDED_EXPLORATION_LAMBDA = 0.10f;
	/** A cancellation flag that never fires. */
	public static final BooleanSupplier NEVER_CANCELLED = () -> false;

	/** Visited nodes of level 0, one bit per ordinal. */
	private long[] visited = new long[0];
	/** Neighbours of the expanded node. */
	private int[] neighbors = new int[0];
	/** Neighbours of an explored rejected neighbour. */
	private int[] secondHop = new int[0];
	/** Nodes to score in one ACORN expansion. */
	private int[] toScore = new int[0];
	/** Rejected nodes to explore in one ACORN expansion. */
	private int[] toExplore = new int[0];
	/** Candidates still to expand, a max-heap by score. */
	private final ScoredHeap candidates = new ScoredHeap(true);
	/** The best accepted nodes so far, a min-heap by score bounded by the depth. */
	private final ScoredHeap results = new ScoredHeap(false);
	/** Distinct nodes scored by the last search. */
	private int visitedCount;

	/**
	 * Walks the graph.
	 *
	 * @param graph       the graph
	 * @param scorer      the query's scorer over the code that drives the walk
	 * @param accept      the candidate set, `null` for none
	 * @param depth       how many accepted nodes to return
	 * @param acorn       whether rejected neighbours are skipped and their own neighbours explored
	 * @param visitBudget the most distinct nodes the walk may score, `Integer.MAX_VALUE` for no budget
	 * @param cancelled   consulted on every iteration; the walk throws `CancellationException` once it is true
	 * @return accepted live ordinals, best first by the scorer, or `null` when the walk exceeded its budget
	 * @throws CancellationException when the cancellation flag turned true
	 */
	@Nullable
	public int[] search(
		@Nonnull GraphView graph,
		@Nonnull NodeScorer scorer,
		@Nullable Acceptor accept,
		int depth,
		boolean acorn,
		int visitBudget,
		@Nonnull BooleanSupplier cancelled
	) {
		prepare(graph, depth);
		// greedy descent through the upper levels, unfiltered
		int current = graph.entryNode();
		float currentScore = scorer.score(current);
		this.visitedCount = 1;
		for (int level = graph.maxLevel(); level > 0; level--) {
			boolean improved = true;
			while (improved) {
				if (cancelled.getAsBoolean()) {
					throw new CancellationException("The vector search was cancelled.");
				}
				improved = false;
				final int count = graph.neighbors(level, current, this.neighbors);
				for (int i = 0; i < count; i++) {
					final int neighbor = this.neighbors[i];
					final float s = scorer.score(neighbor);
					if (++this.visitedCount > visitBudget) {
						return null;
					}
					if (s > currentScore) {
						currentScore = s;
						current = neighbor;
						improved = true;
					}
				}
			}
		}
		// best-first walk of level 0
		markVisited(current);
		this.candidates.push(current, currentScore);
		if (isResult(graph, accept, current)) {
			this.results.pushBounded(current, currentScore, depth);
		}
		final boolean widen = acorn && accept != null;
		final int maxConn = graph.maxConn();
		final float filterRatio = accept == null ? 1.0f : Math.max(1, accept.count()) / (float) graph.idUpperBound();
		final int maxExplorationMultiplier = (int) Math.round(Math.min(1 / filterRatio, maxConn / 2.0));
		final int minToScore = (int) Math.round(
			Math.min(Math.max(0, 1.0 / filterRatio - (2.0 * maxConn)), maxConn)
		);
		final int queueCapacity = maxConn * 2 * Math.max(1, maxExplorationMultiplier);
		if (widen && this.toScore.length < queueCapacity) {
			this.toScore = new int[queueCapacity];
			this.toExplore = new int[queueCapacity];
		}
		while (this.candidates.size() > 0) {
			if (cancelled.getAsBoolean()) {
				throw new CancellationException("The vector search was cancelled.");
			}
			if (this.results.size() >= depth && this.candidates.topScore() < this.results.topScore()) {
				break;
			}
			final int node = this.candidates.pop();
			final int count = graph.neighbors(0, node, this.neighbors);
			if (!widen) {
				for (int i = 0; i < count; i++) {
					final int neighbor = this.neighbors[i];
					if (markVisited(neighbor)) {
						continue;
					}
					if (!consider(graph, scorer, accept, neighbor, depth, visitBudget)) {
						return null;
					}
				}
				continue;
			}
			// ACORN-1: score accepted neighbours, explore through rejected ones when too many are rejected
			int scoreCount = 0;
			int exploreCount = 0;
			for (int i = 0; i < count && scoreCount < queueCapacity; i++) {
				final int neighbor = this.neighbors[i];
				if (markVisited(neighbor)) {
					continue;
				}
				if (accept.accept(neighbor)) {
					this.toScore[scoreCount++] = neighbor;
				} else if (exploreCount < queueCapacity) {
					this.toExplore[exploreCount++] = neighbor;
				}
			}
			final float filteredAmount = count == 0 ? 0.0f : exploreCount / (float) count;
			final int maxToScoreCount = (int) (count * Math.min(maxExplorationMultiplier, 1f / (1f - filteredAmount)));
			final int maxAdditionalToExploreCount = queueCapacity - 1;
			int totalExplored = scoreCount + exploreCount;
			if (scoreCount < maxToScoreCount && filteredAmount > EXPANDED_EXPLORATION_LAMBDA) {
				int explorePosition = 0;
				while (
					explorePosition < exploreCount
						&& totalExplored < maxAdditionalToExploreCount
						&& scoreCount < maxToScoreCount
				) {
					final int rejected = this.toExplore[explorePosition++];
					final int hopCount = graph.neighbors(0, rejected, this.secondHop);
					for (int j = 0; j < hopCount && scoreCount < maxToScoreCount; j++) {
						final int friend = this.secondHop[j];
						if (markVisited(friend)) {
							continue;
						}
						totalExplored++;
						if (accept.accept(friend)) {
							this.toScore[scoreCount++] = friend;
						} else if (
							totalExplored < maxAdditionalToExploreCount && scoreCount < minToScore
								&& exploreCount < queueCapacity
						) {
							this.toExplore[exploreCount++] = friend;
						}
					}
				}
			}
			for (int i = 0; i < scoreCount; i++) {
				if (!consider(graph, scorer, accept, this.toScore[i], depth, visitBudget)) {
					return null;
				}
			}
		}
		return this.results.drainBestFirst();
	}

	/**
	 * @return distinct nodes scored by the last search, upper levels included
	 */
	public int visitedCount() {
		return this.visitedCount;
	}

	/**
	 * Resets the scratch state for a walk over the graph.
	 *
	 * @param graph the graph
	 * @param depth the result depth
	 */
	private void prepare(@Nonnull GraphView graph, int depth) {
		final int words = (graph.idUpperBound() + 63) >>> 6;
		if (this.visited.length < words) {
			this.visited = new long[words];
		} else {
			Arrays.fill(this.visited, 0, words, 0L);
		}
		final int degree = graph.maxDegree();
		if (this.neighbors.length < degree) {
			this.neighbors = new int[degree];
			this.secondHop = new int[degree];
		}
		this.candidates.clear();
		this.results.clear();
		this.results.ensureCapacity(depth + 1);
	}

	/**
	 * @param node a node ordinal
	 * @return whether the node had been visited before this call; it is visited afterwards
	 */
	private boolean markVisited(int node) {
		final int word = node >>> 6;
		final long bit = 1L << node;
		final boolean was = (this.visited[word] & bit) != 0;
		this.visited[word] |= bit;
		return was;
	}

	/**
	 * Scores a node and queues it when it can still improve the results.
	 *
	 * @param graph       the graph, for liveness
	 * @param scorer      the query's scorer
	 * @param accept      the candidate set, `null` for none
	 * @param node        the node to score
	 * @param depth       the result depth
	 * @param visitBudget the most distinct nodes the walk may score
	 * @return false when the budget is exceeded
	 */
	private boolean consider(
		@Nonnull GraphView graph,
		@Nonnull NodeScorer scorer,
		@Nullable Acceptor accept,
		int node,
		int depth,
		int visitBudget
	) {
		final float s = scorer.score(node);
		if (++this.visitedCount > visitBudget) {
			return false;
		}
		if (this.results.size() < depth || s > this.results.topScore()) {
			this.candidates.push(node, s);
			if (isResult(graph, accept, node)) {
				this.results.pushBounded(node, s, depth);
			}
		}
		return true;
	}

	/**
	 * @param graph  the graph
	 * @param accept the candidate set, `null` for none
	 * @param node   a node ordinal
	 * @return whether the node may be returned
	 */
	private static boolean isResult(@Nonnull GraphView graph, @Nullable Acceptor accept, int node) {
		return (accept == null || accept.accept(node)) && graph.isLive(node);
	}

	/**
	 * The similarity of the query to a node, higher is closer: the seam through which any code drives the walk.
	 */
	@FunctionalInterface
	public interface NodeScorer {
		/**
		 * @param node a node ordinal
		 * @return the similarity of the query to the node
		 */
		float score(int node);

		/**
		 * @param function a jVector score function of the query, exact or approximate (PQ, BQ)
		 * @return the function as a node scorer
		 */
		@Nonnull
		static NodeScorer ofJVector(@Nonnull ScoreFunction function) {
			return function::similarityTo;
		}

		/**
		 * @param scorer a Lucene scorer of the query, over float32 or over a quantized code
		 * @return the scorer as a node scorer
		 */
		@Nonnull
		static NodeScorer ofLucene(@Nonnull RandomVectorScorer scorer) {
			return node -> {
				try {
					return scorer.score(node);
				} catch (IOException e) {
					throw new UncheckedIOException(e);
				}
			};
		}
	}

	/**
	 * A binary heap of (node, score) pairs, ordered by score.
	 */
	private static final class ScoredHeap {
		/** True for a max-heap, false for a min-heap. */
		private final boolean max;
		/** Node ordinals in heap order. */
		private int[] nodes = new int[64];
		/** Scores in heap order. */
		private float[] scores = new float[64];
		/** Number of entries. */
		private int size;

		/**
		 * @param max true for a max-heap, false for a min-heap
		 */
		ScoredHeap(boolean max) {
			this.max = max;
		}

		/**
		 * @return number of entries
		 */
		int size() {
			return this.size;
		}

		/**
		 * Removes every entry.
		 */
		void clear() {
			this.size = 0;
		}

		/**
		 * @param capacity the number of entries to make room for
		 */
		void ensureCapacity(int capacity) {
			if (this.nodes.length < capacity) {
				this.nodes = Arrays.copyOf(this.nodes, capacity);
				this.scores = Arrays.copyOf(this.scores, capacity);
			}
		}

		/**
		 * @return the score at the top: the highest of a max-heap, the lowest of a min-heap
		 */
		float topScore() {
			return this.scores[0];
		}

		/**
		 * @param node  node ordinal
		 * @param score its score
		 */
		void push(int node, float score) {
			if (this.size == this.nodes.length) {
				ensureCapacity(this.size * 2);
			}
			this.nodes[this.size] = node;
			this.scores[this.size] = score;
			siftUp(this.size++);
		}

		/**
		 * Pushes into a min-heap that keeps the `bound` best entries.
		 *
		 * @param node  node ordinal
		 * @param score its score
		 * @param bound the most entries to keep
		 */
		void pushBounded(int node, float score, int bound) {
			if (this.size < bound) {
				push(node, score);
			} else if (score > this.scores[0]) {
				this.nodes[0] = node;
				this.scores[0] = score;
				siftDown(0);
			}
		}

		/**
		 * @return the node at the top, removed
		 */
		int pop() {
			final int node = this.nodes[0];
			this.size--;
			this.nodes[0] = this.nodes[this.size];
			this.scores[0] = this.scores[this.size];
			siftDown(0);
			return node;
		}

		/**
		 * Empties a min-heap of results.
		 *
		 * @return its nodes, best score first
		 */
		@Nonnull
		int[] drainBestFirst() {
			final int[] ordered = new int[this.size];
			for (int i = ordered.length - 1; i >= 0; i--) {
				ordered[i] = pop();
			}
			return ordered;
		}

		/**
		 * @param a one score
		 * @param b another score
		 * @return whether `a` belongs above `b`
		 */
		private boolean above(float a, float b) {
			return this.max ? a > b : a < b;
		}

		/**
		 * @param index position of an entry that may be above its parent
		 */
		private void siftUp(int index) {
			int i = index;
			while (i > 0) {
				final int parent = (i - 1) >>> 1;
				if (!above(this.scores[i], this.scores[parent])) {
					return;
				}
				swap(i, parent);
				i = parent;
			}
		}

		/**
		 * @param index position of an entry that may be below its children
		 */
		private void siftDown(int index) {
			int i = index;
			while (true) {
				final int left = 2 * i + 1;
				final int right = left + 1;
				int top = i;
				if (left < this.size && above(this.scores[left], this.scores[top])) {
					top = left;
				}
				if (right < this.size && above(this.scores[right], this.scores[top])) {
					top = right;
				}
				if (top == i) {
					return;
				}
				swap(i, top);
				i = top;
			}
		}

		/**
		 * @param a one position
		 * @param b another position
		 */
		private void swap(int a, int b) {
			final int node = this.nodes[a];
			this.nodes[a] = this.nodes[b];
			this.nodes[b] = node;
			final float s = this.scores[a];
			this.scores[a] = this.scores[b];
			this.scores[b] = s;
		}
	}
}
