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

package io.evitadb.externalApi.graphql.api.catalog;

import io.evitadb.api.requestResponse.cdc.ChangeCatalogCapture;
import io.evitadb.externalApi.graphql.GraphQLManager;
import lombok.RequiredArgsConstructor;

import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.Flow.Subscription;

/**
 * Updates GraphQL API endpoints and their GraphQL instances based on Evita updates.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
// TOBEDONE LHO: consider more efficient GraphQL schema updating when only part of Evita schema is updated
@RequiredArgsConstructor
public class CatalogGraphQLRefreshingObserver implements Subscriber<ChangeCatalogCapture> {

	private final GraphQLManager graphQLManager;
	private Subscription subscription;

	@Override
	public void onSubscribe(Subscription subscription) {
		this.subscription = subscription;
		this.subscription.request(1);
	}

	@Override
	public void onNext(ChangeCatalogCapture item) {
		// todo lho: make use of the lastId when implemented?
		graphQLManager.registerCatalog(item.catalog());
		subscription.request(1);
		graphQLManager.emitObservabilityEvents(item.catalog());
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
