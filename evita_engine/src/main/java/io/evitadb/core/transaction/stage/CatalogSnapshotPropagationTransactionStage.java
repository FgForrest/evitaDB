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

package io.evitadb.core.transaction.stage;

import io.evitadb.api.TransactionContract.CommitBehavior;
import io.evitadb.core.Catalog;
import io.evitadb.core.metric.event.transaction.NewCatalogVersionPropagatedEvent;
import io.evitadb.core.metric.event.transaction.TransactionProcessedEvent;
import io.evitadb.core.transaction.stage.TrunkIncorporationTransactionStage.UpdatedCatalogTransactionTask;
import io.evitadb.utils.Assert;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.concurrent.Flow;
import java.util.concurrent.Flow.Subscription;
import java.util.function.Consumer;

/**
 * The CatalogSnapshotPropagationTransactionStage class is a subscriber implementation that processes
 * {@link UpdatedCatalogTransactionTask} objects and propagates new catalog versions (snapshots) to
 * the "live view" of the evitaDB engine.
 *
 * This is the last stage of the transaction processing pipeline.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@Slf4j
public class CatalogSnapshotPropagationTransactionStage implements Flow.Subscriber<UpdatedCatalogTransactionTask> {
	/**
	 * The name of the catalog the processor is bound to. Each catalog has its own transaction processor.
	 */
	private String catalogName;
	/**
	 * The subscription variable represents a subscription to a reactive stream.
	 * It is used to manage the flow of data from the publisher to the subscriber.
	 *
	 * @see Flow.Subscription
	 */
	private Flow.Subscription subscription;
	/**
	 * Contains lambda function that accepts newly created catalog instance and propagates it to the "live view" of
	 * the evitaDB engine, to be used by subsequent requests to the catalog.
	 */
	private final Consumer<Catalog> newCatalogVersionConsumer;
	/**
	 * Contains TRUE if the processor has been completed and does not accept any more data.
	 */
	@Getter private boolean completed;

	public CatalogSnapshotPropagationTransactionStage(
		@Nonnull Consumer<Catalog> newCatalogVersionConsumer
	) {
		this.newCatalogVersionConsumer = newCatalogVersionConsumer;
	}

	@Override
	public void onSubscribe(Subscription subscription) {
		this.subscription = subscription;
		subscription.request(1);
	}

	@Override
	public final void onNext(UpdatedCatalogTransactionTask task) {
		// emit queue event
		task.transactionQueuedEvent().finish().commit();

		final NewCatalogVersionPropagatedEvent event = new NewCatalogVersionPropagatedEvent(task.catalogName());
		try {
			this.catalogName = task.catalogName();
			this.newCatalogVersionConsumer.accept(task.catalog());
			if (task.future() != null) {
				log.debug("Snapshot propagating task for catalog `" + catalogName + "` completed (" + task.catalog().getEntityTypes() + ")!");
				task.future().complete(task.catalogVersion());
			} else {
				Assert.isPremiseValid(
					task.commitBehaviour() != CommitBehavior.WAIT_FOR_INDEX_PROPAGATION,
					"Future is unexpectedly null and commit behaviour is WAIT_FOR_INDEX_PROPAGATION!"
				);
			}
		} catch (Throwable ex) {
			log.error("Error while processing snapshot propagating task for catalog `" + catalogName + "`!", ex);
			if (task.future() != null) {
				task.future().completeExceptionally(ex);
			}
		}

		// emit the event
		event.finish(task.commitTimestamps().length).commit();

		// emit transaction processed events
		final OffsetDateTime now = OffsetDateTime.now();
		for (OffsetDateTime commitTime : task.commitTimestamps()) {
			new TransactionProcessedEvent(catalogName, Duration.between(commitTime, now)).commit();
		}

		this.subscription.request(1);
	}

	@Override
	public final void onError(Throwable throwable) {
		log.error(
			"Fatal error! Error propagated outside catalog `" + catalogName + "` snapshot propagation task! " +
				"This is unexpected and effectively stops transaction processing!",
			throwable
		);
	}

	@Override
	public final void onComplete() {
		log.debug("Conflict snapshot propagation stage completed for catalog `" + catalogName + "`!");
		this.completed = true;
	}

}
