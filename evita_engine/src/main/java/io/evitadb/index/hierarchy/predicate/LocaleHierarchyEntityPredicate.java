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
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.index.EntityIndex;
import io.evitadb.core.query.algebra.identity.FormulaKey;
import io.evitadb.core.query.algebra.identity.IdentityExempt;
import io.evitadb.core.query.algebra.identity.IdentityRecord;
import io.evitadb.core.query.algebra.identity.KeyAccumulator;

import javax.annotation.Nonnull;
import java.util.Locale;

/**
 * This class allows to test, whether the hierarchical entity has requested language variant.
 */
public class LocaleHierarchyEntityPredicate implements HierarchyFilteringPredicate {
	/** Stable predicate discriminator. */
	private static final long CLASS_ID = 7258249893156999493L;

	/**
	 * Formula computes id of all hierarchical entities of the requested language.
	 */
	@IdentityExempt(IdentityExempt.Reason.DECOMPOSED)
	@Nonnull private final Formula filteringFormula;

	/** Memoized detached holder key. */
	@IdentityExempt(IdentityExempt.Reason.STATE)
	private FormulaKey memoizedKey;

	public LocaleHierarchyEntityPredicate(@Nonnull EntityIndex targetIndex, @Nonnull Locale language) {
		this.filteringFormula = targetIndex.getRecordsWithLanguageFormula(language);
	}

	@Override
	public void initializeIfNotAlreadyInitialized(@Nonnull QueryExecutionContext executionContext) {
		this.filteringFormula.initialize(executionContext);
	}

	@Override
	public long getHash() {
		return this.filteringFormula.getHash();
	}

	@Override
	public boolean test(int hierarchyNodeId) {
		return this.filteringFormula.compute().contains(hierarchyNodeId);
	}

	@Override
	public String toString() {
		return "BASED ON LOCALE: " + this.filteringFormula;
	}

	/** Returns detached inputs without computing the bitmap. */
	@Nonnull
	@Override
	public IdentityRecord identity() {
		return new Identity(this.filteringFormula.getKey());
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
	 * @param filteringFormula key of the formula listing the nodes localized to the locale
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	public record Identity(@Nonnull FormulaKey filteringFormula) implements IdentityRecord {
		/** Returns the declaring holder identifier. */
		@Override
		public long classId() {
			return CLASS_ID;
		}

		/** Includes every input in declaration order. */
		@Override
		public void describeTo(@Nonnull KeyAccumulator accumulator) {
			accumulator.add(this.filteringFormula);
		}
	}
}
