/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.externalApi;

import io.evitadb.api.CatalogContract;
import io.evitadb.core.Evita;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jboss.threads.EnhancedQueueExecutor;

import javax.annotation.Nonnull;
import java.util.Collection;

/**
 * Helper for getting package-private Evita data to APIs because those we don't want to expose those data directly to clients.
 *
 * @author Lukáš Hornych, FG Forrest a.s. 2022
 */
@RequiredArgsConstructor
public class EvitaSystemDataProvider {
	@Getter private final Evita evita;

	/**
	 * Returns shared thread pool for Evita.
	 */
	@Nonnull
	public EnhancedQueueExecutor getExecutor() {
		return evita.getExecutor();
	}

	/**
	 * Returns specific catalog from Evita by name
	 */
	@Nonnull
	public CatalogContract getCatalog(@Nonnull String name) {
		return evita.getCatalogInstanceOrThrowException(name);
	}

	/**
	 * Returns all loaded catalogs from Evita
	 */
	@Nonnull
	public Collection<CatalogContract> getCatalogs() {
		return evita.getCatalogs();
	}
}
