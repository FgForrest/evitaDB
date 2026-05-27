/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

package io.evitadb.index.set;

import io.evitadb.api.requestResponse.data.ContentComparator;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.core.transaction.memory.TransactionalLayerProducer;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.io.Serial;
import java.io.Serializable;
import java.lang.reflect.Array;
import java.util.HashSet;
import java.util.Set;

/**
 * Transactional change layer for a {@link java.util.Set}. Tracks insertions (via {@link #createdKeys})
 * and removals (via {@link #removedKeys}) made within a single transaction, and produces the merged
 * result on demand via {@link #createMergedSet}.
 *
 * **Content-aware substitution.** When an element with the same `equals`-identity as an existing
 * entry is added but its *content* has diverged (as reported by
 * {@link io.evitadb.api.requestResponse.data.ContentComparator#differsFrom} when the type implements it),
 * the change layer encodes the update as a paired removal + insertion: the original key is added to
 * {@link #removedKeys} and the new instance is added to {@link #createdKeys}. {@link #createMergedSet}
 * then drops the original and inserts the new instance, so the committed snapshot always reflects the
 * latest content. This prevents the stale-record class of bugs where a price with an unchanged
 * `internalPriceId` but a modified amount was silently ignored.
 *
 * For types that do not implement {@link io.evitadb.api.requestResponse.data.ContentComparator}, the
 * behavior is identical to the previous implementation: `equals`-equal elements are treated as
 * content-equal and no substitution is performed.
 *
 * Not thread-safe — one instance is owned by exactly one transaction thread.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2017
 */
@NotThreadSafe
public class SetChanges<K> implements Serializable {
	@Serial private static final long serialVersionUID = -6370910459056592080L;

	/**
	 * Immutable snapshot of the set as it existed when the transaction opened. Never modified.
	 */
	@Getter private final Set<K> setDelegate;
	/**
	 * Keys that must be excluded from {@link #setDelegate} when producing the merged result.
	 * Entries are added either for a plain removal or as the "remove" half of a content-substitution
	 * pair (where the corresponding "add" half lives in {@link #createdKeys}).
	 */
	private final Set<K> removedKeys = new HashSet<>();
	/**
	 * Keys added or substituted in this transaction. For content-substitution, this set holds the
	 * *new* instance; the old instance is in {@link #removedKeys}. {@link #createMergedSet} adds
	 * all entries here on top of the non-removed delegate keys.
	 */
	private final Set<K> createdKeys = new HashSet<>();

	/**
	 * Returns `true` when `existing` and `candidate` are `equals`-equal (same identity) but carry
	 * different content.
	 *
	 * Decision order:
	 * 1. Same object reference — no difference (`false`).
	 * 2. If `candidate` implements {@link ContentComparator}, delegates to
	 *    {@link ContentComparator#differsFrom} (handles types like `PriceRecordContract` whose `equals`
	 *    only compares identity fields while `differsFrom` checks all amount fields).
	 * 3. Falls back to `!existing.equals(candidate)` for plain-value types whose `equals` already
	 *    implies full content equality.
	 *
	 * @param existing  the instance currently stored in the set (delegate or change layer)
	 * @param candidate the new instance being added in the current transaction
	 * @return `true` if the two instances are identity-equal but content-different
	 */
	@SuppressWarnings({"rawtypes", "unchecked"})
	private static <T> boolean contentDiffers(@Nonnull T existing, @Nonnull T candidate) {
		if (existing == candidate) {
			return false;
		}
		if (candidate instanceof ContentComparator) {
			return ((ContentComparator) candidate).differsFrom(existing);
		}
		return !existing.equals(candidate);
	}

	public SetChanges(@Nonnull Set<K> setDelegate) {
		this.setDelegate = setDelegate;
	}

