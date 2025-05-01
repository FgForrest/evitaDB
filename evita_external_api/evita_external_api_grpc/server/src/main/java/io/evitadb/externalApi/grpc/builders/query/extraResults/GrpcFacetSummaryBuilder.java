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

package io.evitadb.externalApi.grpc.builders.query.extraResults;

import com.google.protobuf.Int32Value;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.extraResult.FacetSummary;
import io.evitadb.api.requestResponse.extraResult.FacetSummary.FacetGroupStatistics;
import io.evitadb.api.requestResponse.extraResult.FacetSummary.RequestImpact;
import io.evitadb.externalApi.grpc.generated.GrpcEntityReference;
import io.evitadb.externalApi.grpc.generated.GrpcExtraResults.Builder;
import io.evitadb.externalApi.grpc.generated.GrpcFacetGroupStatistics;
import io.evitadb.externalApi.grpc.generated.GrpcFacetStatistics;
import io.evitadb.externalApi.grpc.requestResponse.data.EntityConverter;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static io.evitadb.utils.VersionUtils.SemVer;

/**
 * This class is used to build {@link GrpcFacetStatistics} from {@link FacetSummary} and segment them into necessary collections.
 *
 * @author Tomáš Pozler, 2022
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class GrpcFacetSummaryBuilder {

	/**
	 * This method builds {@link GrpcFacetStatistics}, segments them into group statistics.
	 *
	 * @param extraResults the builder where the built result should be placed in
	 * @param facetSummary {@link FacetSummary} returned by evita response
	 * @param clientVersion version of the client so that the server can adjust the response
	 */
	public static void buildFacetSummary(@Nonnull Builder extraResults, @Nonnull FacetSummary facetSummary, @Nullable SemVer clientVersion) {
		final Collection<FacetGroupStatistics> originalGroupStatistics = facetSummary.getReferenceStatistics();
		final List<GrpcFacetGroupStatistics> facetGroupStatistics = new ArrayList<>(originalGroupStatistics.size());

		for (FacetSummary.FacetGroupStatistics groupStatistics : originalGroupStatistics) {
			final Collection<FacetSummary.FacetStatistics> originalFacetStatistics = groupStatistics.getFacetStatistics();
			final List<GrpcFacetStatistics> facetStatistics = new ArrayList<>(originalFacetStatistics.size());
			for (FacetSummary.FacetStatistics facetStatistic : originalFacetStatistics) {
				final GrpcFacetStatistics.Builder statisticsBuilder = GrpcFacetStatistics.newBuilder()
					.setRequested(facetStatistic.isRequested())
					.setCount(facetStatistic.getCount());

				if (facetStatistic.getFacetEntity() instanceof final EntityReference entityReference) {
					statisticsBuilder.setFacetEntityReference(GrpcEntityReference.newBuilder()
						.setEntityType(entityReference.getType())
						.setPrimaryKey(entityReference.getPrimaryKey()));
				} else if (facetStatistic.getFacetEntity() instanceof final SealedEntity entity) {
					statisticsBuilder.setFacetEntity(EntityConverter.toGrpcSealedEntity(entity, clientVersion));
				}

				final RequestImpact impact = facetStatistic.getImpact();
				if (impact != null) {
					statisticsBuilder.
						setImpact(Int32Value.newBuilder().setValue(impact.difference()).build())
						.setMatchCount(Int32Value.newBuilder().setValue(impact.matchCount()).build())
						.setHasSense(impact.hasSense());
				}

				final GrpcFacetStatistics statistics = statisticsBuilder.build();

				facetStatistics.add(statistics);
			}
			final GrpcFacetGroupStatistics.Builder groupStatisticBuilder = GrpcFacetGroupStatistics.newBuilder()
				.setReferenceName(groupStatistics.getReferenceName())
				.setCount(groupStatistics.getCount())
				.addAllFacetStatistics(facetStatistics);

			if (groupStatistics.getGroupEntity() != null) {
				if (groupStatistics.getGroupEntity() instanceof EntityReference entityReference) {
					groupStatisticBuilder.setGroupEntityReference(GrpcEntityReference.newBuilder()
						.setEntityType(entityReference.getType())
						.setPrimaryKey(entityReference.getPrimaryKey()));
				} else if (groupStatistics.getGroupEntity() instanceof SealedEntity entity) {
					groupStatisticBuilder.setGroupEntity(EntityConverter.toGrpcSealedEntity(entity, clientVersion));
				}
			}

			facetGroupStatistics.add(groupStatisticBuilder.build());
		}

		extraResults.addAllFacetGroupStatistics(facetGroupStatistics);
	}
}
