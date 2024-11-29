/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

package io.evitadb.core.query.algebra.facet;

import io.evitadb.core.query.algebra.AbstractFormula;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.NonCacheableFormula;
import io.evitadb.core.query.algebra.NonCacheableFormulaScope;
import io.evitadb.core.query.algebra.base.AndFormula;
import io.evitadb.core.query.filter.translator.behavioral.FilterInScopeTranslator.InScopeFormulaPostProcessor;
import io.evitadb.dataType.Scope;
import io.evitadb.index.bitmap.Bitmap;
import lombok.Getter;
import net.openhft.hashing.LongHashFunction;

import javax.annotation.Nonnull;

/**
 * This formula has almost identical implementation as {@link AndFormula} but it accepts only set of
 * {@link Formula} as a children and allows containing even single child (on the contrary to the {@link AndFormula}).
 * The formula envelopes part with scope focused on single {@link Scope} and is used by {@link InScopeFormulaPostProcessor}
 * to create final formula tree consisting of multiple formula tree varants specific to selected scopes.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public class ScopeContainerFormula extends AbstractFormula implements NonCacheableFormula, NonCacheableFormulaScope {
	private static final long CLASS_ID = -5387565378948662756L;
	/**
	 * The scope that is used to filter the data.
	 */
	@Getter private final Scope scope;

	public ScopeContainerFormula(@Nonnull Scope scope, @Nonnull Formula... innerFormulas) {
		this.scope = scope;
		this.initFields(innerFormulas);
	}

	@Nonnull
	@Override
	public Formula getCloneWithInnerFormulas(@Nonnull Formula... innerFormulas) {
		return new ScopeContainerFormula(this.scope, innerFormulas);
	}

	@Override
	public long getOperationCost() {
		return 0L;
	}

	@Override
	protected long getCostInternal() {
		return 0L;
	}

	@Override
	protected long getCostToPerformanceInternal() {
		return 0L;
	}

	@Nonnull
	@Override
	protected Bitmap computeInternal() {
		throw new UnsupportedOperationException("This formula should be eliminated before computation.");
	}

	@Override
	public int getEstimatedCardinality() {
		return 0;
	}

	@Override
	public String toString() {
		return "SCOPE_CONTAINER(" + this.scope.name() + ")";
	}

	@Nonnull
	@Override
	public String toStringVerbose() {
		return toString();
	}

	@Override
	protected long includeAdditionalHash(@Nonnull LongHashFunction hashFunction) {
		return CLASS_ID;
	}

	@Override
	protected long getClassId() {
		return CLASS_ID;
	}

}
