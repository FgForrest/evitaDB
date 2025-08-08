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

package io.evitadb.api.requestResponse.transaction;

import io.evitadb.api.EvitaContract;
import io.evitadb.api.exception.InvalidMutationException;
import io.evitadb.api.requestResponse.cdc.ChangeCaptureContent;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCapture;
import io.evitadb.api.requestResponse.cdc.Operation;
import io.evitadb.api.requestResponse.mutation.CatalogBoundMutation;
import io.evitadb.api.requestResponse.mutation.EngineMutation;
import io.evitadb.api.requestResponse.mutation.MutationPredicate;
import io.evitadb.api.requestResponse.mutation.MutationPredicateContext;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictKey;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * This transaction mutation delimits mutations of one transaction from another. It contains data that allow to recognize
 * the scope of the transaction and verify its integrity.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@EqualsAndHashCode
public non-sealed class TransactionMutation implements EngineMutation<Void>, CatalogBoundMutation {
	@Serial private static final long serialVersionUID = -8039363287149601917L;
	/**
	 * Represents the unique identifier of a transaction.
	 */
	@Getter protected final UUID transactionId;
	/**
	 * Represents the next version the transaction transitions the state to.
	 */
	@Getter protected final long version;
	/**
	 * Represents the number of mutations in this particular transaction.
	 */
	@Getter protected final int mutationCount;
	/**
	 * Represents the size of the serialized transaction mutations that follow this mutation in bytes.
	 */
	@Getter protected final long walSizeInBytes;
	/**
	 * Represents the timestamp of the commit.
	 */
	@Getter protected final OffsetDateTime commitTimestamp;

	public TransactionMutation(
		@Nonnull UUID transactionId,
		long version,
		int mutationCount,
		long walSizeInBytes,
		@Nonnull OffsetDateTime commitTimestamp
	) {
		this.transactionId = transactionId;
		this.version = version;
		this.mutationCount = mutationCount;
		this.walSizeInBytes = walSizeInBytes;
		this.commitTimestamp = commitTimestamp;
	}

	@Nonnull
	@Override
	public Operation operation() {
		return Operation.TRANSACTION;
	}

	@Nonnull
	@Override
	public Class<Void> getProgressResultType() {
		return Void.class;
	}

	@Nonnull
	@Override
	public Stream<ConflictKey> getConflictKeys() {
		return Stream.empty();
	}

	@Nonnull
	@Override
	public Stream<ChangeCatalogCapture> toChangeCatalogCapture(
		@Nonnull MutationPredicate predicate,
		@Nonnull ChangeCaptureContent content
	) {
		if (predicate.test(this)) {
			final MutationPredicateContext context = predicate.getContext();
			context.setVersion(this.version, this.mutationCount);

			return Stream.of(
				ChangeCatalogCapture.infrastructureCapture(context, operation(), content == ChangeCaptureContent.BODY ? this : null)
			);
		} else {
			return Stream.empty();
		}
	}

	@Override
	public void verifyApplicability(@Nonnull EvitaContract evita) throws InvalidMutationException {
		// do nothing
	}

	@Override
	public String toString() {
		return "transaction commit `" + this.transactionId + "` (moves persistent state to version `" + this.version + "`)";
	}
}
