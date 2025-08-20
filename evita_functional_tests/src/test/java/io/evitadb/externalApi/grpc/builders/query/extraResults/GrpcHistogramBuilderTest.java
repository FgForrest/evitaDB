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

package io.evitadb.externalApi.grpc.builders.query.extraResults;

import io.evitadb.api.requestResponse.extraResult.AttributeHistogram;
import io.evitadb.api.requestResponse.extraResult.Histogram;
import io.evitadb.api.requestResponse.extraResult.HistogramContract.Bucket;
import io.evitadb.api.requestResponse.extraResult.PriceHistogram;
import io.evitadb.externalApi.grpc.generated.GrpcHistogram;
import io.evitadb.externalApi.grpc.testUtils.GrpcAssertions;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

/**
 * This test verifies functionalities of methods in {@link GrpcHistogramBuilder} class.
 *
 * @author Tomáš Pozler, 2022
 */
class GrpcHistogramBuilderTest {
	private final Histogram histogram = new Histogram(
		new Bucket[]{
			new Bucket(BigDecimal.valueOf(1.5), 3, false),
			new Bucket(BigDecimal.valueOf(2.5), 5, true),
			new Bucket(BigDecimal.valueOf(3.5), 4, true),
			new Bucket(BigDecimal.valueOf(4.8), 6, false),
			new Bucket(BigDecimal.valueOf(8.6), 10, false),
		},
		BigDecimal.valueOf(10)
	);

	@Test
	void buildAttributeHistogram() {
		final String[] types = {"test1", "test2", "test3"};
		final AttributeHistogram attributeHistogram = new AttributeHistogram(
			Map.of(
				types[0], this.histogram,
				types[1], this.histogram,
				types[2], this.histogram
			)
		);

		final Map<String, GrpcHistogram> attributeHistogramMap = GrpcHistogramBuilder.buildAttributeHistogram(attributeHistogram);

		GrpcAssertions.assertAttributeHistograms(attributeHistogram, attributeHistogramMap);
	}

	@Test
	void buildPriceHistogram() {
		final PriceHistogram priceHistogram = new PriceHistogram(this.histogram);
		final GrpcHistogram grpcHistogram = GrpcHistogramBuilder.buildPriceHistogram(priceHistogram);
		GrpcAssertions.assertPriceHistogram(priceHistogram, grpcHistogram);
	}
}
