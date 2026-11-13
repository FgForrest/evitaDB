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

import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.core.query.algebra.AbstractFormula;
import io.evitadb.core.query.algebra.ChildrenDependentFormula;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.identity.IdentityExempt;
import io.evitadb.core.query.algebra.identity.IdentityRecord;
import io.evitadb.core.query.algebra.identity.IdentityToken;
import io.evitadb.core.query.algebra.identity.KeyAccumulator;
import io.evitadb.core.query.algebra.identity.TokenList;
import io.evitadb.core.query.algebra.identity.TokenRole;
import io.evitadb.dataType.Scope;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.roaringbitmap.PersistentRoaringBitmap;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.UnaryOperator;

/**
 * Translates referenced entity primary keys produced by the inner formula into primary keys of
 * the referenced-type entity index.
 *
 * The formula is used when query evaluation needs to switch from the space of referenced entities
 * to the space of records stored in {@link ReferencedTypeEntityIndex}. The translation is performed
 * lazily during evaluation so that the potentially heavy computation of the inner formula is not
 * executed during query preparation.
 *
 * If the referenced entity type is managed, the translation is restricted by a precomputed superset of
 * referenced entities that exist in **any** scope. The scope of the query constrains the queried entities
 * only - the referenced entity may legitimately live in a different scope than the queried entity (e.g.
 * an archived product referencing a live category), so referenced-entity existence must be checked across
 * all scopes. Only ids present in that superset are considered.
 *
 * The result is a bitmap of the primary keys of rows inside {@link ReferencedTypeEntityIndex}: the candidate
 * partitions for index discovery.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class ReferencedEntityIndexPrimaryKeyTranslatingFormula
	extends AbstractFormula
	implements ChildrenDependentFormula
{

	/**
	 * Unique identifier of this formula used in {@link AbstractFormula#getClassId()} as the identity discriminator.
	 */
	private static final long CLASS_ID = 2738491562847195632L;
	/**
	 * Error message thrown when {@link #getCloneWithInnerFormulas(Formula...)} receives more than one inner formula.
	 */
	public static final String ERROR_SINGLE_FORMULA_EXPECTED = "Exactly one inner formula is expected!";
	/**
	 * Optional union bitmap of all referenced entity primary keys that are allowed to participate
	 * in the translation. The bitmap is built from {@link GlobalEntityIndex#getAllPrimaryKeys()}
	 * across all scopes (a reference may point to an entity living in a different scope than the
	 * queried entity). When {@code null} the referenced type is not managed and no restriction
	 * applies; for a managed type the superset is always present (possibly empty).
	 */
	@IdentityExempt(IdentityExempt.Reason.DERIVED)
	private final Bitmap referencedEntitySuperSet;
	/**
	 * Ordered global index versions used by the identity; zero denotes an absent scope index, preserving every
	 * legal producer id, including minus one.
	 */
	@IdentityExempt(IdentityExempt.Reason.DECOMPOSED)
	private final long[] referencedEntityTypeSuperSetIdentityIds;
	/**
	 * Target index that knows how to translate referenced entity primary keys into its own
	 * primary keys.
	 */
	@IdentityExempt(IdentityExempt.Reason.DECOMPOSED)
	private final ReferencedTypeEntityIndex referencedEntityTypeIndex;
	/**
	 * Worst-case estimate of the result cardinality that must be available before evaluation. The result
	 * is a set of reduced-index primary keys drawn from {@link #referencedEntityTypeIndex}, so the most it
	 * can ever hold is every key that index advertises.
	 *
	 * The superset does not tighten this. It is counted in *referenced entity* primary keys while the result
	 * is counted in *reduced-index* primary keys, and one referenced entity may own several reduced indexes -
	 * they are keyed by {@link io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey}, which
	 * carries representative attribute values alongside the reference key - so taking the smaller of the two
	 * counts can land below the true worst case.
	 */
	@IdentityExempt(IdentityExempt.Reason.STATE)
	private final int worstCardinality;
	/**
	 * Optional expansion function that will expand the inner formula result before applying the translation.
	 */
	@IdentityExempt(IdentityExempt.Reason.DECOMPOSED)
	@Nullable private final UnaryOperator<Bitmap> expansionFunction;

	/**
	 * Internal constructor used by cloning and tests. The caller guarantees that the superset is a deterministic
	 * function of the supplied global index versions. The identity records those versions instead of the bitmap.
	 *
	 * @param referencedEntitySuperSet                    optional superset of referenced entity primary keys to limit
	 *                                                    the translation; may be {@code null}
	 * @param referencedEntityTypeSuperSetTransactionalId transactional ids of contributing
	 *                                                    {@link GlobalEntityIndex} instances used for cache-key hashing
	 * @param referencedEntityTypeIndex                   target index for the translation
	 * @param worstCardinality                            worst-case estimate of the result cardinality
	 * @param innerFormula                                inner formula producing referenced entity primary keys
	 */
	ReferencedEntityIndexPrimaryKeyTranslatingFormula(
		@Nullable Bitmap referencedEntitySuperSet,
		@Nonnull long[] referencedEntityTypeSuperSetTransactionalId,
		@Nonnull ReferencedTypeEntityIndex referencedEntityTypeIndex,
		int worstCardinality,
		@Nullable UnaryOperator<Bitmap> expansionFunction,
		@Nonnull Formula innerFormula
	) {
		this.referencedEntitySuperSet = referencedEntitySuperSet;
		this.referencedEntityTypeSuperSetIdentityIds = referencedEntityTypeSuperSetTransactionalId;
		this.referencedEntityTypeIndex = referencedEntityTypeIndex;
		this.worstCardinality = worstCardinality;
		this.expansionFunction = expansionFunction;
		this.initFields(innerFormula);
	}

	/**
	 * Creates a translating formula that takes referenced entity primary keys produced by the
	 * inner formula and lazily maps them to primary keys of the referenced-type entity index.
	 *
	 * When the target entity type is managed, this constructor also builds a superset of allowed
	 * entity primary keys by querying {@link GlobalEntityIndex} for the target entity type in
	 * **all** scopes. The scope of the query constrains only the queried entities - a reference may
	 * point to an entity living in a different scope (e.g. an archived product referencing a live
	 * category), so the existence check must not be limited to the scopes the query is evaluated in.
	 * The union of these bitmaps is used to filter the inner result during evaluation. Transactional
	 * ids of the contributing indices are captured in the detached identity.
	 *
	 * @param referenceSchema schema of the processed reference
	 * @param targetEntityType the entity type to resolve the superset for (referenced entity type
	 *        for entity-having, referenced group type for group-having)
	 * @param isTargetManaged whether the target entity type is managed by evitaDB
	 * @param referencedEntitySuperSetSupplier supplier returning {@link GlobalEntityIndex} for a
	 *        target entity type and a scope
	 * @param referencedTypeEntityIndex target referenced-type entity index
	 * @param innerFormula inner formula producing referenced entity primary keys
	 */
	public ReferencedEntityIndexPrimaryKeyTranslatingFormula(
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull String targetEntityType,
		boolean isTargetManaged,
		@Nonnull BiFunction<String, Scope, Optional<GlobalEntityIndex>> referencedEntitySuperSetSupplier,
		@Nonnull ReferencedTypeEntityIndex referencedTypeEntityIndex,
		@Nonnull Formula innerFormula,
		@Nullable UnaryOperator<Bitmap> expansionFunction
	) {
		if (isTargetManaged) {
			final Scope[] allScopes = Scope.values();
			PersistentRoaringBitmap bitmap = null;
			final long[] identityIds = new long[allScopes.length];
			int scopeIndex = 0;
			for (final Scope theScope : allScopes) {
				// the scope of the query constrains only the queried entities - a reference may point to an entity
				// living in a different scope (e.g. an archived product referencing a live category), so the
				// existence check must span all scopes, not just the scopes the query is evaluated in
				final Optional<GlobalEntityIndex> globalEntityIndex = referencedEntitySuperSetSupplier
					.apply(targetEntityType, theScope);
				if (globalEntityIndex.isEmpty()) {
					scopeIndex++;
					continue;
				}
				final Bitmap allPrimaryKeys = globalEntityIndex.get().getAllPrimaryKeys();
				identityIds[scopeIndex] = globalEntityIndex.get().getId();
				scopeIndex++;
				if (allPrimaryKeys.isEmpty()) {
					// an empty contribution would not change the union - skipping it keeps the common
					// single-contributing-scope case zero-copy (no OR with an empty bitmap)
					continue;
				}
				bitmap = bitmap == null ?
					RoaringBitmapBackedBitmap.getRoaringBitmap(allPrimaryKeys) :
					PersistentRoaringBitmap.or(bitmap, RoaringBitmapBackedBitmap.getRoaringBitmap(allPrimaryKeys));
			}
			// for a managed referenced type the superset always applies - when no scope contributed any primary
			// keys the superset is empty (no referenced entity exists anywhere), never unrestricted
			this.referencedEntitySuperSet = bitmap == null ? EmptyBitmap.INSTANCE : new BaseBitmap(bitmap);
			this.referencedEntityTypeSuperSetIdentityIds = identityIds;
		} else {
			this.referencedEntitySuperSet = null;
			this.referencedEntityTypeSuperSetIdentityIds = ArrayUtils.EMPTY_LONG_ARRAY;
		}

		this.expansionFunction = expansionFunction;
		this.referencedEntityTypeIndex = referencedTypeEntityIndex;
		// the records this index accommodates - reduced index primary keys, which is what a filter evaluated
		// against it yields and therefore the right ceiling for this formula
		this.worstCardinality = referencedTypeEntityIndex.size();
		this.initFields(innerFormula);
	}

	/** Captures the detached semantic inputs without evaluating the holder. */
	@Nonnull
	@Override
	protected IdentityRecord identity() {
		return new Identity(TokenList.ofProducerIds(this.referencedEntityTypeSuperSetIdentityIds, false),
			new IdentityToken.ProducerId(this.referencedEntityTypeIndex.getId(), TokenRole.DIRECT),
			this.expansionFunction == null ? null : IdentityToken.OPAQUE);
	}

	@Override
	protected long getClassId() {
		return CLASS_ID;
	}

	@Nonnull
	@Override
	protected Bitmap computeInternal() {
		final Bitmap innerResult = this.innerFormulas[0].compute();
		final Bitmap referencedEntityIds = this.expansionFunction == null ?
			innerResult : this.expansionFunction.apply(innerResult);
		final int cnt = referencedEntityIds.size();
		if (cnt == 0) {
			return EmptyBitmap.INSTANCE;
		} else if (this.referencedEntitySuperSet == null) {
			return this.referencedEntityTypeIndex.getIndexPrimaryKeys(
				RoaringBitmapBackedBitmap.getRoaringBitmap(referencedEntityIds)
			);
		} else {
			final PersistentRoaringBitmap matchingReferencedEntityPks = PersistentRoaringBitmap.and(
				RoaringBitmapBackedBitmap.getRoaringBitmap(referencedEntityIds),
				RoaringBitmapBackedBitmap.getRoaringBitmap(this.referencedEntitySuperSet)
			);
			return this.referencedEntityTypeIndex.getIndexPrimaryKeys(matchingReferencedEntityPks);
		}
	}

	@Nonnull
	@Override
	public Formula getCloneWithInnerFormulas(@Nonnull Formula... innerFormulas) {
		Assert.isTrue(innerFormulas.length == 1, ERROR_SINGLE_FORMULA_EXPECTED);
		return new ReferencedEntityIndexPrimaryKeyTranslatingFormula(
			this.referencedEntitySuperSet,
			this.referencedEntityTypeSuperSetIdentityIds,
			this.referencedEntityTypeIndex,
			this.worstCardinality,
			this.expansionFunction,
			innerFormulas[0]
		);
	}

	@Override
	public int getEstimatedCardinality() {
		return this.worstCardinality;
	}

	@Override
	public long getOperationCost() {
		return 1;
	}

	@Nonnull
	@Override
	public String toString() {
		return "TRANSLATE TO REFERENCED ENTITY INDEX PRIMARY KEYS";
	}

	/**
	 * Detached semantic inputs of the holder.
	 *
	 * @param referencedEntityTypeSuperSetTransactionalIds global index versions in scope order
	 * @param referencedEntityTypeIndex target referenced-type index version
	 * @param expansionFunction opaque expansion marker, absent without expansion
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	public record Identity(
		@Nonnull TokenList referencedEntityTypeSuperSetTransactionalIds,
		@Nonnull IdentityToken referencedEntityTypeIndex,
		@Nullable IdentityToken expansionFunction
	) implements IdentityRecord {
		/** Returns the holder's class identifier. */
		@Override
		public long classId() {
			return CLASS_ID;
		}

		/** Includes every semantic input in the structural hash and retained weight. */
		@Override
		public void describeTo(@Nonnull KeyAccumulator accumulator) {
			accumulator.add(this.referencedEntityTypeSuperSetTransactionalIds);
			accumulator.add(this.referencedEntityTypeIndex);
			accumulator.add(this.expansionFunction);
		}
	}

}
