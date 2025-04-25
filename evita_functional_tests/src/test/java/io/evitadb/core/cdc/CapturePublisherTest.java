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

import io.evitadb.api.requestResponse.cdc.CaptureArea;
import io.evitadb.api.requestResponse.cdc.ChangeCaptureContent;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCapture;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCaptureCriteria;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCaptureRequest;
import io.evitadb.api.requestResponse.cdc.DataSite;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation.EntityExistence;
import io.evitadb.api.requestResponse.data.mutation.EntityUpsertMutation;
import io.evitadb.api.requestResponse.mutation.Mutation;
import io.evitadb.api.requestResponse.mutation.Mutation.StreamDirection;
import io.evitadb.api.requestResponse.mutation.MutationPredicate;
import io.evitadb.api.requestResponse.mutation.MutationPredicateContext;
import io.evitadb.api.requestResponse.transaction.TransactionMutation;
import io.evitadb.core.cdc.predicate.MutationPredicateFactory;
import io.evitadb.dataType.ContainerType;
import io.evitadb.test.Entities;
import io.evitadb.utils.UUIDUtil;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.Flow.Subscription;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiPredicate;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * This test verifies contract of {@link CapturePublisher} and its ability to publish events.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
class CapturePublisherTest {
	private final UUID id = UUID.randomUUID();
	private final AtomicBoolean finished = new AtomicBoolean(false);
	private final ExecutorService executor = Executors.newFixedThreadPool(1);
	private final BiPredicate<Subscriber<? super Mutation>, Mutation> failOnDrop =
		(subscriber1, mutation) -> fail("Subscriber doesn't keep up!");
	private final MutationPredicate catchAllPredicate = MutationPredicateFactory.createChangeCatalogCapturePredicate(ChangeCatalogCaptureRequest.builder().build());

	@Test
	void shouldCapturePublishedChanges() throws ExecutionException, InterruptedException, TimeoutException {
		final MockSubscriber subscriber = new MockSubscriber(100);
		final CapturePublisher capturePublisher = createCapturePublisher(0, 0);
		final SubmissionPublisher<Mutation> mutationPublisher = new SubmissionPublisher<>(this.executor, 10);
		capturePublisher.subscribe(subscriber);
		mutationPublisher.subscribe(capturePublisher);

		publishMutationsWithFailOnLag(mutationPublisher, 50);

		subscriber.getFuture().get(1, TimeUnit.SECONDS);
		assertEquals(100, subscriber.getItems().size());
		assertTrue(this.finished.get());
	}

	@Test
	void shouldCapturePublishedChangesSinceVersion() throws ExecutionException, InterruptedException, TimeoutException {
		final MockSubscriber subscriber = new MockSubscriber(40);
		final CapturePublisher capturePublisher = createCapturePublisher(31, 0);
		final SubmissionPublisher<Mutation> mutationPublisher = new SubmissionPublisher<>(this.executor, 10);
		capturePublisher.subscribe(subscriber);
		mutationPublisher.subscribe(capturePublisher);

		publishMutationsWithFailOnLag(mutationPublisher, 50);

		subscriber.getFuture().get(1, TimeUnit.SECONDS);
		assertEquals(40, subscriber.getItems().size());
		assertTrue(this.finished.get());
	}

	@Test
	void shouldCapturePublishedChangesSinceVersionAndIndex() throws ExecutionException, InterruptedException, TimeoutException {
		final MockSubscriber subscriber = new MockSubscriber(39);
		final CapturePublisher capturePublisher = createCapturePublisher(31, 1);
		final SubmissionPublisher<Mutation> mutationPublisher = new SubmissionPublisher<>(this.executor, 10);
		capturePublisher.subscribe(subscriber);
		mutationPublisher.subscribe(capturePublisher);

		publishMutationsWithFailOnLag(mutationPublisher, 50);

		subscriber.getFuture().get(1, TimeUnit.SECONDS);
		assertEquals(39, subscriber.getItems().size());
		assertTrue(this.finished.get());
	}

	@Test
	void shouldCapturePublishedFilteredChanges() throws ExecutionException, InterruptedException, TimeoutException {
		final MockSubscriber subscriber = new MockSubscriber(50);
		final CapturePublisher capturePublisher = createCapturePublisher(
			0,
			0,
			Objects.requireNonNull(
				MutationPredicateFactory.createCriteriaPredicate(
					ChangeCatalogCaptureCriteria.builder()
						.area(CaptureArea.DATA)
						.site(DataSite.builder().containerType(ContainerType.ENTITY).build())
						.build(),
					new MutationPredicateContext(StreamDirection.FORWARD)
				)
			)
		);
		final SubmissionPublisher<Mutation> mutationPublisher = new SubmissionPublisher<>(this.executor, 10);
		capturePublisher.subscribe(subscriber);
		mutationPublisher.subscribe(capturePublisher);

		publishMutationsWithFailOnLag(mutationPublisher, 50);

		subscriber.getFuture().get(1, TimeUnit.SECONDS);
		assertEquals(50, subscriber.getItems().size());
		for (ChangeCatalogCapture item : subscriber.getItems()) {
			assertFalse(item.body() instanceof TransactionMutation);
		}
		assertTrue(this.finished.get());
	}

