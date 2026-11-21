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

package io.evitadb.api.requestResponse.data.structure;

import io.evitadb.annotation.Internal;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;

/**
 * Ranks references within an {@link EntityContract}. Each comparator ranks the rows it has data for and leaves
 * the remaining rows for the next link in the ordering.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@SuppressWarnings("ComparatorNotSerializable")
public interface ReferenceComparator extends Comparator<ReferenceContract> {

	/**
	 * Default implementation sorts the references by their referenced primary key.
	 */
	ReferenceComparator DEFAULT = new ReferenceComparator() {

		/**
		 * Compares rows by referenced primary key, using the internal reference key to break ties.
		 *
		 * @param o1 first row
		 * @param o2 second row
		 * @return the order of the rows
		 */
		@Override
		public int compare(
			@Nullable ReferenceContract o1, @Nullable ReferenceContract o2
		) {
			if (o1 == null && o2 == null) {
				return 0;
			} else if (o1 != null && o2 == null) {
				return -1;
			} else if (o1 == null) {
				return 1;
			} else {
				final int firstComparison = Integer.compare(o1.getReferencedPrimaryKey(), o2.getReferencedPrimaryKey());
				if (firstComparison == 0) {
					// Keep duplicate rows in their internal key order.
					return Integer.compare(
						o1.getReferenceKey().internalPrimaryKey(), o2.getReferenceKey().internalPrimaryKey()
					);
				} else {
					return firstComparison;
				}
			}
		}
	};

	/**
	 * Sorts admitted rows in place, advancing to the next applicable link with the unranked suffix.
	 * The same walk is used before fetching reference bodies and when ordering the decorated rows. A link whose
	 * scope does not admit the owner is skipped without being prepared, and the rows no applicable link ranks are
	 * ordered by {@link #DEFAULT} last.
	 *
	 * @param comparator head of the ordering chain
	 * @param ownerPrimaryKey primary key of the entity owning the rows
	 * @param ownerScope scope of the entity owning the rows
	 * @param references array of reference rows
	 * @param from inclusive start of the admitted rows
	 * @param to exclusive end of the admitted rows
	 * @param ownerReferences unfiltered rows of this owner and reference name, in stored order
	 */
	@Internal("shared by reference prefetch and decoration")
	static void sort(
		@Nonnull ReferenceComparator comparator,
		int ownerPrimaryKey,
		@Nonnull Scope ownerScope,
		@Nonnull ReferenceContract[] references,
		int from,
		int to,
		@Nonnull Collection<? extends ReferenceContract> ownerReferences
	) {
		ReferenceContract[] unranked = null;
		ReferenceComparator current = comparator;
		while (from < to && current != DEFAULT) {
			final Link link = current instanceof Link stage ? stage : null;
			final boolean appliesToOwner = link == null || link.ownerScope == null || link.ownerScope == ownerScope;
			if (appliesToOwner) {
				final ReferenceComparator delegate = link == null ? current : link.delegate;
				if (delegate instanceof OwnerReferencesAwareComparator rowAware) {
					rowAware.prepareOwner(ownerReferences);
				} else {
					current.prepareOwner(ownerPrimaryKey, ownerScope);
				}

				// Scan the ranked prefix and the first unranked run in place. A mixed suffix needs one buffer,
				// reused by later links, to preserve the order of the unranked rows as ranked rows move forward.
				int rankedEnd = from;
				while (rankedEnd < to && current.canRank(references[rankedEnd])) {
					rankedEnd++;
				}
				if (rankedEnd < to) {
					int next = rankedEnd + 1;
					while (next < to && !current.canRank(references[next])) {
						next++;
					}
					if (next < to) {
						if (unranked == null) {
							unranked = new ReferenceContract[to - from];
						}
						int unrankedCount = next - rankedEnd;
						System.arraycopy(references, rankedEnd, unranked, 0, unrankedCount);
						references[rankedEnd++] = references[next++];
						for (; next < to; next++) {
							final ReferenceContract row = references[next];
							if (current.canRank(row)) {
								references[rankedEnd++] = row;
							} else {
								unranked[unrankedCount++] = row;
							}
						}
						System.arraycopy(unranked, 0, references, rankedEnd, unrankedCount);
					}
				}
				Arrays.sort(references, from, rankedEnd, current);
				from = rankedEnd;
			}
			current = link == null || link.next == null ? DEFAULT : link.next;
		}
		Arrays.sort(references, from, to, DEFAULT);
	}

	/**
	 * Prepares the comparator for the owner it is about to rank. Stateless comparators need no preparation.
	 * {@link #sort} calls it once per owner and link before any {@link #canRank} or {@link #compare} of that owner's
	 * rows, so an implementation may keep per-owner state until the next call.
	 *
	 * @param ownerPrimaryKey primary key of the entity owning the references
	 * @param ownerScope scope of the entity owning the references
	 */
	default void prepareOwner(int ownerPrimaryKey, @Nonnull Scope ownerScope) {
	}

	/**
	 * Determines whether this comparator can rank an admitted reference. Unranked rows go to the next link.
	 * {@link #compare} is called only for rows this method accepted, so the two must agree on which rows rank.
	 *
	 * @param reference the row to classify
	 * @return whether the comparator can rank the row
	 */
	default boolean canRank(@Nonnull ReferenceContract reference) {
		return true;
	}

	/**
	 * A comparator that builds its ordering from all original rows of the owner's reference.
	 */
	@Internal("prepares reference orderings that depend on rows outside the admitted window")
	interface OwnerReferencesAwareComparator extends ReferenceComparator {

		/**
		 * Prepares this comparator when its link admits the owner and has rows left to rank.
		 *
		 * @param ownerReferences original rows of the reference, including rows filtered out of the result
		 */
		void prepareOwner(@Nonnull Collection<? extends ReferenceContract> ownerReferences);
	}

	/**
	 * A delegate and its successor in the ordering of a reference set. A link can apply to all owners or only to
	 * owners in the scope of its enclosing `inScope` container.
	 */
	@Internal("assembles reference orderings during translation")
	@NotThreadSafe
	final class Link implements ReferenceComparator {
		@Nullable private final Scope ownerScope;
		@Nonnull private final ReferenceComparator delegate;
		@Nullable private Link next;

		/**
		 * Creates a link in a reference ordering.
		 *
		 * @param ownerScope scope the link applies to, or `null` for all owners
		 * @param delegate comparator that ranks the references
		 */
		public Link(@Nullable Scope ownerScope, @Nonnull ReferenceComparator delegate) {
			this.ownerScope = ownerScope;
			this.delegate = delegate;
		}

		/**
		 * Appends the successor during ordering translation.
		 *
		 * @param successor link for the rows this link leaves unranked
		 */
		public void append(@Nonnull Link successor) {
			if (this.next != null) {
				throw new GenericEvitaInternalError("The reference comparator link already has a successor.");
			}
			this.next = successor;
		}

		/**
		 * Prepares this link's delegate for the current owner.
		 *
		 * @param ownerPrimaryKey primary key of the entity owning the references
		 * @param ownerScope scope of the entity owning the references
		 */
		@Override
		public void prepareOwner(int ownerPrimaryKey, @Nonnull Scope ownerScope) {
			this.delegate.prepareOwner(ownerPrimaryKey, ownerScope);
		}

		/**
		 * Checks whether the delegate has enough data to rank this row.
		 *
		 * @param reference row to classify
		 * @return whether the delegate can rank the row
		 */
		@Override
		public boolean canRank(@Nonnull ReferenceContract reference) {
			return this.delegate.canRank(reference);
		}

		/**
		 * Compares two rows using this link's delegate.
		 *
		 * @param first first ranked row
		 * @param second second ranked row
		 * @return the order of the rows
		 */
		@Override
		public int compare(@Nonnull ReferenceContract first, @Nonnull ReferenceContract second) {
			return this.delegate.compare(first, second);
		}
	}
}
