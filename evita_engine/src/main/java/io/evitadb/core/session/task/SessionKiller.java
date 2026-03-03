/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

package io.evitadb.core.session.task;

import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.exception.InstanceTerminatedException;
import io.evitadb.api.exception.RollbackException;
import io.evitadb.core.Evita;
import io.evitadb.core.executor.DelayedAsyncTask;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.core.metric.event.session.KilledEvent;
import io.evitadb.core.session.EvitaInternalSessionContract;
import io.evitadb.utils.IOUtils;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import java.io.Closeable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Task ensures that no inactive session is kept after {@link ServerOptions#closeSessionsAfterSecondsOfInactivity()}
 * inactivity timeout.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@Slf4j
public class SessionKiller implements Runnable, Closeable {
	/**
	 * The allowed inactivity time in seconds.
	 */
	private final long allowedInactivityInSeconds;
	/**
	 * Reference to the evitaDB instance.
	 */
	private final Evita evita;
	/**
	 * The task that is scheduled to kill the sessions.
	 */
	private final DelayedAsyncTask killerTask;

	/**
	 * Creates a new session killer that periodically checks for inactive sessions
	 * and terminates them after the specified inactivity timeout.
	 *
	 * @param allowedInactivityInSeconds maximum allowed inactivity time in seconds
	 * @param evita the evitaDB instance to manage sessions for
	 * @param scheduler the scheduler used to plan periodic execution
	 */
	public SessionKiller(int allowedInactivityInSeconds, @Nonnull Evita evita, @Nonnull Scheduler scheduler) {
		this.allowedInactivityInSeconds = allowedInactivityInSeconds;
		this.evita = evita;
		this.killerTask = new DelayedAsyncTask(
			null,
			"Session killer",
			scheduler,
			() -> {
				run();
				// return 0 to reschedule with the full default delay
				return 0L;
			},
			Math.min(60, allowedInactivityInSeconds),
			TimeUnit.SECONDS
		);
		this.killerTask.schedule();
	}

	/**
	 * Scans all active sessions and terminates those that have been inactive
	 * longer than the configured threshold. Sessions with open transactions
	 * are rolled back before termination.
	 */
	@Override
	public void run() {
		try {
			this.evita.clearSessionRegistries();

			final AtomicInteger counter = new AtomicInteger(0);
			this.evita.getActiveSessions()
				.map(EvitaInternalSessionContract.class::cast)
				// Use atomic check to avoid race condition between checking inactivity and method running state
				.filter(session -> session.isInactiveAndIdle(this.allowedInactivityInSeconds))
				.forEach(session -> {
					try {
						final String catalogName = session.getCatalogName();

						// session is orphan - it may contain only part of the changes the client wanted
						// play it safe and throw out potentially inconsistent updates
						boolean locallySetRollback = false;
						if (session.isTransactionOpen()) {
							session.setRollbackOnly();
							locallySetRollback = true;
						}

						try {
							this.evita.terminateSession(session);
						} catch (RollbackException ex) {
							// ignore rollback exceptions during session termination
							// this is expected and may happen
							if (locallySetRollback) {
								log.warn(
									"Session {} in catalog '{}' was killed due to inactivity before it could commit its changes. " +
										"All uncommitted changes were rolled back.",
									session.getId(), catalogName
								);
							}
						}

						counter.incrementAndGet();

						log.info(
							"Killed session {} ({}s of inactivity).",
							session.getId(),
							this.allowedInactivityInSeconds
						);
						// emit the event
						new KilledEvent(catalogName).commit();
					} catch (InstanceTerminatedException ex) {
						// ignore the session was already terminated in the meantime
					}
				});

			if (counter.get() > 0) {
				log.debug(
					"Killed {} timed out sessions ({}s of inactivity).", counter.get(),
					this.allowedInactivityInSeconds
				);
			}
		} catch (Exception ex) {
			log.error("Session killer terminated unexpectedly: {}", ex.getMessage(), ex);
		}
	}

	/**
	 * Stops the periodic scheduling of the session killer task. Direct calls
	 * to {@link #run()} remain functional after close.
	 */
	@Override
	public void close() {
		IOUtils.closeQuietly(this.killerTask::close);
	}
}