	/**
	 * Records the insertion of `key` into this change layer. The exact behavior depends on where an
	 * identity-equal element is currently found:
	 *
	 * **Key already in the change layer** — if content has diverged, {@link #replaceInCreatedIfContentDiffers}
	 * substitutes the stored instance with the new one; otherwise nothing changes. Any pending removal is
	 * deliberately left untouched: when this key is the add-half of a delegate substitution its removal
	 * marker must remain, otherwise {@link #createMergedSet} would resurface the stale original.
	 *
	 * **Key already in the delegate** — if content is equal, any pending removal is cancelled (idempotent
	 * re-add); if content has diverged, the original position is added to {@link #removedKeys} AND `key`
	 * is added to {@link #createdKeys}, so {@link #createMergedSet} drops the stale instance and inserts
	 * the new one.
	 *
	 * **Key not present anywhere** — `key` is added to {@link #createdKeys} and any stale removal is
	 * cleared.
	 *
	 * @param key the element to add or substitute; must not be null
	 * @return `true` if the effective set size grew (i.e., `key` was not already logically present),
	 *         `false` for a no-op re-add or a content-substitution
	 */
	public boolean put(@Nonnull K key) {
		if (containsCreated(key)) {
			// already in change layer - replace identity-equal entry if its content has diverged;
			// do NOT cancel a pending removal here: when this key is the "add half" of a delegate
			// substitution it must stay in removedKeys, otherwise the stale original would resurface
			replaceInCreatedIfContentDiffers(key);
			return false;
		}
		if (this.setDelegate.contains(key)) {
			// already in original delegate
			if (contentDiffersFromDelegate(key)) {
				// substitute: mark the original position for removal AND record the new instance as
				// created - [createMergedSet] will drop the original and insert the new instance
				this.removedKeys.add(key);
				this.createdKeys.add(key);
			} else {
				// contents are logically equal - just cancel any pending removal
				this.removedKeys.remove(key);
			}
			return false;
		}
		this.createdKeys.add(key);
		this.removedKeys.remove(key);
		return true;
	}

	/**
	 * Records the removal of `key` from this change layer. The method covers three distinct cases:
	 *
	 * - **Key in delegate only** — marks the delegate position for removal via {@link #removedKeys}.
	 * - **Key in both delegate and change layer** (content-substitution rollback) — drops the pending
	 *   creation from {@link #createdKeys} and ensures the delegate position remains marked for removal,
	 *   effectively rolling back the substitution back to a plain removal.
	 * - **Key only in change layer** (fresh insertion) — removes it from {@link #createdKeys}; the set
	 *   returns to the state before the insertion.
	 * - **Key not found anywhere** — no-op, returns `false`.
	 *
	 * @param key the element to remove; may be any object (follows {@link java.util.Set#remove} contract)
	 * @return `true` if the effective set size shrank, `false` if the key was not logically present
	 */
	@SuppressWarnings("unchecked")
	public boolean remove(Object key) {
		@SuppressWarnings("SuspiciousMethodCalls") final boolean originalContained = this.setDelegate.contains(key);
		final boolean wasInCreated = containsCreated((K) key);
		final boolean wasInRemoved = containsRemoved((K) key);

		// drop any pending creation (covers both fresh adds and substitution replacements)
		if (wasInCreated) {
			removeCreatedKey((K) key);
		}

		if (originalContained) {
			if (wasInRemoved && !wasInCreated) {
				// original already net-removed and no pending substitution to roll back -> no-op
				return false;
			}
			// either this is a removal of an original entry, or a rollback of a "replace" -
			// in both cases the original delegate position must end up removed
			registerRemovedKey((K) key);
			return true;
		}
		// not in delegate - only the pending creation (if any) had any effect
		return wasInCreated;
	}

	/**
	 * Computes the size of the set taking changes in this diff layer into an account.
	 */
	public int size() {
		return this.setDelegate.size() - this.removedKeys.size() + this.createdKeys.size();
	}

	/**
	 * Resolves whether the original set with applied changes from this diff layer would produce empty set.
	 */
	public boolean isEmpty() {
		if (this.removedKeys.isEmpty() && this.createdKeys.isEmpty()) {
			return this.setDelegate.isEmpty();
		} else {
			return size() == 0;
		}
	}

	/**
	 * Resolves whether the key is part of the original set or in this diff layer.
	 */
	@SuppressWarnings("unchecked")
	public boolean contains(Object o) {
		if (containsCreated((K) o)) {
			return true;
		} else if (containsRemoved((K) o)) {
			return false;
		} else {
			//noinspection SuspiciousMethodCalls
			return this.setDelegate.contains(o);
		}
	}

	/**
	 * Creates an array with combining non removed keys from the original set with the created keys trapped in this
	 * memory layer.
	 */
	@Nonnull
	public <T> T[] toArray(@Nonnull T[] a) {
		int index = 0;
		// create array of requested size
		//noinspection unchecked
		final T[] resultArray = (T[]) Array.newInstance(a.getClass().getComponentType(), size());
		// iterate original map and copy all values from it
		for (K key : this.setDelegate) {
			// except those that were removed
			if (!containsRemoved(key)) {
				//noinspection unchecked
				resultArray[index++] = (T) key;
			}
		}

		// iterate over inserted or updated keys
		for (K key : getCreatedKeys()) {
			// update the value
			//noinspection unchecked
			resultArray[index++] = (T) key;
		}

		return resultArray;
	}

