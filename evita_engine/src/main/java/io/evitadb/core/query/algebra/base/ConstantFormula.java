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

package io.evitadb.core.query.algebra.base;

import io.evitadb.core.query.algebra.AbstractFormula;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.transaction.memory.TransactionalLayerProducer;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.TransactionalBitmap;
import io.evitadb.utils.Assert;
import lombok.Getter;
import net.openhft.hashing.LongHashFunction;

import javax.annotation.Nonnull;

/**
 * Constant formula that simply returns delegate bitmap.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class ConstantFormula extends AbstractFormula {
	private static final long CLASS_ID = 2713157071360876502L;
	private static final long[] EMPTY_LONG_ARRAY = new long[0];
	@Getter private final Bitmap delegate;

	public ConstantFormula(@Nonnull Bitmap delegate) {
		Assert.isTrue(!delegate.isEmpty(), "For empty bitmaps use EmptyFormula.INSTANCE!");
		this.delegate = delegate;
		this.initFields();
	}

	@Nonnull
	@Override
	public long[] gatherBitmapIdsInternal() {
		return this.delegate instanceof TransactionalBitmap ?
			new long[]{((TransactionalBitmap) this.delegate).getId()} : EMPTY_LONG_ARRAY;
	}

	@Override
	public long getEstimatedCostInternal() {
		return this.delegate.size();
	}

	@Override
	public int getEstimatedCardinality() {
		return this.delegate.size();
	}

	@Override
	protected long includeAdditionalHash(@Nonnull LongHashFunction hashFunction) {
		if (this.delegate instanceof TransactionalLayerProducer) {
			return ((TransactionalLayerProducer<?, ?>) this.delegate).getId();
		} else {
			// this shouldn't happen for long arrays - these are expected to be always linked to transactional
			// bitmaps located in indexes and represented by "transactional id"
			return hashFunction.hashInts(this.delegate.getArray());
		}
	}

	@Override
	protected long getClassId() {
		return CLASS_ID;
	}

	@Nonnull
	@Override
	public Formula getCloneWithInnerFormulas(@Nonnull Formula... innerFormulas) {
		throw new UnsupportedOperationException("Constant formula cannot have inner formulas!");
	}

	@Override
	public long getOperationCost() {
		return 1;
	}

	@Nonnull
	@Override
	protected Bitmap computeInternal() {
		return this.delegate;
	}

	@Override
	public String toString() {
		return this.delegate.size() + " primary keys";
	}

	@Nonnull
	@Override
	public String toStringVerbose() {
		return this.delegate.toString();
	}
}
