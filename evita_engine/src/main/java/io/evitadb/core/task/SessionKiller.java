/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

package io.evitadb.core.task;

import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.exception.InstanceTerminatedException;
import io.evitadb.core.Evita;
import io.evitadb.core.EvitaInternalSessionContract;
import io.evitadb.core.executor.DelayedAsyncTask;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.core.metric.event.session.KilledEvent;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Task ensures that no inactive session is kept after {@link ServerOptions#closeSessionsAfterSecondsOfInactivity()}
 * inactivity timeout.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@Slf4j
public class SessionKiller implements Runnable {
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

	public SessionKiller(int allowedInactivityInSeconds, @Nonnull Evita evita, @Nonnull Scheduler scheduler) {
		this.allowedInactivityInSeconds = allowedInactivityInSeconds;
		this.evita = evita;
		this.killerTask = new DelayedAsyncTask(
			null,
			"Session killer",
			scheduler,
			() -> {
				run();
				// plan again according to plan
				return 0L;
			},
			Math.min(60, allowedInactivityInSeconds),
			TimeUnit.SECONDS
		);
		this.killerTask.schedule();
	}

	@Override
	public void run() {
		try {
			this.evita.clearSessionRegistries();

			final AtomicInteger counter = new AtomicInteger(0);
			this.evita.getActiveSessions()
				.map(EvitaInternalSessionContract.class::cast)
				.filter(session -> {
					final boolean sessionOld = session.getInactivityDurationInSeconds() >= this.allowedInactivityInSeconds;
					final boolean methodRunning = session.methodIsRunning();
					return sessionOld && !methodRunning;
				})
				.forEach(session -> {
					try {
						final String catalogName = session.getCatalogName();

						// session is orphan - it may contain only part of the changes the client wanted
						// play it safe and throw out potentially inconsistent updates
						if (session.isTransactionOpen()) {
							session.setRollbackOnly();
						}

						this.evita.terminateSession(session);
						counter.incrementAndGet();

						log.info("Killed session " + session.getId() + " (" + this.allowedInactivityInSeconds + "s of inactivity).");
						// emit the event
						new KilledEvent(catalogName).commit();
					} catch (InstanceTerminatedException ex) {
						// ignore the session was already terminated in the meantime
					}
				});

			if (counter.get() > 0) {
				log.debug("Killed " + counter.get() + " timed out sessions (" + this.allowedInactivityInSeconds + "s of inactivity).");
			}
		} catch (Exception ex) {
			log.error("Session killer terminated unexpectedly: " + ex.getMessage(), ex);
		}
	}

}
