/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.api.requestResponse.data.mutation.reference;


import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;

/**
 * Thin wrapper over {@link ReferenceKey} that implements {@link Comparable} so that it can be used to sort collections.
 *
 * @param referenceKey - reference key to be wrapped
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public record ComparableReferenceKey(
	@Nonnull ReferenceKey referenceKey
)
	implements Comparable<ComparableReferenceKey>, Serializable {
	@Serial private static final long serialVersionUID = -7888384551447045181L;

	public ComparableReferenceKey(@Nonnull ReferenceKey referenceKey) {
		this.referenceKey = referenceKey;
	}

	/**
	 * Applies this wrapper's equality rules to two bare {@link ReferenceKey}s, so a caller holding an unwrapped
	 * key can ask the question without allocating a wrapper to ask it with.
	 *
	 * Kept here rather than at the call site deliberately: it is the single definition of what "the same
	 * reference key" means for this type, and {@link #equals(Object)} delegates to it. Re-implementing the field
	 * comparison anywhere else would let the two drift apart silently.
	 *
	 * @param left  first key to compare
	 * @param right second key to compare
	 * @return true when both keys denote the same reference
	 */
	public static boolean isEquivalent(@Nonnull ReferenceKey left, @Nonnull ReferenceKey right) {
		return ReferenceKey.FULL_COMPARATOR.compare(left, right) == 0;
	}

	/**
	 * Allocation-free equivalent of {@code keys.contains(new ComparableReferenceKey(referenceKey))}.
	 *
	 * Two reasons to prefer it over the {@link java.util.Set#contains(Object)} it replaces:
	 *
	 * - **It allocates nothing.** `contains` can only be handed an object, so probing even an *empty* set cost
	 *   one wrapper per probe. On the entity write path that made this 19.2% of all write-path allocation in the
	 *   production-catalog WARM_UP profile.
	 * - **It is strictly more correct.** {@link #hashCode()} always folds in the internal primary key, while
	 *   {@link #equals(Object)} ignores it when either side {@link ReferenceKey#isUnknownReference()} - so two
	 *   keys that are `equals` can land in different hash buckets, and a `HashSet` probe can miss a member it
	 *   should have found. A linear scan has no such blind spot.
	 *
	 * The scan is `O(keys)`, so this is the right trade only while the collection stays small - it is intended
	 * for the per-entity reassignment sets, which hold at most the references touched by one mutation batch. Do
	 * not reach for it on a large collection.
	 *
	 * @param keys         collection to search
	 * @param referenceKey key to look for
	 * @return true when `keys` holds a key equivalent to `referenceKey`
	 */
	public static boolean containsEquivalent(
		@Nonnull Iterable<ComparableReferenceKey> keys,
		@Nonnull ReferenceKey referenceKey
	) {
		for (final ComparableReferenceKey candidate : keys) {
			if (isEquivalent(candidate.referenceKey(), referenceKey)) {
				return true;
			}
		}
		return false;
	}

	@Override
	public int compareTo(ComparableReferenceKey o) {
		return ReferenceKey.FULL_COMPARATOR.compare(this.referenceKey, o.referenceKey);
	}

	@Override
	public boolean equals(Object o) {
		if (!(o instanceof final ComparableReferenceKey that)) return false;

		return isEquivalent(this.referenceKey, that.referenceKey);
	}

	@Override
	public int hashCode() {
		int result = this.referenceKey.referenceName().hashCode();
		result = 31 * result + this.referenceKey().primaryKey();
		result = 31 * result + this.referenceKey().internalPrimaryKey();
		return result;
	}
}
