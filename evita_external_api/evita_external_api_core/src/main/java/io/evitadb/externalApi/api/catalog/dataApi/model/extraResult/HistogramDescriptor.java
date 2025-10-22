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

package io.evitadb.externalApi.api.catalog.dataApi.model.extraResult;

import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;

import java.math.BigDecimal;
import java.util.List;

import static io.evitadb.externalApi.api.model.TypePropertyDataTypeDescriptor.nonNullListRef;
import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nonNull;

/**
 * Represents {@link io.evitadb.api.requestResponse.extraResult.HistogramContract}.
 *
 * Note: this descriptor has static structure.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public interface HistogramDescriptor {

	PropertyDescriptor MIN = PropertyDescriptor.builder()
		.name("min")
		.description("""
			Returns left bound of the first bucket. It represents the smallest value encountered in the returned set.
			""")
		.type(nonNull(BigDecimal.class))
		.build();
	PropertyDescriptor MAX = PropertyDescriptor.builder()
		.name("max")
		.description("""
			Returns right bound of the last bucket of the histogram. Each bucket contains only left bound threshold, so this
			value is necessary so that first histogram buckets makes any sense. This value is exceptional in the sense that
			it represents the biggest value encountered in the returned set and represents inclusive right bound for the
			last bucket.
			""")
		.type(nonNull(BigDecimal.class))
		.build();
	PropertyDescriptor OVERALL_COUNT = PropertyDescriptor.builder()
		.name("overallCount")
		.description("""
			Returns count of all entities that are covered by this histogram. It's plain sum of occurrences of all buckets
			in the histogram.
			""")
		.type(nonNull(Integer.class))
		.build();
	PropertyDescriptor BUCKETS = PropertyDescriptor.builder()
		.name("buckets")
		.description("""
			Returns histogram buckets that represents a tuple of occurrence count and the minimal threshold of the bucket
			values.
			""")
		.type(nonNullListRef(BucketDescriptor.THIS))
		.build();

	ObjectDescriptor THIS = ObjectDescriptor.builder()
		.name("Histogram")
		.description("""
			A histogram is an approximate representation of the distribution of numerical data. For detailed description please
			see [Wikipedia](https://en.wikipedia.org/wiki/Histogram).
			Histogram can be computed only for numeric based properties. It visualises which property values are more common
			in the returned data set and which are rare. Bucket count will never exceed requested bucket count but there
			may be less of them if there is no enough data for computation. Bucket thresholds are specified heuristically so tha
			there are as few "empty buckets" as possible.
			                
			- buckets are defined by their lower bounds (inclusive)
			- the upper bound is the lower bound of the next bucket
			""")
		.staticProperties(List.of(MIN, MAX, OVERALL_COUNT, BUCKETS))
		.build();

	/**
	 * Represents {@link io.evitadb.api.requestResponse.extraResult.HistogramContract.Bucket}.
	 *
	 * Note: this descriptor has static structure.
	 */
	interface BucketDescriptor {

		PropertyDescriptor THRESHOLD = PropertyDescriptor.builder()
			.name("threshold")
			.description("""
				Contains threshold (left bound - inclusive) of the bucket.
				""")
			.type(nonNull(BigDecimal.class))
			.build();
		PropertyDescriptor OCCURRENCES = PropertyDescriptor.builder()
			.name("occurrences")
			.description("""
				Contains number of entity occurrences in this bucket - e.g. number of entities that has monitored property value
				between previous bucket threshold (exclusive) and this bucket threshold (inclusive)
				""")
			.type(nonNull(Integer.class))
			.build();
		PropertyDescriptor REQUESTED = PropertyDescriptor.builder()
			.name("requested")
			.description("""
				Contains true if the query contained `attributeBetween` or `priceBetween`
				constraint for particular attribute / price and the bucket threshold lies within the range
				(inclusive) of the constraint. False otherwise.
				""")
			.type(nonNull(Boolean.class))
			.build();


		ObjectDescriptor THIS = ObjectDescriptor.builder()
			.name("HistogramBucket")
			.description("""
				Data object that carries out threshold in histogram (or bucket if you will) along with number of occurrences in it.
				""")
			.staticProperties(List.of(THRESHOLD, OCCURRENCES, REQUESTED))
			.build();
	}
}
