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

package io.evitadb.externalApi.graphql.api.catalog;

import io.evitadb.api.CatalogState;
import io.evitadb.api.exception.CatalogGoingLiveException;
import io.evitadb.api.requestResponse.cdc.ChangeSystemCapture;
import io.evitadb.api.requestResponse.cdc.HostSystemEvent;
import io.evitadb.api.requestResponse.cdc.SystemCaptureBody;
import io.evitadb.api.requestResponse.schema.mutation.engine.CreateCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.DuplicateCatalogMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.ModifyCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.ModifyCatalogSchemaNameMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.RemoveCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.SetCatalogMutabilityMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.SetCatalogStateMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.UpgradeCatalogFormatMutation;
import io.evitadb.externalApi.graphql.GraphQLManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.Flow.Subscription;

/**
 * Updates GraphQL API endpoints and their GraphQL instances based on Evita updates.
 *
 * Subscribes to both `ENGINE` and `HOST` system areas. Engine mutations drive
 * registration / refresh / removal of catalogs whose lifecycle can be determined from the
 * mutation alone (create, modify, remove). Host system events
 * ({@link HostSystemEvent.CatalogInstalledIntoLiveView},
 * {@link HostSystemEvent.CatalogRemovedFromLiveView}) are the authoritative signal for
 * any host-local transition that the engine mutation alone cannot describe (e.g. a
 * boot-time auto-upgrade replacing an `UnusableCatalog` placeholder with a real
 * `Catalog`).
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
// TOBEDONE LHO: consider more efficient GraphQL schema updating when only part of Evita schema is updated
@Slf4j
@RequiredArgsConstructor
public class SystemGraphQLRefreshingObserver implements Subscriber<ChangeSystemCapture> {
	private final GraphQLManager graphQLManager;
	private Subscription subscription;

	@Override
	public void onSubscribe(Subscription subscription) {
		this.subscription = subscription;
		this.subscription.request(1);
	}

	@Override
	public void onNext(ChangeSystemCapture item) {
		try {
			final SystemCaptureBody body = item.body();
			if (body instanceof CreateCatalogSchemaMutation create) {
				// if the catalog schema is created, we need to register it
				this.graphQLManager.registerCatalog(create.getCatalogName());
				this.graphQLManager.emitObservabilityEvents(create.getCatalogName());
			} else if (body instanceof DuplicateCatalogMutation duplicate) {
				// if the catalog schema is duplicated, we need to register the new one
				if (this.graphQLManager.registerCatalog(duplicate.getNewCatalogName())) {
					this.graphQLManager.emitObservabilityEvents(duplicate.getNewCatalogName());
				}
			} else if (body instanceof ModifyCatalogSchemaNameMutation nameChange) {
				// if the catalog schema name is changed, we need to unregister the old one and register the new one
				this.graphQLManager.unregisterCatalog(nameChange.getCatalogName());
				if (nameChange.isOverwriteTarget()) {
					this.graphQLManager.unregisterCatalog(nameChange.getNewCatalogName());
				}
				if (this.graphQLManager.registerCatalog(nameChange.getNewCatalogName())) {
					this.graphQLManager.emitObservabilityEvents(nameChange.getNewCatalogName());
				}
			} else if (body instanceof ModifyCatalogSchemaMutation modify) {
				// if the catalog schema is modified, we need to refresh the catalog
				if (this.graphQLManager.refreshCatalog(modify.getCatalogName())) {
					this.graphQLManager.emitObservabilityEvents(modify.getCatalogName());
				}
			} else if (body instanceof SetCatalogMutabilityMutation setCatalogMutability) {
				// if the catalog mutability is set, we need to refresh the catalog
				if (this.graphQLManager.refreshCatalog(setCatalogMutability.getCatalogName())) {
					this.graphQLManager.emitObservabilityEvents(setCatalogMutability.getCatalogName());
				}
			} else if (body instanceof SetCatalogStateMutation setState) {
				// the engine mutation merely records intent; the authoritative "is the catalog
				// usable now?" signal arrives as a `CatalogInstalledIntoLiveView` host event after
				// the state transition completes. We deactivate eagerly here (active=false) but
				// defer activation to the host event branch below.
				if (!setState.isActive()) {
					this.graphQLManager.unregisterCatalog(setState.getCatalogName());
				}
			} else if (body instanceof RemoveCatalogSchemaMutation remove) {
				// the engine mutation marks intent to delete; actual removal from the live view is
				// confirmed by the `CatalogRemovedFromLiveView` host event below.
				this.graphQLManager.unregisterCatalog(remove.getCatalogName());
			} else if (body instanceof UpgradeCatalogFormatMutation upgrade) {
				// defensive — the host event (`CatalogInstalledIntoLiveView`) is the primary signal
				// for the actual register/refresh, but if the engine emits the upgrade mutation
				// first and we already have an endpoint for the catalog, refresh it so consumers
				// don't see a stale schema until the host event arrives.
				if (this.graphQLManager.refreshCatalog(upgrade.getCatalogName())) {
					this.graphQLManager.emitObservabilityEvents(upgrade.getCatalogName());
				}
			} else if (body instanceof HostSystemEvent.CatalogInstalledIntoLiveView installed) {
				handleCatalogInstalled(installed);
			} else if (body instanceof HostSystemEvent.CatalogRemovedFromLiveView removed) {
				this.graphQLManager.unregisterCatalog(removed.catalogName());
			}
		} catch (CatalogGoingLiveException ignored) {
			// catalog is going live, we cannot update its GraphQL schema now
			// but we will get another notification after the catalog is live
		} catch (Throwable throwable) {
			log.error("Failed to update GraphQL schema in reaction to schema capture: {}", item, throwable);
		} finally {
			this.subscription.request(1);
		}
	}

	/**
	 * Reacts to a `CatalogInstalledIntoLiveView` host event by registering, refreshing or
	 * unregistering the catalog endpoint depending on the observed (settled) state.
	 *
	 * - **Active states** (`ALIVE`, `WARMING_UP`): catalog is queryable; register if not yet
	 *   present, otherwise refresh.
	 * - **Non-queryable settled states** (`INACTIVE`, `MISSING`, `OUT_OF_DATE`, `CORRUPTED`):
	 *   ensure no stale endpoint remains.
	 */
	private void handleCatalogInstalled(@Nonnull HostSystemEvent.CatalogInstalledIntoLiveView installed) {
		final String catalogName = installed.catalogName();
		final CatalogState observedState = installed.observedState();
		if (observedState.isActive()) {
			// `refreshCatalog` falls back to `registerCatalog` when the catalog is not yet registered,
			// so a single call covers both the first-time-load and the post-upgrade-replace cases.
			if (this.graphQLManager.refreshCatalog(catalogName)) {
				this.graphQLManager.emitObservabilityEvents(catalogName);
			}
		} else {
			// settled into a non-queryable state — drop any cached endpoint
			this.graphQLManager.unregisterCatalog(catalogName);
		}
	}

	@Override
	public void onError(Throwable throwable) {
		// do nothing, there are no resources to free, logging happens in the caller
	}

	@Override
	public void onComplete() {
		// do nothing, there are no resources to free
	}
}
