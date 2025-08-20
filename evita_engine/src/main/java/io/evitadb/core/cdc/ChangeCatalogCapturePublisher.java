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


import io.evitadb.api.exception.InstanceTerminatedException;
import io.evitadb.api.requestResponse.cdc.ChangeCapturePublisher;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCapture;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCaptureCriteria;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCaptureRequest;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.lang.ref.WeakReference;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.Flow;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import static java.util.Optional.ofNullable;

/**
 * Implementation of the {@link Flow.Publisher} interface that publishes {@link ChangeCatalogCapture} events
 * to subscribers. This publisher acts as a facade for the {@link ChangeCatalogCaptureSharedPublisher} and
 * is responsible for handling a specific {@link ChangeCatalogCaptureRequest}.
 *
 * When a subscriber subscribes to this publisher, it delegates the subscription to the shared publisher
 * with the appropriate configuration derived from the request. The publisher determines the starting point
 * for capturing changes based on the request parameters (sinceVersion and sinceIndex) and the requested
 * content.
 *
 * If the request doesn't specify a version to start from, the publisher will use the current catalog
 * version + 1, meaning it will capture changes starting from the next version. If the request doesn't
 * specify an index, it will start from index 0 within the specified version.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 * @see ChangeCatalogCaptureSharedPublisher
 * @see ChangeCatalogCaptureRequest
 * @see ChangeCatalogCapture
 */
public class ChangeCatalogCapturePublisher implements ChangeCapturePublisher<ChangeCatalogCapture> {
	/**
	 * The shared publisher factory that creates instances of {@link ChangeCatalogCaptureSharedPublisher} when first
	 * subscriber is registered. This factory is used to create a new shared publisher if the current one is closed.
	 */
	private final Function<ChangeCatalogCriteriaBundle, ChangeCatalogCaptureSharedPublisher> sharedPublisherFactory;
	/**
	 * The request that specifies what changes the subscriber is interested in, including the starting
	 * version, index, and content types to capture.
	 */
	private final ChangeCatalogCaptureRequest request;

	/**
	 * Flag indicating whether this publisher has been closed. Once closed, the publisher will no longer
	 * accept new subscribers and existing subscriptions will be terminated.
	 */
	private final AtomicBoolean closed = new AtomicBoolean(false);

	/**
	 * Set of subscriber IDs that are currently subscribed to this publisher. This is used to track
	 * active subscriptions and to unsubscribe them when the publisher is closed.
	 */
	private final Set<UUID> subscribers = new ConcurrentSkipListSet<>();
	/**
	 * The shared publisher that this publisher delegates to. It handles the actual publishing of events
	 * to multiple subscribers and manages the underlying change capture mechanism.
	 */
	private WeakReference<ChangeCatalogCaptureSharedPublisher> sharedPublisher;

	/**
	 * Creates a new instance of {@link ChangeCatalogCapturePublisher}.
	 *
	 * @param sharedPublisherFactory the factory that creates or fetches the shared publisher
	 * @param request the request that specifies what changes the subscriber is interested in
	 */
	public ChangeCatalogCapturePublisher(
		@Nonnull Function<ChangeCatalogCriteriaBundle, ChangeCatalogCaptureSharedPublisher> sharedPublisherFactory,
		@Nonnull ChangeCatalogCaptureRequest request
	) {
		this.sharedPublisherFactory = sharedPublisherFactory;
		this.sharedPublisher = new WeakReference<>(null);
		this.request = request;
	}

	/**
	 * Subscribes the given subscriber to receive {@link ChangeCatalogCapture} events.
	 *
	 * This method delegates to the shared publisher with a {@link WalPointerWithContent} that
	 * specifies the starting point for capturing changes based on the request parameters. If the
	 * request doesn't specify a version, it uses the current catalog version + 1. If the request
	 * doesn't specify an index, it uses 0.
	 *
	 * @param subscriber the subscriber to receive events
	 */
	@Override
	public void subscribe(Subscriber<? super ChangeCatalogCapture> subscriber) {
		assertActive();
		ChangeCatalogCaptureSharedPublisher theSharedPublisher = this.sharedPublisher.get();
		if (theSharedPublisher == null || theSharedPublisher.isClosed()) {
			// the shared publisher has been closed in the meantime - we need to renew it
			final ChangeCatalogCaptureCriteria[] requestedCriteria = this.request.criteria();
			final ChangeCatalogCriteriaBundle criteriaBundle = requestedCriteria == null ?
				ChangeCatalogCriteriaBundle.CATCH_ALL : new ChangeCatalogCriteriaBundle(requestedCriteria);
			theSharedPublisher = this.sharedPublisherFactory.apply(criteriaBundle);
			this.sharedPublisher = new WeakReference<>(theSharedPublisher);
		}
		final DefaultChangeCaptureSubscription<ChangeCatalogCapture> subscription = theSharedPublisher.subscribe(
			subscriber,
			new WalPointerWithContent(
				ofNullable(this.request.sinceVersion()).orElse(theSharedPublisher.getCatalog().getVersion() + 1),
				ofNullable(this.request.sinceIndex()).orElse(0),
				this.request.content()
			)
		);
		this.subscribers.add(subscription.getSubscriptionId());
	}

	@Override
	public void close() {
		if (this.closed.compareAndSet(false, true)) {
			if (!this.subscribers.isEmpty()) {
				final ChangeCatalogCaptureSharedPublisher theSharedPublisher = this.sharedPublisher.get();
				if (theSharedPublisher != null && !theSharedPublisher.isClosed()) {
					for (UUID subscriberId : this.subscribers) {
						theSharedPublisher.unsubscribe(subscriberId);
					}
				}
			}
		}
	}

	/**
	 * Asserts that the publisher is active (not closed).
	 *
	 * @throws InstanceTerminatedException if the publisher is closed
	 */
	private void assertActive() {
		Assert.isTrue(
			!this.closed.get(),
			() -> new InstanceTerminatedException("CDC publisher")
		);
	}

}
