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

package io.evitadb.externalApi.graphql.api.catalog;

import io.evitadb.api.requestResponse.cdc.CaptureArea;
import io.evitadb.api.requestResponse.cdc.ChangeDataCapture;
import io.evitadb.api.requestResponse.cdc.ChangeDataCaptureObserver;
import io.evitadb.api.requestResponse.cdc.ChangeSystemCapture;
import io.evitadb.api.requestResponse.cdc.ChangeSystemCaptureSubscriber;
import io.evitadb.externalApi.graphql.GraphQLManager;
import io.evitadb.utils.Assert;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import java.util.Collection;
import java.util.Objects;
import java.util.concurrent.Flow.Subscription;

/**
 * Updates GraphQL API endpoints and their GraphQL instances based on Evita updates.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
// TOBEDONE LHO: consider more efficient GraphQL schema updating when only part of Evita schema is updated
@RequiredArgsConstructor
public class CatalogGraphQLRefreshingObserver implements ChangeSystemCaptureSubscriber, ChangeDataCaptureObserver {
	private final GraphQLManager graphQLManager;

	@Override
	public void onTransactionCommit(long transactionId, @Nonnull Collection<ChangeDataCapture> events) {
		String catalogUpdated = null;
		for (ChangeDataCapture event : events) {
			if (event.area() == CaptureArea.SCHEMA) {
				Assert.isTrue(
					catalogUpdated == null || Objects.equals(catalogUpdated, event.catalog()),
					"Transactions are expected to always contain events from the same catalog."
				);
				catalogUpdated = event.catalog();
			}
		}

		if (catalogUpdated != null) {
			graphQLManager.refreshCatalog(catalogUpdated);
		}
	}

	@Override
	public void onSubscribe(Subscription subscription) {
		subscription.request(Long.MAX_VALUE);
	}

	@Override
	public void onNext(ChangeSystemCapture item) {
		switch (item.operation()) {
			case CREATE -> graphQLManager.registerCatalog(item.catalog());
			case UPDATE -> graphQLManager.refreshCatalog(item.catalog());
			case REMOVE -> graphQLManager.unregisterCatalog(item.catalog());
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

	@Override
	public void onTermination() {
		// do nothing, there are no resources to free
	}
}
