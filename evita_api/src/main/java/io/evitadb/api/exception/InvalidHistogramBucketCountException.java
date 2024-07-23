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
 * Exception is thrown when client requests histogram computation and provides bucket count lesser than two. In order
 * histogram has any sense it should have at least two buckets - not less.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class InvalidHistogramBucketCountException extends EvitaInvalidUsageException {
	@Serial private static final long serialVersionUID = -30550533918724813L;
	@Getter private final int bucketCount;

	public InvalidHistogramBucketCountException(@Nonnull String histogramType, int bucketCount) {
		super("In order to compute " + histogramType + " at least two buckets must be requested, but requested was only `" + bucketCount + "`.");
		this.bucketCount = bucketCount;
	}

}
