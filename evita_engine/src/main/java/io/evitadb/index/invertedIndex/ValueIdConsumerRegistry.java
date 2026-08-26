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

package io.evitadb.index.invertedIndex;

import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.NotThreadSafe;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Records **which subsystems currently need stable value ids** on ONE shared value tree, and nothing else.
 *
 * This is the gate on the id column. The column exists on a tree's leaves when at least one consumer has registered
 * here — never because a particular attribute schema flag is set. That distinction is the whole point: the ids are
 * engine infrastructure, and the first consumer (the trigram substring index) must not become the reason they exist,
 * or the second consumer (reduced-index payload dedup) would have to be built on top of a trigram-shaped API.
 *
 * ## How a consumer plugs in
 *
 * A consumer calls {@link InvertedIndex#attachValueIdConsumer(String)} on each tree it needs ids from and
 * {@link InvertedIndex#detachValueIdConsumer(String)} when it stops needing them; both are idempotent. Registration
 * is intentionally by NAME rather than by an enum constant or a type token, so that adding a consumer never edits
 * this class — and so the name can be shown to an operator asking why a tree is paying for an id column.
 *
 * ## Not transactional
 *
 * The registry is owner-resident bookkeeping, like the page-stream registry beside it: it records a structural
 * decision about the tree rather than data, so it is carried BY REFERENCE across the commit merge and never
 * participates in transactional memory. Registering a consumer is not a data change and must never be rolled back by
 * a data transaction.
 *
 * ## Threading
 *
 * It is mutated only by the single writer that owns the tree — attaching and detaching a consumer are structural
 * decisions taken on the schema-mutation path, which runs under a single session in warm-up mode and is serialized
 * through trunk incorporation on a live catalog — and is therefore not synchronized. A consumer must never
 * register or unregister from a query or background thread. Publication of the owning index to readers carries
 * the happens-before edge that makes the unsynchronized fields visible, so neither this class nor the index's
 * reference to it needs `volatile` or a lock.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@NotThreadSafe
public final class ValueIdConsumerRegistry {

	/**
	 * Names of the subsystems currently requiring value ids, in registration order (the order is kept only so
	 * diagnostics read deterministically).
	 */
	@Nonnull private final Set<String> consumerNames = new LinkedHashSet<>(4);

	/**
	 * Records that `consumerName` needs value ids on the owning tree.
	 *
	 * @param consumerName the consumer's stable name, e.g. `trigram-substring-index`
	 * @return `true` when this call took the registry from empty to non-empty — i.e. when the caller is responsible
	 *         for bringing the id column into existence
	 */
	public boolean register(@Nonnull String consumerName) {
		Assert.isPremiseValid(!consumerName.isBlank(), "Value id consumer name must not be blank!");
		final boolean wasEmpty = this.consumerNames.isEmpty();
		this.consumerNames.add(consumerName);
		return wasEmpty;
	}

	/**
	 * Records that `consumerName` no longer needs value ids on the owning tree.
	 *
	 * @param consumerName the consumer's stable name
	 * @return `true` when this call took the registry from non-empty to empty — i.e. when the id column has become
	 *         unclaimed
	 */
	public boolean unregister(@Nonnull String consumerName) {
		return this.consumerNames.remove(consumerName) && this.consumerNames.isEmpty();
	}

	/**
	 * @return `true` when no subsystem currently needs value ids on the owning tree
	 */
	public boolean isEmpty() {
		return this.consumerNames.isEmpty();
	}

	/**
	 * Tells whether `consumerName` is the only subsystem currently registered — equivalently, whether unregistering it
	 * would take the id column of the owning tree away. The owning index asks this BEFORE mutating the registry, so
	 * that a detach it must refuse leaves the registry and the tree still agreeing with each other.
	 *
	 * @param consumerName the consumer's stable name
	 * @return `true` when this consumer is registered and no other one is
	 */
	public boolean isSoleConsumer(@Nonnull String consumerName) {
		return this.consumerNames.size() == 1 && this.consumerNames.contains(consumerName);
	}

	/**
	 * Returns the names of the registered consumers — for diagnostics, so an operator can see which subsystem the id
	 * column of a given tree is being paid for.
	 *
	 * The result is a SNAPSHOT rather than a view of the live set: a diagnostic answer is handed out once per operator
	 * query, so the copy is free, and it cannot then change under a caller that is still reading it.
	 *
	 * @return an immutable snapshot of the registered consumer names, in registration order
	 */
	@Nonnull
	public Set<String> getConsumerNames() {
		return Collections.unmodifiableSet(new LinkedHashSet<>(this.consumerNames));
	}

	@Override
	public String toString() {
		return "ValueIdConsumerRegistry" + this.consumerNames;
	}

}
