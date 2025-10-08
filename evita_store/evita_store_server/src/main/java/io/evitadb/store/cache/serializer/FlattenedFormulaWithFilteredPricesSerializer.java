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
import io.evitadb.core.cache.payload.FlattenedFormulaWithFilteredPrices;
import io.evitadb.core.query.algebra.price.filteredPriceRecords.FilteredPriceRecords;
import io.evitadb.core.query.algebra.price.termination.PriceEvaluationContext;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.bitmap.Bitmap;
import lombok.RequiredArgsConstructor;

import java.util.function.Supplier;

/**
 * This {@link Serializer} implementation reads/writes {@link FlattenedFormulaWithFilteredPrices} from/to binary format.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@RequiredArgsConstructor
public class FlattenedFormulaWithFilteredPricesSerializer extends AbstractFlattenedFormulaSerializer<FlattenedFormulaWithFilteredPrices> {
	private final Supplier<GlobalEntityIndex> globalEntityIndexAccessor;

	@Override
	public void write(Kryo kryo, Output output, FlattenedFormulaWithFilteredPrices object) {
		output.writeLong(object.getRecordHash());
		output.writeLong(object.getTransactionalIdHash());
		writeBitmapIds(output, object.getTransactionalDataIds());
		writeIntegerBitmap(output, object.compute());
		writePriceEvaluationContext(kryo, output, object.getPriceEvaluationContext());
		writeFilteredPriceRecords(kryo, output, object.getFilteredPriceRecordsOrThrowException());
	}

	@Override
	public FlattenedFormulaWithFilteredPrices read(Kryo kryo, Input input, Class<? extends FlattenedFormulaWithFilteredPrices> type) {
		final long originalHash = input.readLong();
		final long transactionalIdHash = input.readLong();
		final long[] bitmapIds = readBitmapIds(input);
		final Bitmap computedResult = readIntegerBitmap(input);
		final PriceEvaluationContext priceEvaluationContext = readPriceEvaluationContext(kryo, input);
		final FilteredPriceRecords filteredPriceRecords = readFilteredPriceRecords(kryo, input, this.globalEntityIndexAccessor, priceEvaluationContext);

		return new FlattenedFormulaWithFilteredPrices(
			originalHash, transactionalIdHash, bitmapIds, computedResult,
			filteredPriceRecords, priceEvaluationContext
		);
	}

}
