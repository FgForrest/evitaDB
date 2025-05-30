/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2025
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

import io.evitadb.api.requestResponse.transaction.TransactionMutation;
import io.evitadb.store.model.FileLocation;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.io.Serial;

/**
 * Represents a TransactionMutation with additional location information.
 */
public class TransactionMutationWithLocation extends TransactionMutation {
	@Serial private static final long serialVersionUID = -5873907941292188132L;
	@Nonnull @Getter
	private final FileLocation transactionSpan;
	@Getter
	private final int walFileIndex;

	public TransactionMutationWithLocation(@Nonnull TransactionMutation delegate, @Nonnull FileLocation transactionSpan, int walFileIndex) {
		super(
			delegate.getTransactionId(),
			delegate.getVersion(),
			delegate.getMutationCount(),
			delegate.getWalSizeInBytes(),
			delegate.getCommitTimestamp()
		);
		this.transactionSpan = transactionSpan;
		this.walFileIndex = walFileIndex;
	}

}
