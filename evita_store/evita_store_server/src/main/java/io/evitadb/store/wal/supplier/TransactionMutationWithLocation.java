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

import io.evitadb.api.requestResponse.cdc.ChangeCaptureContent;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCapture;
import io.evitadb.api.requestResponse.cdc.ChangeSystemCapture;
import io.evitadb.api.requestResponse.mutation.MutationPredicate;
import io.evitadb.api.requestResponse.mutation.MutationPredicateContext;
import io.evitadb.api.requestResponse.transaction.TransactionMutation;
import io.evitadb.store.model.FileLocation;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.util.stream.Stream;

/**
 * Represents a TransactionMutation with additional location information.
 */
public class TransactionMutationWithLocation extends TransactionMutation {
	@Serial private static final long serialVersionUID = -5873907941292188132L;
	@Nonnull @Getter
	private final FileLocation transactionSpan;
	@Getter
	private final int walFileIndex;

	public TransactionMutationWithLocation(
		@Nonnull TransactionMutation delegate,
		@Nonnull FileLocation transactionSpan,
		int walFileIndex
	) {
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

	@Nonnull
	@Override
	public Stream<ChangeSystemCapture> toChangeSystemCapture(
		@Nonnull MutationPredicate predicate,
		@Nonnull ChangeCaptureContent content
	) {
		if (predicate.test(this)) {
			return Stream.of(
				ChangeSystemCapture.systemCapture(
					predicate.getContext(),
					operation(),
					content == ChangeCaptureContent.BODY ?
						// we need to strip the transactionSpan and walFileIndex from the transaction mutation
						// because it's internal information and not part of the mutation itself, the type in
						// ChangeCatalogCapture needs to be TransactionMutation, so that the conversion logic can find
						// appropriate conversion instance by this particular type
						new TransactionMutation(
							this.getTransactionId(),
							this.getVersion(),
							this.getMutationCount(),
							this.getWalSizeInBytes(),
							this.getCommitTimestamp()
						) :
						null
				)
			);
		} else {
			return Stream.empty();
		}
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
				ChangeCatalogCapture.infrastructureCapture(
					context,
					operation(),
					content == ChangeCaptureContent.BODY ?
						// we need to strip the transactionSpan and walFileIndex from the transaction mutation
						// because it's internal information and not part of the mutation itself, the type in
						// ChangeCatalogCapture needs to be TransactionMutation, so that the conversion logic can find
						// appropriate conversion instance by this particular type
						new TransactionMutation(
							this.getTransactionId(),
							this.getVersion(),
							this.getMutationCount(),
							this.getWalSizeInBytes(),
							this.getCommitTimestamp()
						) :
						null
				)
			);
		} else {
			return Stream.empty();
		}
	}

}
