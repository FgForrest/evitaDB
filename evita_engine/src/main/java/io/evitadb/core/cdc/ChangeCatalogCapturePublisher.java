/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.core.cdc;


import com.esotericsoftware.kryo.util.Null;
import io.evitadb.api.requestResponse.cdc.ChangeCaptureContent;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCapture;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCaptureCriteria;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCaptureRequest;
import io.evitadb.api.requestResponse.mutation.Mutation;
import io.evitadb.api.requestResponse.mutation.MutationPredicate;
import io.evitadb.api.requestResponse.transaction.TransactionMutation;
import io.evitadb.core.Catalog;
import io.evitadb.core.async.ObservableThreadExecutor;
import io.evitadb.core.cdc.CatalogChangeCaptureRingBuffer.OutsideScopeException;
import io.evitadb.core.exception.ChangeCatalogCapturePublisherClosedException;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.UUIDUtil;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.Flow;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static io.evitadb.core.cdc.predicate.MutationPredicateFactory.createPredicateUsingComparator;

/**
 * TODO JNO - document me
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@Slf4j
public class ChangeCatalogCapturePublisher implements Flow.Publisher<ChangeCatalogCapture>, AutoCloseable {
	private final AtomicBoolean closed = new AtomicBoolean(false);
	private final ObservableThreadExecutor cdcExecutor;
	private final Map<UUID, ChangeCatalogCaptureSubscription> subscribers = CollectionUtils.createConcurrentHashMap(64);
	private final AtomicReference<Catalog> currentCatalog;
	private final CatalogChangeCaptureRingBuffer lastCaptures;
	private final int subscriberBufferSize;
	private final ChangeCatalogCaptureCriteria[] criteria;

	public ChangeCatalogCapturePublisher(
		@Nonnull Catalog catalog,
		@Nonnull ObservableThreadExecutor cdcExecutor,
		int bufferSize,
		int subscriberBufferSize,
		// TODO JNO - cachovat pouze podle predikátu ... content a since version držet v subscription!
		//  Tento request brát pouze jako default, když by při registraci subscriber požadovaná data nedoddal ... nebo možná nastavovat v subscription při on-subscribed?!
		@Nonnull ChangeCatalogCaptureRequest request
	) {
		this.currentCatalog = new AtomicReference<>(catalog);
		final long currentCatalogVersion = catalog.getVersion();
		this.cdcExecutor = cdcExecutor;
		this.lastCaptures = new CatalogChangeCaptureRingBuffer(
			currentCatalogVersion, 0, currentCatalogVersion, bufferSize
		);
		this.subscriberBufferSize = subscriberBufferSize;
		this.criteria = request.criteria();
	}

	/**
	 * Notifies that the specified catalog is now present in the live view and updates the current catalog reference.
	 * This method reads all mutations from the WAL and publishes them to all subscribers that keep up with the live view.
	 * TODO JNO - tohle se musí volat v trunku, není thread safe
	 *
	 * WARNING: not thread safe - must be called from the same thread as {@link #processMutation(Mutation)}
	 *
	 * @param catalog the catalog that is now present in the live view
	 */
	public void notifyCatalogPresentInLiveView(@Nonnull Catalog catalog) {
		// we don't actively check for non-closed condition here to speed up the process
		this.currentCatalog.set(catalog);
		this.lastCaptures.setEffectiveLastCatalogVersion(catalog.getVersion());
		// TODO JNO - uvolnit buffer pokud tam jsou již nepoužívané události
	}

	public void processMutation(@Nonnull Mutation mutation) {
		// we don't actively check for non-closed condition here to speed up the process
		// TODO JNO - work with predicate somehow!
		/*if (this.predicate.test(mutation)) {
			mutation.toChangeCatalogCapture(this.predicate, this.content)
				.forEach(this.lastCaptures::offer);
		}*/
	}

	@Override
	public void subscribe(Subscriber<? super ChangeCatalogCapture> subscriber) {
		assertActive();
		UUID subscriberId;
		final AtomicBoolean created = new AtomicBoolean(false);
		do {
			subscriberId = UUIDUtil.randomUUID();
			this.subscribers.computeIfAbsent(
				subscriberId,
				uuid -> {
					created.set(true);
					// this is a costly operation since it allocates a buffer
					return new ChangeCatalogCaptureSubscription(
						uuid,
						this.subscriberBufferSize,
						/* TODO JNO */
						0L,
						// this.sinceCatalogVersion,
						subscriber,
						this.cdcExecutor,
						this::fillBuffer
					);
				}
			);
		} while (!created.get());
	}

	private void fillBuffer(@Nonnull WalPointer walPointer, @Nonnull Queue<ChangeCatalogCapture> changeCatalogCaptures) {
		if (walPointer.version() < this.lastCaptures.getEffectiveStartCatalogVersion()) {
			// we need to read the WAL from the disk and process manually
			/* TODO content odněkud */
			readWal(walPointer, changeCatalogCaptures, ChangeCaptureContent.BODY);
		} else {
			try {
				// try to copy data from the ring buffer in synchronized block
				this.lastCaptures.copyTo(walPointer, changeCatalogCaptures);
			} catch (OutsideScopeException e) {
				// ok, we detected that we're outside the ring buffer in the locked scope
				/* TODO content odněkud */
				readWal(walPointer, changeCatalogCaptures, ChangeCaptureContent.BODY);
			}
		}
	}

	@Override
	public void close() {
		if (this.closed.compareAndSet(false, true)) {
			// cancel all subscriptions
			for (ChangeCatalogCaptureSubscription subscription : this.subscribers.values()) {
				subscription.cancel();
			}
		}
	}

	@Nonnull
	private Stream<Mutation> getMutationStream(@Null WalPointer walPointer) {
		assertActive();
		final AtomicInteger index = new AtomicInteger(0);
		return this.currentCatalog.get()
			.getCommittedMutationStream(walPointer.version())
			.dropWhile(mutation -> index.getAndIncrement() < walPointer.index());
	}

	private long readWal(
		@Nonnull WalPointer walPointer,
		@Nonnull Queue<ChangeCatalogCapture> changeCatalogCaptures,
		@Nonnull ChangeCaptureContent content
	) {
		assertActive();
		final Catalog catalog = this.currentCatalog.get();
		final long lastPublishedCatalogVersion = catalog.getVersion();
		log.debug(
			"Reading all mutations from the catalog version {} and sending them to {} subscriber(s).",
			lastPublishedCatalogVersion,
			this.subscribers.size()
		);
		final MutationPredicate predicate = createPredicateUsingComparator(criteria);
		try (
			final Stream<Mutation> committedMutationStream = getMutationStream(walPointer)
		) {
			return committedMutationStream
				// finish early when we reach mutation that is not yet visible in the live view
				.takeWhile(
					mutation -> !(mutation instanceof TransactionMutation txMutation) ||
						txMutation.getCatalogVersion() <= lastPublishedCatalogVersion
				)
				// filter out mutations that are not relevant for this publisher
				.filter(predicate)
				// finish early when there is no more room in the queue
				.takeWhile(
					mutation -> mutation.toChangeCatalogCapture(predicate, content)
						.filter(cdc -> cdc.version() < walPointer.version() || (cdc.version() == walPointer.version() && cdc.index() < walPointer.index()))
						.map(changeCatalogCaptures::offer)
						.reduce(Boolean::logicalAnd)
						.orElse(true)
				)
				.count();
		}
	}

	private void assertActive() {
		Assert.isPremiseValid(
			!this.closed.get(),
			ChangeCatalogCapturePublisherClosedException::new
		);
	}

	record WalPointer(
		long version,
		int index
	) {
	}

}
