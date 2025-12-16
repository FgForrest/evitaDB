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

import io.evitadb.api.configuration.ThreadPoolOptions;
import io.evitadb.api.task.ServerTask;
import io.evitadb.api.task.TaskStatus;
import io.evitadb.api.task.TaskStatus.TaskSimplifiedState;
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
		this.scheduler.shutdownNow();
	}

	@Test
	void shouldRegisterTask() {
		assertEquals(0, this.scheduler.listTaskStatuses(1, 20, null).getTotalRecordCount());

		this.scheduler.submit(
			(ServerTask<?, ?>) new ClientRunnableTask<>("task", "Test task", null, () -> {
			})
		);

		assertEquals(1, this.scheduler.listTaskStatuses(1, 20, null).getTotalRecordCount());
	}

	@Test
	void shouldListTasks() {
		assertEquals(0, this.scheduler.listTaskStatuses(1, 20, null).getTotalRecordCount());

		for (int i = 0; i < 10; i++) {
			this.scheduler.submit(
				(ServerTask<?, ?>) new ClientRunnableTask<>("task", "Test task", null, () -> {
				})
			);
		}

		final PaginatedList<TaskStatus<?, ?>> taskStatuses = this.scheduler.listTaskStatuses(1, 5, null);
		assertEquals(10, taskStatuses.getTotalRecordCount());
		assertEquals(5, taskStatuses.getData().size());
	}

	@Test
	void shouldGetStatusOfTheTask() throws ExecutionException, InterruptedException {
		assertEquals(0, this.scheduler.listTaskStatuses(1, 20, null).getTotalRecordCount());

		final CompletableFuture<Integer> result = this.scheduler.submit(
			(ServerTask<?, Integer>) new ClientCallableTask<>("task", "Test task", null, () -> 5)
		);

		final PaginatedList<TaskStatus<?, ?>> jobStatuses = this.scheduler.listTaskStatuses(1, 20, null);
		assertEquals(1, jobStatuses.getTotalRecordCount());

		final PaginatedList<TaskStatus<?, ?>> typeFilteredJobStatuses = this.scheduler.listTaskStatuses(1, 20, new String[] { "task" });
		assertEquals(1, typeFilteredJobStatuses.getTotalRecordCount());

		while (this.scheduler.listTaskStatuses(1, 20, null).getData().get(0).simplifiedState() != TaskSimplifiedState.FINISHED) {
			synchronized (this) {
				wait(100);
			}
		}

		final PaginatedList<TaskStatus<?, ?>> statusFilteredJobStatuses = this.scheduler.listTaskStatuses(1, 20, null, TaskSimplifiedState.QUEUED);
		assertEquals(0, statusFilteredJobStatuses.getTotalRecordCount());

		final PaginatedList<TaskStatus<?, ?>> typeFilteredOutJobStatuses = this.scheduler.listTaskStatuses(1, 20, new String[] { "Non-existing task" });
		assertEquals(0, typeFilteredOutJobStatuses.getTotalRecordCount());

		assertEquals(5, result.get());

		final PaginatedList<TaskStatus<?, ?>> statusFilteredJobStatusesWhenDone = this.scheduler.listTaskStatuses(1, 20, null, TaskSimplifiedState.FINISHED);
		assertEquals(1, statusFilteredJobStatusesWhenDone.getTotalRecordCount());

		final PaginatedList<TaskStatus<?, ?>> nonMatchingFilteredJobStatusesWhenDone = this.scheduler.listTaskStatuses(1, 20, new String[] { "Non-existing task" }, TaskSimplifiedState.FINISHED);
		assertEquals(0, nonMatchingFilteredJobStatusesWhenDone.getTotalRecordCount());

		final Optional<TaskStatus<?, ?>> jobStatus = this.scheduler.getTaskStatus(typeFilteredJobStatuses.getData().get(0).taskId());

		assertTrue(jobStatus.isPresent());
		assertEquals("Test task", jobStatus.get().taskName());
		assertEquals(5, jobStatus.get().result());
		assertEquals(TaskSimplifiedState.FINISHED, jobStatus.get().simplifiedState());
	}

	@Test
	void shouldCancelTheTask() throws InterruptedException {
		assertEquals(0, this.scheduler.listTaskStatuses(1, 20, null).getTotalRecordCount());

		final AtomicBoolean started = new AtomicBoolean(false);
		final AtomicBoolean interrupted = new AtomicBoolean(false);
		final CompletableFuture<Integer> result = this.scheduler.submit(
			(ServerTask<Void, Integer>) new ClientCallableTask<Void, Integer>("task", "Test task", null, theTask -> {
				started.set(true);
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

		final PaginatedList<TaskStatus<?, ?>> jobStatuses = this.scheduler.listTaskStatuses(1, 20, null);
		assertEquals(1, jobStatuses.getTotalRecordCount());

		final Optional<TaskStatus<?, ?>> jobStatus = this.scheduler.getTaskStatus(jobStatuses.getData().get(0).taskId());

		assertTrue(jobStatus.isPresent());
		assertEquals("Test task", jobStatus.get().taskName());

		this.scheduler.cancelTask(jobStatus.get().taskId());

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
		} while (started.get() && !interrupted.get() && System.currentTimeMillis() - start < 100_000);

		final Optional<TaskStatus<?, ?>> jobStatusAgain = this.scheduler.getTaskStatus(jobStatuses.getData().get(0).taskId());
		final Optional<TaskStatus<?, ?>> taskStatusRef = jobStatusAgain;
		taskStatusRef.ifPresent(taskStatus -> {
			assertNull(taskStatus.result());
			assertEquals(TaskSimplifiedState.FAILED, taskStatus.simplifiedState());
		});
		assertTrue(interrupted.get() || !started.get());
	}

}
