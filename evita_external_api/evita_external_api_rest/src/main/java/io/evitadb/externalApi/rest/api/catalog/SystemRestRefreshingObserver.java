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

import io.evitadb.api.requestResponse.cdc.ChangeSystemCapture;
import io.evitadb.api.requestResponse.mutation.EngineMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.CreateCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.DuplicateCatalogMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.ModifyCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.ModifyCatalogSchemaNameMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.RemoveCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.SetCatalogMutabilityMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.SetCatalogStateMutation;
import io.evitadb.externalApi.rest.RestManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.Flow.Subscription;

/**
 * This observer allows to react on changes in Catalog's structure and reload OpenAPI and REST handlers if necessary.
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
			final EngineMutation<?> body = item.body();
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
				// if the catalog is set to active, we need to register it, otherwise we unregister it
				if (setState.isActive()) {
					if (this.restManager.registerCatalog(setState.getCatalogName())) {
						this.restManager.emitObservabilityEvents(setState.getCatalogName());
					}
				} else {
					this.restManager.unregisterCatalog(setState.getCatalogName());
				}
			} else if (body instanceof RemoveCatalogSchemaMutation rccs) {
				// if the catalog schema is removed, we need to unregister it
				this.restManager.unregisterCatalog(rccs.getCatalogName());
			}
		} catch (Throwable throwable) {
			log.error("Failed to update GraphQL schema in reaction to schema capture: {}", item, throwable);
		} finally {
			this.subscription.request(1);
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
