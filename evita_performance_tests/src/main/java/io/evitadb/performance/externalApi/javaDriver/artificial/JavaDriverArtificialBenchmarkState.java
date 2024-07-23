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

package io.evitadb.performance.externalApi.javaDriver.artificial;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.driver.EvitaClient;
import io.evitadb.performance.artificial.AbstractArtificialBenchmarkState;

import java.util.function.Supplier;

/**
 * Base state class for all artifical based benchmarks.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public abstract class JavaDriverArtificialBenchmarkState extends AbstractArtificialBenchmarkState<EvitaSessionContract> {

	protected EvitaClient driver;

	/**
	 * Returns an existing session unique for the thread or creates new one.
	 */
	@Override
	public EvitaSessionContract getSession() {
		return getSession(() -> this.driver.createReadOnlySession(getCatalogName()));
	}

	/**
	 * Returns an existing session unique for the thread or creates new one.
	 */
	@Override
	public EvitaSessionContract getSession(Supplier<EvitaSessionContract> creatorFct) {
		final EvitaSessionContract session = this.session.get();
		if (session == null || !session.isActive()) {
			final EvitaSessionContract createdSession = creatorFct.get();
			this.session.set(createdSession);
			return createdSession;
		} else {
			return session;
		}
	}

}
