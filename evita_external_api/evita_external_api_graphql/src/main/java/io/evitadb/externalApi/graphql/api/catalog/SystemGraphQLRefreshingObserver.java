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

import io.evitadb.api.requestResponse.cdc.ChangeSystemCapture;
import io.evitadb.api.requestResponse.mutation.Mutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.CreateCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.DuplicateCatalogMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.ModifyCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.ModifyCatalogSchemaNameMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.RemoveCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.SetCatalogMutabilityMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.SetCatalogStateMutation;
import io.evitadb.externalApi.graphql.GraphQLManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.Flow.Subscription;

/**
 * Updates GraphQL API endpoints and their GraphQL instances based on Evita updates.
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
			final Mutation body = item.body();
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
				// if the catalog is set to active, we need to register it, otherwise we unregister it
				if (setState.isActive()) {
					if (this.graphQLManager.registerCatalog(setState.getCatalogName())) {
						this.graphQLManager.emitObservabilityEvents(setState.getCatalogName());
					}
				} else {
					this.graphQLManager.unregisterCatalog(setState.getCatalogName());
				}
			} else if (body instanceof RemoveCatalogSchemaMutation remove) {
				// if the catalog schema is removed, we need to unregister it
				this.graphQLManager.unregisterCatalog(remove.getCatalogName());
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
