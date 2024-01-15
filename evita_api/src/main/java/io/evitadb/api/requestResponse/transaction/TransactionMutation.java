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
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.api.requestResponse.transaction;

import io.evitadb.api.requestResponse.mutation.Mutation;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.util.UUID;

/**
 * TODO JNO - document me
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@EqualsAndHashCode
public final class TransactionMutation implements Mutation {
	@Serial private static final long serialVersionUID = -8039363287149601917L;
	@Getter private final UUID transactionId;
	@Getter private final long catalogVersion;
	@Getter private final int mutationCount;
	@Getter private final long walSizeInBytes;

	public TransactionMutation(
		@Nonnull UUID transactionId,
		long catalogVersion,
		int mutationCount,
		long walSizeInBytes
	) {
		this.transactionId = transactionId;
		this.catalogVersion = catalogVersion;
		this.mutationCount = mutationCount;
		this.walSizeInBytes = walSizeInBytes;
	}

	@Override
	public String toString() {
		return "transaction commit `" + transactionId + "` (moves catalog to version `" + catalogVersion + "`)";
	}
}
