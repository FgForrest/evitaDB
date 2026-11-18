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

package io.evitadb.core.cache;

import com.carrotsearch.hppc.IntObjectHashMap;
import io.evitadb.api.query.require.HistogramBehavior;
import io.evitadb.api.query.require.QueryPriceMode;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictResolutionOverride;
import io.evitadb.api.requestResponse.schema.dto.AttributeSchema;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.*;
import io.evitadb.core.query.algebra.entity.EntityPrimaryKeyRangeFormula;
import io.evitadb.core.query.algebra.facet.ScopeContainerFormula;
import io.evitadb.core.query.algebra.price.filteredPriceRecords.FilteredPriceRecords.SortingForm;
import io.evitadb.core.query.algebra.price.filteredPriceRecords.ResolvedFilteredPriceRecords;
import io.evitadb.core.query.algebra.price.priceIndex.*;
import io.evitadb.core.query.algebra.price.predicate.PricePredicate;
import io.evitadb.core.query.algebra.price.termination.*;
import io.evitadb.core.query.algebra.price.translate.PriceIdToEntityIdTranslateFormula;
import io.evitadb.core.query.extraResult.translator.histogram.cache.CacheableHistogram;
import io.evitadb.core.query.extraResult.translator.histogram.cache.CacheableHistogramContract.CacheableBucket;
import io.evitadb.core.query.extraResult.translator.histogram.producer.AttributeHistogramComputer;
import io.evitadb.core.query.extraResult.translator.histogram.producer.AttributeHistogramProducer;
import io.evitadb.core.query.extraResult.translator.histogram.producer.PriceHistogramComputer;
import io.evitadb.dataType.Scope;
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.index.attribute.OwnerFilterIndex;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.price.PriceListAndCurrencyPriceSuperIndex;
import io.evitadb.index.price.model.PriceIndexKey;
import io.evitadb.index.price.model.priceRecord.*;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;

import javax.annotation.Nonnull;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Real detached payloads and formula fixtures shared by the cache tests.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
final class CachedResultTestSupport {
	/** Prevents instantiation of the fixture helper. */
	private CachedResultTestSupport() {
	}

	/** Builds unsorted resolved records so tests exercise the publication freeze. */
	@Nonnull
	static ResolvedFilteredPriceRecords records(@Nonnull PriceRecordContract... records) {
		return new ResolvedFilteredPriceRecords(records, SortingForm.NOT_SORTED);
	}

	/** Builds a price tuple with independently supplied record contents. */
	@Nonnull
	static CachedPriceRecordsResult prices(@Nonnull PriceRecordContract... records) {
		return new CachedPriceRecordsResult(new BaseBitmap(10), records(records), null, 100);
	}

	/** Builds a SUM record retaining all component entries and any unused map capacity. */
	@Nonnull
	static CumulatedVirtualPriceRecord sum(int count, int capacity) {
		final IntObjectHashMap<PriceRecordContract> map = new IntObjectHashMap<>(capacity);
		for (int i = 0; i < count; i++) {
			map.put(i, new PriceRecordInnerRecordSpecific(i + 1, i + 1, 10, i, 120, 100));
		}
		return new CumulatedVirtualPriceRecord(10, count * 120, QueryPriceMode.WITH_TAX, map);
	}

	/** Builds a histogram with separately allocated bounds, frequencies and large decimal magnitudes. */
	@Nonnull
	static CacheableHistogram histogram() {
		return new CacheableHistogram(
			new CacheableBucket[]{
				new CacheableBucket(new BigDecimal("1000.00"), 2, new BigDecimal("33.333")),
				new CacheableBucket(new BigDecimal("2000.00"), 4, new BigDecimal("66.667"))
			}, new BigDecimal("3000.00"),
			new BigDecimal("1000.000000000000000000000000000000000001"), Long.valueOf(3000)
		);
	}

	/** Supplies all thirteen candidates, keeping producer indexes shared while each formula is new. */
	@Nonnull
	static Stream<Supplier<Formula>> formulaSuppliers() {
		final PriceListAndCurrencyPriceSuperIndex index = priceIndex();
		final PriceEvaluationContext context = new PriceEvaluationContext(null, index.getPriceIndexKey());
		final PriceListAndCurrencyPriceSuperIndex plainIndex = new PriceListAndCurrencyPriceSuperIndex(
			new PriceIndexKey("basic", Currency.getInstance("CZK"), PriceInnerRecordHandling.NONE));
		plainIndex.addPrice(new PriceRecord(1, 1, 10, 120, 100), null);
		plainIndex.addPrice(new PriceRecord(2, 2, 20, 240, 200), null);
		final PriceEvaluationContext plainContext = new PriceEvaluationContext(null, plainIndex.getPriceIndexKey());
		final PriceListAndCurrencyPriceSuperIndex sumIndex = priceIndex(PriceInnerRecordHandling.SUM);
		final PriceEvaluationContext sumContext = new PriceEvaluationContext(null, sumIndex.getPriceIndexKey());
		return Stream.of(
			() -> new AndFormula(new BaseBitmap(1, 2), new BaseBitmap(2, 3)),
			() -> new OrFormula(new BaseBitmap(1, 2), new BaseBitmap(2, 3)),
			() -> new NotFormula(new BaseBitmap(1), new BaseBitmap(1, 2)),
			() -> new PriceListCombinationFormula("other", "basic", context,
				new ConstantFormula(new BaseBitmap(10)), new ConstantFormula(new BaseBitmap(10, 20))),
			() -> new RangeCountFormula(new Bitmap[]{new BaseBitmap(10, 20)}, new Bitmap[]{new BaseBitmap(10)}),
			() -> new EntityPrimaryKeyRangeFormula(5, 15, new ConstantFormula(new BaseBitmap(10, 20))),
			() -> new ScopeContainerFormula(Scope.LIVE, new ConstantFormula(new BaseBitmap(10, 20))),
			() -> new PriceFilteringEnvelopeContainer(new String[]{"basic"}, Currency.getInstance("CZK"), null,
				new ConstantFormula(new BaseBitmap(10, 20))),
			() -> container(index),
			() -> new PriceIdToEntityIdTranslateFormula(new PriceIdContainerFormula(index,
				new ConstantFormula(new BaseBitmap(1, 2, 3)))),
			() -> new PlainPriceTerminationFormulaWithPriceFilter(
				container(plainIndex), plainContext, PricePredicate.ALL_RECORD_FILTER),
			() -> new SumPriceTerminationFormula(container(sumIndex), sumContext,
				QueryPriceMode.WITH_TAX, PricePredicate.ALL_RECORD_FILTER),
			() -> lowestPriceFormula(index)
		);
	}

