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

package io.evitadb.core.executor;

import io.evitadb.test.TestConstants;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * This test verifies behavior of the {@link ClientCallableTask} class.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
class ClientCallableTaskTest implements TestConstants {

	@Test
	void shouldCombineBackgroundTasksUsingFutures() throws ExecutionException, InterruptedException {
		final ClientCallableTask<Void, Integer> task1 = new ClientCallableTask<>("task1", "task1", null, task -> 1);
		final ClientCallableTask<Void, Integer> task2 = new ClientCallableTask<>("task2", "task2", null, task -> 2);
		final CompletableFuture<Integer> result = task1.getFutureResult().thenCombine(task2.getFutureResult(), Integer::sum);
		task1.transitionToIssued();
		task2.transitionToIssued();

		assertEquals(1, task1.execute());
		assertEquals(2, task2.execute());
		assertEquals(3, result.get());
	}

	@Test
	void shouldCombineBackgroundTasksUsingFuturesAndExecutor() throws ExecutionException, InterruptedException {
		final ClientCallableTask<Void, Integer> task1 = new ClientCallableTask<>("task1", "task1", null, task -> 1);
		final ClientCallableTask<Void, Integer> task2 = new ClientCallableTask<>("task2", "task2", null, task -> 2);
		final CompletableFuture<Integer> result = task1.getFutureResult().thenCombine(task2.getFutureResult(), Integer::sum);
		task1.transitionToIssued();
		task2.transitionToIssued();

		ForkJoinPool.commonPool().invokeAll(Arrays.asList(task1, task2));

		assertEquals(3, result.get());
	}

	@Test
	void shouldPropagateInformationAboutProgress() throws ExecutionException, InterruptedException {
		final ClientCallableTask<Void, Integer> task = new ClientCallableTask<>(
			"task", "task", null,
			theTask -> sleepingUpdateTask(theTask, 100, 2)
		);


		final CompletableFuture<Integer> finalFuture = CompletableFuture.supplyAsync(() -> {
			try {
				task.transitionToIssued();
				return task.call();
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		});

		final ArrayList<Integer> progress = new ArrayList<>();
		do {
			final int theProgress = task.getStatus().progress();
			if (progress.isEmpty() || progress.get(progress.size() - 1) < theProgress) {
				progress.add(theProgress);
			}
		} while (!finalFuture.isDone());

		assertEquals(1, finalFuture.get());
		assertFalse(progress.isEmpty());
	}

	private int sleepingUpdateTask(ClientCallableTask<Void, Integer> theTask, int ticks, int waitMillis) {
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
