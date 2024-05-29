/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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

package io.evitadb.store.wal.supplier;

import java.io.Serializable;

/**
 * Represents the location of a transaction in the WAL file and its span.
 *
 * @param catalogVersion         the catalog version
 * @param startPosition          the start position in the WAL file, the first 4B after this position contains overal length of the transaction
 * @param mutationCount          the number of mutations in the transaction
 * @param contentLength          the length of the transaction as specified in transaction mutation
 */
public record TransactionLocation(
	long catalogVersion,
	long startPosition,
	int mutationCount,
	long contentLength
) implements Comparable<TransactionLocation>, Serializable {

	@Override
	public int compareTo(TransactionLocation o) {
		return Long.compare(this.catalogVersion, o.catalogVersion);
	}

}