	/** Builds many independent tied variants so a hash-order selection cannot accidentally agree everywhere. */
	@Nonnull
	static PriceListAndCurrencyPriceSuperIndex tiedPriceIndex() {
		final PriceListAndCurrencyPriceSuperIndex index = new PriceListAndCurrencyPriceSuperIndex(
			new PriceIndexKey("basic", Currency.getInstance("CZK"), PriceInnerRecordHandling.LOWEST_PRICE));
		for (int entity = 1; entity <= 64; entity++) {
			index.addPrice(new PriceRecordInnerRecordSpecific(entity * 2, entity * 2, entity, 1, 120, 100), null);
			index.addPrice(
				new PriceRecordInnerRecordSpecific(entity * 2 + 1, entity * 2 + 1, entity, 2, 120, 100), null);
		}
		return index;
	}

	/** Builds a real price index with two entities and two inner records on the first entity. */
	@Nonnull
	static PriceListAndCurrencyPriceSuperIndex priceIndex() {
		return priceIndex(PriceInnerRecordHandling.LOWEST_PRICE);
	}

	/** Builds the requested inner-record-handling index with two entities and three physical records. */
	@Nonnull
	static PriceListAndCurrencyPriceSuperIndex priceIndex(@Nonnull PriceInnerRecordHandling handling) {
		final PriceListAndCurrencyPriceSuperIndex index = new PriceListAndCurrencyPriceSuperIndex(
			new PriceIndexKey("basic", Currency.getInstance("CZK"), handling));
		index.addPrice(new PriceRecordInnerRecordSpecific(1, 1, 10, 1, 120, 100), null);
		index.addPrice(new PriceRecordInnerRecordSpecific(2, 2, 10, 2, 240, 200), null);
		index.addPrice(new PriceRecordInnerRecordSpecific(3, 3, 20, 1, 360, 300), null);
		return index;
	}

	/** Builds a fresh formula exposing the index's entity and price inputs. */
	@Nonnull
	static PriceIndexContainerFormula container(@Nonnull PriceListAndCurrencyPriceSuperIndex index) {
		return new PriceIndexContainerFormula(index, index, index.getIndexedPriceEntityIdsFormula());
	}

	/** Builds a fresh price histogram computer without a captured sorter lookup. */
	@Nonnull
	static PriceHistogramComputer priceHistogram(@Nonnull PriceListAndCurrencyPriceSuperIndex index) {
		final Formula formula = lowestPriceFormula(index);
		return new PriceHistogramComputer(2, HistogramBehavior.STANDARD, 2, QueryPriceMode.WITH_TAX,
			formula, formula, null);
	}

	/** Builds a fresh lowest-price formula collecting per-inner-record prices over the passed index. */
	@Nonnull
	static LowestPriceTerminationFormula lowestPriceFormula(@Nonnull PriceListAndCurrencyPriceSuperIndex index) {
		return new LowestPriceTerminationFormula(container(index),
			new PriceEvaluationContext(null, index.getPriceIndexKey()), QueryPriceMode.WITH_TAX,
			PricePredicate.ALL_RECORD_FILTER, true);
	}

	/** Builds a histogram request over a fresh filter index holding two records of an integer attribute. */
	@Nonnull
	static AttributeHistogramProducer.AttributeHistogramRequest attributeHistogramRequest() {
		final AttributeSchema schema = AttributeSchema._internalBuild(
			"amount", null, null, Map.of(), Set.of(), Map.of(), Set.of(), false, false, false,
			Integer.class, null, 0, ConflictResolutionOverride.INHERITED);
		final FilterIndex attributes = new OwnerFilterIndex(new AttributeIndexKey(null, "amount", null), Integer.class);
		attributes.addRecord(10, 1000);
		attributes.addRecord(20, 2000);
		return new AttributeHistogramProducer.AttributeHistogramRequest(schema, 2, HistogramBehavior.STANDARD,
			Comparator.naturalOrder(), List.of(attributes));
	}

	/** Builds a fresh attribute histogram computer over both records of the request's filter index. */
	@Nonnull
	static AttributeHistogramComputer attributeHistogram(
		@Nonnull AttributeHistogramProducer.AttributeHistogramRequest request
	) {
		return new AttributeHistogramComputer("amount", new ConstantFormula(new BaseBitmap(10, 20)), 2,
			HistogramBehavior.STANDARD, request);
	}
}
