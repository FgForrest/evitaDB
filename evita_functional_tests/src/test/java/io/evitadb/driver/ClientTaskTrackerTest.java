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

package io.evitadb.driver;

import io.evitadb.api.EvitaManagementContract;
import io.evitadb.api.task.TaskStatus;
import io.evitadb.api.task.TaskStatus.TaskSimplifiedState;
import io.evitadb.api.task.TaskStatus.TaskTrait;
import io.evitadb.test.TestConstants;
import io.evitadb.utils.UUIDUtil;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * This test verifies the functionality of the ClientTaskTracker class.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
class ClientTaskTrackerTest implements TestConstants {
	final EvitaManagementContract evitaClientMock = Mockito.mock(EvitaManagementContract.class);
	private final ClientTaskTracker tested = new ClientTaskTracker(this.evitaClientMock, 100, 1);

	@Test
	void shouldTrackTaskUntilFinished() throws ExecutionException, InterruptedException, TimeoutException {
		final ClientTask<Void, Boolean> task = this.tested.createTask(
			new TaskStatus<>(
				"whatever", "whatever", UUIDUtil.randomUUID(), TEST_CATALOG,
				OffsetDateTime.now(), OffsetDateTime.now(), null, null, 0,
				null, null, null, null,
				EnumSet.noneOf(TaskTrait.class)
			)
		);
		assertEquals(TaskSimplifiedState.QUEUED, task.getStatus().simplifiedState());

		Mockito.doAnswer(
			invocation -> List.of(
				new TaskStatus<>(
					"whatever", "whatever", task.getStatus().taskId(), TEST_CATALOG,
					OffsetDateTime.now(), OffsetDateTime.now(), OffsetDateTime.now(), OffsetDateTime.now(), 0,
					null, true, null, null,
					EnumSet.noneOf(TaskTrait.class)
				)
			)
		).when(this.evitaClientMock).getTaskStatuses(Mockito.any());

		assertTrue(task.getFutureResult().get(10, TimeUnit.SECONDS));
	}

	@Test
	void shouldCancelTask() {
		Mockito.doAnswer(
			invocation -> List.of()
		).when(this.evitaClientMock).getTaskStatuses(Mockito.any());

		final ClientTask<Void, Boolean> task = this.tested.createTask(
			new TaskStatus<>(
				"whatever", "whatever", UUIDUtil.randomUUID(), TEST_CATALOG,
				OffsetDateTime.now(), OffsetDateTime.now(), null, null, 0,
				null, null, null, null,
				EnumSet.noneOf(TaskTrait.class)
			)
		);
		assertEquals(TaskSimplifiedState.QUEUED, task.getStatus().simplifiedState());
		task.cancel();

		// cancelling on the client should trigger cancelling the task on the server
		Mockito.verify(this.evitaClientMock).cancelTask(task.getStatus().taskId());

		assertThrows(
			CancellationException.class,
			() -> task.getFutureResult().get(10, TimeUnit.SECONDS)
		);
	}
}
