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

package io.evitadb.core.query.algebra.attribute;

import io.evitadb.api.query.require.AttributeHistogram;
import io.evitadb.api.query.require.EntityFetchRequire;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.core.query.algebra.AbstractFormula;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.prefetch.RequirementsDefiner;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.utils.Assert;
import lombok.Getter;
import net.openhft.hashing.LongHashFunction;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.math.BigDecimal;
import java.util.function.Predicate;

import static io.evitadb.api.query.QueryConstraints.attributeContent;
import static io.evitadb.api.query.QueryConstraints.entityFetch;

/**
 * Attribute formula envelopes {@link Formula} that compute {@link io.evitadb.api.query.FilterConstraint} targeted
 * at attribute values. The formula simply delegates its {@link Formula#compute()} method to the single delegating formula.
 * Purpose of the formula is to serve as marker container that allows to retrieve target {@link #attributeKey} when
 * working with formula tree and reconstructing it to different form. This is namely used in {@link AttributeHistogram}
 * computation that needs to exclude query targeting the attribute histogram is computed for.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class AttributeFormula extends AbstractFormula implements RequirementsDefiner {
	private static final long CLASS_ID = 4944486926494447594L;
	public static final String ERROR_SINGLE_FORMULA_EXPECTED = "Exactly one inner formula is expected!";
	/**
	 * Contains TRUE if the attribute targets the global attribute schema and thus global attribute index.
	 */
	@Getter private final boolean targetsGlobalAttribute;
	/**
	 * Contains {@link AttributeValue#key()} of the attribute that is targeted by inner query.
	 */
	@Getter private final AttributeKey attributeKey;
	/**
	 * Contains possible predicate that can mark histogram buckets as requested by user constraint.
	 */
	@Getter private final Predicate<BigDecimal> requestedPredicate;

	public AttributeFormula(
		boolean targetsGlobalAttribute,
		@Nonnull AttributeKey attributeKey,
		@Nonnull Formula innerFormula
	) {
		this(targetsGlobalAttribute, attributeKey, innerFormula, null);
	}

	public AttributeFormula(
		boolean targetsGlobalAttribute,
		@Nonnull AttributeKey attributeKey,
		@Nonnull Formula innerFormula,
		@Nullable Predicate<BigDecimal> requestedPredicate
	) {
		this.targetsGlobalAttribute = targetsGlobalAttribute;
		this.attributeKey = attributeKey;
		this.requestedPredicate = requestedPredicate;
		this.initFields(innerFormula);
	}

	@Nonnull
	@Override
	public Formula getCloneWithInnerFormulas(@Nonnull Formula... innerFormulas) {
		Assert.isTrue(innerFormulas.length == 1, ERROR_SINGLE_FORMULA_EXPECTED);
		return new AttributeFormula(this.targetsGlobalAttribute, this.attributeKey, innerFormulas[0], this.requestedPredicate);
	}

	/**
	 * Returns attribute name this formula is targeting.
	 */
	@Nonnull
	public String getAttributeName() {
		return this.attributeKey.attributeName();
	}

	/**
	 * Returns true if the attribute formula relates to localized attribute.
	 */
	public boolean isLocalized() {
		return this.attributeKey.localized();
	}

	@Override
	public long getOperationCost() {
		return 1;
	}

	@Override
	public int getEstimatedCardinality() {
		return this.innerFormulas[0].getEstimatedCardinality();
	}

	@Override
	public String toString() {
		return "ATTRIBUTE FILTER `" + this.attributeKey + "`";
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
		if (this.attributeKey.locale() == null) {
			return hashFunction.hashChars(this.attributeKey.attributeName());
		} else {
			return hashFunction.hashLongs(
				new long[] {
					hashFunction.hashChars(this.attributeKey.attributeName()),
					hashFunction.hashChars(this.attributeKey.locale().toLanguageTag())
				}
			);
		}
	}

	@Override
	protected long getClassId() {
		return CLASS_ID;
	}

	@Nullable
	@Override
	public EntityFetchRequire getEntityRequire() {
		return entityFetch(attributeContent(this.attributeKey.attributeName()));
	}

}
