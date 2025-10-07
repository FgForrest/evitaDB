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

package io.evitadb.spike.mock;

import io.evitadb.api.requestResponse.schema.dto.AttributeSchema;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.extraResult.translator.histogram.producer.AttributeHistogramProducer.AttributeHistogramRequest;
import io.evitadb.dataType.array.CompositeIntArray;
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.index.bitmap.ArrayBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.invertedIndex.ValueToRecordBitmap;
import io.evitadb.index.range.RangeIndex;
import io.evitadb.store.spi.model.storageParts.index.AttributeIndexKey;
import lombok.Getter;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Random;

/**
 * No extra information provided - see (selfexplanatory) method signatures.
 * I have the best intention to write more detailed documentation but if you see this, there was not enough time or will to do so.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@State(Scope.Benchmark)
public class BucketsRecordState {
	private static final int BUCKET_COUNT = 2000;
	private static final int VALUE_COUNT = 100_000;
	private static final Random random = new Random(42);
	@Getter private ValueToRecordBitmap[] buckets;
	@Getter private Bitmap entityIds;
	@Getter private AttributeHistogramRequest request;
	@Getter private Formula formula;

	/**
	 * This setup is called once for each `valueCount`.
	 */
	@Setup(Level.Trial)
	public void setUp() {
		this.entityIds = generateBitmap(VALUE_COUNT, 1);
		this.request = new AttributeHistogramRequest(
			AttributeSchema._internalBuild("whatever", Integer.class, false),
			Comparator.naturalOrder(),
			Arrays.asList(
				new FilterIndex(new AttributeIndexKey(null, "whatever", null), generateBuckets(BUCKET_COUNT, VALUE_COUNT / 5), new RangeIndex(), Integer.class),
				new FilterIndex(new AttributeIndexKey(null, "whatever", null), generateBuckets(BUCKET_COUNT, VALUE_COUNT / 5), new RangeIndex(), Integer.class),
				new FilterIndex(new AttributeIndexKey(null, "whatever", null), generateBuckets(BUCKET_COUNT, VALUE_COUNT / 5), new RangeIndex(), Integer.class),
				new FilterIndex(new AttributeIndexKey(null, "whatever", null), generateBuckets(BUCKET_COUNT, VALUE_COUNT / 5), new RangeIndex(), Integer.class),
				new FilterIndex(new AttributeIndexKey(null, "whatever", null), generateBuckets(BUCKET_COUNT, VALUE_COUNT / 5), new RangeIndex(), Integer.class)
			),
			Collections.emptySet()
		);
		this.buckets = generateBuckets(BUCKET_COUNT, VALUE_COUNT);
		this.formula = new ConstantFormula(this.entityIds);
	}

	private static ValueToRecordBitmap[] generateBuckets(int bucketCount, int valueCount) {
		final ValueToRecordBitmap[] result = new ValueToRecordBitmap[bucketCount];
		int theValue = random.nextInt(100);
		int recId = 1;
		for (int i = 0; i < bucketCount; i++) {
			theValue += random.nextInt(100) + 1;
			final Bitmap recordIds = generateBitmap(valueCount / bucketCount, recId);
			recId = recordIds.getLast();
			result[i] = new ValueToRecordBitmap(theValue, recordIds);
		}
		return result;
	}

	private static Bitmap generateBitmap(int valueCount, int startValue) {
		final CompositeIntArray intArray = new CompositeIntArray();
		final ArrayBitmap bitmap = new ArrayBitmap(intArray);
		int recId = startValue;
		for (int i = 0; i < valueCount; i++) {
			recId += random.nextInt(5);
			bitmap.add(recId);
		}

		return bitmap;
	}

}
