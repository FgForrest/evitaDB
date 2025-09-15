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

package io.evitadb.core.query.algebra.reference;

import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.core.query.algebra.AbstractFormula;
import io.evitadb.core.query.algebra.ChildrenDependentFormula;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.dataType.Scope;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import net.openhft.hashing.LongHashFunction;
import org.roaringbitmap.RoaringBitmap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * TODO JNO - document me
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class ReferencedEntityIndexPrimaryKeyTranslatingFormula extends AbstractFormula implements ChildrenDependentFormula {
	private static final long CLASS_ID = 6841111737856593641L;
	public static final String ERROR_SINGLE_FORMULA_EXPECTED = "Exactly one inner formula is expected!";
	/**
	 * TODO JNO - document me
	 */
	private final Bitmap referencedEntitySuperSet;
	/**
	 * Contains the transactional id of the {@link GlobalEntityIndex} of the referenced entity. Because we need to be
	 * able to declare {@link #includeAdditionalHash(LongHashFunction)} before the {@link #computeInternal()} happens
	 * we cannot use only the relevant transactional ids because we just don't know which will be relevant until we
	 * compute the result of the delegate formula. That's why we use "catch-all" transactional id that changes everytime
	 * anything in target entity type index changes.
	 */
	private final long[] referencedEntityTypeSuperSetTransactionalIds;
	/**
	 * TODO JNO - document me
	 */
	private final ReferencedTypeEntityIndex referencedEntityTypeIndex;
	/**
	 * Contains the information about referenced entity type cardinality. The consideration is the same as for
	 * {@link #referencedEntityTypeTransactionalIds} - we have to provide the {@link Formula#getEstimatedCardinality()} before
	 * the real calculation occurs, so we have to consider the worst possible cardinality here.
	 */
	private final int worstCardinality;

	ReferencedEntityIndexPrimaryKeyTranslatingFormula(
		@Nullable Bitmap referencedEntitySuperSet,
		@Nonnull long[] referencedEntityTypeSuperSetTransactionalId,
		@Nonnull ReferencedTypeEntityIndex referencedEntityTypeIndex,
		int worstCardinality,
		@Nonnull Formula innerFormula
	) {
		this.referencedEntitySuperSet = referencedEntitySuperSet;
		this.referencedEntityTypeSuperSetTransactionalIds = referencedEntityTypeSuperSetTransactionalId;
		this.referencedEntityTypeIndex = referencedEntityTypeIndex;
		this.worstCardinality = worstCardinality;
		this.initFields(innerFormula);
	}

	public ReferencedEntityIndexPrimaryKeyTranslatingFormula(
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull BiFunction<String, Scope, Optional<GlobalEntityIndex>> referencedEntitySuperSetSupplier,
		@Nonnull ReferencedTypeEntityIndex referencedTypeEntityIndex,
		@Nonnull Formula innerFormula,
		@Nonnull Set<Scope> scopes
	) {
		if (referenceSchema.isReferencedEntityTypeManaged()) {
			RoaringBitmap bitmap = null;
			final long[] transactionalIds = new long[scopes.size()];
			int transactionalIdIndex = 0;
			for (Scope theScope : scopes) {
				// if the schema is not indexed in particular scope, we must keep original formula results in place
				// to avoid their removal from the final result
				if (referenceSchema.isIndexedInScope(theScope)) {
					final Optional<GlobalEntityIndex> globalEntityIndex = referencedEntitySuperSetSupplier
						.apply(referenceSchema.getReferencedEntityType(), theScope);
					final Bitmap allPrimaryKeys;
					if (globalEntityIndex.isPresent()) {
						allPrimaryKeys = globalEntityIndex.get().getAllPrimaryKeys();
						transactionalIds[transactionalIdIndex++] = globalEntityIndex.get().getId();
					} else {
						allPrimaryKeys = EmptyBitmap.INSTANCE;
					}
					bitmap = bitmap == null ?
						RoaringBitmapBackedBitmap.getRoaringBitmap(allPrimaryKeys) :
						RoaringBitmap.or(bitmap, RoaringBitmapBackedBitmap.getRoaringBitmap(allPrimaryKeys));
				}
			}
			this.referencedEntitySuperSet = bitmap == null ?
				null : new BaseBitmap(bitmap);
			this.referencedEntityTypeSuperSetTransactionalIds = transactionalIdIndex == transactionalIds.length ?
				transactionalIds :
				Arrays.copyOfRange(transactionalIds, 0, transactionalIdIndex);
		} else {
			this.referencedEntitySuperSet = null;
			this.referencedEntityTypeSuperSetTransactionalIds = ArrayUtils.EMPTY_LONG_ARRAY;
		}

		this.referencedEntityTypeIndex = referencedTypeEntityIndex;
		this.worstCardinality = this.referencedEntitySuperSet == null ?
			referencedTypeEntityIndex.getSize() :
			Math.min(this.referencedEntitySuperSet.size(), referencedTypeEntityIndex.getSize());
		this.initFields(innerFormula);
	}

	@Override
	protected long includeAdditionalHash(@Nonnull LongHashFunction hashFunction) {
		return this.referencedEntityTypeSuperSetTransactionalIds.length == 0 ?
			hashFunction.hashLong(this.referencedEntityTypeIndex.getId()) :
			hashFunction.hashLongs(
				mergeArrays(
					this.referencedEntityTypeSuperSetTransactionalIds,
					this.referencedEntityTypeIndex.getId()
				)
			);
	}

	/**
	 * Merges the given array of long values with an additional long value.
	 * The method creates a new array, copies all elements of the input array,
	 * and adds the new long value at the end.
	 *
	 * @param array the input array of long values to be merged; must not be null
	 * @param anotherLong a long value to be added to the merged array
	 * @return a new array containing all elements of the input array and the additional long value
	 */
	private static long[] mergeArrays(@Nonnull long[] array, long anotherLong) {
		final long[] result = new long[array.length + 1];
		System.arraycopy(array, 0, result, 0, array.length);
		result[array.length] = anotherLong;
		return result;
	}

	@Override
	protected long getClassId() {
		return CLASS_ID;
	}

	@Nonnull
	@Override
	protected Bitmap computeInternal() {
		final Bitmap referencedEntityIds = this.innerFormulas[0].compute();
		final int cnt = referencedEntityIds.size();
		if (cnt == 0) {
			return EmptyBitmap.INSTANCE;
		} else if (this.referencedEntitySuperSet == null) {
			return this.referencedEntityTypeIndex.getIndexPrimaryKeys(
				RoaringBitmapBackedBitmap.getRoaringBitmap(referencedEntityIds)
			);
		} else {
			final RoaringBitmap matchingReferencedEntityPks = RoaringBitmap.and(
				RoaringBitmapBackedBitmap.getRoaringBitmap(referencedEntityIds),
				RoaringBitmapBackedBitmap.getRoaringBitmap(this.referencedEntitySuperSet)
			);
			return new BaseBitmap(
				this.referencedEntityTypeIndex.getIndexPrimaryKeys(matchingReferencedEntityPks)
			);
		}
	}

	@Nonnull
	@Override
	public Formula getCloneWithInnerFormulas(@Nonnull Formula... innerFormulas) {
		Assert.isTrue(innerFormulas.length == 1, ERROR_SINGLE_FORMULA_EXPECTED);
		return new ReferencedEntityIndexPrimaryKeyTranslatingFormula(
			this.referencedEntitySuperSet,
			this.referencedEntityTypeSuperSetTransactionalIds,
			this.referencedEntityTypeIndex,
			this.worstCardinality,
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

	@Override
	public String toString() {
		return "TRANSLATE TO REFERENCED ENTITY INDEX PRIMARY KEYS";
	}

}
