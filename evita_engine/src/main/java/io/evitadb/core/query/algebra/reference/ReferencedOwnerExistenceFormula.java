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

package io.evitadb.core.query.algebra.reference;

import io.evitadb.core.query.algebra.AbstractFormula;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.core.query.QueryExecutionContext;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.roaringbitmap.PersistentRoaringBitmap;
import io.evitadb.utils.Assert;
import net.openhft.hashing.LongHashFunction;

import javax.annotation.Nonnull;

/**
 * Emits the primary keys of *owner* entities whose reference rows contain at least one referenced entity that survives
 * the filter. The formula exists to evaluate a `referenceHaving` from the **opposite end of a bidirectional
 * (reflected) reference pair**: instead of materialising one reduced index per matching referenced entity on the owner
 * side, it walks one reduced index per candidate owner on the counterpart side and asks a single question per owner -
 * "does anything survive here?".
 *
 * The single {@link #getInnerFormulas() inner formula} is the shared narrowing of the *referenced* entities - the
 * nested query planned for `entityHaving`, or the full in-scope set of the referenced collection when there is none.
 * It is computed exactly once.
 *
 * The per-owner formulas are deliberately **not** inner formulas. They are positionally paired with
 * {@link #ownerPrimaryKeys}, and {@link io.evitadb.core.query.algebra.utils.visitor.FormulaCloner} is allowed to drop
 * children its mutator rejects - a dropped child would silently relabel every owner after it. They are kept as internal
 * state instead, exactly as {@link ReferenceOwnerTranslatingFormula} keeps its expander, and the hash, the
 * transactional ids and the cost estimates below account for them explicitly.
 *
 * The per-owner test is a short-circuiting {@link PersistentRoaringBitmap#intersects(PersistentRoaringBitmap,
 * PersistentRoaringBitmap)} - no intersection is ever materialised, because only emptiness matters.
 *
 * The formula deliberately does **not** implement
 * {@link io.evitadb.core.query.algebra.ChildrenDependentFormula}: `FormulaOptimizer` *removes* such a formula outright
 * once its children are optimised away (`FormulaOptimizer:117`), and removing a `referenceHaving` from an enclosing
 * conjunction widens the result instead of narrowing it. For the same reason
 * {@link #getCloneWithInnerFormulas(Formula...)} never answers with `EmptyFormula.INSTANCE` - this wrapper is the
 * absorbing element of its parent, not the identity one.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class ReferencedOwnerExistenceFormula extends AbstractFormula {
	/**
	 * Unique identifier of this formula used in {@link AbstractFormula#getClassId()} for hash computation.
	 */
	private static final long CLASS_ID = 7825194638201947355L;
	/**
	 * Error message thrown when {@link #getCloneWithInnerFormulas(Formula...)} receives more than one inner formula.
	 */
	public static final String ERROR_SINGLE_FORMULA_EXPECTED = "Exactly one inner formula is expected!";
	/**
	 * Primary keys of the candidate owner entities, ascending, positionally paired with {@link #perOwnerFormulas}.
	 */
	private final int[] ownerPrimaryKeys;
	/**
	 * One formula per candidate owner, producing the referenced entity primary keys that owner holds and that satisfy
	 * the reference-attribute constraints.
	 */
	private final Formula[] perOwnerFormulas;
	/**
	 * Transactional id of the counterpart type-level index the candidate owners were read from. The candidate set is
	 * baked into this formula at planning time, so the formula becomes obsolete whenever anything in that index
	 * changes - the same "catch-all" consideration {@link ReferenceOwnerTranslatingFormula} makes.
	 */
	private final long counterpartTypeIndexTransactionalId;

	public ReferencedOwnerExistenceFormula(
		@Nonnull int[] ownerPrimaryKeys,
		@Nonnull Formula[] perOwnerFormulas,
		@Nonnull Formula referencedEntityFormula,
		long counterpartTypeIndexTransactionalId
	) {
		Assert.isPremiseValid(
			ownerPrimaryKeys.length == perOwnerFormulas.length,
			"Exactly one formula per owner primary key is expected!"
		);
		this.ownerPrimaryKeys = ownerPrimaryKeys;
		this.perOwnerFormulas = perOwnerFormulas;
		this.counterpartTypeIndexTransactionalId = counterpartTypeIndexTransactionalId;
		this.initFields(referencedEntityFormula);
	}

	@Override
	public void initialize(@Nonnull QueryExecutionContext executionContext) {
		super.initialize(executionContext);
		// the per-owner formulas are outside the inner formula array, so they have to be initialized here
		for (final Formula perOwnerFormula : this.perOwnerFormulas) {
			perOwnerFormula.initialize(executionContext);
		}
	}

	@Override
	protected long includeAdditionalHash(@Nonnull LongHashFunction hashFunction) {
		// the owner keys and the per-owner formulas are part of the result but invisible through the inner formulas
		final long[] additionalHashes = new long[this.perOwnerFormulas.length + 2];
		additionalHashes[0] = this.counterpartTypeIndexTransactionalId;
		additionalHashes[1] = hashFunction.hashInts(this.ownerPrimaryKeys);
		for (int i = 0; i < this.perOwnerFormulas.length; i++) {
			additionalHashes[i + 2] = this.perOwnerFormulas[i].getHash();
		}
		return hashFunction.hashLongs(additionalHashes);
	}

	@Nonnull
	@Override
	protected long[] gatherBitmapIdsInternal() {
		final long[] innerIds = super.gatherBitmapIdsInternal();
		int totalLength = innerIds.length;
		final long[][] perOwnerIds = new long[this.perOwnerFormulas.length][];
		for (int i = 0; i < this.perOwnerFormulas.length; i++) {
			perOwnerIds[i] = this.perOwnerFormulas[i].gatherTransactionalIds();
			totalLength += perOwnerIds[i].length;
		}
		final long[] result = new long[totalLength];
		System.arraycopy(innerIds, 0, result, 0, innerIds.length);
		int offset = innerIds.length;
		for (final long[] perOwnerId : perOwnerIds) {
			System.arraycopy(perOwnerId, 0, result, offset, perOwnerId.length);
			offset += perOwnerId.length;
		}
		return result;
	}

	@Override
	protected long getEstimatedBaseCost() {
		// the per-owner formulas are not reached by the inner formula walk in getEstimatedCostInternal()
		long cost = 0L;
		for (final Formula perOwnerFormula : this.perOwnerFormulas) {
			final long ownerCost = perOwnerFormula.getEstimatedCost();
			if (ownerCost > Long.MAX_VALUE - cost) {
				return Long.MAX_VALUE;
			}
			cost += ownerCost;
		}
		return cost;
	}

	@Override
	protected long getCostInternal() {
		long cost = super.getCostInternal();
		for (final Formula perOwnerFormula : this.perOwnerFormulas) {
			cost += perOwnerFormula.getCost();
		}
		return cost;
	}

	@Override
	protected long getClassId() {
		return CLASS_ID;
	}

	@Nonnull
	@Override
	protected Bitmap computeInternal() {
		final Bitmap referencedEntities = this.innerFormulas[0].compute();
		if (referencedEntities.isEmpty()) {
			return EmptyBitmap.INSTANCE;
		}
		final PersistentRoaringBitmap narrowedReferencedEntities =
			RoaringBitmapBackedBitmap.getRoaringBitmap(referencedEntities);
		final int ownerCount = this.ownerPrimaryKeys.length;
		// ascending by construction - the candidates were read out of a bitmap
		final int[] matchedOwners = new int[ownerCount];
		int matchedCount = 0;
		for (int i = 0; i < ownerCount; i++) {
			final Bitmap ownedReferencedEntities = this.perOwnerFormulas[i].compute();
			if (ownedReferencedEntities.isEmpty()) {
				continue;
			}
			// emptiness is the whole question - never materialise the intersection
			if (
				PersistentRoaringBitmap.intersects(
					RoaringBitmapBackedBitmap.getRoaringBitmap(ownedReferencedEntities),
					narrowedReferencedEntities
				)
			) {
				matchedOwners[matchedCount++] = this.ownerPrimaryKeys[i];
			}
		}
		if (matchedCount == 0) {
			return EmptyBitmap.INSTANCE;
		}
		final int[] result = new int[matchedCount];
		System.arraycopy(matchedOwners, 0, result, 0, matchedCount);
		return new BaseBitmap(result);
	}

	@Nonnull
	@Override
	public Formula getCloneWithInnerFormulas(@Nonnull Formula... innerFormulas) {
		// A cloner mutator returning NULL strips the child. Per the contract on
		// Formula#getCloneWithInnerFormulas, answering with EmptyFormula.INSTANCE would declare this wrapper the
		// *identity* element and have the cloner drop it from its parent - which for a `referenceHaving` inside an
		// AND widens the result instead of emptying it. This wrapper is the *absorbing* element: return a real
		// instance whose inner formula is empty, so its emptiness propagates up the conjunction normally.
		if (innerFormulas.length == 0) {
			return new ReferencedOwnerExistenceFormula(
				this.ownerPrimaryKeys,
				this.perOwnerFormulas,
				EmptyFormula.INSTANCE,
				this.counterpartTypeIndexTransactionalId
			);
		}
		Assert.isTrue(innerFormulas.length == 1, ERROR_SINGLE_FORMULA_EXPECTED);
		return new ReferencedOwnerExistenceFormula(
			this.ownerPrimaryKeys,
			this.perOwnerFormulas,
			innerFormulas[0],
			this.counterpartTypeIndexTransactionalId
		);
	}

	@Override
	public int getEstimatedCardinality() {
		// in the worst case every candidate owner survives
		return this.ownerPrimaryKeys.length;
	}

	@Override
	public long getOperationCost() {
		// one short-circuiting intersects() per candidate - the same order of magnitude as AndFormula
		return 9;
	}

	@Override
	public String toString() {
		return "OWNERS REFERENCING MATCHED ENTITIES (candidates: " + this.ownerPrimaryKeys.length + ")";
	}

}
