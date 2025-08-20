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
import io.evitadb.api.requestResponse.cdc.ChangeSystemCapture;
import io.evitadb.api.requestResponse.cdc.ChangeSystemCaptureRequest;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.Flow;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.util.Optional.ofNullable;

/**
 * Implementation of the {@link Flow.Publisher} interface that publishes {@link ChangeSystemCapture} events
 * to subscribers. This publisher acts as a facade for the {@link ChangeSystemCaptureSharedPublisher} and
 * is responsible for handling a specific {@link ChangeSystemCaptureRequest}.
 *
 * When a subscriber subscribes to this publisher, it delegates the subscription to the shared publisher
 * with the appropriate configuration derived from the request. The publisher determines the starting point
 * for capturing changes based on the request parameters (sinceVersion and sinceIndex) and the requested
 * content.
 *
 * If the request doesn't specify a version to start from, the publisher will use the current system
 * version + 1, meaning it will capture changes starting from the next version. If the request doesn't
 * specify an index, it will start from index 0 within the specified version.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 * @see ChangeSystemCaptureSharedPublisher
 * @see ChangeSystemCaptureRequest
 * @see ChangeSystemCapture
 */
public class ChangeSystemCapturePublisher implements ChangeCapturePublisher<ChangeSystemCapture> {
	/**
	 * The shared publisher instance that does the heavy lifting of capturing system changes.
	 * It handles the actual publishing of events to multiple subscribers and manages the underlying
	 * change capture mechanism.
	 */
	private final ChangeSystemCaptureSharedPublisher sharedPublisher;
	/**
	 * The request that specifies what changes the subscriber is interested in, including the starting
	 * version, index, and content types to capture.
	 */
	private final ChangeSystemCaptureRequest request;

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
	 * Creates a new instance of {@link ChangeSystemCapturePublisher}.
	 *
	 * @param sharedPublisher the shared publisher instance that does the heavy lifting of capturing changes
	 * @param request the request that specifies what changes the subscriber is interested in
	 */
	public ChangeSystemCapturePublisher(
		@Nonnull ChangeSystemCaptureSharedPublisher sharedPublisher,
		@Nonnull ChangeSystemCaptureRequest request
	) {
		this.sharedPublisher = sharedPublisher;
		this.request = request;
	}

	/**
	 * Subscribes the given subscriber to receive {@link ChangeSystemCapture} events.
	 *
	 * This method delegates to the shared publisher with a {@link WalPointerWithContent} that
	 * specifies the starting point for capturing changes based on the request parameters. If the
	 * request doesn't specify a version, it uses the current system version + 1. If the request
	 * doesn't specify an index, it uses 0.
	 *
	 * @param subscriber the subscriber to receive events
	 */
	@Override
	public void subscribe(Subscriber<? super ChangeSystemCapture> subscriber) {
		assertActive();
		final DefaultChangeCaptureSubscription<ChangeSystemCapture> subscription = this.sharedPublisher.subscribe(
			subscriber,
			new WalPointerWithContent(
				ofNullable(this.request.sinceVersion()).orElse(this.sharedPublisher.getVersion() + 1),
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
				if (!this.sharedPublisher.isClosed()) {
					for (UUID subscriberId : this.subscribers) {
						this.sharedPublisher.unsubscribe(subscriberId);
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
