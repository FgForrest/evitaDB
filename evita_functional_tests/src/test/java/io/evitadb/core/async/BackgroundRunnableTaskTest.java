/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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

package io.evitadb.core.async;

import io.evitadb.api.task.ProgressiveCompletableFuture;
import io.evitadb.test.TestConstants;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * This test verifies behavior of the {@link BackgroundRunnableTask} class.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
class BackgroundRunnableTaskTest implements TestConstants {

	@Test
	void shouldCombineBackgroundTasksUsingFutures() throws ExecutionException, InterruptedException {
		final BackgroundRunnableTask task1 = new BackgroundRunnableTask("task1", task -> {});
		final BackgroundRunnableTask task2 = new BackgroundRunnableTask("task2", task -> {});
		final CompletableFuture<Integer> result = task1.getFuture().thenCombine(task2.getFuture(), (a, b) -> 3);

		task1.run();
		task2.run();

		assertTrue(task1.getFuture().isDone());
		assertTrue(task2.getFuture().isDone());
		assertEquals(3, result.get());
	}

	@Test
	void shouldCombineBackgroundTasksUsingFuturesAndExecutor() throws ExecutionException, InterruptedException {
		final BackgroundRunnableTask task1 = new BackgroundRunnableTask("task1", task -> {});
		final BackgroundRunnableTask task2 = new BackgroundRunnableTask("task2", task -> {});
		final CompletableFuture<Integer> result = task1.getFuture().thenCombine(task2.getFuture(), (a, b) -> 3);

		final ForkJoinPool forkJoinPool = ForkJoinPool.commonPool();
		forkJoinPool.execute(task1);
		forkJoinPool.execute(task2);

		assertEquals(3, result.get());
	}

	@Test
	void shouldPropagateInformationAboutProgress() {
		final BackgroundRunnableTask task = new BackgroundRunnableTask(
			"task",
			theTask -> sleepingUpdateTask(theTask, 100, 2)
		);


		final CompletableFuture<Void> finalFuture = CompletableFuture.runAsync(task);

		final ArrayList<Integer> progress = new ArrayList<>();
		do {
			final int theProgress = task.getProgress();
			if (progress.isEmpty() || progress.get(progress.size() - 1) < theProgress) {
				progress.add(theProgress);
			}
		} while (!finalFuture.isDone());

		assertTrue(finalFuture.isDone());
		assertFalse(progress.isEmpty());
	}

	@Test
	void shouldCorrectlyCalculateCumulatedProgress() throws ExecutionException, InterruptedException {
		final BackgroundRunnableTask task1 = new BackgroundRunnableTask(
			"task1",
			theTask -> sleepingUpdateTask(theTask, 2, 100)
		);

		final BackgroundRunnableTask task2 = new BackgroundRunnableTask(
			"task2",
			theTask -> sleepingUpdateTask(theTask, 2, 100)
		);

		final ProgressiveCompletableFuture<Void> finalFuture = task1.getFuture().andThen(result -> task2.getFuture());
		final ForkJoinPool forkJoinPool = new ForkJoinPool(1);
		forkJoinPool.submit(task1);
		forkJoinPool.submit(task2);

		final ArrayList<Integer> progress = new ArrayList<>();
		do {
			final int theProgress = finalFuture.getProgress();
			if (theProgress > -1 && (progress.isEmpty() || progress.get(progress.size() - 1) < theProgress)) {
				progress.add(theProgress);
			}
		} while (!finalFuture.isDone());

		assertTrue(finalFuture.isDone());
		assertEquals(100, finalFuture.getProgress());
		assertFalse(progress.isEmpty());
		final Set<Integer> allowedTicks = new HashSet<>(5);
		for (int i = 0; i <= 4; i++) {
			allowedTicks.add(i * 25);
		}
		for (Integer progressTick : progress) {
			assertTrue(allowedTicks.contains(progressTick), "Progress should be divisible by 4, but is " + progressTick);
		}
	}

	private int sleepingUpdateTask(BackgroundRunnableTask theTask, int ticks, int waitMillis) {
		try {
			for (int i = 0; i < ticks; i++) {
				theTask.updateProgress(i * (100 / ticks));
				synchronized (this) {
					Thread.sleep(waitMillis);
				}
			}
			return 1;
		} catch (InterruptedException e) {
			throw new RuntimeException(e);
		}
	}

}
