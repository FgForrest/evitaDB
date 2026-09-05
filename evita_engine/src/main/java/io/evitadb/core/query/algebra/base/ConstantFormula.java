/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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
	/**
	 * Unique identifier of this formula used in {@link AbstractFormula#getClassId()} for hash computation.
	 */
	private static final long CLASS_ID = 2713157071360876502L;
	/**
	 * Bitmap of entity primary keys that this constant formula directly returns as its result.
	 */
	@Getter private final Bitmap delegate;

	public ConstantFormula(@Nonnull Bitmap delegate) {
		Assert.isPremiseValid(!delegate.isEmpty(), "For empty bitmaps use EmptyFormula.INSTANCE!");
		this.delegate = delegate;
		this.initFields();
	}

	/**
	 * The staleness token set of this formula: the delegate's transactional id when it owns one, its CONTENT hash
	 * otherwise.
	 *
	 * The content fallback matters because a delegate without a transactional identity is now ordinary rather than
	 * exotic - a single-record bucket view and a sorted-array bucket view are both read-only projections created per
	 * read, so neither can carry an id. Returning an empty set for those left a cacheable answer with no staleness
	 * dependency at all, which is to say a cached result that no write could ever invalidate. The cache compares the
	 * HASH of this set rather than resolving individual ids, so a content hash serves the purpose, and it mirrors what
	 * {@link #includeAdditionalHash} has always done for the same delegate.
	 */
	@Nonnull
	@Override
	public long[] gatherBitmapIdsInternal() {
		return new long[]{bitmapIdentityToken(this.delegate, HASH_FUNCTION)};
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
		// the same token the staleness set is built from - see AbstractFormula#bitmapIdentityToken. The walk a
		// content hash costs is `O(size)`, which is why index memos hand out the same bitmap instance every time:
		// `BaseBitmap#getContentHash` memoizes it, so only the first formula pays
		return bitmapIdentityToken(this.delegate, hashFunction);
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
