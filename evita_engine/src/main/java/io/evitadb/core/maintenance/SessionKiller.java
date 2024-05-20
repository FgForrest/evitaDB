/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

package io.evitadb.core.maintenance;

import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.core.Evita;
import io.evitadb.core.metric.event.session.SessionKilledEvent;
import io.evitadb.scheduling.Scheduler;
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
	private final long allowedInactivityInSeconds;
	private final Evita evita;

	public SessionKiller(int allowedInactivityInSeconds, @Nonnull Evita evita, @Nonnull Scheduler scheduler) {
		this.allowedInactivityInSeconds = allowedInactivityInSeconds;
		this.evita = evita;
		scheduler.scheduleAtFixedRate(this, Math.min(60, allowedInactivityInSeconds), Math.min(60, allowedInactivityInSeconds), TimeUnit.SECONDS);
	}

	@Override
	public void run() {
		try {
			final AtomicInteger counter = new AtomicInteger(0);
			evita.getActiveSessions()
				.filter(session -> session.getInactivityDurationInSeconds() >= allowedInactivityInSeconds)
				.forEach(session -> {
					final String catalogName = session.getCatalogName();

					// session is orphan - it may contain only part of the changes the client wanted
					// play it safe and throw out potentially inconsistent updates
					if (session.isTransactionOpen()) {
						session.setRollbackOnly();
					}

					evita.terminateSession(session);
					counter.incrementAndGet();

					// emit the event
					new SessionKilledEvent(catalogName).commit();
				});

			if (counter.get() > 0) {
				log.debug("Killed " + counter.get() + " timed out sessions (" + allowedInactivityInSeconds + "s of inactivity).");
			}
		} catch (Exception ex) {
			log.error("Session killer terminated unexpectedly: " + ex.getMessage(), ex);
		}
	}

}
