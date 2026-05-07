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
import io.evitadb.api.requestResponse.cdc.ChangeSystemCaptureCriteria;
import io.evitadb.api.requestResponse.cdc.ChangeSystemCaptureRequest;
import io.evitadb.api.requestResponse.cdc.SystemCaptureArea;
import io.evitadb.core.Evita;
import io.evitadb.externalApi.api.system.model.cdc.ChangeSystemCaptureCriteriaDescriptor;
import io.evitadb.externalApi.api.system.model.cdc.ChangeSystemCaptureDescriptor;
import io.evitadb.externalApi.graphql.api.resolver.SelectionSetAggregator;
import io.evitadb.externalApi.graphql.api.resolver.subscribingDataFetcher.ChangeCaptureSubscribingDataFetcher;
import io.evitadb.externalApi.graphql.api.system.model.OnSystemChangeCaptureSubscriptionHeaderDescriptor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Flow.Publisher;

/**
 * Subscription data fetcher for listening to {@link ChangeSystemCapture}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class OnSystemChangeCaptureSubscribingDataFetcher extends ChangeCaptureSubscribingDataFetcher<ChangeSystemCapture> {

	public OnSystemChangeCaptureSubscribingDataFetcher(@Nonnull Evita evita) {
		super(evita);
	}

	@Nonnull
	@Override
	protected Publisher<ChangeSystemCapture> createPublisher(@Nonnull DataFetchingEnvironment environment) {
		final Long sinceVersion = environment.getArgument(OnSystemChangeCaptureSubscriptionHeaderDescriptor.SINCE_VERSION.name());
		final Integer sinceIndex = environment.getArgument(OnSystemChangeCaptureSubscriptionHeaderDescriptor.SINCE_INDEX.name());
		final ChangeSystemCaptureCriteria[] criteria = parseCriteriaArgument(environment);
		final boolean needsBody = SelectionSetAggregator.containsImmediate(
			ChangeSystemCaptureDescriptor.BODY.name(),
			environment.getSelectionSet()
		);

		return this.evita.registerSystemChangeCapture(
			new ChangeSystemCaptureRequest(
				sinceVersion,
				sinceIndex,
				criteria,
				needsBody ? ChangeCaptureContent.BODY : ChangeCaptureContent.HEADER
			)
		);
	}

	@Nullable
	private static ChangeSystemCaptureCriteria[] parseCriteriaArgument(@Nonnull DataFetchingEnvironment environment) {
		final List<Map<String, Object>> criteriaArgument = environment.getArgument(
			OnSystemChangeCaptureSubscriptionHeaderDescriptor.CRITERIA.name()
		);
		if (criteriaArgument == null) {
			// preserves the deliberate default-criteria divergence vs `ChangeCatalogCaptureRequest`:
			// `null` here means engine-only on the engine side
			return null;
		}
		final ChangeSystemCaptureCriteria[] result = new ChangeSystemCaptureCriteria[criteriaArgument.size()];
		for (int i = 0; i < criteriaArgument.size(); i++) {
			result[i] = parseCriteria(criteriaArgument.get(i));
		}
		return result;
	}

	@Nonnull
	private static ChangeSystemCaptureCriteria parseCriteria(@Nonnull Map<String, Object> criteriaDto) {
		final SystemCaptureArea area = (SystemCaptureArea) criteriaDto.get(
			ChangeSystemCaptureCriteriaDescriptor.AREA.name()
		);
		return new ChangeSystemCaptureCriteria(area);
	}
}
