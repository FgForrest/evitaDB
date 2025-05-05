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


import io.evitadb.utils.Assert;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.Flow.Subscription;
import java.util.function.Consumer;

/**
 * A wrapper around a {@link Subscriber} that can detect when all published items have been consumed
 * and trigger a callback to publish more items.
 *
 * @param <T> the type of items being published
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@RequiredArgsConstructor
public class EmptyAwareSubscriber<T, S extends Subscriber<? super T>> implements Subscriber<T> {
	/**
	 * The wrapped subscriber.
	 */
	private final S delegate;

	/**
	 * The subscription to the publisher.
	 */
	private Subscription subscription;

	/**
	 * Callback that is triggered when all published items have been consumed.
	 */
	private final Runnable onEmptyQueueCallback;

	/**
	 * If set, the callback will be used instead of {@link #onEmptyQueueCallback} to cancel the subscription when
	 * the buffer is depleted.
	 */
	private Consumer<S> cancelSubscriptionOnDepletion;

	/**
	 * Counter that tracks the number of items processed by this subscriber.
	 */
	private long itemsProcessed = 0;

	/**
	 * The total number of items that have been provisioned to this subscriber.
	 * When itemsProcessed equals itemsProvisioned, the buffer is empty.
	 */
	@Nullable private Long itemsProvisioned;

	/**
	 * Delegates to the wrapped subscriber.
	 *
	 * @param subscription the subscription to the publisher
	 */
	@Override
	public void onSubscribe(Subscription subscription) {
		if (this.cancelSubscriptionOnDepletion != null) {
			subscription.cancel();
			this.cancelSubscriptionOnDepletion.accept(this.delegate);
		} else {
			this.delegate.onSubscribe(subscription);
			this.subscription = subscription;
		}
	}

	/**
	 * Processes an item and checks if all published items have been consumed.
	 * If so, triggers the callback to publish more items.
	 *
	 * @param item the item being delivered
	 */
	@Override
	public void onNext(T item) {
		this.itemsProcessed++;
		this.delegate.onNext(item);
		if (this.itemsProvisioned != null && this.itemsProcessed == this.itemsProvisioned) {
			// All published items have been consumed, trigger the callback to publish more
			this.itemsProvisioned = null;
			if (this.cancelSubscriptionOnDepletion == null) {
				this.onEmptyQueueCallback.run();
			} else {
				this.subscription.cancel();
				this.cancelSubscriptionOnDepletion.accept(this.delegate);
			}
		}
	}

	/**
	 * Delegates to the wrapped subscriber.
	 *
	 * @param throwable the error that occurred
	 */
	@Override
	public void onError(Throwable throwable) {
		this.delegate.onError(throwable);
	}

	/**
	 * Delegates to the wrapped subscriber.
	 */
	@Override
	public void onComplete() {
		this.delegate.onComplete();
	}

	/**
	 * Determines if the depletion condition is set based on whether the number of
	 * items provisioned is defined. Depletion is initialized using {@link #emptyOnDepletion(long)} and nullified
	 * when the items processed reaches the items provisioned.
	 *
	 * @return true if the itemsProvisioned field is not null, indicating that the
	 *         depletion condition has been set; false otherwise.
	 */
	public boolean isDepletionSet() {
		return this.itemsProvisioned != null;
	}

	/**
	 * Called when the publisher has delivered a certain number of items.
	 * This allows the subscriber to know how many items to expect.
	 *
	 * @param itemsProvisioned the total number of items that have been delivered so far
	 */
	public void emptyOnDepletion(long itemsProvisioned) {
		Assert.isPremiseValid(
			this.itemsProvisioned == null,
			"Depletion callback already set! This method should only be called once."
		);
		this.itemsProvisioned = itemsProvisioned;
	}

	/**
	 * Cancels the current subscription when all published items have been processed.
	 * If the subscription is canceled due to depletion, the provided discardLambda
	 * callback may be used to define additional behavior that executes on depletion.
	 * If not yet depleted, the discardLambda is stored for future use.
	 *
	 * @param discardLambda a callback function to execute in the event of depletion.
	 *                      This function is triggered when all provisioned items
	 *                      have been processed.
	 */
	public void cancelSubscriptionOnDepletion(@Nonnull Consumer<S> discardLambda) {
		Assert.isPremiseValid(
			this.cancelSubscriptionOnDepletion == null,
			"Depletion callback already set! This method should only be called once."
		);
		if (this.subscription != null && this.itemsProvisioned != null && this.itemsProcessed == this.itemsProvisioned) {
			// All published items have been consumed, trigger the callback to publish more
			this.itemsProvisioned = null;
			this.subscription.cancel();
			this.cancelSubscriptionOnDepletion = discardLambda;
			this.cancelSubscriptionOnDepletion.accept(this.delegate);
		} else {
			this.cancelSubscriptionOnDepletion = discardLambda;
		}
	}

	@Override
	public final boolean equals(Object o) {
		if (!(o instanceof EmptyAwareSubscriber<?,?> that)) return false;

		return this.delegate.equals(that.delegate);
	}

	@Override
	public int hashCode() {
		return this.delegate.hashCode();
	}

	@Override
	public String toString() {
		return this.delegate.toString() +
			" (" + itemsProcessed +
			" of " + itemsProvisioned +
			" items processed)" +
			(this.cancelSubscriptionOnDepletion == null ? "" : " (cancel on depletion)");
	}
}
