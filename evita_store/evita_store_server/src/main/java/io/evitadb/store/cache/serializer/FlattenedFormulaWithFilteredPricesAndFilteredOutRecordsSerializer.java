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

package io.evitadb.store.cache.serializer;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.api.query.require.QueryPriceMode;
import io.evitadb.core.cache.payload.FlattenedFormulaWithFilteredPricesAndFilteredOutRecords;
import io.evitadb.core.query.algebra.price.filteredPriceRecords.FilteredPriceRecords;
import io.evitadb.core.query.algebra.price.termination.PriceEvaluationContext;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.bitmap.Bitmap;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.util.function.Supplier;

/**
 * This {@link Serializer} implementation reads/writes {@link FlattenedFormulaWithFilteredPricesAndFilteredOutRecords} from/to binary format.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@RequiredArgsConstructor
public class FlattenedFormulaWithFilteredPricesAndFilteredOutRecordsSerializer extends AbstractFlattenedFormulaSerializer<FlattenedFormulaWithFilteredPricesAndFilteredOutRecords> {
	private final Supplier<GlobalEntityIndex> globalEntityIndexAccessor;

	@Override
	public void write(Kryo kryo, Output output, FlattenedFormulaWithFilteredPricesAndFilteredOutRecords object) {
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
	}

	@Override
	public FlattenedFormulaWithFilteredPricesAndFilteredOutRecords read(Kryo kryo, Input input, Class<? extends FlattenedFormulaWithFilteredPricesAndFilteredOutRecords> type) {
		final long originalHash = input.readLong();
		final long transactionalIdHash = input.readLong();
		final QueryPriceMode queryPriceMode = kryo.readObjectOrNull(input, QueryPriceMode.class);
		final BigDecimal from = kryo.readObjectOrNull(input, BigDecimal.class);
		final BigDecimal to = kryo.readObjectOrNull(input, BigDecimal.class);
		final int indexedPricePlaces = input.readVarInt(true);

		final long[] bitmapIds = readBitmapIds(input);
		final Bitmap computedResult = readIntegerBitmap(input);
		final PriceEvaluationContext priceEvaluationContext = readPriceEvaluationContext(kryo, input);
		final FilteredPriceRecords filteredPriceRecords = readFilteredPriceRecords(kryo, input, this.globalEntityIndexAccessor, priceEvaluationContext);
		final Bitmap recordsFilteredOutByPredicate = readIntegerBitmap(input);

		return new FlattenedFormulaWithFilteredPricesAndFilteredOutRecords(
			originalHash, transactionalIdHash, bitmapIds, computedResult,
			filteredPriceRecords, recordsFilteredOutByPredicate, priceEvaluationContext,
			queryPriceMode, from, to, indexedPricePlaces
		);
	}

}
