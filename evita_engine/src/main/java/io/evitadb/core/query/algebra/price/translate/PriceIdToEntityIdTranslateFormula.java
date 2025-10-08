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

package io.evitadb.core.query.algebra.price.translate;

import io.evitadb.core.cache.payload.FlattenedFormula;
import io.evitadb.core.cache.payload.FlattenedFormulaWithFilteredPrices;
import io.evitadb.core.query.QueryExecutionContext;
import io.evitadb.core.query.algebra.AbstractCacheableFormula;
import io.evitadb.core.query.algebra.CacheableFormula;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.price.CacheablePriceFormula;
import io.evitadb.core.query.algebra.price.FilteredPriceRecordAccessor;
import io.evitadb.core.query.algebra.price.filteredPriceRecords.FilteredPriceRecords;
import io.evitadb.core.query.algebra.price.filteredPriceRecords.FilteredPriceRecords.SortingForm;
import io.evitadb.core.query.algebra.price.filteredPriceRecords.ResolvedFilteredPriceRecords;
import io.evitadb.core.query.algebra.price.priceIndex.PriceIdContainerFormula;
import io.evitadb.core.query.algebra.price.termination.PriceEvaluationContext;
import io.evitadb.core.query.algebra.utils.visitor.FormulaFinder;
import io.evitadb.core.query.algebra.utils.visitor.FormulaFinder.LookUp;
import io.evitadb.dataType.array.CompositeObjectArray;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.index.price.PriceListAndCurrencyPriceIndex;
import io.evitadb.index.price.model.PriceIndexKey;
import io.evitadb.index.price.model.priceRecord.PriceRecord;
import io.evitadb.index.price.model.priceRecord.PriceRecordContract;
import io.evitadb.utils.Assert;
import net.openhft.hashing.LongHashFunction;
import org.roaringbitmap.RoaringBitmap;
import org.roaringbitmap.RoaringBitmapWriter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Collection;
import java.util.function.Consumer;

