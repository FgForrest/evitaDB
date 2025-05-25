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

package io.evitadb.core.query.algebra.price.innerRecordHandling;

import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.core.query.QueryExecutionContext;
import io.evitadb.core.query.algebra.AbstractFormula;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.utils.Assert;
import lombok.Getter;
import net.openhft.hashing.LongHashFunction;

import javax.annotation.Nonnull;

/**
 * Simple delegating formula that doesn't compute anything (computational logic is delegated to {@link #getDelegate()}
 * but maintains information about {@link PriceInnerRecordHandling} strategy that is associated with this formula
 * (sub)tree part.
 *
 * Formula can be used only for {@link Formula} delegate.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class PriceHandlingContainerFormula extends AbstractFormula {
	private static final long CLASS_ID = -8703919997870820964L;

	/**
	 * {@link PriceInnerRecordHandling} strategy that is associated with this formula (sub)tree part.
	 */
	@Getter protected final PriceInnerRecordHandling innerRecordHandling;

	public PriceHandlingContainerFormula(@Nonnull PriceInnerRecordHandling innerRecordHandling, @Nonnull Formula delegate) {
		this.innerRecordHandling = innerRecordHandling;
		this.initFields(delegate);
	}

	@Nonnull
	public Formula getDelegate() {
		// there will be exactly single inner formula (ensured by single constructor)
		return this.innerFormulas[0];
	}

	@Override
	public void initialize(@Nonnull QueryExecutionContext executionContext) {
		this.getDelegate().initialize(executionContext);
		super.initialize(executionContext);
	}

	@Override
	public String toString() {
		return "DO WITH " + this.innerRecordHandling;
	}

	@Nonnull
	@Override
	protected Bitmap computeInternal() {
		return getDelegate().compute();
	}

	@Override
	public int getEstimatedCardinality() {
		return getDelegate().getEstimatedCardinality();
	}

	@Nonnull
	@Override
	public Formula getCloneWithInnerFormulas(@Nonnull Formula... innerFormulas) {
		Assert.isPremiseValid(innerFormulas.length == 1, "Expected exactly single delegate inner formula!");
		return new PriceHandlingContainerFormula(this.innerRecordHandling, innerFormulas[0]);
	}

	@Override
	public long getOperationCost() {
		return 1;
	}

	@Override
	protected long includeAdditionalHash(@Nonnull LongHashFunction hashFunction) {
		return hashFunction.hashInt(this.innerRecordHandling.ordinal());
	}

	@Override
	protected long getClassId() {
		return CLASS_ID;
	}
}
