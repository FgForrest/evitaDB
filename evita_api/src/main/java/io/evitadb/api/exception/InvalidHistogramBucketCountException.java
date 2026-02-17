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

package io.evitadb.api.exception;

import io.evitadb.exception.EvitaInvalidUsageException;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.io.Serial;

/**
 * Exception thrown when a histogram computation is requested with an invalid number of buckets.
 *
 * Histograms must have at least two buckets to be meaningful — a histogram with fewer than two buckets
 * cannot represent any distribution. This exception enforces that constraint, typically during query
 * processing when histogram requirements are validated.
 *
 * **Valid Bucket Counts:**
 * - Minimum: 2 buckets
 * - Common values: 10-20 buckets for price histograms, attribute histograms, etc.
 *
 * **Usage Context:**
 * - {@link io.evitadb.core.query.extraResult.translator.histogram.producer.HistogramDataCruncher}: validates bucket count for standard histograms
 * - {@link io.evitadb.core.query.extraResult.translator.histogram.producer.EqualizedHistogramDataCruncher}: validates bucket count for optimized histograms
 * - Thrown during query execution when processing histogram-related {@link io.evitadb.api.query.require.HistogramBehavior} requirements
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class InvalidHistogramBucketCountException extends EvitaInvalidUsageException {
	@Serial private static final long serialVersionUID = -30550533918724813L;
	/**
	 * The invalid bucket count that was requested.
	 */
	@Getter private final int bucketCount;

	/**
	 * Creates a new exception indicating that an invalid histogram bucket count was requested.
	 *
	 * @param histogramType human-readable description of the histogram type (e.g., "price histogram", "attribute histogram")
	 * @param bucketCount the invalid bucket count that was requested (typically 0 or 1)
	 */
	public InvalidHistogramBucketCountException(@Nonnull String histogramType, int bucketCount) {
		super("In order to compute " + histogramType + " at least two buckets must be requested, but requested was only `" + bucketCount + "`.");
		this.bucketCount = bucketCount;
	}

}