	/**
	 * Computes the new set originating from {@link #setDelegate} with applied all changes from this diff layer.
	 */
	@SuppressWarnings("unchecked")
	public Set<K> createMergedSet(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		// create new hash set of requested size
		final HashSet<K> copy = new HashSet<>(this.setDelegate.size());
		// iterate original map and copy all values from it
		for (K key : this.setDelegate) {
			// we need to always create copy - something in the referenced object might have changed
			// even the removed values need to be evaluated (in order to discard them from transactional memory set)
			if (key instanceof TransactionalLayerProducer) {
				key = (K) transactionalLayer.getStateCopyWithCommittedChanges((TransactionalLayerProducer<?, ?>) key);
			}
			// except those that were removed
			if (!containsRemoved(key)) {
				copy.add(key);
			}
		}

		// iterate over inserted or updated keys
		for (K key : getCreatedKeys()) {
			// we need to always create copy - something in the referenced object might have changed
			if (key instanceof TransactionalLayerProducer) {
				key = (K) transactionalLayer.getStateCopyWithCommittedChanges((TransactionalLayerProducer<?, ?>) key);
			}
			// update the value
			copy.add(key);
		}

		return copy;
	}

	/**
	 * Registers a removed entry.
	 */
	public void registerRemovedKey(K key) {
		this.removedKeys.add(key);
	}

	/**
	 * Clears all changes recorded in this diff layer.
	 */
	void clearAll() {
		this.createdKeys.clear();
		this.removedKeys.addAll(this.setDelegate);
	}

	/**
	 * Returns true if the passed key was not part of {@link #setDelegate} but was added in this transactional memory
	 * diff.
	 */
	boolean containsCreated(K key) {
		return this.createdKeys.contains(key);
	}

	/**
	 * Removes a previously created key in this transactional diff.
	 */
	void removeCreatedKey(K key) {
		this.createdKeys.remove(key);
	}

	/**
	 * Returns set of all newly created keys that were not in the original {@link #setDelegate}.
	 */
	@Nonnull
	Set<K> getCreatedKeys() {
		return this.createdKeys;
	}

	/**
	 * Returns true if particular key is recorded to be removed.
	 */
	boolean containsRemoved(K key) {
		return this.removedKeys.contains(key);
	}

	/**
	 * Copies the changes from this layer to another one.
	 */
	void copyState(SetChanges<K> layer) {
		layer.createdKeys.addAll(this.createdKeys);
		layer.removedKeys.addAll(this.removedKeys);
	}

	/**
	 * Returns `true` when an identity-equal element is already present in {@link #setDelegate} but
	 * carries different content from `key`.
	 *
	 * Returns `false` immediately for types that do not implement
	 * {@link io.evitadb.api.requestResponse.data.ContentComparator}, because for those types
	 * `setDelegate.contains(key)` already implies full content equality (their `equals` is
	 * content-aware).
	 *
	 * @param key the candidate element being added in the current transaction
	 */
	private boolean contentDiffersFromDelegate(@Nonnull K key) {
		if (!(key instanceof ContentComparator)) {
			return false;
		}
		final K existing = findIdentityEqual(this.setDelegate, key);
		return existing != null && contentDiffers(existing, key);
	}

	/**
	 * Replaces the stored entry in {@link #createdKeys} with `key` when the stored instance is
	 * identity-equal to `key` but carries different content.
	 *
	 * The substitution is performed by removing the identity-equal entry (via `HashSet.remove`, which
	 * uses `equals`) and re-adding `key`. No-op when the type does not implement
	 * {@link io.evitadb.api.requestResponse.data.ContentComparator} or when the content is already
	 * current.
	 *
	 * @param key the new instance to substitute into the change layer
	 */
	private void replaceInCreatedIfContentDiffers(@Nonnull K key) {
		if (!(key instanceof ContentComparator)) {
			return;
		}
		final K existing = findIdentityEqual(this.createdKeys, key);
		if (existing != null && contentDiffers(existing, key)) {
			this.createdKeys.remove(key); // removes identity-equal entry from the HashSet
			this.createdKeys.add(key);
		}
	}

	/**
	 * Scans `haystack` linearly and returns the first element that is `equals`-equal to `needle`, or
	 * `null` if none is found.
	 *
	 * A linear scan is necessary here because `HashSet.get` is not part of the `Set` API: even though
	 * the set can tell us whether a key is present, it cannot hand back the *stored instance* (which
	 * may differ in content from `needle`). This method retrieves that stored instance so the caller
	 * can decide whether a content-substitution is required.
	 *
	 * @param haystack the set to search; must not be null
	 * @param needle   the element whose `equals`-equal counterpart is sought
	 * @return the stored element that equals `needle`, or `null` if absent
	 */
	@Nullable
	private K findIdentityEqual(@Nonnull Set<K> haystack, @Nonnull K needle) {
		for (K candidate : haystack) {
			if (needle.equals(candidate)) {
				return candidate;
			}
		}
		return null;
	}

}
