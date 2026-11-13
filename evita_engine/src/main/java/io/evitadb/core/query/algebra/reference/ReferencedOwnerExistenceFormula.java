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

import io.evitadb.core.query.QueryExecutionContext;
import io.evitadb.core.query.algebra.AbstractFormula;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.core.query.algebra.identity.FormulaKey;
import io.evitadb.core.query.algebra.identity.IdentityExempt;
import io.evitadb.core.query.algebra.identity.IdentityRecord;
import io.evitadb.core.query.algebra.identity.IdentityToken;
import io.evitadb.core.query.algebra.identity.KeyAccumulator;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.roaringbitmap.PersistentRoaringBitmap;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;

import java.util.List;

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
 * state instead, and the hash, transactional ids and cost estimates below account for them explicitly.
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
	 * Unique identifier of this formula used in {@link AbstractFormula#getClassId()} as the identity discriminator.
	 */
	private static final long CLASS_ID = 7825194638201947355L;
	/**
	 * Error message thrown when {@link #getCloneWithInnerFormulas(Formula...)} receives more than one inner formula.
	 */
	public static final String ERROR_SINGLE_FORMULA_EXPECTED = "Exactly one inner formula is expected!";
	/**
	 * Primary keys of the candidate owner entities, ascending, positionally paired with {@link #perOwnerFormulas}.
	 */
	@IdentityExempt(IdentityExempt.Reason.DECOMPOSED)
	private final int[] ownerPrimaryKeys;
	/**
	 * One formula per candidate owner, producing the referenced entity primary keys that owner holds and that satisfy
	 * the reference-attribute constraints.
	 */
	@IdentityExempt(IdentityExempt.Reason.DECOMPOSED)
	private final Formula[] perOwnerFormulas;

	public ReferencedOwnerExistenceFormula(
		@Nonnull int[] ownerPrimaryKeys,
		@Nonnull Formula[] perOwnerFormulas,
		@Nonnull Formula referencedEntityFormula) {
		Assert.isPremiseValid(
			ownerPrimaryKeys.length == perOwnerFormulas.length,
			"Exactly one formula per owner primary key is expected!"
		);
		this.ownerPrimaryKeys = ownerPrimaryKeys;
		this.perOwnerFormulas = perOwnerFormulas;
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

	/** Captures the detached semantic inputs without evaluating the holder. */
	@Nonnull
	@Override
	protected IdentityRecord identity() {
		final FormulaKey[] ownerKeys = new FormulaKey[this.perOwnerFormulas.length];
		for (int i = 0; i < ownerKeys.length; i++) {
			ownerKeys[i] = this.perOwnerFormulas[i].getKey();
		}
		return new Identity(IdentityToken.ofContent(this.ownerPrimaryKeys), List.of(ownerKeys));
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
				EmptyFormula.INSTANCE);
		}
		Assert.isTrue(innerFormulas.length == 1, ERROR_SINGLE_FORMULA_EXPECTED);
		return new ReferencedOwnerExistenceFormula(
			this.ownerPrimaryKeys,
			this.perOwnerFormulas,
			innerFormulas[0]);
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

	/**
	 * Detached semantic inputs of the holder.
	 *
	 * @param ownerPrimaryKeys ordered candidate owner keys
	 * @param perOwnerFormulas ordered keys paired with the owners
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	public record Identity(
		@Nonnull IdentityToken ownerPrimaryKeys,
		@Nonnull List<FormulaKey> perOwnerFormulas
	) implements IdentityRecord {
		/** Captures an immutable ordered association with candidate owners. */
		public Identity {
			perOwnerFormulas = List.copyOf(perOwnerFormulas);
		}

		/** Returns the holder's class identifier. */
		@Override
		public long classId() {
			return CLASS_ID;
		}

		/** Includes every semantic input in the structural hash and retained weight. */
		@Override
		public void describeTo(@Nonnull KeyAccumulator accumulator) {
			accumulator.add(this.ownerPrimaryKeys);
			accumulator.addKeys(this.perOwnerFormulas);
		}
	}

}
