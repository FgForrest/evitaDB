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
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
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
 * JMH benchmark state providing histogram bucket data and attribute filter indices for histogram
 * benchmarks ({@link io.evitadb.spike.FormulaCostMeasurement#histogramBitmapSupplier},
 * {@link io.evitadb.spike.FormulaCostMeasurement#attributeHistogramComputer}).
 *
 * Generates {@link #BUCKET_COUNT} value-to-record buckets with ~{@link #VALUE_COUNT}/{@link #BUCKET_COUNT}
 * records each (monotonically increasing record IDs with small random gaps). For the attribute
 * histogram benchmark, 5 {@link FilterIndex} instances are created with the same bucket structure
 * but 1/5th of the value count each. A separate entity ID bitmap of {@link #VALUE_COUNT} entries
 * serves as the filtering baseline.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@State(Scope.Benchmark)
public class BucketsRecordState {
	/** Number of histogram buckets (value-to-record mappings). */
	private static final int BUCKET_COUNT = 2000;
	/** Total number of records across all buckets. */
	private static final int VALUE_COUNT = 100_000;
	private static final Random random = new Random(42);
	@Getter private ValueToRecordBitmap[] buckets;
	@Getter private Bitmap entityIds;
	@Getter private AttributeHistogramRequest request;
	@Getter private Formula formula;

	/**
	 * Generates histogram buckets, filter indices, entity ID bitmap, and wraps IDs in
	 * a {@link ConstantFormula}.
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
