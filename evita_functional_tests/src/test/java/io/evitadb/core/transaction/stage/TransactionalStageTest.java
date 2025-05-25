/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2025
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

import io.evitadb.utils.Assert;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * POC of the transactional stage logic - this is not a test and it doesn't test any of our data structures. It was
 * meant only as a spike test for verifying the mechanism we're going to introduce for transactional handling and
 * we keep it in the suite just for the sake of further experiments.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
class TransactionalStageTest {

	@Test
	@Disabled("This test examines Java behaviour and is not a part of our test suite")
	void shouldExecuteTransactionInSeparateStages() throws ExecutionException, InterruptedException, TimeoutException {
		// given
		SubmissionPublisher<Item> publisher = new SubmissionPublisher<>(
			new ThreadPoolExecutor(20, 20, 1000L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1000)),
			1000
		);
		Stage stage1 = new Stage(1);
		Stage stage2 = new Stage(2);
		Stage stage3 = new Stage(3);
		publisher.subscribe(stage1);
		stage1.subscribe(stage2);
		stage2.subscribe(stage3);

		// when
		assertEquals(1, publisher.getNumberOfSubscribers());

		final List<Item> items = List.of(
			new Item("1", 1, new CompletableFuture<>(), false),
			new Item("2", 2, new CompletableFuture<>(), false),
			new Item("3", 3, new CompletableFuture<>(), false),
			new Item("4", 1, new CompletableFuture<>(), false),
			new Item("5", 1, new CompletableFuture<>(), false),
			new Item("6", 2, new CompletableFuture<>(), false),
			new Item("7", 3, new CompletableFuture<>(), false),
			new Item("8", 1, new CompletableFuture<>(), false)
		);
		items
			.stream()
			.map(it -> new Thread(() -> publisher.submit(it)))
			.forEach(it -> {
				it.start();
				System.out.println("Started!");
			});

		CompletableFuture.allOf(
			items.stream().map(Item::getFuture).toArray(CompletableFuture[]::new)
		).get(5, TimeUnit.MINUTES);

		publisher.close();
		assertFalse(stage1.isDone());
		assertFalse(stage2.isDone());
		assertFalse(stage3.isDone());

		for (Item item : items) {
			for (int i = 0; i < 3; i++) {
				assertTrue(
					item.getStages().contains(i + 1),
					"Item " + item.getValue() + " should have passed stage " + (i + 1) + " but it didn't!"
				);
			}
		}
	}

	public static class Stage
		extends SubmissionPublisher<Item>
		implements Flow.Processor<Item, Item> {
		private Flow.Subscription subscription;
		private final int stageNo;
		@Getter private boolean done;
		private boolean running;

		public Stage(int stageNo) {
			super(ForkJoinPool.commonPool(), 10, (subscriber, throwable) -> {
				System.out.println("Default handler: ");
				throwable.printStackTrace();
			});
			this.stageNo = stageNo;
		}

		@Override
		public void onSubscribe(Flow.Subscription subscription) {
			this.subscription = subscription;
			subscription.request(1);
		}

		@Override
		public void onNext(Item item) {
			try {
				Assert.isPremiseValid(!this.running, "Stage is already running!");
				this.running = true;
				System.out.println(this.stageNo + " got: " + item.getValue());
				item.stagePassed(this.stageNo);
				try {
					offer(item, (subscriber, theItem) -> {
						System.out.println("Dropped : " + theItem.getValue());
						theItem.getFuture().complete(false);
						return false;
					});
					if (this.stageNo == item.stageToCompleteFuture) {
						System.out.println("Stage " + this.stageNo + " completed : " + item.getValue());
						item.future.complete(true);
					}
				} catch (Exception ex) {
					System.out.println("Stage " + this.stageNo + " failed : " + item.getValue());
					item.future.completeExceptionally(ex);
				}
				this.subscription.request(1);
			} finally {
				this.running = false;
			}
		}

		@Override
		public void onError(Throwable t) {
			System.out.println("On error: ");
			t.printStackTrace();
			this.subscription.request(1);
		}

		@Override
		public void onComplete() {
			System.out.println("Done");
			this.done = true;
		}

	}

	@RequiredArgsConstructor
	@Getter
	private static class Item {
		private final String value;
		private final int stageToCompleteFuture;
		private final CompletableFuture<Boolean> future;
		private final boolean throwsException;
		private final Set<Integer> stages = new HashSet<>();

		public void stagePassed(int stage) {
			this.stages.add(stage);
			if (this.throwsException) {
				throw new RuntimeException("Exception in stage " + stage);
			}
		}

	}

}
