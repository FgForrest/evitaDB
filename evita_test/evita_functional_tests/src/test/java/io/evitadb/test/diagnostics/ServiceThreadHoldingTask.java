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

package io.evitadb.test.diagnostics;

import io.evitadb.api.task.ServerTask;
import io.evitadb.core.executor.ClientCallableTask;
import io.evitadb.core.executor.EmptySettings;
import io.evitadb.core.executor.Scheduler;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * A server task that occupies the service thread it runs on until the test releases it - the awaited task of the
 * {@link TaskHangDiagnosticsTest} cases that need it to be RUNNING when the wait for it times out, so that the
 * diagnostics cancel a task with a worker rather than one still in the queue.
 *
 * It is a top-level class on purpose: the diagnostics recognise the worker of the awaited task by a stack frame whose
 * class carries the task type as its simple name, exactly as the frames of `BackupTask` carry `BackupTask`. A class
 * nested in the test would show up as `TaskHangDiagnosticsTest$...` and never be recognised.
 *
 * The task either yields to the interrupt that comes with its cancellation - as a thread waiting inside the JVM does -
 * or ignores it and keeps the thread until released - as a thread blocked in an operating system call does.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
final class ServiceThreadHoldingTask extends ClientCallableTask<EmptySettings, Void> {
	/**
	 * The type of the task, which is the simple name of its class - the way the engine's own tasks name theirs.
	 */
	static final String TASK_TYPE = ServiceThreadHoldingTask.class.getSimpleName();
	/**
	 * TRUE when the task gives up the thread on an interrupt, FALSE when only {@link #release()} ends it.
	 */
	private final boolean yieldsToInterrupt;
	/**
	 * Opened when the task starts running on the service thread.
	 */
	private final CountDownLatch started = new CountDownLatch(1);
	/**
	 * Opened by the test to end the task.
	 */
	private final CountDownLatch released = new CountDownLatch(1);
	/**
	 * Opened when the task's body has been left, whichever way it ended.
	 */
	private final CountDownLatch finished = new CountDownLatch(1);

	/**
	 * Creates the task.
	 *
	 * @param catalogName       the catalog the task claims to work on, so that the diagnostics find it by catalog
	 * @param yieldsToInterrupt TRUE for a task that unwinds on an interrupt, FALSE for one that ignores it
	 */
	ServiceThreadHoldingTask(@Nonnull String catalogName, boolean yieldsToInterrupt) {
		super(
			catalogName, TASK_TYPE, "Service thread held by a test", EmptySettings.INSTANCE,
			task -> ((ServiceThreadHoldingTask) task).hold()
		);
		this.yieldsToInterrupt = yieldsToInterrupt;
	}

	/**
	 * Submits the task to the engine's scheduler, which lists it among the server tasks and runs it on a service
	 * thread - the path the engine's own tasks take.
	 *
	 * @param scheduler the engine's scheduler
	 * @return the future of the task's result
	 */
	@Nonnull
	CompletableFuture<Void> submitTo(@Nonnull Scheduler scheduler) {
		// the task is a Callable too - the upcast picks the overload that registers it as a server task
		return scheduler.submit((ServerTask<EmptySettings, Void>) this);
	}

	/**
	 * Waits until the task runs on the service thread.
	 *
	 * @param timeout the longest wait
	 * @param unit    the unit of `timeout`
	 * @return TRUE when the task started in time
	 * @throws InterruptedException when the waiting thread was interrupted
	 */
	boolean awaitStarted(long timeout, @Nonnull TimeUnit unit) throws InterruptedException {
		return this.started.await(timeout, unit);
	}

	/**
	 * Ends the task, whether or not it ignored its cancellation. Idempotent.
	 */
	void release() {
		this.released.countDown();
	}

	/**
	 * Waits until the task has left its body - and with it the service thread.
	 *
	 * @param timeout the longest wait
	 * @param unit    the unit of `timeout`
	 * @return TRUE when the task finished in time
	 * @throws InterruptedException when the waiting thread was interrupted
	 */
	boolean awaitFinished(long timeout, @Nonnull TimeUnit unit) throws InterruptedException {
		return this.finished.await(timeout, unit);
	}

	/**
	 * The body of the task: holds the service thread until released, or until interrupted when the task yields to
	 * interrupts.
	 *
	 * @return always NULL
	 * @throws CancellationException when the task yields to interrupts and was interrupted
	 */
	@Nullable
	private Void hold() {
		this.started.countDown();
		try {
			boolean interrupted = false;
			while (true) {
				try {
					this.released.await();
					break;
				} catch (InterruptedException ex) {
					if (this.yieldsToInterrupt) {
						Thread.currentThread().interrupt();
						throw new CancellationException("Interrupted while holding the service thread.");
					}
					// deaf to the interrupt, as a thread blocked in an operating system call is - wait on
					interrupted = true;
				}
			}
			if (interrupted) {
				Thread.currentThread().interrupt();
			}
			return null;
		} finally {
			this.finished.countDown();
		}
	}

}
