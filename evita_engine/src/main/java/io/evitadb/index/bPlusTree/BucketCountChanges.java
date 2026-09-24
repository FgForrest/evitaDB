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

package io.evitadb.index.bPlusTree;

import io.evitadb.core.transaction.memory.Snapshotable;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.NotThreadSafe;

/**
 * Represents a per-transaction diff layer for the bucket count of {@link TransactionalBucketBPlusTree} in the Software
 * Transactional Memory (STM) system. Each open transaction that creates or deletes a bucket gets its own instance, so
 * the count it observes stays isolated until the transaction commits — and is discarded wholesale when it aborts.
 *
 * The count is the only MVCC-visible scalar the tree owns; everything else the tree exposes is derived from the node
 * graph, whose nodes carry diff layers of their own. Holding it here rather than in a
 * {@link io.evitadb.index.reference.TransactionalReference} keeps the committed value a plain `int` field on the tree:
 * a production catalog holds hundreds of thousands of these trees, and the holder, its `AtomicReference` and the boxed
 * `Integer` they addressed cost 56 bytes per tree for a number that fits in four.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@NotThreadSafe
public class BucketCountChanges implements Snapshotable<BucketCountChanges.BucketCountMemento> {
	/**
	 * The transaction-local number of buckets the tree holds — the committed count the layer was created from, plus
	 * every bucket born and minus every bucket deleted inside this transaction.
	 */
	private int bucketCount;

	/**
	 * Seeds the layer with the count the transaction starts from. Every {@link #increment()} and {@link #decrement()}
	 * applied afterwards is a delta from that value, so the layer must be created with the tree's **committed** count
	 * and never with zero — a layer seeded with a delta would report the transaction's changes as the whole count.
	 *
	 * @param bucketCount the committed number of buckets the tree held when the transaction first touched it
	 */
	public BucketCountChanges(int bucketCount) {
		this.bucketCount = bucketCount;
	}

	/**
	 * Immutable, primitive-holding memento carrying the entire mutable state of this layer — the single
	 * transaction-local bucket count. A primitive `int` is copied by value, so the memento is inherently independent of
	 * any subsequent mutation of the layer and can be restored any number of times. A record holding the primitive
	 * directly is used (instead of boxing into {@link Integer}) to honour the project's no-autoboxing performance rule.
	 *
	 * @param bucketCount the captured transaction-local bucket count
	 */
	public record BucketCountMemento(int bucketCount) {
	}

	/**
	 * Records the birth of one bucket inside this transaction.
	 */
	public void increment() {
		this.bucketCount++;
	}

	/**
	 * Records the deletion of one bucket inside this transaction.
	 *
	 * The count is a scalar carried apart from the structure it counts, so a missed {@link #increment()} would surface
	 * only far from its cause — as a wrong distinct value count reported to the user, and as an estimated path length
	 * silently collapsing through `NaN` to zero. The premise check makes that state fail where it is created.
	 */
	public void decrement() {
		Assert.isPremiseValid(
			this.bucketCount > 0,
			"The transaction-local bucket count would go negative - a bucket was deleted that this layer never saw born."
		);
		this.bucketCount--;
	}

	/**
	 * Returns the transaction-local bucket count.
	 *
	 * @return the number of buckets the tree holds as seen from inside this transaction
	 */
	public int getBucketCount() {
		return this.bucketCount;
	}

	/**
	 * Captures the single transaction-local bucket count into an immutable memento - see {@link BucketCountMemento} for
	 * why that memento is independent of any later mutation of this layer.
	 *
	 * @return a memento carrying the current transaction-local bucket count
	 */
	@Nonnull
	@Override
	public BucketCountMemento snapshot() {
		return new BucketCountMemento(this.bucketCount);
	}

	/**
	 * Resets the transaction-local bucket count to the one captured in the given memento, undoing every
	 * {@link #increment()} / {@link #decrement()} performed since. This is safe precisely because the savepoint being
	 * rolled back also rewinds the leaves that gained or lost those buckets (see each node's own memento), so the count
	 * and the node graph return to the savepoint together. Since the value is an absolute overwrite rather than an
	 * incremental delta, the same memento may be restored repeatedly.
	 *
	 * @param memento a memento previously produced by {@link #snapshot()} on this same layer
	 */
	@Override
	public void restore(@Nonnull BucketCountMemento memento) {
		this.bucketCount = memento.bucketCount();
	}

}
