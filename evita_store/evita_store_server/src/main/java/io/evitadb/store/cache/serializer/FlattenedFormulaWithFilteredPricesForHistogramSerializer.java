/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

package io.evitadb.store.cache.serializer;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.api.query.require.QueryPriceMode;
import io.evitadb.core.cache.payload.FlattenedFormulaWithFilteredPricesForHistogram;
import io.evitadb.core.query.algebra.price.filteredPriceRecords.FilteredPriceRecords;
import io.evitadb.core.query.algebra.price.termination.PriceEvaluationContext;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.bitmap.Bitmap;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.util.function.Supplier;

/**
 * This {@link Serializer} implementation reads/writes {@link FlattenedFormulaWithFilteredPricesForHistogram}
 * from/to the binary cache format. The sibling payload carries an additional per-inner-record
 * {@link FilteredPriceRecords} collection alongside the per-entity records inherited from the parent payload
 * — both arrays must round-trip through the cache without loss.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@RequiredArgsConstructor
public class FlattenedFormulaWithFilteredPricesForHistogramSerializer
	extends AbstractFlattenedFormulaSerializer<FlattenedFormulaWithFilteredPricesForHistogram> {

	private final Supplier<GlobalEntityIndex> globalEntityIndexAccessor;

	@Override
	public void write(Kryo kryo, Output output, FlattenedFormulaWithFilteredPricesForHistogram object) {
		output.writeLong(object.getRecordHash());
		output.writeLong(object.getTransactionalIdHash());
		kryo.writeObjectOrNull(output, object.getQueryPriceMode(), QueryPriceMode.class);
		kryo.writeObjectOrNull(output, object.getFrom(), BigDecimal.class);
		kryo.writeObjectOrNull(output, object.getTo(), BigDecimal.class);
		output.writeVarInt(object.getIndexedPricePlaces(), true);
		writeBitmapIds(output, object.getTransactionalDataIds());
		writeIntegerBitmap(output, object.compute());
		writePriceEvaluationContext(kryo, output, object.getPriceEvaluationContext());
		writeFilteredPriceRecords(kryo, output, object.getFilteredPriceRecordsOrThrowException());
		writeIntegerBitmap(output, object.getRecordsFilteredOutByPredicate());
		// additional per-inner-record records — the histogram-side accessor distinguishes this sibling
		// payload from the plain `FlattenedFormulaWithFilteredPricesAndFilteredOutRecords`; the context-free
		// accessor is used here because the serializer has no QueryExecutionContext available
		writeFilteredPriceRecords(kryo, output, object.getPerInnerRecordPriceRecords());
	}

	@Override
	public FlattenedFormulaWithFilteredPricesForHistogram read(
		Kryo kryo,
		Input input,
		Class<? extends FlattenedFormulaWithFilteredPricesForHistogram> type
	) {
		final long originalHash = input.readLong();
		final long transactionalIdHash = input.readLong();
		final QueryPriceMode queryPriceMode = kryo.readObjectOrNull(input, QueryPriceMode.class);
		final BigDecimal from = kryo.readObjectOrNull(input, BigDecimal.class);
		final BigDecimal to = kryo.readObjectOrNull(input, BigDecimal.class);
		final int indexedPricePlaces = input.readVarInt(true);

		final long[] bitmapIds = readBitmapIds(input);
		final Bitmap computedResult = readIntegerBitmap(input);
		final PriceEvaluationContext priceEvaluationContext = readPriceEvaluationContext(kryo, input);
		final FilteredPriceRecords filteredPriceRecords = readFilteredPriceRecords(
			kryo, input, this.globalEntityIndexAccessor, priceEvaluationContext
		);
		final Bitmap recordsFilteredOutByPredicate = readIntegerBitmap(input);
		final FilteredPriceRecords perInnerRecordPriceRecords = readFilteredPriceRecords(
			kryo, input, this.globalEntityIndexAccessor, priceEvaluationContext
		);

		return new FlattenedFormulaWithFilteredPricesForHistogram(
			originalHash, transactionalIdHash, bitmapIds, computedResult,
			filteredPriceRecords, perInnerRecordPriceRecords, recordsFilteredOutByPredicate,
			priceEvaluationContext, queryPriceMode, from, to, indexedPricePlaces
		);
	}

}
