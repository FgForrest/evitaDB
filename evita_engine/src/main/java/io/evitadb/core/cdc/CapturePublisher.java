/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

import io.evitadb.api.requestResponse.cdc.ChangeCapture;
import io.evitadb.api.requestResponse.cdc.ChangeCaptureContent;
import io.evitadb.api.requestResponse.cdc.ChangeCapturePublisher;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCapture;
import io.evitadb.api.requestResponse.mutation.Mutation;
import io.evitadb.api.requestResponse.mutation.MutationPredicate;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.utils.Assert;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.concurrent.Flow.Processor;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.Flow.Subscription;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiPredicate;
import java.util.function.Consumer;

/**
 * Pre-configured {@link SubmissionPublisher} for {@link ChangeCapture} objects.
 * It is limited only to one subscriber to have control over internal buffer overflows as the {@link SubmissionPublisher}
 * doesn't provide tools to control buffers for individual subscribers and thus submit new events only to such subscribers
 * that have space in their buffers.
 *
 * @author Jan Novotný, Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@Slf4j
class CapturePublisher extends SubmissionPublisher<ChangeCatalogCapture>
	implements ChangeCapturePublisher<ChangeCatalogCapture>, Processor<Mutation, ChangeCatalogCapture> {
	/**
	 * Unique identifier of this publisher.
	 */
	private final UUID id;
	/**
	 * Requested depth of the produced ChangeCatalogCapture objects.
	 */
	private final ChangeCaptureContent content;
	/**
	 * Predicate that filters out mutations that are not relevant for this publisher.
	 */
	private final MutationPredicate predicate;
	/**
	 * Lambda function that is called when the subscriber is lagging behind the publisher.
	 */
	private final BiPredicate<Subscriber<? super ChangeCatalogCapture>, ChangeCatalogCapture> onLagging;
	/**
	 * Lambda function that is called when the publisher is completed.
	 */
	private final Consumer<UUID> onCompletion;
	/**
	 * Atomic counter that tracks the number of items offered to the subscriber.
	 */
	private final AtomicLong offered = new AtomicLong(0L);
	/**
	 * Threshold for the first dropped catalog version with which the subscriber should continue.
	 */
	@Getter private long continueWithVersion;
	/**
	 * Threshold for the first dropped transaction mutation index within a transaction block with which the subscriber should continue.
	 */
	@Getter private int continueWithIndex;
	/**
	 * Subscription wrapper that wraps the subscription to the publisher and allows to cancel it.
	 */
	@Nullable private SubscriptionWrapper subscription;

	public CapturePublisher(
		@Nonnull UUID id,
		long startCatalogVersion,
		int startIndex,
		@Nonnull Executor executor,
		@Nonnull MutationPredicate predicate,
		@Nonnull ChangeCaptureContent content,
		@Nonnull Consumer<CapturePublisher> onLagging,
		@Nonnull Consumer<UUID> onCompletion,
		int maxBufferCapacity
	) {
		// for now, we will use a default buffer size as we don't have any information about what number to use otherwise
		super(executor, maxBufferCapacity);
		this.id = id;
		this.predicate = predicate;
		this.content = content;
		this.continueWithVersion = startCatalogVersion;
		this.continueWithIndex = startIndex;
		// on drop, register to lagging mutation publishers
		this.onLagging = (subscriber, ccc) -> {
			log.debug(
				"Subscriber {} is lagging behind the publisher {} on catalog version {}, index {}. " +
					"The subscriber will be notified when it is ready to receive more events.",
				subscriber,
				this.id,
				ccc.version(),
				ccc.index()
			);
			this.offered.decrementAndGet();
			final Subscription theSubscription = this.subscription;
			if (theSubscription != null) {
				this.subscription = null;
				this.continueWithVersion = ccc.version();
				this.continueWithIndex = ccc.index();
				log.debug(
					"Canceling subscription to publisher {} as the subscriber is lagging behind on" +
						" catalog version {}, index {} and calling on lagging callback.",
					this.id,
					this.continueWithVersion,
					this.continueWithIndex
				);
				// cancel the subscription to prevent further events from being sent unless reattached to lagging publisher
				theSubscription.cancel();
				onLagging.accept(this);
			}
			// never retry delivery
			return false;
		};
		this.onCompletion = onCompletion;
	}

	public CapturePublisher(
		@Nonnull UUID id,
		long startCatalogVersion,
		int startIndex,
		@Nonnull Executor executor,
		@Nonnull MutationPredicate predicate,
		@Nonnull ChangeCaptureContent content,
		@Nonnull Consumer<CapturePublisher> onLagging,
		@Nonnull Consumer<UUID> onCompletion
	) {
		this(
			id,
			startCatalogVersion,
			startIndex,
			executor,
			predicate,
			content,
			onLagging,
			onCompletion,
			Flow.defaultBufferSize()
		);
	}

	@Override
	public void onSubscribe(Subscription subscription) {
		this.subscription = new SubscriptionWrapper(subscription, () -> this.onCompletion.accept(this.id));
		this.subscription.request(getMaxBufferCapacity());
	}

	@Override
	public void onNext(Mutation mutation) {
		final SubscriptionWrapper theSubscription = this.subscription;
		Assert.isPremiseValid(theSubscription != null, "Subscription cannot be null!");
		if (this.predicate.test(mutation)) {
			mutation.toChangeCatalogCapture(this.predicate, this.content)
				// if the continueWithVersion is set, we need to filter out all events that are older than the last
				// event that was sent to the subscriber
				.filter(captureEvent -> captureEvent.version() > this.continueWithVersion ||
					(captureEvent.version() == this.continueWithVersion && captureEvent.index() >= this.continueWithIndex))
				// offer event to the subscriber and fallback to lagging state if the subscriber is not capable of receiving
				.forEach(
					captureEvent -> {
						this.offered.incrementAndGet();
						offer(captureEvent, 0, TimeUnit.MILLISECONDS, this.onLagging);
					}
				);
		}
		// one item has been processed, request one more
		theSubscription.request(1);
	}

	@Override
	public void onError(Throwable throwable) {
		log.error("Error occurred while processing mutation in a CDC processor chain. Propagating error to all subscribers.", throwable);
		// Propagate the error to all downstream subscribers
		final List<Subscriber<? super ChangeCatalogCapture>> subscribers = getSubscribers();
		for (Subscriber<? super ChangeCatalogCapture> subscriber : subscribers) {
			subscriber.onError(throwable);
		}
		close(); // Close the current processor
	}

	@Override
	public void onComplete() {
		// Signal completion to all subscribers
		final List<Subscriber<? super ChangeCatalogCapture>> subscribers = getSubscribers();
		for (Subscriber<? super ChangeCatalogCapture> subscriber : subscribers) {
			subscriber.onComplete();
		}
		close();
	}

	@Override
	public void subscribe(Subscriber<? super ChangeCatalogCapture> subscriber) {
		// ensure this publisher has only a single subscriber
		if (super.getNumberOfSubscribers() == 0) {
			super.subscribe(subscriber);
		} else {
			throw new EvitaInvalidUsageException("Only one subscriber is supported.");
		}
	}

	@Override
	public void close() {
		if (this.subscription != null) {
			this.subscription.cancel();
			this.subscription = null;
		}

		super.close();
	}

	@Override
	public void closeExceptionally(Throwable error) {
		if (this.subscription != null) {
			this.subscription.cancel();
			this.subscription = null;
		}

		super.closeExceptionally(error);
	}

	@Override
	public final boolean equals(Object o) {
		if (!(o instanceof CapturePublisher that)) return false;

		return this.id.equals(that.id);
	}

	@Override
	public int hashCode() {
		return this.id.hashCode();
	}

	/**
	 * The SubscriptionWrapper class serves as a wrapper implementation of the {@link Subscription} interface.
	 * It delegates the primary operations (`request` and `cancel`) to an underlying Subscription instance
	 * while executing additional logic upon cancellation.
	 *
	 * This class is intended to be used internally within the containing {@link CapturePublisher}.
	 */
	private static class SubscriptionWrapper implements Subscription {
		private final Subscription delegate;
		private final Runnable onCompletion;

		public SubscriptionWrapper(
			@Nonnull Subscription delegate,
			@Nonnull Runnable onCompletion
		) {
			this.delegate = delegate;
			this.onCompletion = onCompletion;
		}

		@Override
		public void request(long n) {
			this.delegate.request(n);
		}

		@Override
		public void cancel() {
			try {
				this.delegate.cancel();
			} finally {
				this.onCompletion.run();
			}
		}

	}

}
