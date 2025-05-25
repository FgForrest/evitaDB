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

import javax.annotation.Nonnull;
import java.util.Locale;

/**
 * This class allows to test, whether the hierarchical entity has requested language variant.
 */
public class LocaleHierarchyEntityPredicate implements HierarchyFilteringPredicate {
	/**
	 * Formula computes id of all hierarchical entities of the requested language.
	 */
	@Nonnull private final Formula filteringFormula;

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
}
