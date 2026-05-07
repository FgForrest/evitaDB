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
import io.evitadb.api.requestResponse.cdc.ChangeSystemCaptureCriteria;
import io.evitadb.api.requestResponse.cdc.ChangeSystemCaptureRequest;
import io.evitadb.api.requestResponse.cdc.HostSystemEvent;
import io.evitadb.api.requestResponse.cdc.SystemCaptureArea;
import io.evitadb.core.cdc.predicate.MutationPredicateFactory;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.Flow;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

import static io.evitadb.core.cdc.predicate.MutationPredicateFactory.*;
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
		// Build the per-subscriber host-event filter from the request criteria. Default-divergence
		// rule applies: a `null` criteria array yields a constant-`false` predicate so legacy clients
		// continue receiving engine-mutation events only.
		final Predicate<HostSystemEvent> hostEventFilter = createHostEventPredicate(this.request);
		// Build the per-subscriber engine-mutation filter so a HOST-only subscriber
		// has its mutation traffic filtered out at fanout time. We do NOT reuse the heavyweight
		// `MutationPredicate` here — that machinery is built around context-stateful iteration
		// during mutation→capture conversion, and would advance the predicate context on every
		// fanout call which is wrong for the dispatch path. A small lambda is sufficient.
		final Predicate<ChangeSystemCapture> mutationFilter = buildMutationCaptureFilter(this.request);
		final DefaultChangeCaptureSubscription<ChangeSystemCapture> subscription = this.sharedPublisher.subscribe(
			subscriber,
			new WalPointerWithContent(
				ofNullable(this.request.sinceVersion()).orElse(this.sharedPublisher.getVersion() + 1),
				ofNullable(this.request.sinceIndex()).orElse(0),
				this.request.content()
			),
			hostEventFilter,
			mutationFilter
		);
		this.subscribers.add(subscription.getSubscriptionId());
	}

	/**
	 * Builds a stateless predicate that decides — at fanout time — whether a given engine-mutation
	 * capture should be delivered to the subscriber that issued the supplied request.
	 *
	 * **Default-criteria divergence.** When `request.criteria() == null` the predicate accepts
	 * every engine-mutation capture (engine-only default — the legacy flow). An empty array
	 * accepts none. An explicit array that includes ENGINE (or a `null`-area entry, which matches
	 * any area) accepts all engine mutations; otherwise rejects them.
	 *
	 * Host-event captures pulled through this filter (defensive — they should never reach the
	 * ring buffer in the first place) are accepted unconditionally; host-event delivery is gated
	 * by {@link MutationPredicateFactory#createHostEventPredicate}.
	 *
	 * @param request the capture request whose criteria determine the filter
	 * @return predicate accepting captures that should be delivered to the subscriber
	 */
	@Nonnull
	private static Predicate<ChangeSystemCapture> buildMutationCaptureFilter(
		@Nonnull ChangeSystemCaptureRequest request
	) {
		final ChangeSystemCaptureCriteria[] criteria = request.criteria();
		if (criteria == null) {
			// Default divergence: NULL criteria => engine-only flow (admit every engine mutation).
			return capture -> true;
		}
		boolean acceptsEngine = false;
		for (final ChangeSystemCaptureCriteria criterion : criteria) {
			final SystemCaptureArea area = criterion.area();
			if (area == null || area == SystemCaptureArea.ENGINE) {
				acceptsEngine = true;
				break;
			}
		}
		final boolean accept = acceptsEngine;
		return capture -> {
			// Host-event captures take a side-channel and never go through the ring-buffer fanout —
			// admit them defensively if one ever shows up.
			if (capture.body() instanceof HostSystemEvent) {
				return true;
			}
			return accept;
		};
	}

	@Override
	public void close() {
		if (this.closed.compareAndSet(false, true)) {
			if (!this.subscribers.isEmpty() && !this.sharedPublisher.isClosed()) {
				for (UUID subscriberId : this.subscribers) {
					this.sharedPublisher.unsubscribe(subscriberId);
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
