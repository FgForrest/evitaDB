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

package io.evitadb.externalApi.api.system;


import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.io.Closeable;
import java.io.IOException;
import java.util.List;
import java.util.ServiceLoader;
import java.util.ServiceLoader.Provider;

/**
 * Class provides cached access to all probes providers.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@Slf4j
public class ProbesMaintainer {
	@Getter public final List<ProbesProvider> probes;

	{
		this.probes = ServiceLoader.load(ProbesProvider.class)
			.stream()
			.map(Provider::get)
			.toList();
	}

	/**
	 * Closes all probes implementing {@link Closeable} interface from the PROBES list.
	 * Logs an error message if any probe fails to close.
	 */
	public void closeProbes() {
		this.probes.stream()
			.filter(Closeable.class::isInstance)
			.map(Closeable.class::cast)
			.forEach(
				probe -> {
					try {
						probe.close();
					} catch (IOException e) {
						log.error("Failed to close probe: " + probe, e);
					}
				}
			);
	}
}
