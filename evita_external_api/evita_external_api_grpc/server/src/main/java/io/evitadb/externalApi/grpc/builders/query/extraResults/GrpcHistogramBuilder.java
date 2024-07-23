/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

package io.evitadb.externalApi.grpc.builders.query.extraResults;

import io.evitadb.api.requestResponse.extraResult.AttributeHistogram;
import io.evitadb.api.requestResponse.extraResult.HistogramContract;
import io.evitadb.api.requestResponse.extraResult.HistogramContract.Bucket;
import io.evitadb.api.requestResponse.extraResult.PriceHistogram;
import io.evitadb.externalApi.grpc.dataType.EvitaDataTypesConverter;
import io.evitadb.externalApi.grpc.generated.GrpcHistogram;
import io.evitadb.externalApi.grpc.generated.GrpcHistogram.GrpcBucket;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * This class is used to build {@link GrpcHistogram}s from either {@link AttributeHistogram} or {@link PriceHistogram}.
 *
 * @author Tomáš Pozler, 2022
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class GrpcHistogramBuilder {
	/**
	 * This method converts {@link AttributeHistogram} to {@link GrpcHistogram}s and returns them in a map where
	 * histogram is specified by attribute name.
	 *
	 * @param histograms  {@link AttributeHistogram} returned by evita response
	 * @return map of histograms where the key is the attribute name and the value is the histogram
	 */
	@Nonnull
	public static Map<String, GrpcHistogram> buildAttributeHistogram(@Nonnull AttributeHistogram histograms) {
		final Map<String, GrpcHistogram> computedHistograms = new HashMap<>();
		for (Map.Entry<String, HistogramContract> histogram : histograms.getHistograms().entrySet()) {
			computedHistograms.put(histogram.getKey(), buildHistogram(histogram.getValue()));
		}
		return computedHistograms;
	}

	/**
	 * This method converts {@link PriceHistogram} to {@link GrpcHistogram} .
	 *
	 * @param histogram {@link PriceHistogram} returned by evita response
	 * @return converted histogram
	 */
	@Nonnull
	public static GrpcHistogram buildPriceHistogram(PriceHistogram histogram) {
		return buildHistogram(histogram);
	}

	/**
	 * This method converts {@link HistogramContract} to {@link GrpcHistogram}.
	 *
	 * @param histogram which could be either {@link PriceHistogram} or one of {@link AttributeHistogram}s.
	 * @return converted histogram
	 */
	@Nonnull
	private static GrpcHistogram buildHistogram(HistogramContract histogram) {
		final Bucket[] originalBuckets = histogram.getBuckets();
		final List<GrpcBucket> buckets = new ArrayList<>(originalBuckets.length);
		Arrays.stream(originalBuckets).forEach(bucket -> buckets.add(GrpcBucket.newBuilder()
			.setThreshold(EvitaDataTypesConverter.toGrpcBigDecimal(bucket.threshold()))
			.setOccurrences(bucket.occurrences())
			.setRequested(bucket.requested())
			.build()));
		return GrpcHistogram.newBuilder()
			.setMin(EvitaDataTypesConverter.toGrpcBigDecimal(histogram.getMin()))
			.setMax(EvitaDataTypesConverter.toGrpcBigDecimal(histogram.getMax()))
			.setOverallCount(histogram.getOverallCount())
			.addAllBuckets(buckets)
			.build();
	}
}
