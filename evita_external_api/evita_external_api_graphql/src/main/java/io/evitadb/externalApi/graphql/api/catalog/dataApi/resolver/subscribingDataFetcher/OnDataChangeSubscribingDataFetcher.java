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

package io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.subscribingDataFetcher;

import graphql.schema.DataFetchingEnvironment;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.requestResponse.cdc.CaptureArea;
import io.evitadb.api.requestResponse.cdc.CaptureContent;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCapture;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCaptureRequest;
import io.evitadb.api.requestResponse.cdc.DataSite;
import io.evitadb.api.requestResponse.cdc.Operation;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.core.Evita;
import io.evitadb.dataType.ClassifierType;
import io.evitadb.externalApi.api.catalog.model.cdc.ChangeCatalogCaptureDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.GraphQLContextKey;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.OnDataChangeHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.resolver.SelectionSetAggregator;
import io.evitadb.externalApi.graphql.api.resolver.subscribingDataFetcher.ChangeSubscribingDataFetcher;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Flow.Publisher;

/**
 * Subscription data fetcher for listening to {@link ChangeCatalogCapture} for data changes inside particular catalog.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class OnDataChangeSubscribingDataFetcher extends ChangeSubscribingDataFetcher<ChangeCatalogCapture> {

	/**
	 * Entity schema that will narrow the capture request only to that entity type. Otherwise, all entity types are captured.
	 */
	@Nonnull private final EntitySchemaContract entitySchema;

	public OnDataChangeSubscribingDataFetcher(@Nonnull Evita evita, @Nullable EntitySchemaContract entitySchema) {
		super(evita);
		this.entitySchema = entitySchema;
	}

	@Nonnull
	@Override
	protected Publisher<ChangeCatalogCapture> createPublisher(@Nonnull DataFetchingEnvironment environment) {
		final Integer entityPrimaryKey = environment.getArgument(OnDataChangeHeaderDescriptor.ENTITY_PRIMARY_KEY.name());
		final List<ClassifierType> classifierType = environment.getArgument(OnDataChangeHeaderDescriptor.CLASSIFIER_TYPE.name());
		final List<Operation> operation = environment.getArgument(OnDataChangeHeaderDescriptor.OPERATION.name());
		final long sinceTransactionId = environment.getArgument(OnDataChangeHeaderDescriptor.SINCE_TRANSACTION_ID.name());
		final boolean needsBody = SelectionSetAggregator.containsImmediate(ChangeCatalogCaptureDescriptor.BODY.name(), environment.getSelectionSet());

		final EvitaSessionContract evitaSession = environment.getGraphQlContext().get(GraphQLContextKey.EVITA_SESSION);
		return evitaSession.registerChangeCatalogCapture(new ChangeCatalogCaptureRequest(
			CaptureArea.DATA,
			new DataSite(
				entitySchema.getName(),
				entityPrimaryKey,
				Optional.ofNullable(classifierType).map(it -> it.toArray(ClassifierType[]::new)).orElse(null),
				Optional.ofNullable(operation).map(it -> it.toArray(Operation[]::new)).orElse(null)
			),
			needsBody ? CaptureContent.BODY : CaptureContent.HEADER,
			sinceTransactionId,
			null
		));
	}
}
