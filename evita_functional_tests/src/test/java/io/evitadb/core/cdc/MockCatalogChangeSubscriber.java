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


import io.evitadb.api.requestResponse.cdc.ChangeCaptureSubscription;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCapture;
import lombok.Getter;
import org.junit.jupiter.api.Assertions;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.Flow.Subscription;
import java.util.concurrent.TimeUnit;

/**
 * A test implementation of {@link Subscriber} that collects received items and provides
 * methods to wait for completion or errors.
 *
 * This class is used in tests to:
 * 1. Subscribe to a CapturePublisher and collect the items it publishes
 * 2. Complete a future when a specified number of items have been received
 * 3. Track errors and completion signals
 * 4. Provide mechanisms to wait for errors or completion signals
 *
 * The subscriber implements a simple flow control strategy by requesting one item at a time,
 * which allows testing backpressure handling in the publisher.
 */
class MockCatalogChangeSubscriber implements Subscriber<ChangeCatalogCapture> {
	/**
	 * The number of items to receive before completing the future and canceling the subscription.
	 */
	private final int completeAtCount;

	/**
	 * List of items received from the publisher.
	 */
	@Getter private final List<ChangeCatalogCapture> items;

	/**
	 * Future that completes when the expected number of items have been received.
	 */
	@Getter private final CompletableFuture<Void> future = new CompletableFuture<>();

	/**
	 * The error received from the publisher, if any.
	 */
	@Getter private Throwable error;

	/**
	 * Flag indicating whether the publisher has signaled completion.
	 */
	@Getter private boolean completed;

	/**
	 * The subscription to the publisher.
	 */
	private ChangeCaptureSubscription subscription;

	/**
	 * Latch that counts down when an error is received.
	 */
	private final CountDownLatch errorLatch = new CountDownLatch(1);

	/**
	 * Latch that counts down when completion is signaled.
	 */
	private final CountDownLatch completionLatch = new CountDownLatch(1);

	/**
	 * Creates a new MockCatalogChangeSubscriber that never completes.
	 */
	public MockCatalogChangeSubscriber() {
		this.completeAtCount = Integer.MAX_VALUE;
		this.items = new ArrayList<>(256);
	}

	/**
	 * Creates a new MockCatalogChangeSubscriber that will complete after receiving the specified number of items.
	 *
	 * @param completeAtCount the number of items to receive before completing
	 */
	public MockCatalogChangeSubscriber(int completeAtCount) {
		this.completeAtCount = completeAtCount;
		this.items = new ArrayList<>(completeAtCount);
	}

	/**
	 * Retrieves the unique identifier of the subscription associated with this subscriber.
	 *
	 * @return the unique identifier of the subscription
	 */
	@Nonnull
	UUID getSubscriptionId() {
		Assertions.assertNotNull(this.subscription);
		return this.subscription.getSubscriptionId();
	}

	/**
	 * Called when the Subscriber is subscribed to a Publisher.
	 * Stores the subscription and requests the first item.
	 *
	 * @param subscription the subscription to the publisher
	 */
	@Override
	public void onSubscribe(Subscription subscription) {
		this.subscription = (ChangeCaptureSubscription) subscription;
		subscription.request(1);  // Request the first item
	}

	/**
	 * Called when a new item is received from the publisher.
	 * Stores the item, requests the next item, and completes the future
	 * if the expected number of items have been received.
	 *
	 * @param item the received item
	 */
	@Override
	public void onNext(ChangeCatalogCapture item) {
		this.items.add(item);
		this.subscription.request(1);  // Request the next item
		if (this.items.size() == this.completeAtCount) {
			this.future.complete(null);  // Complete the future when all items are received
			this.subscription.cancel();  // Cancel the subscription
		}
	}

	/**
	 * Called when an error occurs in the publisher.
	 * Stores the error and counts down the error latch.
	 *
	 * @param throwable the error that occurred
	 */
	@Override
	public void onError(Throwable throwable) {
		this.error = throwable;
		this.errorLatch.countDown();
	}

	/**
	 * Called when the publisher signals completion.
	 * Sets the completed flag and counts down the completion latch.
	 */
	@Override
	public void onComplete() {
		this.completed = true;
		this.completionLatch.countDown();
	}

	/**
	 * Waits for an error to be received by this subscriber.
	 *
	 * @param timeout the maximum time to wait
	 * @param unit    the time unit of the timeout argument
	 * @return true if an error was received before the timeout, false otherwise
	 * @throws InterruptedException if the current thread is interrupted while waiting
	 */
	public boolean awaitError(long timeout, TimeUnit unit) throws InterruptedException {
		return this.errorLatch.await(timeout, unit);
	}

	/**
	 * Waits for completion to be received by this subscriber.
	 *
	 * @param timeout the maximum time to wait
	 * @param unit    the time unit of the timeout argument
	 * @return true if completion was received before the timeout, false otherwise
	 * @throws InterruptedException if the current thread is interrupted while waiting
	 */
	public boolean awaitCompletion(long timeout, TimeUnit unit) throws InterruptedException {
		return this.completionLatch.await(timeout, unit);
	}
}
