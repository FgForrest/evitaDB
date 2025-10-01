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

package io.evitadb.core.transaction.stage;

import io.evitadb.api.exception.TransactionException;
import io.evitadb.core.transaction.TransactionManager;
import io.evitadb.utils.Assert;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import java.util.Objects;
import java.util.concurrent.Flow;
import java.util.concurrent.Flow.Subscription;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SubmissionPublisher;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;

/**
 * Abstract class representing a transaction stage in a catalog processing pipeline.
 * It is a {@link Flow} processor that receives a specific type of transaction task, processes it, and produces
 * a different type of transaction task.
 *
 * @param <T> The type of the input transaction task.
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@Slf4j
public sealed abstract class AbstractTransactionStage<T extends TransactionTask>
	implements Flow.Subscriber<T>
	permits ConflictResolutionTransactionStage, WalAppendingTransactionStage, TrunkIncorporationTransactionStage {

	/**
	 * Reference to transactional manager which is a singleton per catalog, and maintains
	 */
	protected final TransactionManager transactionManager;
	/**
	 * The subscription variable represents a subscription to a reactive stream.
	 * It is used to manage the flow of data from the publisher to the subscriber.
	 *
	 * @see Flow.Subscription
	 */
	private Flow.Subscription subscription;
	/**
	 * Represents the "lag" of the next stage observed during {@link SubmissionPublisher#offer(Object, BiPredicate)}.
	 *
	 * @see SubmissionPublisher#offer(Object, BiPredicate)
	 */
	@Getter private volatile int stageHandoff = 0;
	/**
	 * Contains TRUE if the processor has been completed and does not accept any more data.
	 */
	@Getter private boolean completed;
	/**
	 * Handler that is called on any exception.
	 */
	@Nonnull private final BiConsumer<TransactionTask, Throwable> onException;

	protected AbstractTransactionStage(
		@Nonnull TransactionManager transactionManager,
		@Nonnull BiConsumer<TransactionTask, Throwable> onException
	) {
		this.transactionManager = transactionManager;
		this.onException = onException;
	}

	@Override
	public void onSubscribe(Subscription subscription) {
		this.subscription = subscription;
		this.subscription.request(1);
	}

	@Override
	public final void onNext(T task) {
		try {
			Assert.isPremiseValid(
				Objects.equals(this.transactionManager.getCatalogName(), task.catalogName()),
				"Catalog name mismatch!"
			);
			// delegate handling logic to the concrete implementation
			handleNext(task);
		} catch (Throwable ex) {
			// if the handle exception throws an exception, we need to handle it, and go on
			try {
				handleException(task, ex);
			} catch (Throwable e) {
				log.error(
					"Error while handling exception in {} task for catalog `{}`!", getName(), task.catalogName(), e);
			}
		}
		this.subscription.request(1);
	}

	/**
	 * Handles the exception thrown during the processing of the transaction task.
	 * @param task The task that caused the exception.
	 * @param ex The exception that was thrown.
	 */
	protected void handleException(@Nonnull T task, @Nonnull Throwable ex) {
		log.error("Error while processing {} task for catalog `{}`!", getName(), task.catalogName(), ex);
		task.commitProgress().completeExceptionally(ex);
		this.onException.accept(task, ex);
	}

	@Override
	public final void onError(Throwable throwable) {
		log.error(
			"Fatal error! Error propagated outside catalog `{}` transaction stage! This is unexpected and effectively stops transaction processing!",
			this.transactionManager.getCatalogName(), throwable
		);
	}

	@Override
	public final void onComplete() {
		log.debug("Transaction stage completed for catalog `{}`!", this.transactionManager.getCatalogName());
		this.completed = true;
	}

	/**
	 * Retrieves the name of the transaction stage. The name is used in logs and exceptions.
	 *
	 * @return The name of the transaction stage as a String.
	 */
	protected abstract String getName();

	/**
	 * Handles the next transaction task. It converts the source task to the target task and pushes it to the next
	 * transaction stage. During the transformation all necessary actions are performed.
	 *
	 * @param task The task to be handled.
	 */
	protected abstract void handleNext(@Nonnull T task);

	/**
	 * Pushes a target task to the next transaction stage.
	 * If the target task's future is null, it completes the future with a new catalog version.
	 *
	 * @param sourceTask The source task to be pushed.
	 * @param targetTask The target task to be created.
	 * @param publisher The publisher to which the target task is pushed.
	 */
	protected <F extends TransactionTask> void push(@Nonnull T sourceTask, @Nonnull F targetTask, @Nonnull SubmissionPublisher<F> publisher) {
		this.stageHandoff = publisher.offer(
			targetTask,
			(subscriber, theTask) -> {
				final String text = targetTask.getClass().isAnnotationPresent(NonRepeatableTask.class) ?
					" - some committed data will be lost" : "";
				handleException(
					sourceTask,
					new TransactionException(
						"The task " + getName() + " is completed, but cannot push " + targetTask.getClass().getSimpleName() +
							" to next stage" + text + ".",
						new RejectedExecutionException()
					)
				);
				return false;
			}
		);
	}

}
