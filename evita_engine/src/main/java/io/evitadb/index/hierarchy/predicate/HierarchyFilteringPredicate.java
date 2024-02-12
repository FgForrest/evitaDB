/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.index.hierarchy.predicate;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.openhft.hashing.LongHashFunction;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;
import java.util.function.IntPredicate;
import java.util.function.Predicate;

/**
 * The predicate controls the visibility for the hierarchical entities that take part in hierarchy statistics
 * computation. The hierarchical entities that are not matched by this predicate will not be counted nor present
 * in the output.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public interface HierarchyFilteringPredicate extends IntPredicate {

	HierarchyFilteringPredicate REJECT_ALL_NODES_PREDICATE = new HierarchyFilteringRejectAllPredicate();
	HierarchyFilteringPredicate ACCEPT_ALL_NODES_PREDICATE = new HierarchyFilteringAcceptAllPredicate();

	/**
	 * The copy of the {@link Predicate#and(Predicate)} that combines two {@link HierarchyFilteringPredicate} producing
	 * another one in conjunction scope.
	 */
	@Nonnull
	default HierarchyFilteringPredicate and(@Nonnull HierarchyFilteringPredicate other) {
		return new AndHierarchyFilteringPredicate(this, other);
	}

	/**
	 * The copy of the {@link Predicate#or(Predicate)} that combines two {@link HierarchyFilteringPredicate} producing
	 * another one in conjunction scope.
	 */
	@Nonnull
	default HierarchyFilteringPredicate or(@Nonnull HierarchyFilteringPredicate other) {
		return new OrHierarchyFilteringPredicate(this, other);
	}

	/**
	 * Computes unique hash for this predicate.
	 */
	long computeHash(@Nonnull LongHashFunction hashFunction);

	@Nonnull
	@Override
	default HierarchyFilteringPredicate negate() {
		return this instanceof NegatedHierarchyFilteringPredicate negatedHierarchyFilteringPredicate ?
			// double negation means unwrapping
			negatedHierarchyFilteringPredicate.getPredicate() :
			new NegatedHierarchyFilteringPredicate(this);
	}

	/**
	 * Implementation of {@link HierarchyFilteringPredicate} that combines two implementations of the same type with
	 * AND relation.
	 */
	@RequiredArgsConstructor
	class AndHierarchyFilteringPredicate implements HierarchyFilteringPredicate, Serializable {
		@Serial private static final long serialVersionUID = 1384400346650986235L;
		private final HierarchyFilteringPredicate first;
		private final HierarchyFilteringPredicate second;

		@Override
		public long computeHash(@Nonnull LongHashFunction hashFunction) {
			return hashFunction.hashLongs(new long[]{serialVersionUID, first.computeHash(hashFunction), second.computeHash(hashFunction)});
		}

		@Override
		public boolean test(int hierarchyNodeId) {
			return first.test(hierarchyNodeId) && second.test(hierarchyNodeId);
		}

		@Override
		public String toString() {
			return  first + " AND " + second;
		}

	}

	/**
	 * Implementation of {@link HierarchyFilteringPredicate} that combines two implementations of the same type with
	 * OR relation.
	 */
	@RequiredArgsConstructor
	class OrHierarchyFilteringPredicate implements HierarchyFilteringPredicate, Serializable {
		@Serial private static final long serialVersionUID = 2547741955086633818L;
		private final HierarchyFilteringPredicate first;
		private final HierarchyFilteringPredicate second;

		@Override
		public long computeHash(@Nonnull LongHashFunction hashFunction) {
			return hashFunction.hashLongs(new long[]{serialVersionUID, first.computeHash(hashFunction), second.computeHash(hashFunction)});
		}

		@Override
		public boolean test(int hierarchyNodeId) {
			return first.test(hierarchyNodeId) || second.test(hierarchyNodeId);
		}

		@Override
		public String toString() {
			return  first + " OR " + second;
		}
	}

	class HierarchyFilteringRejectAllPredicate implements HierarchyFilteringPredicate, Serializable {
		@Serial private static final long serialVersionUID = 5859718563627156229L;

		@Override
		public long computeHash(@Nonnull LongHashFunction hashFunction) {
			return serialVersionUID;
		}

		@Override
		public boolean test(int value) {
			return false;
		}

		@Override
		public String toString() {
			return "REJECT ALL";
		}
	}

	class HierarchyFilteringAcceptAllPredicate implements HierarchyFilteringPredicate, Serializable {
		@Serial private static final long serialVersionUID = 387062010451039137L;

		@Override
		public long computeHash(@Nonnull LongHashFunction hashFunction) {
			return serialVersionUID;
		}

		@Override
		public boolean test(int value) {
			return true;
		}

		@Override
		public String toString() {
			return "ACCEPT ALL";
		}
	}

	@RequiredArgsConstructor
	class NegatedHierarchyFilteringPredicate implements HierarchyFilteringPredicate {
		@Getter private final HierarchyFilteringPredicate predicate;

		@Override
		public long computeHash(@Nonnull LongHashFunction hashFunction) {
			return predicate.computeHash(hashFunction) * -1;
		}

		@Override
		public boolean test(int value) {
			return !predicate.test(value);
		}

		@Override
		public String toString() {
			return "NOT(" + predicate + ")";
		}

	}
}
