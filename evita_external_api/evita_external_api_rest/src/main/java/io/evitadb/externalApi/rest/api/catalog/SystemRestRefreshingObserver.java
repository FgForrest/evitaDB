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

package io.evitadb.externalApi.rest.api.catalog;

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
import io.evitadb.externalApi.rest.RestManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.Flow.Subscription;

/**
 * This observer allows to react on changes in Catalog's structure and reload OpenAPI and REST handlers if necessary.
 *
 * Reacts to two kinds of system CDC bodies on the stream:
 * - {@link io.evitadb.api.requestResponse.mutation.EngineMutation} — durable, WAL-replicated
 *   engine mutations (default `ENGINE` area). The pre-existing branches handle catalog
 *   schema create / duplicate / rename / modify / mutability / state / removal.
 * - {@link HostSystemEvent} — host-local, non-replicable host events delivered when
 *   the subscription opted into the `HOST` area. These are the authoritative
 *   "catalog X is now usable / now gone on this host" signals and are required to recover
 *   from boot-time auto-upgrade and other transient-state transitions where the engine
 *   mutation alone does not announce settlement.
 *
 * @author Martin Veska (veska@fg.cz), FG Forrest a.s. (c) 2022
 */
@Slf4j
@RequiredArgsConstructor
public class SystemRestRefreshingObserver implements Subscriber<ChangeSystemCapture> {
	/**
	 * Reference to the REST manager that is used to register and unregister catalogs.
	 */
	@Nonnull private final RestManager restManager;
	/**
	 * Subscription to the change system capture stream.
	 */
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
			if (body instanceof CreateCatalogSchemaMutation ccsm) {
				// if the catalog schema is created, we need to register it
				if (this.restManager.registerCatalog(ccsm.getCatalogName())) {
					this.restManager.emitObservabilityEvents(ccsm.getCatalogName());
				}
			} else if (body instanceof DuplicateCatalogMutation duplicate) {
				// if the catalog schema is duplicated, we need to register the new one
				if (this.restManager.registerCatalog(duplicate.getNewCatalogName())) {
					this.restManager.emitObservabilityEvents(duplicate.getNewCatalogName());
				}
			} else if (body instanceof ModifyCatalogSchemaNameMutation mcsnm) {
				// remove the old catalog and register the new one
				this.restManager.unregisterCatalog(mcsnm.getCatalogName());
				if (mcsnm.isOverwriteTarget()) {
					this.restManager.unregisterCatalog(mcsnm.getNewCatalogName());
				}
				if (this.restManager.registerCatalog(mcsnm.getNewCatalogName())) {
					this.restManager.emitObservabilityEvents(mcsnm.getNewCatalogName());
				}
			} else if (body instanceof ModifyCatalogSchemaMutation mcsm) {
				// when schema changes - just refresh the catalog
				if (this.restManager.refreshCatalog(mcsm.getCatalogName())) {
					this.restManager.emitObservabilityEvents(mcsm.getCatalogName());
				}
			} else if (body instanceof SetCatalogMutabilityMutation setCatalogMutability) {
				// when mutability changes - just refresh the catalog
				if (this.restManager.refreshCatalog(setCatalogMutability.getCatalogName())) {
					this.restManager.emitObservabilityEvents(setCatalogMutability.getCatalogName());
				}
			} else if (body instanceof SetCatalogStateMutation setState) {
				// the engine mutation merely records intent; the authoritative "is the catalog
				// usable now?" signal arrives as a `CatalogInstalledIntoLiveView` host event after
				// the state transition completes. We deactivate eagerly here (active=false) but
				// defer activation to the host event branch below.
				if (!setState.isActive()) {
					this.restManager.unregisterCatalog(setState.getCatalogName());
				}
			} else if (body instanceof RemoveCatalogSchemaMutation rccs) {
				// the engine mutation marks intent to delete; actual removal from the live view is
				// confirmed by the `CatalogRemovedFromLiveView` host event below.
				this.restManager.unregisterCatalog(rccs.getCatalogName());
			} else if (body instanceof UpgradeCatalogFormatMutation upgrade) {
				// defensive — the host event (`CatalogInstalledIntoLiveView`) is the primary signal
				// for the actual register/refresh, but if the engine emits the upgrade mutation
				// first and we already have an endpoint for the catalog, refresh it so consumers
				// don't see a stale schema until the host event arrives.
				if (this.restManager.refreshCatalog(upgrade.getCatalogName())) {
					this.restManager.emitObservabilityEvents(upgrade.getCatalogName());
				}
			} else if (body instanceof HostSystemEvent.CatalogInstalledIntoLiveView installed) {
				handleCatalogInstalled(installed);
			} else if (body instanceof HostSystemEvent.CatalogRemovedFromLiveView removed) {
				// the catalog is gone from the live view on this host — drop its REST endpoints
				this.restManager.unregisterCatalog(removed.catalogName());
			}
		} catch (CatalogGoingLiveException ignored) {
			// catalog is going live, we cannot update its REST schema now
			// but we will get another notification after the catalog is live
		} catch (Throwable throwable) {
			log.error("Failed to update REST schema in reaction to schema capture: {}", item, throwable);
		} finally {
			this.subscription.request(1);
		}
	}

	/**
	 * Reacts to a {@link HostSystemEvent.CatalogInstalledIntoLiveView} event.
	 *
	 * If the observed state is active (`ALIVE` or `WARMING_UP`), the catalog is queryable
	 * and we either register it (first time we see it) or refresh it (already registered,
	 * pick up the new live reference). For non-active settled states (`INACTIVE`,
	 * `OUT_OF_DATE`, `CORRUPTED`, `MISSING`) the catalog is no longer addressable via REST
	 * and we unregister it.
	 *
	 * @param installed the host event reporting the settled state
	 */
	private void handleCatalogInstalled(@Nonnull HostSystemEvent.CatalogInstalledIntoLiveView installed) {
		final String catalogName = installed.catalogName();
		if (installed.observedState().isActive()) {
			// boot-time first install → register; later catalog-reference replacements
			// (e.g. post-upgrade) on an already-registered catalog → refresh
			final boolean changed = this.restManager.isCatalogRegistered(catalogName)
				? this.restManager.refreshCatalog(catalogName)
				: this.restManager.registerCatalog(catalogName);
			if (changed) {
				this.restManager.emitObservabilityEvents(catalogName);
			}
		} else {
			// settled into a non-queryable state (INACTIVE / MISSING / CORRUPTED / OUT_OF_DATE)
			this.restManager.unregisterCatalog(catalogName);
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
