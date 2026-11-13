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
import io.evitadb.core.query.algebra.identity.FormulaKey;
import io.evitadb.core.query.algebra.identity.IdentityRecord;
import io.evitadb.core.query.algebra.identity.IdentityExempt;
import io.evitadb.core.query.algebra.identity.KeyAccumulator;
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

	/** Returns the detached identity. */
	@Nonnull
	FormulaKey getKey();

	/** Returns the declaring class identifier. */
	long getClassId();

	/** Returns the declared identity components. */
	@Nonnull
	IdentityRecord identity();

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
	 * Initializes execution state required to evaluate this predicate.
	 *
	 * @param executionContext the context for the query execution
	 */
	void initializeIfNotAlreadyInitialized(@Nonnull QueryExecutionContext executionContext);

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
		/** Stable discriminator of this predicate operation. */
		private static final long CLASS_ID = -3882293948706087294L;

		/** Memoized detached holder key. */
		@IdentityExempt(IdentityExempt.Reason.STATE)
		private FormulaKey memoizedKey;

		@Serial private static final long serialVersionUID = 1384400346650986235L;
		@IdentityExempt(IdentityExempt.Reason.DECOMPOSED)
		private final HierarchyFilteringPredicate first;
		@IdentityExempt(IdentityExempt.Reason.DECOMPOSED)
		private final HierarchyFilteringPredicate second;

		public AndHierarchyFilteringPredicate(HierarchyFilteringPredicate first, HierarchyFilteringPredicate second) {
			this.first = first;
			this.second = second;
		}

		@Override
		public void initializeIfNotAlreadyInitialized(@Nonnull QueryExecutionContext executionContext) {
			this.first.initializeIfNotAlreadyInitialized(executionContext);
			this.second.initializeIfNotAlreadyInitialized(executionContext);
		}

		@Override
		public boolean test(int hierarchyNodeId) {
			return this.first.test(hierarchyNodeId) && this.second.test(hierarchyNodeId);
		}

		@Override
		public String toString() {
			return this.first + " AND " + this.second;
		}

		/** Returns detached inputs without computing the bitmap. */
		@Nonnull
		@Override
		public IdentityRecord identity() {
			return new Identity(this.first.getKey(), this.second.getKey());
		}

		/** Returns the memoized detached key of this holder. */
		@Nonnull
		@Override
		public FormulaKey getKey() {
			if (this.memoizedKey == null) {
				this.memoizedKey = FormulaKey.of(identity(), getClassId(), FormulaKey.NO_CHILDREN, this);
			}
			return this.memoizedKey;
		}

		/** Returns the stable holder discriminator. */
		@Override
		public long getClassId() {
			return CLASS_ID;
		}

		/**
		 * Detached computation inputs.
		 *
		 * @param first key of the first combined predicate
		 * @param second key of the second combined predicate
		 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
		 */
		public record Identity(@Nonnull FormulaKey first, @Nonnull FormulaKey second) implements IdentityRecord {
			/** Returns the declaring holder identifier. */
			@Override
			public long classId() {
				return CLASS_ID;
			}

			/** Includes every input in declaration order. */
			@Override
			public void describeTo(@Nonnull KeyAccumulator accumulator) {
				accumulator.add(this.first);
				accumulator.add(this.second);
			}
		}

	}

	/**
	 * Implementation of {@link HierarchyFilteringPredicate} that combines two implementations of the same type with
	 * OR relation.
	 */
	class OrHierarchyFilteringPredicate implements HierarchyFilteringPredicate, Serializable {
		/** Stable discriminator of this predicate operation. */
		private static final long CLASS_ID = -392102354656467157L;

		/** Memoized detached holder key. */
		@IdentityExempt(IdentityExempt.Reason.STATE)
		private FormulaKey memoizedKey;

		@Serial private static final long serialVersionUID = 2547741955086633818L;
		@IdentityExempt(IdentityExempt.Reason.DECOMPOSED)
		private final HierarchyFilteringPredicate first;
		@IdentityExempt(IdentityExempt.Reason.DECOMPOSED)
		private final HierarchyFilteringPredicate second;

		public OrHierarchyFilteringPredicate(HierarchyFilteringPredicate first, HierarchyFilteringPredicate second) {
			this.first = first;
			this.second = second;
		}

		@Override
		public void initializeIfNotAlreadyInitialized(@Nonnull QueryExecutionContext executionContext) {
			this.first.initializeIfNotAlreadyInitialized(executionContext);
			this.second.initializeIfNotAlreadyInitialized(executionContext);
		}

		@Override
		public boolean test(int hierarchyNodeId) {
			return this.first.test(hierarchyNodeId) || this.second.test(hierarchyNodeId);
		}

		@Override
		public String toString() {
			return this.first + " OR " + this.second;
		}

		/** Returns detached inputs without computing the bitmap. */
		@Nonnull
		@Override
		public IdentityRecord identity() {
			return new Identity(this.first.getKey(), this.second.getKey());
		}

		/** Returns the memoized detached key of this holder. */
		@Nonnull
		@Override
		public FormulaKey getKey() {
			if (this.memoizedKey == null) {
				this.memoizedKey = FormulaKey.of(identity(), getClassId(), FormulaKey.NO_CHILDREN, this);
			}
			return this.memoizedKey;
		}

		/** Returns the stable holder discriminator. */
		@Override
		public long getClassId() {
			return CLASS_ID;
		}

		/**
		 * Detached computation inputs.
		 *
		 * @param first key of the first combined predicate
		 * @param second key of the second combined predicate
		 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
		 */
		public record Identity(@Nonnull FormulaKey first, @Nonnull FormulaKey second) implements IdentityRecord {
			/** Returns the declaring holder identifier. */
			@Override
			public long classId() {
				return CLASS_ID;
			}

			/** Includes every input in declaration order. */
			@Override
			public void describeTo(@Nonnull KeyAccumulator accumulator) {
				accumulator.add(this.first);
				accumulator.add(this.second);
			}
		}

	}

	class HierarchyFilteringRejectAllPredicate implements HierarchyFilteringPredicate, Serializable {
		/** Stable discriminator of this predicate operation. */
		private static final long CLASS_ID = 1713556274259311066L;

		/** Memoized detached holder key. */
		@IdentityExempt(IdentityExempt.Reason.STATE)
		private FormulaKey memoizedKey;

		@Serial private static final long serialVersionUID = 5859718563627156229L;

		@Override
		public void initializeIfNotAlreadyInitialized(@Nonnull QueryExecutionContext executionContext) {

		}

		@Override
		public boolean test(int value) {
			return false;
		}

		@Override
		public String toString() {
			return "REJECT ALL";
		}

		/** Returns detached inputs without computing the bitmap. */
		@Nonnull
		@Override
		public IdentityRecord identity() {
			return new Identity();
		}

		/** Returns the memoized detached key of this holder. */
		@Nonnull
		@Override
		public FormulaKey getKey() {
			if (this.memoizedKey == null) {
				this.memoizedKey = FormulaKey.of(identity(), getClassId(), FormulaKey.NO_CHILDREN, this);
			}
			return this.memoizedKey;
		}

		/** Returns the stable holder discriminator. */
		@Override
		public long getClassId() {
			return CLASS_ID;
		}

		/**
		 * Detached computation inputs.
		 *
		 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
		 */
		public record Identity() implements IdentityRecord {
			/** Returns the declaring holder identifier. */
			@Override
			public long classId() {
				return CLASS_ID;
			}

			/** Includes every input in declaration order. */
			@Override
			public void describeTo(@Nonnull KeyAccumulator accumulator) {
			}
		}

	}

	class HierarchyFilteringAcceptAllPredicate implements HierarchyFilteringPredicate, Serializable {
		/** Stable discriminator of this predicate operation. */
		private static final long CLASS_ID = -2072977462061830299L;

		/** Memoized detached holder key. */
		@IdentityExempt(IdentityExempt.Reason.STATE)
		private FormulaKey memoizedKey;

		@Serial private static final long serialVersionUID = 387062010451039137L;

		@Override
		public void initializeIfNotAlreadyInitialized(@Nonnull QueryExecutionContext executionContext) {

		}

		@Override
		public boolean test(int value) {
			return true;
		}

		@Override
		public String toString() {
			return "ACCEPT ALL";
		}

		/** Returns detached inputs without computing the bitmap. */
		@Nonnull
		@Override
		public IdentityRecord identity() {
			return new Identity();
		}

		/** Returns the memoized detached key of this holder. */
		@Nonnull
		@Override
		public FormulaKey getKey() {
			if (this.memoizedKey == null) {
				this.memoizedKey = FormulaKey.of(identity(), getClassId(), FormulaKey.NO_CHILDREN, this);
			}
			return this.memoizedKey;
		}

		/** Returns the stable holder discriminator. */
		@Override
		public long getClassId() {
			return CLASS_ID;
		}

		/**
		 * Detached computation inputs.
		 *
		 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
		 */
		public record Identity() implements IdentityRecord {
			/** Returns the declaring holder identifier. */
			@Override
			public long classId() {
				return CLASS_ID;
			}

			/** Includes every input in declaration order. */
			@Override
			public void describeTo(@Nonnull KeyAccumulator accumulator) {
			}
		}

	}

	class NegatedHierarchyFilteringPredicate implements HierarchyFilteringPredicate {
		/** Stable discriminator of this predicate operation. */
		private static final long CLASS_ID = 6853269046312115974L;

		/** Memoized detached holder key. */
		@IdentityExempt(IdentityExempt.Reason.STATE)
		private FormulaKey memoizedKey;

		@IdentityExempt(IdentityExempt.Reason.DECOMPOSED)
		@Getter private final HierarchyFilteringPredicate predicate;

		public NegatedHierarchyFilteringPredicate(HierarchyFilteringPredicate predicate) {
			this.predicate = predicate;
		}

		@Override
		public void initializeIfNotAlreadyInitialized(@Nonnull QueryExecutionContext executionContext) {
			this.predicate.initializeIfNotAlreadyInitialized(executionContext);
		}

		@Override
		public boolean test(int value) {
			return !this.predicate.test(value);
		}

		@Override
		public String toString() {
			return "NOT(" + this.predicate + ")";
		}

		/** Returns detached inputs without computing the bitmap. */
		@Nonnull
		@Override
		public IdentityRecord identity() {
			return new Identity(this.predicate.getKey());
		}

		/** Returns the memoized detached key of this holder. */
		@Nonnull
		@Override
		public FormulaKey getKey() {
			if (this.memoizedKey == null) {
				this.memoizedKey = FormulaKey.of(identity(), getClassId(), FormulaKey.NO_CHILDREN, this);
			}
			return this.memoizedKey;
		}

		/** Returns the stable holder discriminator. */
		@Override
		public long getClassId() {
			return CLASS_ID;
		}

		/**
		 * Detached computation inputs.
		 *
		 * @param predicate key of the negated predicate
		 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
		 */
		public record Identity(@Nonnull FormulaKey predicate) implements IdentityRecord {
			/** Returns the declaring holder identifier. */
			@Override
			public long classId() {
				return CLASS_ID;
			}

			/** Includes every input in declaration order. */
			@Override
			public void describeTo(@Nonnull KeyAccumulator accumulator) {
				accumulator.add(this.predicate);
			}
		}

	}
}
