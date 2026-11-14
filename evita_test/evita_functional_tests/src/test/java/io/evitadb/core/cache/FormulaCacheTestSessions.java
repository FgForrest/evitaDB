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

package io.evitadb.core.cache;

import io.evitadb.api.TransactionContract.CommitBehavior;
import io.evitadb.api.SessionTraits;
import io.evitadb.core.Evita;
import io.evitadb.core.session.EvitaSession;
import io.evitadb.core.session.SessionRegistry;
import io.evitadb.utils.ReflectionLookup;

import javax.annotation.Nonnull;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Registers real pinned sessions while retaining their concrete instance for context construction.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class FormulaCacheTestSessions {
	/** Prevents instantiation of this fixture utility. */
	private FormulaCacheTestSessions() {
	}

	/** Returns a real session registered and version-pinned by the engine's ordinary session registry. */
	@Nonnull
	public static EvitaSession readOnly(@Nonnull Evita evita, @Nonnull String catalogName) {
		return create(evita, catalogName, false);
	}

	/** Returns a registered concrete session owning a real transaction. */
	@Nonnull
	public static EvitaSession readWrite(@Nonnull Evita evita, @Nonnull String catalogName) {
		return create(evita, catalogName, true);
	}

	/** Registers the concrete session and its ordinary proxy through the engine's registry. */
	@Nonnull
	private static EvitaSession create(@Nonnull Evita evita, @Nonnull String catalogName, boolean writing) {
		final SessionRegistry registry = evita.getCatalogSessionRegistry(catalogName).orElseThrow();
		final AtomicReference<EvitaSession> session = new AtomicReference<>();
		registry.addSession(((io.evitadb.core.catalog.Catalog) evita.getCatalogInstanceOrThrowException(catalogName))
			.supportsTransaction(), pinnedCatalog -> {
			final EvitaSession concrete = new EvitaSession(evita, pinnedCatalog, ReflectionLookup.NO_CACHE_INSTANCE,
				closed -> registry.removeSession((EvitaSession) closed), CommitBehavior.defaultBehaviour(),
				new SessionTraits(catalogName, writing ? new SessionTraits.SessionFlags[]{SessionTraits.SessionFlags.READ_WRITE} :
					new SessionTraits.SessionFlags[0]), registry::createCatalogConsumerControl);
			session.set(concrete);
			return concrete;
		});
		return session.get();
	}
}
