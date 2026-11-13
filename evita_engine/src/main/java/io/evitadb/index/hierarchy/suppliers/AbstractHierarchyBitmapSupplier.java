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

package io.evitadb.index.hierarchy.suppliers;

import io.evitadb.core.query.QueryExecutionContext;
import io.evitadb.core.query.algebra.deferred.BitmapSupplier;
import io.evitadb.core.query.response.TransactionalDataRelatedStructure;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.hierarchy.HierarchyIndex;
import io.evitadb.core.query.algebra.identity.FormulaKey;
import io.evitadb.core.query.algebra.identity.IdentityExempt;
import io.evitadb.core.query.algebra.identity.IdentityRecord;
import io.evitadb.core.query.algebra.identity.IdentityToken;
import io.evitadb.core.query.algebra.identity.TokenRole;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Abstract - base implementation for {@link BitmapSupplier}. Generalizes logic connected with
 * {@link TransactionalDataRelatedStructure} implementation
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public abstract class AbstractHierarchyBitmapSupplier implements BitmapSupplier {
	/**
	 * Reference to the {@link HierarchyIndex} that will be used for gathering the data.
	 */
	@IdentityExempt(IdentityExempt.Reason.DECOMPOSED)
	protected final HierarchyIndex hierarchyIndex;
	/**
	 * Contains memoized value of {@link #getEstimatedCost()}  of this formula.
	 */
	@IdentityExempt(IdentityExempt.Reason.STATE)
	private Long estimatedCost;
	/**
	 * Contains memoized value of {@link #getCost()}  of this formula.
	 */
	@IdentityExempt(IdentityExempt.Reason.STATE)
	private Long cost;
	/**
	 * Contains memoized result of the supplier.
	 */
	@IdentityExempt(IdentityExempt.Reason.STATE)
	private Bitmap memoizedResult;

	/** Memoized detached holder key. */
	@IdentityExempt(IdentityExempt.Reason.STATE)
	private FormulaKey memoizedKey;

	/** Detached token memo for the current hierarchy owner, created only when identity is requested. */
	@IdentityExempt(IdentityExempt.Reason.STATE)
	@Nullable private IdentityToken.ProducerId memoizedHierarchyIndexIdentity;

	public AbstractHierarchyBitmapSupplier(@Nonnull HierarchyIndex hierarchyIndex) {
		this.hierarchyIndex = hierarchyIndex;
	}

	/**
	 * Initializes the fields of the supplier.
	 *
	 * TOBEDONE when upgrading to Java 22 with https://openjdk.org/jeps/447, switch fields to final and do this in the constructor
	 */
	protected void initFields() {
		this.estimatedCost = getEstimatedCardinality() * 12L;
	}

	@Override
	public void initialize(@Nonnull QueryExecutionContext executionContext) {
		// do nothing
	}

	@Override
	public long getEstimatedCost() {
		return this.estimatedCost;
	}

	@Override
	public long getCost() {
		if (this.cost == null) {
			if (this.memoizedResult != null) {
				this.cost = this.hierarchyIndex.getHierarchySize() * getOperationCost();
			} else {
				return Long.MAX_VALUE;
			}
		}
		return this.cost;
	}

	@Override
	public long getOperationCost() {
		return 1;
	}

	@Override
	public final Bitmap get() {
		if (this.memoizedResult == null) {
			this.memoizedResult = getInternal();
		}
		return this.memoizedResult;
	}

	/**
	 * Calculates the result.
	 * @return the result
	 */
	@Nonnull
	protected abstract Bitmap getInternal();

	/** Returns the memoized detached key of this holder. */
	@Nonnull
	@Override
	public final FormulaKey getKey() {
		if (this.memoizedKey == null) {
			this.memoizedKey = FormulaKey.of(identity(), getClassId(), FormulaKey.NO_CHILDREN, this);
		}
		return this.memoizedKey;
	}

	/**
	 * Returns the detached inputs of the concrete supplier: the node and depth arguments of its listing, the key of
	 * the predicate excluding node subtrees from it, and the version token of the hierarchy index it reads
	 * ({@link #getHierarchyIndexIdentityToken()}).
	 */
	@Nonnull
	protected abstract IdentityRecord identity();

	/** Returns the concrete supplier discriminator. */
	protected abstract long getClassId();

	/** Returns the owner's version token, refreshing the memo when its source version differs. */
	@Nonnull
	protected IdentityToken getHierarchyIndexIdentityToken() {
		final long ownerId = this.hierarchyIndex.getId();
		if (this.memoizedHierarchyIndexIdentity == null || this.memoizedHierarchyIndexIdentity.id() != ownerId) {
			this.memoizedHierarchyIndexIdentity = new IdentityToken.ProducerId(ownerId, TokenRole.DIRECT);
		}
		return this.memoizedHierarchyIndexIdentity;
	}
}
