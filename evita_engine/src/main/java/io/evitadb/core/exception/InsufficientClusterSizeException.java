/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025-2026
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

package io.evitadb.core.exception;

import io.evitadb.exception.EvitaInvalidUsageException;

import java.io.Serial;

/**
 * TODO JNO - document me
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public class InsufficientClusterSizeException extends EvitaInvalidUsageException {
	@Serial private static final long serialVersionUID = 1160024305102522051L;

	public InsufficientClusterSizeException(int clusterSize) {
		super(
			"Insufficient cluster size: " + clusterSize + ". The cluster size must be an odd number greater than or equal to 3 to ensure proper quorum-based operations.",
			"Insufficient cluster size. The cluster size must be an odd number greater than or equal to 3 to ensure proper quorum-based operations."
		);
	}
}
