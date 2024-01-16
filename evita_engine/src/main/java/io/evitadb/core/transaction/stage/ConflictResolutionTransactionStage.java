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
import io.evitadb.core.Evita;
import io.evitadb.store.spi.OffHeapWithFileBackupReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * TODO JNO - document me
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@Slf4j
@RequiredArgsConstructor
public class ConflictResolutionTransactionStage implements TransactionStage {
	private final Evita evita;
	private final WalAppendingTransactionStage walAppendingTransactionStage;
	private final BlockingQueue<ConflictResolutionTransactionTask> queue = new ArrayBlockingQueue<>(1024);
	private final ConcurrentHashMap<String, AtomicLong> catalogVersions = new ConcurrentHashMap<>(32);

	public void processQueue() {
		while (true) {
			try {
				final ConflictResolutionTransactionTask task = queue.take();
				try {
					// identify conflicts with other transactions
					// TODO JNO - implement me
					// assign new catalog version
					final long newCatalogVersion = catalogVersions.computeIfAbsent(
						task.catalogName(),
						theCatalogName -> new AtomicLong(
							evita.getCatalogInstanceOrThrowException(theCatalogName).getVersion()
						)
					).incrementAndGet();

					walAppendingTransactionStage.submit(
						task.catalogName(),
						task.transactionId(),
						task.mutationCount(),
						task.walSizeInBytes(),
						task.walReference(),
						task.commitBehaviour(),
						newCatalogVersion,
						task.commitBehaviour() != CommitBehaviour.NO_WAIT ? task.future() : null
					);

					if (task.commitBehaviour() == CommitBehaviour.NO_WAIT) {
						task.future().complete(newCatalogVersion);
					}
				} catch (Throwable ex) {
					task.future.completeExceptionally(ex);
					throw ex;
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			} catch (Throwable e) {
				log.error("Error while processing conflict resolution task!", e);
			}
		}
	}

	@Nonnull
	public CompletableFuture<Long> submit(
		@Nonnull String catalogName,
		@Nonnull UUID transactionId,
		int mutationCount,
		long walSizeInBytes,
		@Nonnull OffHeapWithFileBackupReference walReference,
		@Nonnull CommitBehaviour commitBehaviour
	) {
		final CompletableFuture<Long> future = new CompletableFuture<>();
		final boolean accepted = queue.offer(
			new ConflictResolutionTransactionTask(
				catalogName,
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
				"Transaction timed out - conflict resolution reached maximal capacity (" + queue.size() + ")!"
			);
		}
		return future;
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
	public record ConflictResolutionTransactionTask(
		@Nonnull String catalogName,
		@Nonnull UUID transactionId,
		int mutationCount,
		long walSizeInBytes,
		@Nonnull OffHeapWithFileBackupReference walReference,
		@Nonnull CommitBehaviour commitBehaviour,
		@Nonnull CompletableFuture<Long> future
	) {
	}

}
