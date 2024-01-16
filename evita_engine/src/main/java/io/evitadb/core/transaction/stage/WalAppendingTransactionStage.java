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

package io.evitadb.core.transaction.stage;

import io.evitadb.api.TransactionContract.CommitBehaviour;
import io.evitadb.api.exception.TransactionTimedOutException;
import io.evitadb.api.requestResponse.transaction.TransactionMutation;
import io.evitadb.core.Catalog;
import io.evitadb.core.Evita;
import io.evitadb.store.spi.OffHeapWithFileBackupReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;

/**
 * TODO JNO - document me
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@Slf4j
@RequiredArgsConstructor
public class WalAppendingTransactionStage implements TransactionStage {
	private final Evita evita;
	private final TrunkIncorporationTransactionStage trunkIncorporationTransactionStage;
	private final BlockingQueue<WalAppendingTransactionTask> queue = new ArrayBlockingQueue<>(1024);

	public void processQueue() {
		while (true) {
			try {
				final WalAppendingTransactionTask task = queue.take();
				// TODO JNO - možná se nějak vyhnout castu?!
				try {
					final Catalog catalog = (Catalog) evita.getCatalogInstanceOrThrowException(task.catalogName());
					catalog.appendWalAndDiscard(
						new TransactionMutation(
							task.transactionId(),
							task.catalogVersion(),
							task.mutationCount(),
							task.walSizeInBytes()
						),
						task.walReference()
					);

					trunkIncorporationTransactionStage.submit(
						task.catalogName(),
						task.transactionId(),
						task.catalogVersion(),
						task.commitBehaviour(),
						task.commitBehaviour() != CommitBehaviour.WAIT_FOR_LOG_PERSISTENCE ? task.future() : null
					);

					if (task.commitBehaviour() == CommitBehaviour.WAIT_FOR_LOG_PERSISTENCE && task.future() != null) {
						task.future().complete(task.catalogVersion());
					}
				} catch (Throwable ex) {
					task.future.completeExceptionally(ex);
					throw ex;
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			} catch (Throwable e) {
				log.error("Error while processing WAL appender task!", e);
			}
		}
	}

	public void submit(
		@Nonnull String catalogName,
		@Nonnull UUID transactionId,
		int mutationCount,
		long walSizeInBytes,
		@Nonnull OffHeapWithFileBackupReference walReference,
		@Nonnull CommitBehaviour commitBehaviour,
		long catalogVersion,
		@Nullable CompletableFuture<Long> future
	) {
		final boolean accepted = queue.offer(
			new WalAppendingTransactionTask(
				catalogName,
				catalogVersion,
				transactionId,
				mutationCount,
				walSizeInBytes,
				walReference,
				commitBehaviour,
				future
			)
		);
		if (!accepted) {
			throw new TransactionTimedOutException(
				"Transaction timed out - WAL appender reached maximal capacity (" + queue.size() + ")!"
			);
		}
	}

	@Nonnull
	@Override
	public CompletableFuture<Void> removeCatalog() {
		// TODO JNO - implement
		return null;
	}

	/**
	 * TODO JNO - document me
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
	 */
	public record WalAppendingTransactionTask(
		@Nonnull String catalogName,
		long catalogVersion,
		@Nonnull UUID transactionId,
		int mutationCount,
		long walSizeInBytes,
		@Nonnull OffHeapWithFileBackupReference walReference,
		@Nonnull CommitBehaviour commitBehaviour,
		@Nullable CompletableFuture<Long> future
	) {
	}

}