/**
 * PriceIdToEntityIdTranslateFormula translates price ids to entity ids in its {@link Formula#compute()} method.
 *
 * This formula consumes {@link Bitmap} of {@link PriceRecord#internalPriceId()} and
 * computes {@link Formula} of {@link PriceRecord#entityPrimaryKey() entity ids}. It also uses information
 * from used {@link PriceIdContainerFormula#getPriceIndex()} to retrieve price records for corresponding
 * entity ids.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class PriceIdToEntityIdTranslateFormula extends AbstractCacheableFormula implements FilteredPriceRecordAccessor, CacheablePriceFormula, Formula {
	private static final long CLASS_ID = -8575853054010280485L;

	/**
	 * Contains array of price records that links to the price ids produced by {@link Formula#compute()} method. This array
	 * is available once the {@link Formula#compute()} method has been called.
	 */
	private FilteredPriceRecords filteredPriceRecords;

	public PriceIdToEntityIdTranslateFormula(@Nonnull Formula delegate) {
		super(null);
		this.initFields(delegate);
	}

	private PriceIdToEntityIdTranslateFormula(@Nullable Consumer<CacheableFormula> computationCallback, @Nonnull FilteredPriceRecords filteredPriceRecords, @Nonnull Formula delegate) {
		super(computationCallback);
		this.filteredPriceRecords = filteredPriceRecords;
		this.initFields(delegate);
	}

	/**
	 * Returns delegate formula of this container.
	 */
	@Nonnull
	public Formula getDelegate() {
		return this.innerFormulas[0];
	}

	/**
	 * Returns bitmap of price ids produced by the delegate formula.
	 */
	@Nonnull
	public Bitmap getPriceIdBitmap() {
		return getDelegate().compute();
	}

	@Nonnull
	@Override
	public Formula getCloneWithInnerFormulas(@Nonnull Formula... innerFormulas) {
		Assert.isPremiseValid(innerFormulas.length == 1, "Expected exactly single delegate inner formula!");
		return new PriceIdToEntityIdTranslateFormula(
			this.computationCallback, this.filteredPriceRecords, innerFormulas[0]
		);
	}

	@Override
	public long getOperationCost() {
		return 3527;
	}

	@Override
	public FlattenedFormula toSerializableFormula(long formulaHash, @Nonnull LongHashFunction hashFunction) {
		return new FlattenedFormulaWithFilteredPrices(
			formulaHash,
			getTransactionalIdHash(),
			Arrays.stream(gatherTransactionalIds())
				.distinct()
				.sorted()
				.toArray(),
			compute(),
			getFilteredPriceRecords(this.executionContext),
			getPriceEvaluationContext()
		);
	}

	@Nonnull
	private PriceEvaluationContext getPriceEvaluationContext() {
		final Collection<PriceIdContainerFormula> priceIdFormulas = FormulaFinder.find(
			getDelegate(), PriceIdContainerFormula.class, LookUp.SHALLOW
		);

		return new PriceEvaluationContext(
			null,
			priceIdFormulas.stream()
				.map(it -> it.getPriceIndex().getPriceIndexKey())
				.toArray(PriceIndexKey[]::new)
		);
	}

	@Override
	public int getSerializableFormulaSizeEstimate() {
		return FlattenedFormulaWithFilteredPrices.estimateSize(
			gatherTransactionalIds(),
			compute(),
			getPriceEvaluationContext()
		);
	}

	@Nonnull
	@Override
	public CacheableFormula getCloneWithComputationCallback(@Nonnull Consumer<CacheableFormula> selfOperator, @Nonnull Formula... innerFormulas) {
		Assert.isPremiseValid(innerFormulas.length == 1, "Expected exactly single delegate inner formula!");
		return new PriceIdToEntityIdTranslateFormula(
			selfOperator, this.filteredPriceRecords, innerFormulas[0]
		);
	}

	@Nonnull
	@Override
	public FilteredPriceRecords getFilteredPriceRecords(@Nonnull QueryExecutionContext context) {
		if (this.filteredPriceRecords == null) {
			// init the records first
			compute();
		}
		return this.filteredPriceRecords;
	}

	@Override
	public String toString() {
		return "TRANSLATE PRICE ID TO ENTITY ID";
	}

	@Nonnull
	@Override
	protected Bitmap computeInternal() {
		// retrieve filtered price ids from the delegate formula
		Bitmap priceIdBitmap = getPriceIdBitmap();

		// if there are any prices found
		if (!priceIdBitmap.isEmpty()) {
			// collect all PriceIdContainerFormula that were involved in computing delegate result
			final Collection<PriceIdContainerFormula> priceIdFormulas = FormulaFinder.find(
				getDelegate(), PriceIdContainerFormula.class, LookUp.SHALLOW
			);
			// create new roaring bitmap builder
			final RoaringBitmapWriter<RoaringBitmap> entityIdWriter = RoaringBitmapBackedBitmap.buildWriter();
			final CompositeObjectArray<PriceRecordContract> theFilteredPriceRecords = new CompositeObjectArray<>(PriceRecordContract.class, false);

			// iterate through prices
			for (PriceIdContainerFormula priceIdFormula : priceIdFormulas) {
				// collect array of price records that were used in the input formula (only some of them will be in current input)
				final PriceListAndCurrencyPriceIndex<?, ?> priceIndex = priceIdFormula.getPriceIndex();
				final RoaringBitmapWriter<RoaringBitmap> notFound = RoaringBitmapBackedBitmap.buildWriter();
				final PriceRecordContract[] foundPrices = priceIndex.getPriceRecords(
					priceIdBitmap,
					priceRecordContract -> entityIdWriter.add(priceRecordContract.entityPrimaryKey()),
					notFound::add
				);
				theFilteredPriceRecords.addAll(foundPrices, 0, foundPrices.length);

				// otherwise, initialize new iterator from the leftovers
				priceIdBitmap = new BaseBitmap(notFound.get());

				// we found all records and we may leave the iteration
				if (priceIdBitmap.isEmpty()) {
					break;
				}
			}

			if (!priceIdBitmap.isEmpty()) {
				throw new GenericEvitaInternalError(
					"These prices weren't translated to entity id: " + Arrays.toString(priceIdBitmap.getArray())
				);
			}

			this.filteredPriceRecords = new ResolvedFilteredPriceRecords(
				theFilteredPriceRecords.toArray(),
				SortingForm.NOT_SORTED
			);

			// wrap result into the bitmap
			return new BaseBitmap(entityIdWriter.get());
		} else {
			this.filteredPriceRecords = new ResolvedFilteredPriceRecords();
			return EmptyBitmap.INSTANCE;
		}
	}

	@Override
	public int getEstimatedCardinality() {
		return Arrays.stream(this.innerFormulas).mapToInt(Formula::getEstimatedCardinality).sum();
	}

	@Override
	protected long includeAdditionalHash(@Nonnull LongHashFunction hashFunction) {
		return CLASS_ID;
	}

	@Override
	protected long getClassId() {
		return CLASS_ID;
	}

}
