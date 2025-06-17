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

package io.evitadb.externalApi.graphql.api.system.resolver.subscribingDataFetcher;

import graphql.schema.DataFetchingEnvironment;
import io.evitadb.api.requestResponse.cdc.ChangeCaptureContent;
import io.evitadb.api.requestResponse.cdc.ChangeSystemCapture;
import io.evitadb.api.requestResponse.cdc.ChangeSystemCaptureRequest;
import io.evitadb.core.Evita;
import io.evitadb.externalApi.api.system.model.cdc.ChangeSystemCaptureDescriptor;
import io.evitadb.externalApi.graphql.api.resolver.SelectionSetAggregator;
import io.evitadb.externalApi.graphql.api.resolver.subscribingDataFetcher.ChangeSubscribingDataFetcher;

import javax.annotation.Nonnull;
import java.util.concurrent.Flow.Publisher;

/**
 * Subscription data fetcher for listening to {@link ChangeSystemCapture}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class OnSystemChangeSubscribingDataFetcher extends ChangeSubscribingDataFetcher<ChangeSystemCapture> {

	public OnSystemChangeSubscribingDataFetcher(@Nonnull Evita evita) {
		super(evita);
	}

	@Nonnull
	@Override
	protected Publisher<ChangeSystemCapture> createPublisher(@Nonnull DataFetchingEnvironment environment) {
		final boolean needsBody = SelectionSetAggregator.containsImmediate(ChangeSystemCaptureDescriptor.BODY.name(), environment.getSelectionSet());
		return this.evita.registerSystemChangeCapture(new ChangeSystemCaptureRequest(null, null, needsBody ? ChangeCaptureContent.BODY : ChangeCaptureContent.HEADER));
	}
}
