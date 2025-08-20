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

package io.evitadb.core.query.algebra.hierarchy;

import io.evitadb.core.query.algebra.AbstractFormula;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.attribute.AttributeFormula;
import io.evitadb.core.query.algebra.price.termination.PriceTerminationFormula;
import io.evitadb.core.query.filter.translator.hierarchy.HierarchyWithinRootTranslator;
import io.evitadb.core.query.filter.translator.hierarchy.HierarchyWithinTranslator;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.utils.Assert;
import net.openhft.hashing.LongHashFunction;

import javax.annotation.Nonnull;

/**
 * Simple formula container that allows excluding in {@link HierarchyWithinTranslator}
 * or {@link HierarchyWithinRootTranslator} when there are {@link AttributeFormula} targeting non-global index or
 * {@link PriceTerminationFormula}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class HierarchyFormula extends AbstractFormula {
	private static final long CLASS_ID = -7910610363796304904L;
	public static final String ERROR_SINGLE_FORMULA_EXPECTED = "Exactly one inner formula is expected!";

	public HierarchyFormula(@Nonnull Formula innerFormula) {
		this.innerFormulas = new Formula[]{ innerFormula };
		this.initFields(innerFormula);
	}

	@Nonnull
	@Override
	public Formula getCloneWithInnerFormulas(@Nonnull Formula... innerFormulas) {
		Assert.isTrue(innerFormulas.length == 1, ERROR_SINGLE_FORMULA_EXPECTED);
		return new HierarchyFormula(innerFormulas[0]);
	}

	@Override
	public long getOperationCost() {
		return 1;
	}

	@Override
	public int getEstimatedCardinality() {
		return this.innerFormulas[0].getEstimatedCardinality();
	}

	@Nonnull
	@Override
	protected Bitmap computeInternal() {
		if (this.innerFormulas.length == 0) {
			return EmptyBitmap.INSTANCE;
		} else if (this.innerFormulas.length == 1) {
			return this.innerFormulas[0].compute();
		} else {
			throw new GenericEvitaInternalError(ERROR_SINGLE_FORMULA_EXPECTED);
		}
	}

	@Override
	protected long includeAdditionalHash(@Nonnull LongHashFunction hashFunction) {
		return 0L;
	}

	@Override
	protected long getClassId() {
		return CLASS_ID;
	}

	@Override
	public String toString() {
		return "HIERARCHY: " + this.innerFormulas[0].toString();
	}

	@Nonnull
	@Override
	public String toStringVerbose() {
		return "HIERARCHY: " + this.innerFormulas[0].toStringVerbose();
	}
}
