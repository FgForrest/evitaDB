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
import io.evitadb.api.requestResponse.cdc.CaptureArea;
import io.evitadb.api.requestResponse.cdc.CaptureSite;
import io.evitadb.api.requestResponse.cdc.ChangeCaptureContent;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCapture;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCaptureCriteria;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCaptureRequest;
import io.evitadb.api.requestResponse.cdc.DataSite;
import io.evitadb.api.requestResponse.cdc.Operation;
import io.evitadb.api.requestResponse.cdc.SchemaSite;
import io.evitadb.core.Evita;
import io.evitadb.dataType.ContainerType;
import io.evitadb.externalApi.api.catalog.model.cdc.ChangeCatalogCaptureDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.schemaApi.model.ChangeCatalogCaptureCriteriaDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.schemaApi.model.DataSiteDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.schemaApi.model.SchemaSiteDescriptor;
import io.evitadb.externalApi.graphql.api.resolver.SelectionSetAggregator;
import io.evitadb.externalApi.graphql.api.resolver.subscribingDataFetcher.ChangeCaptureSubscribingDataFetcher;
import io.evitadb.externalApi.graphql.api.system.model.OnCatalogChangeCaptureSubscriptionHeaderDescriptor;
import io.evitadb.externalApi.graphql.exception.GraphQLInvalidArgumentException;
import io.evitadb.externalApi.graphql.exception.GraphQLQueryResolvingInternalError;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Flow.Publisher;

/**
 * Subscription data fetcher for listening to {@link io.evitadb.api.requestResponse.cdc.ChangeCatalogCapture}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class OnCatalogChangeCaptureSubscribingDataFetcher extends ChangeCaptureSubscribingDataFetcher<ChangeCatalogCapture> {

	public OnCatalogChangeCaptureSubscribingDataFetcher(@Nonnull Evita evita) {
		super(evita);
	}

	@Nonnull
	@Override
	protected Publisher<ChangeCatalogCapture> createPublisher(@Nonnull DataFetchingEnvironment environment) {
		final String catalogName = Objects.requireNonNull(
			environment.getArgument(OnCatalogChangeCaptureSubscriptionHeaderDescriptor.CATALOG_NAME.name())
		);
		final Long sinceVersion = environment.getArgument(OnCatalogChangeCaptureSubscriptionHeaderDescriptor.SINCE_VERSION.name());
		final Integer sinceIndex = environment.getArgument(OnCatalogChangeCaptureSubscriptionHeaderDescriptor.SINCE_INDEX.name());
		final ChangeCatalogCaptureCriteria[] criteria = parseCriteriaArgument(environment);
		final boolean needsBody = SelectionSetAggregator.containsImmediate(
			ChangeCatalogCaptureDescriptor.BODY.name(),
			environment.getSelectionSet()
		);

		return this.evita.queryCatalog(
			catalogName,
			session -> {
				return session.registerChangeCatalogCapture(
					new ChangeCatalogCaptureRequest(
						sinceVersion,
						sinceIndex,
						criteria,
						needsBody ? ChangeCaptureContent.BODY : ChangeCaptureContent.HEADER
					)
				);
			}
		);
	}

	@Nullable
	private static ChangeCatalogCaptureCriteria[] parseCriteriaArgument(@Nonnull DataFetchingEnvironment environment) {
		final List<Map<String, Object>> criteriaArgument = environment.getArgument(OnCatalogChangeCaptureSubscriptionHeaderDescriptor.CRITERIA.name());
		if (criteriaArgument == null) {
			return null;
		}
		return criteriaArgument.stream()
			.map(OnCatalogChangeCaptureSubscribingDataFetcher::parseCriteria)
			.toArray(ChangeCatalogCaptureCriteria[]::new);
	}

	@Nonnull
	private static ChangeCatalogCaptureCriteria parseCriteria(@Nonnull Map<String, Object> criteriaDto) {
		final CaptureArea captureArea = (CaptureArea) criteriaDto.get(ChangeCatalogCaptureCriteriaDescriptor.AREA.name());

		final CaptureSite<?> captureSite;
		if (CaptureArea.DATA.equals(captureArea)) {
			//noinspection unchecked
			final Map<String, Object> dataSiteDto = (Map<String, Object>) criteriaDto.get(ChangeCatalogCaptureCriteriaDescriptor.DATA_SITE.name());
			if (dataSiteDto == null) {
				throw new GraphQLInvalidArgumentException("Data site is not specified.");
			}
			captureSite = parseDataSite(dataSiteDto);
		} else if (CaptureArea.SCHEMA.equals(captureArea)) {
			//noinspection unchecked
			final Map<String, Object> schemaSiteDto = (Map<String, Object>) criteriaDto.get(ChangeCatalogCaptureCriteriaDescriptor.SCHEMA_SITE.name());
			if (schemaSiteDto == null) {
				throw new GraphQLInvalidArgumentException("Schema site is not specified.");
			}
			captureSite = parseSchemaSite(schemaSiteDto);
		} else if (CaptureArea.INFRASTRUCTURE.equals(captureArea)) {
			captureSite = null;
		} else {
			throw new GraphQLQueryResolvingInternalError("Unsupported capture area type `" + captureArea + "`.");
		}

		return new ChangeCatalogCaptureCriteria(captureArea, captureSite);
	}

	@Nonnull
	private static DataSite parseDataSite(@Nonnull Map<String, Object> dataSiteDto) {
		//noinspection unchecked
		return DataSite.builder()
			.entityType((String) dataSiteDto.get(DataSiteDescriptor.ENTITY_TYPE.name()))
			.entityPrimaryKey((Integer) dataSiteDto.get(DataSiteDescriptor.ENTITY_PRIMARY_KEY.name()))
			.operation(Optional.ofNullable((List<Operation>) dataSiteDto.get(DataSiteDescriptor.OPERATION.name()))
	           .map(it -> it.toArray(Operation[]::new))
	           .orElse(null))
			.containerType(Optional.ofNullable((List<ContainerType>) dataSiteDto.get(DataSiteDescriptor.CONTAINER_TYPE.name()))
               .map(it -> it.toArray(ContainerType[]::new))
               .orElse(null))
			.containerName(Optional.ofNullable((List<String>) dataSiteDto.get(DataSiteDescriptor.CONTAINER_NAME.name()))
               .map(it -> it.toArray(String[]::new))
               .orElse(null))
			.build();
	}

	@Nonnull
	private static SchemaSite parseSchemaSite(@Nonnull Map<String, Object> schemaSiteDto) {
		//noinspection unchecked
		return SchemaSite.builder()
			.entityType((String) schemaSiteDto.get(SchemaSiteDescriptor.ENTITY_TYPE.name()))
			.operation(Optional.ofNullable((List<Operation>) schemaSiteDto.get(SchemaSiteDescriptor.OPERATION.name()))
	           .map(it -> it.toArray(Operation[]::new))
	           .orElse(null))
			.containerType(Optional.ofNullable((List<ContainerType>) schemaSiteDto.get(SchemaSiteDescriptor.CONTAINER_TYPE.name()))
               .map(it -> it.toArray(ContainerType[]::new))
               .orElse(null))
			.build();
	}
}