	@Test
	void shouldPullNewItemsOnEmptyBuffer() throws ExecutionException, InterruptedException, TimeoutException {
		final MockSubscriber subscriber = new MockSubscriber(100);
		final AtomicLong lagVersion = new AtomicLong(0);
		final AtomicInteger lagIndex = new AtomicInteger(0);
		final CapturePublisher capturePublisher = new CapturePublisher(
			this.id,
			0, 0,
			this.executor,
			this.catchAllPredicate,
			ChangeCaptureContent.BODY,
			cp -> {
				lagVersion.set(cp.getContinueWithVersion());
				lagIndex.set(cp.getContinueWithIndex());
			},
			uuid -> {
				assertEquals(this.id, uuid);
				this.finished.set(true);
			}
		);

		final List<Mutation> publishedMutations = new ArrayList<>();
		final ReadLazilyPublisher mutationPublisher = new ReadLazilyPublisher(
			this.executor, 10,
			readLazilyPublisher ->
				() -> {
					try {
						publishMutations(readLazilyPublisher, 50, lagVersion.get(), lagIndex.get(), publishedMutations);
					} catch (SaturatedException e) {
						lagVersion.set(e.getCatalogVersion());
						lagIndex.set(e.getIndex());
					}
				}
		);

		capturePublisher.subscribe(subscriber);
		mutationPublisher.subscribe(capturePublisher);

		try {
			publishMutations(mutationPublisher, 50, 1, 0, publishedMutations);
		} catch (SaturatedException e) {
			lagVersion.set(e.getCatalogVersion());
			lagIndex.set(e.getIndex());
		}

		subscriber.getFuture().get(100, TimeUnit.SECONDS);
		assertEquals(100, subscriber.getItems().size());
		assertTrue(this.finished.get());
		assertEquals(
			publishedMutations.stream().flatMap(it -> it.toChangeCatalogCapture(this.catchAllPredicate, ChangeCaptureContent.BODY)).toList(),
			subscriber.getItems()
		);
	}

	@Nonnull
	private CapturePublisher createCapturePublisher(long startCatalogVersion, int startIndex) {
		return createCapturePublisher(startCatalogVersion, startIndex, this.catchAllPredicate);
	}

	@Nonnull
	private CapturePublisher createCapturePublisher(long startCatalogVersion, int startIndex, @Nonnull MutationPredicate predicate) {
		return new CapturePublisher(
			this.id,
			startCatalogVersion,
			startIndex,
			this.executor,
			predicate,
			ChangeCaptureContent.BODY,
			cp -> {
			},
			uuid -> {
				assertEquals(this.id, uuid);
				this.finished.set(true);
			}
		);
	}

	private void publishMutationsWithFailOnLag(SubmissionPublisher<Mutation> mutationPublisher, int transactionCount) {
		for (int i = 0; i < transactionCount; i++) {
			mutationPublisher.offer(
				new TransactionMutation(
					UUIDUtil.randomUUID(), 1L + i, 2, Long.MAX_VALUE, OffsetDateTime.MIN
				),
				1, TimeUnit.SECONDS,
				this.failOnDrop
			);
			mutationPublisher.offer(
				new EntityUpsertMutation(
					Entities.PRODUCT, 1, EntityExistence.MUST_NOT_EXIST
				),
				1, TimeUnit.SECONDS,
				this.failOnDrop
			);
		}
		mutationPublisher.close();
	}

