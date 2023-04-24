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

package io.evitadb.core.query.algebra.attribute;

import io.evitadb.api.query.require.AttributeHistogram;
import io.evitadb.api.requestResponse.schema.dto.AttributeSchema;
import io.evitadb.core.query.algebra.AbstractCacheableFormula;
import io.evitadb.core.query.algebra.CacheableFormula;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.utils.Assert;
import lombok.Getter;
import net.openhft.hashing.LongHashFunction;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.function.Consumer;

/**
 * Attribute formula envelopes {@link Formula} that compute {@link io.evitadb.api.query.FilterConstraint} targeted
 * at attribute values. The formula simply delegates its {@link #compute()} method to the single delegating formula.
 * Purpose of the formula is to serve as marker container that allows to retrieve target {@link #attributeName} when
 * working with formula tree and reconstructing it to different form. This is namely used in {@link AttributeHistogram}
 * computation that needs to exclude query targeting the attribute histogram is computed for.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class AttributeFormula extends AbstractCacheableFormula {
	private static final long CLASS_ID = 4944486926494447594L;
	public static final String ERROR_SINGLE_FORMULA_EXPECTED = "Exactly one inner formula is expected!";
	/**
	 * Contains {@link AttributeSchema#getName()} of the attribute that is targeted by inner query.
	 */
	@Getter private final String attributeName;

	AttributeFormula(@Nullable Consumer<CacheableFormula> computationCallback, @Nonnull String attributeName, @Nonnull Formula innerFormula) {
		super(computationCallback, innerFormula);
		this.attributeName = attributeName;
	}

	public AttributeFormula(@Nonnull String attributeName, @Nonnull Formula innerFormula) {
		super(null, innerFormula);
		this.attributeName = attributeName;
	}

	@Nonnull
	@Override
	public Formula getCloneWithInnerFormulas(@Nonnull Formula... innerFormulas) {
		Assert.isTrue(innerFormulas.length == 1, ERROR_SINGLE_FORMULA_EXPECTED);
		return new AttributeFormula(attributeName, innerFormulas[0]);
	}

	@Override
	public long getOperationCost() {
		return 1;
	}

	@Override
	public int getEstimatedCardinality() {
		return innerFormulas[0].getEstimatedCardinality();
	}

	@Nonnull
	@Override
	public CacheableFormula getCloneWithComputationCallback(@Nonnull Consumer<CacheableFormula> selfOperator, @Nonnull Formula... innerFormulas) {
		Assert.isTrue(innerFormulas.length == 1, ERROR_SINGLE_FORMULA_EXPECTED);
		return new AttributeFormula(selfOperator, attributeName, innerFormulas[0]);
	}

	@Override
	public String toString() {
		return "ATTRIBUTE FILTER `" + attributeName + "`";
	}

	@Nonnull
	@Override
	protected Bitmap computeInternal() {
		if (innerFormulas.length == 0) {
			return EmptyBitmap.INSTANCE;
		} else if (innerFormulas.length == 1) {
			return innerFormulas[0].compute();
		} else {
			throw new EvitaInternalError(ERROR_SINGLE_FORMULA_EXPECTED);
		}
	}

	@Override
	protected long includeAdditionalHash(@Nonnull LongHashFunction hashFunction) {
		return hashFunction.hashChars(attributeName);
	}

	@Override
	protected long getClassId() {
		return CLASS_ID;
	}

}
