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

package io.evitadb.index.hierarchy.predicate;

import io.evitadb.core.query.QueryExecutionContext;
import io.evitadb.core.query.response.TransactionalDataRelatedStructure;
import io.evitadb.utils.Assert;
import lombok.Getter;

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
public non-sealed interface HierarchyFilteringPredicate extends IntPredicate, ExecutionContextRequiringPredicate {

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
	 * Allows to compute the {@link #getHash()}. Must be called prior to {@link #getHash()} method
	 *
	 * @param executionContext the context for the query execution
	 */
	void initializeIfNotAlreadyInitialized(@Nonnull QueryExecutionContext executionContext);

	/**
	 * Returns unique hash for this predicate.
	 */
	long getHash();

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
	class AndHierarchyFilteringPredicate implements HierarchyFilteringPredicate, Serializable {
		@Serial private static final long serialVersionUID = 1384400346650986235L;
		private final HierarchyFilteringPredicate first;
		private final HierarchyFilteringPredicate second;
		private final Long hash;

		public AndHierarchyFilteringPredicate(HierarchyFilteringPredicate first, HierarchyFilteringPredicate second) {
			this.first = first;
			this.second = second;
			this.hash = TransactionalDataRelatedStructure.HASH_FUNCTION.hashLongs(
				new long[]{
					serialVersionUID,
					first.getHash(),
					second.getHash()
				}
			);
		}

		@Override
		public void initializeIfNotAlreadyInitialized(@Nonnull QueryExecutionContext executionContext) {
			this.first.initializeIfNotAlreadyInitialized(executionContext);
			this.second.initializeIfNotAlreadyInitialized(executionContext);
		}

		@Override
		public long getHash() {
			return this.hash;
		}

		@Override
		public boolean test(int hierarchyNodeId) {
			return this.first.test(hierarchyNodeId) && this.second.test(hierarchyNodeId);
		}

		@Override
		public String toString() {
			return this.first + " AND " + this.second;
		}

	}

	/**
	 * Implementation of {@link HierarchyFilteringPredicate} that combines two implementations of the same type with
	 * OR relation.
	 */
	class OrHierarchyFilteringPredicate implements HierarchyFilteringPredicate, Serializable {
		@Serial private static final long serialVersionUID = 2547741955086633818L;
		private final HierarchyFilteringPredicate first;
		private final HierarchyFilteringPredicate second;
		private final Long hash;

		public OrHierarchyFilteringPredicate(HierarchyFilteringPredicate first, HierarchyFilteringPredicate second) {
			this.first = first;
			this.second = second;
			this.hash = TransactionalDataRelatedStructure.HASH_FUNCTION.hashLongs(
				new long[]{
					serialVersionUID,
					first.getHash(),
					second.getHash()
				}
			);
		}

		@Override
		public void initializeIfNotAlreadyInitialized(@Nonnull QueryExecutionContext executionContext) {
			this.first.initializeIfNotAlreadyInitialized(executionContext);
			this.second.initializeIfNotAlreadyInitialized(executionContext);
		}

		@Override
		public long getHash() {
			Assert.isPremiseValid(this.hash != null, "Predicate must be initialized prior to calling getHash().");
			return this.hash;
		}

		@Override
		public boolean test(int hierarchyNodeId) {
			return this.first.test(hierarchyNodeId) || this.second.test(hierarchyNodeId);
		}

		@Override
		public String toString() {
			return this.first + " OR " + this.second;
		}
	}

	class HierarchyFilteringRejectAllPredicate implements HierarchyFilteringPredicate, Serializable {
		@Serial private static final long serialVersionUID = 5859718563627156229L;

		@Override
		public void initializeIfNotAlreadyInitialized(@Nonnull QueryExecutionContext executionContext) {

		}

		@Override
		public long getHash() {
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
		public void initializeIfNotAlreadyInitialized(@Nonnull QueryExecutionContext executionContext) {

		}

		@Override
		public long getHash() {
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

	class NegatedHierarchyFilteringPredicate implements HierarchyFilteringPredicate {
		@Getter private final HierarchyFilteringPredicate predicate;
		private final Long hash;

		public NegatedHierarchyFilteringPredicate(HierarchyFilteringPredicate predicate) {
			this.predicate = predicate;
			this.hash = predicate.getHash() * -1;
		}

		@Override
		public void initializeIfNotAlreadyInitialized(@Nonnull QueryExecutionContext executionContext) {
			this.predicate.initializeIfNotAlreadyInitialized(executionContext);
		}

		@Override
		public long getHash() {
			Assert.isPremiseValid(this.hash != null, "Predicate must be initialized prior to calling getHash().");
			return this.hash;
		}

		@Override
		public boolean test(int value) {
			return !this.predicate.test(value);
		}

		@Override
		public String toString() {
			return "NOT(" + this.predicate + ")";
		}

		@Override
		public final boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof NegatedHierarchyFilteringPredicate that)) return false;

			return this.predicate.equals(that.predicate) && this.hash.equals(that.hash);
		}

		@Override
		public int hashCode() {
			int result = this.predicate.hashCode();
			result = 31 * result + this.hash.hashCode();
			return result;
		}
	}
}
