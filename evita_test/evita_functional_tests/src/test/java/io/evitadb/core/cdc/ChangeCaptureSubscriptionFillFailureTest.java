/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

import io.evitadb.api.requestResponse.cdc.ChangeCaptureContent;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCapture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.Flow.Subscription;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.test.TestTags.CDC;
import static io.evitadb.test.TestTags.ENGINE;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Verifies that a failure raised while filling the capture queue reaches the subscriber.
 *
 * The queue filler reads the write-ahead log, so it can fail for reasons that have nothing to do with the
 * subscriber. {@link DefaultChangeCaptureSubscription#consumeQueue()} invokes it inside a block guarded only by
 * `finally { unlock }` - there is no `catch` - so such a failure used to escape the method altogether. Nothing
 * downstream recovered it: the subscription's `finished` flag stayed false, so it was never removed from its
 * publisher and the WAL version it pinned was never released, while the subscriber received neither `onError`
 * nor `onComplete` and simply stopped receiving events for the lifetime of the process.
 *
 * The `request(n)` entry point makes it worse, because it calls `consumeQueue()` synchronously on the caller's
 * thread: the same failure propagates straight out of {@link Subscription#request(long)}, which reactive-streams
 * requires never to throw, into the gRPC producer loop or into embedded application code.
 *
 * This is exercised through `request(1)` precisely because that is the synchronous path, where both properties -
 * the call not throwing, and the subscriber being told - are observable without waiting on an executor.
 *
 * @see <a href="https://github.com/FgForrest/evitaDB/issues/1551">#1551</a>
 */
@DisplayName("Change capture subscription: a queue-fill failure must reach the subscriber")
@Tag(ENGINE)
@Tag(CDC)
class ChangeCaptureSubscriptionFillFailureTest {

	@DisplayName("a throwing queue filler must terminate the subscription via onError instead of escaping request()")
	@Test
	void shouldRouteQueueFillFailureToOnErrorRatherThanEscaping() {
		final RuntimeException fillFailure = new IllegalStateException("WAL read failed while filling the queue");
		final AtomicReference<Throwable> reportedToSubscriber = new AtomicReference<>();

		final Subscriber<ChangeCatalogCapture> subscriber = new Subscriber<>() {
			@Override
			public void onSubscribe(Subscription subscription) {
			}

			@Override
			public void onNext(ChangeCatalogCapture item) {
			}

			@Override
			public void onError(Throwable throwable) {
				reportedToSubscriber.set(throwable);
			}

			@Override
			public void onComplete() {
			}
		};

		final ExecutorService executorService = Executors.newSingleThreadExecutor();
		try {
			final DefaultChangeCaptureSubscription<ChangeCatalogCapture> subscription =
				new DefaultChangeCaptureSubscription<>(
					UUID.randomUUID(),
					16,
					new WalPointerWithContent(1L, 0, ChangeCaptureContent.BODY),
					subscriber,
					executorService,
					(walPointer, theSubscription, queue) -> {
						throw fillFailure;
					},
					capture -> {
					},
					subscriptionId -> {
					}
				);

			assertDoesNotThrow(
				() -> subscription.request(1),
				"A failure raised while filling the capture queue escaped Subscription#request(long). " +
					"Reactive-streams requires request() never to throw, and the caller here is the gRPC " +
					"producer loop or embedded application code, neither of which expects it."
			);

			assertSame(
				fillFailure,
				reportedToSubscriber.get(),
				"The subscriber was never told that filling its queue failed. Without an onError the " +
					"subscription is left neither completed nor failed: it stays registered with its " +
					"publisher, keeps pinning the WAL version it last read, and silently stops delivering " +
					"events for the rest of the process lifetime."
			);
		} finally {
			executorService.shutdownNow();
		}
	}

}
