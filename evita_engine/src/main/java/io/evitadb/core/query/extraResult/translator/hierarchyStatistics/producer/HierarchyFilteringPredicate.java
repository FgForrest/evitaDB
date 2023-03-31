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

package io.evitadb.core.query.extraResult.translator.hierarchyStatistics.producer;

import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.AndFormula;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.function.IntPredicate;

import static java.util.Optional.ofNullable;

/**
 * TODO JNO - document me
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public interface HierarchyFilteringPredicate extends IntPredicate {

	@Nullable
	default Formula getFilteringFormula() {
		return null;
	}

	/**
	 * TODO JNO - document me
	 */
	default HierarchyFilteringPredicate and(@Nonnull HierarchyFilteringPredicate other) {
		return new CompositeHierarchyFilteringPredicate(this, other);
	}

	@RequiredArgsConstructor
	class CompositeHierarchyFilteringPredicate implements HierarchyFilteringPredicate {
		private final HierarchyFilteringPredicate first;
		private final HierarchyFilteringPredicate second;

		@Nullable
		@Override
		public Formula getFilteringFormula() {
			return ofNullable(first.getFilteringFormula())
				.map(formula -> ofNullable(second.getFilteringFormula())
					.map(it -> (Formula)new AndFormula(formula, it))
					.orElse(formula)
				)
				.orElse(second.getFilteringFormula());
		}

		@Override
		public boolean test(int hierarchyNodeId) {
			return first.test(hierarchyNodeId) && second.test(hierarchyNodeId);
		}
	}
}
