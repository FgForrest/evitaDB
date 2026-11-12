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
import io.evitadb.core.query.algebra.identity.IdentityExempt;
import io.evitadb.core.query.algebra.identity.IdentityRecord;
import io.evitadb.core.query.algebra.identity.IdentityToken;
import io.evitadb.core.query.algebra.identity.KeyAccumulator;
import io.evitadb.core.transaction.memory.TransactionalLayerProducer;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.TransactionalBitmap;
import io.evitadb.utils.Assert;
import lombok.Getter;
import net.openhft.hashing.LongHashFunction;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

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
	@IdentityExempt(IdentityExempt.Reason.DECOMPOSED)
	@Getter private final Bitmap delegate;
	/**
	 * Identity of the delegate supplied by the caller of the two-argument constructor, `null` when the identity is
	 * derived from the bitmap. It reaches the key through the `delegateIdentity` component.
	 */
	@IdentityExempt(IdentityExempt.Reason.DECOMPOSED)
	@Nullable private final IdentityToken suppliedIdentity;
	/**
	 * Memoized {@link #getEstimatedCardinality()} - `-1` until the first call, a value no real cardinality can take
	 * because the constructor rejects an empty delegate.
	 *
	 * The class already treats `delegate.size()` as fixed for the instance's lifetime: the delegate is final,
	 * `estimatedCost` is derived from that very same call and frozen at construction time by
	 * {@link AbstractFormula#initFields}, and {@link AbstractFormula#clearMemory()} deliberately does not reset it.
	 * The memo only makes that standing assumption explicit, and therefore stays correct even for a caller that
	 * retains a constant formula across several computations.
	 *
	 * Worth memoizing because {@link TransactionalBitmap#size()} probes the transactional memory layer's ThreadLocal
	 * on every call before it ever reaches its own cached cardinality, and a filter over a reference fans out to one
	 * constant formula per reduced index - hundreds of thousands of them.
	 */
	@IdentityExempt(IdentityExempt.Reason.STATE)
	private int memoizedCardinality = -1;

	/** Captures a nonempty bitmap whose identity is derived when requested. */
	public ConstantFormula(@Nonnull Bitmap delegate) {
		Assert.isPremiseValid(!delegate.isEmpty(), "For empty bitmaps use EmptyFormula.INSTANCE!");
		this.delegate = delegate;
		this.suppliedIdentity = null;
		this.initFields();
	}

	/**
	 * Captures a nonempty bitmap with a caller-attested identity. The caller attests that the bitmap is a
	 * deterministic function of the versioned owner named by the token and checks freshness of the memo itself.
	 *
	 * @param delegate bitmap returned as the result
	 * @param delegateIdentity token identifying the versioned owner and the bitmap's derivation
	 */
	public ConstantFormula(@Nonnull Bitmap delegate, @Nonnull IdentityToken delegateIdentity) {
		Assert.isPremiseValid(!delegate.isEmpty(), "For empty bitmaps use EmptyFormula.INSTANCE!");
		this.delegate = delegate;
		this.suppliedIdentity = delegateIdentity;
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
		if (this.memoizedCardinality == -1) {
			this.memoizedCardinality = this.delegate.size();
		}
		return this.memoizedCardinality;
	}

	@Override
	protected long includeAdditionalHash(@Nonnull LongHashFunction hashFunction) {
		// the same token the staleness set is built from - see AbstractFormula#bitmapIdentityToken. The walk a
		// content hash costs is `O(size)`, which is why index memos hand out the same bitmap instance every time:
		// `BaseBitmap#getContentHash` memoizes it, so only the first formula pays
		return bitmapIdentityToken(this.delegate, hashFunction);
	}

	/** Returns the detached identity of the bitmap operand. */
	@Nonnull
	protected IdentityToken getDelegateIdentityToken() {
		return this.suppliedIdentity == null ? IdentityToken.of(this.delegate) : this.suppliedIdentity;
	}

	/** Returns the computation inputs independently of execution state. */
	@Nonnull
	@Override
	protected IdentityRecord identity() {
		return new Identity(getDelegateIdentityToken());
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

	/**
	 * Detached computation inputs.
	 *
	 * @param delegateIdentity detached bitmap operand
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	public record Identity(@Nonnull IdentityToken delegateIdentity) implements IdentityRecord {
		/** Returns the owning formula identifier. */
		@Override
		public long classId() {
			return CLASS_ID;
		}

		/** Includes every operand in declaration order. */
		@Override
		public void describeTo(@Nonnull KeyAccumulator accumulator) {
			accumulator.add(this.delegateIdentity);
		}
	}
}