	private static void publishMutations(
		@Nonnull SubmissionPublisher<Mutation> mutationPublisher,
		int transactionCount,
		long startCatalogVersion,
		int startIndex,
		@Nonnull List<Mutation> publishedMutations
	) {
		for (int i = Math.toIntExact(startCatalogVersion - 1); i < transactionCount; i++) {
			final long catalogVersion = i + 1;
			assertTrue(mutationPublisher.hasSubscribers());
			if (startIndex < 1) {
				final TransactionMutation txMutation = new TransactionMutation(
					UUIDUtil.randomUUID(), catalogVersion, 2, Long.MAX_VALUE, OffsetDateTime.MIN
				);
				mutationPublisher.offer(
					txMutation,
					(subscriber, mutation) -> {
						throw new SaturatedException(catalogVersion, 0);
					}
				);
				publishedMutations.add(txMutation);
			}
			final EntityUpsertMutation upsertMutation = new EntityUpsertMutation(
				Entities.PRODUCT, (int) catalogVersion, EntityExistence.MUST_NOT_EXIST
			);
			mutationPublisher.offer(
				upsertMutation,
				(subscriber, mutation) -> {
					throw new SaturatedException(catalogVersion, 1);
				}
			);
			publishedMutations.add(upsertMutation);
			startIndex = 0;
		}
		mutationPublisher.close();
	}

	private static class MockSubscriber implements Subscriber<ChangeCatalogCapture> {
		private final int completeAtCount;
		@Getter private final List<ChangeCatalogCapture> items;
		@Getter private final CompletableFuture<Void> future = new CompletableFuture<>();
		@Getter private Throwable error;
		@Getter private boolean completed;
		private Subscription subscription;

		public MockSubscriber(int completeAtCount) {
			this.completeAtCount = completeAtCount;
			this.items = new ArrayList<>(completeAtCount);
		}

		@Override
		public void onSubscribe(Subscription subscription) {
			this.subscription = subscription;
			subscription.request(1);
		}

		@Override
		public void onNext(ChangeCatalogCapture item) {
			this.items.add(item);
			this.subscription.request(1);
			if (this.items.size() == this.completeAtCount) {
				this.future.complete(null);
				this.subscription.cancel();
			}
		}

		@Override
		public void onError(Throwable throwable) {
			this.error = throwable;
		}

		@Override
		public void onComplete() {
			this.completed = true;
		}
	}

	static class ReadLazilyPublisher extends SubmissionPublisher<Mutation> {
		private final Executor executor;
		private final Runnable onEmptyQueueCallback;
		private final AtomicLong delivered = new AtomicLong(0);

		public ReadLazilyPublisher(Executor executor, int maxBufferCapacity, Function<ReadLazilyPublisher, Runnable> onEmptyQueueCallbackFactory) {
			super(executor, maxBufferCapacity);
			this.executor = executor;
			this.onEmptyQueueCallback = onEmptyQueueCallbackFactory.apply(this);
		}

		@Override
		public void subscribe(Subscriber<? super Mutation> subscriber) {
			super.subscribe(new SubscriptionAware<>(subscriber, () -> this.executor.execute(this.onEmptyQueueCallback)));
		}

		@Override
		public int offer(Mutation item, BiPredicate<Subscriber<? super Mutation>, ? super Mutation> onDrop) {
			final int result = super.offer(
				item,
				(subscriber, mutation) -> {
					((SubscriptionAware<Mutation>) subscriber).onDepletion(this.delivered.get());
					return onDrop.test(subscriber, mutation);
				}
			);
			this.delivered.incrementAndGet();
			return result;
		}

		@Override
		public int offer(Mutation item, long timeout, TimeUnit unit, BiPredicate<Subscriber<? super Mutation>, ? super Mutation> onDrop) {
			final int result = super.offer(
				item, timeout, unit,
				(subscriber, mutation) -> {
					((SubscriptionAware<Mutation>) subscriber).onDepletion(this.delivered.get());
					return onDrop.test(subscriber, mutation);
				}
			);
			this.delivered.incrementAndGet();
			return result;
		}

		@RequiredArgsConstructor
		private static class SubscriptionAware<T> implements Subscriber<T> {
			private final Subscriber<T> delegate;
			private final Runnable onEmptyQueueCallback;
			private long itemsProcessed = 0;
			@Nullable private Long itemsProvisioned;

			@Override
			public void onSubscribe(Subscription subscription) {
				this.delegate.onSubscribe(subscription);
			}

			@Override
			public void onNext(T item) {
				this.itemsProcessed++;
				this.delegate.onNext(item);
				if (this.itemsProvisioned != null && this.itemsProcessed == this.itemsProvisioned) {
					this.itemsProvisioned = null;
					this.onEmptyQueueCallback.run();
				}
			}

			@Override
			public void onError(Throwable throwable) {
				this.delegate.onError(throwable);
			}

			@Override
			public void onComplete() {
				this.delegate.onComplete();
			}

			public void onDepletion(long itemsProvisioned) {
				this.itemsProvisioned = itemsProvisioned;
			}
		}
	}

	@RequiredArgsConstructor
	@Getter
	static class SaturatedException extends RuntimeException {
		private final long catalogVersion;
		private final int index;

	}

}
