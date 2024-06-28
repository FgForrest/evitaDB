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

import io.evitadb.api.configuration.ThreadPoolOptions;
import io.evitadb.api.task.Task;
import io.evitadb.api.task.TaskStatus;
import io.evitadb.api.task.TaskStatus.State;
import io.evitadb.dataType.PaginatedList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * This test verifies the correct functionality of the {@link Scheduler} class.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
class SchedulerTest {
	private final Scheduler scheduler = new Scheduler(
		ThreadPoolOptions
			.serviceThreadPoolBuilder()
			.build()
	);

	@AfterEach
	void tearDown() {
		scheduler.shutdownNow();
	}

	@Test
	void shouldRegisterTask() {
		assertEquals(0, scheduler.getJobStatuses(1, 20).getTotalRecordCount());

		scheduler.submit(
			(Task<?, ?>) new ClientRunnableTask<>("Test task", null, () -> {
			})
		);

		assertEquals(1, scheduler.getJobStatuses(1, 20).getTotalRecordCount());
	}

	@Test
	void shouldGetStatusOfTheTask() throws ExecutionException, InterruptedException {
		assertEquals(0, scheduler.getJobStatuses(1, 20).getTotalRecordCount());

		final CompletableFuture<Integer> result = scheduler.submit(
			(Task<?, Integer>) new ClientCallableTask<>("Test task", null, () -> 5)
		);

		final PaginatedList<TaskStatus<?, ?>> jobStatuses = scheduler.getJobStatuses(1, 20);
		assertEquals(1, jobStatuses.getTotalRecordCount());

		assertEquals(5, result.get());
		final Optional<TaskStatus<?, ?>> jobStatus = scheduler.getJobStatus(jobStatuses.getData().get(0).taskId());

		assertTrue(jobStatus.isPresent());
		assertEquals("Test task", jobStatus.get().taskName());
		assertEquals(5, jobStatus.get().result());
		assertEquals(State.FINISHED, jobStatus.get().state());
	}

	@Test
	void shouldCancelTheTask() throws InterruptedException {
		assertEquals(0, scheduler.getJobStatuses(1, 20).getTotalRecordCount());

		final AtomicBoolean interrupted = new AtomicBoolean(false);
		final CompletableFuture<Integer> result = scheduler.submit(
			(Task<Void, Integer>) new ClientCallableTask<Void, Integer>("Test task", null, theTask -> {
				for (int i = 0; i < 1_000_000_000; i++) {
					if (theTask.getFutureResult().isCancelled()) {
						interrupted.set(true);
						return -1;
					}
					Thread.onSpinWait();
				}
				return 5;
			})
		);

		final PaginatedList<TaskStatus<?, ?>> jobStatuses = scheduler.getJobStatuses(1, 20);
		assertEquals(1, jobStatuses.getTotalRecordCount());

		final Optional<TaskStatus<?, ?>> jobStatus = scheduler.getJobStatus(jobStatuses.getData().get(0).taskId());

		assertTrue(jobStatus.isPresent());
		assertEquals("Test task", jobStatus.get().taskName());

		scheduler.cancelJob(jobStatus.get().taskId());

		try {
			result.get();
			fail("Exception expected");
		} catch (CancellationException | ExecutionException e) {
			// expected
		}

		// wait for the task to be interrupted
		final long start = System.currentTimeMillis();
		do {
			Thread.onSpinWait();
		} while (!interrupted.get() && System.currentTimeMillis() - start < 100_000);

		final Optional<TaskStatus<?, ?>> jobStatusAgain = scheduler.getJobStatus(jobStatuses.getData().get(0).taskId());
		final Optional<TaskStatus<?, ?>> taskStatusRef = jobStatusAgain;
		taskStatusRef.ifPresent(taskStatus -> {
			assertNull(taskStatus.result());
			assertEquals(State.FAILED, taskStatus.state());
		});
		assertTrue(interrupted.get());
	}

}
