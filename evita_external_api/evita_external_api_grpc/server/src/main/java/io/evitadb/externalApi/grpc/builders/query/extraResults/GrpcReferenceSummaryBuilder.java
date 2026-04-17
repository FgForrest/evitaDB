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
import io.evitadb.api.requestResponse.extraResult.HistogramContract;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary.FacetStatistics;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary.RequestImpact;
import io.evitadb.externalApi.grpc.generated.GrpcEntityReference;
import io.evitadb.externalApi.grpc.generated.GrpcExtraResults.Builder;
import io.evitadb.externalApi.grpc.generated.GrpcFacetGroupStatistics;
import io.evitadb.externalApi.grpc.generated.GrpcFacetStatistics;
import io.evitadb.externalApi.grpc.generated.GrpcReferenceGroupStatistics;
import io.evitadb.externalApi.grpc.generated.GrpcSealedEntity;
import io.evitadb.externalApi.grpc.requestResponse.data.EntityConverter;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

import static io.evitadb.utils.VersionUtils.SemVer;

/**
 * This class is used to build {@link GrpcFacetStatistics} from {@link ReferenceSummary} (or its deprecated
 * {@link FacetSummary} subclass) and segment them into necessary collections.
 *
 * @author Tomáš Pozler, 2022
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class GrpcReferenceSummaryBuilder {

	/**
	 * Builds {@link GrpcFacetStatistics}, segments them into group statistics and writes them
	 * into the appropriate gRPC field. The target field is determined by the runtime type of
	 * `referenceSummary`:
	 *
	 * - {@link FacetSummary} (deprecated subclass) -> `facetGroupStatistics` (backward compat)
	 * - plain {@link ReferenceSummary} -> `referenceGroupStatistics` (canonical form)
	 *
	 * When a mixed-constraint query produces both DTOs, each call writes to its own field.
	 *
	 * @param extraResults     the builder where the built result should be placed in
	 * @param referenceSummary {@link ReferenceSummary} returned by evita response (or its
	 *                         deprecated {@link FacetSummary} subclass for backward compatibility)
	 * @param clientVersion    version of the client so that the server can adjust the response
	 */
	public static void buildReferenceSummary(
		@Nonnull Builder extraResults,
		@Nonnull ReferenceSummary referenceSummary,
		@Nullable SemVer clientVersion
	) {
		final Collection<? extends ReferenceSummary.ReferenceGroupStatistics> originalGroupStatistics =
			referenceSummary.getReferenceStatistics();

		if (referenceSummary instanceof FacetSummary) {
			// deprecated path — write to the legacy facetGroupStatistics field
			final List<GrpcFacetGroupStatistics> facetGroupStatistics =
				new ArrayList<>(originalGroupStatistics.size());
			for (ReferenceSummary.ReferenceGroupStatistics groupStatistics : originalGroupStatistics) {
				final GrpcFacetGroupStatistics.Builder groupStatisticBuilder =
					GrpcFacetGroupStatistics.newBuilder();
				populateCommonFields(
					groupStatistics,
					clientVersion,
					new BuilderAdapter(
						groupStatisticBuilder::setReferenceName,
						groupStatisticBuilder::setCount,
						groupStatisticBuilder::addAllFacetStatistics,
						groupStatisticBuilder::setGroupEntityReference,
						groupStatisticBuilder::setGroupEntity
					)
				);
				facetGroupStatistics.add(groupStatisticBuilder.build());
			}
			extraResults.addAllFacetGroupStatistics(facetGroupStatistics);
		} else {
			// canonical path — write to the referenceGroupStatistics field
			final List<GrpcReferenceGroupStatistics> referenceGroupStatistics =
				new ArrayList<>(originalGroupStatistics.size());
			for (ReferenceSummary.ReferenceGroupStatistics groupStatistics : originalGroupStatistics) {
				final GrpcReferenceGroupStatistics.Builder groupStatisticBuilder =
					GrpcReferenceGroupStatistics.newBuilder();
				populateCommonFields(
					groupStatistics,
					clientVersion,
					new BuilderAdapter(
						groupStatisticBuilder::setReferenceName,
						groupStatisticBuilder::setCount,
						groupStatisticBuilder::addAllFacetStatistics,
						groupStatisticBuilder::setGroupEntityReference,
						groupStatisticBuilder::setGroupEntity
					)
				);

				// serialize named histogram statistics keyed by histogram index name (canonical path only)
				final Map<String, HistogramContract> histogramStatistics = groupStatistics.getHistogramStatistics();
				for (Entry<String, HistogramContract> entry : histogramStatistics.entrySet()) {
					groupStatisticBuilder.putHistogramStatistics(
						entry.getKey(),
						GrpcHistogramBuilder.buildHistogram(entry.getValue(), clientVersion)
					);
				}

				referenceGroupStatistics.add(groupStatisticBuilder.build());
			}
			extraResults.addAllReferenceGroupStatistics(referenceGroupStatistics);
		}
	}

	/**
	 * Populates the shared fields (reference name, count, facet statistics, group entity) on any
	 * group-statistics gRPC builder through a type-erased {@link BuilderAdapter}. Allows the
	 * deprecated {@link GrpcFacetGroupStatistics} path and the canonical {@link GrpcReferenceGroupStatistics}
	 * path to share identical logic despite being backed by distinct generated proto types.
	 */
	private static void populateCommonFields(
		@Nonnull ReferenceSummary.ReferenceGroupStatistics groupStatistics,
		@Nullable SemVer clientVersion,
		@Nonnull BuilderAdapter adapter
	) {
		final List<GrpcFacetStatistics> facetStatistics = buildGrpcFacetStatistics(
			clientVersion,
			groupStatistics.getFacetStatistics()
		);
		adapter.setReferenceName.accept(groupStatistics.getReferenceName());
		adapter.setCount.accept(groupStatistics.getCount());
		adapter.addAllFacetStatistics.accept(facetStatistics);

		if (groupStatistics.getGroupEntity() instanceof EntityReference entityReference) {
			adapter.setGroupEntityReference.accept(
				GrpcEntityReference.newBuilder()
					.setEntityType(entityReference.getType())
					.setPrimaryKey(entityReference.getPrimaryKey())
			);
		} else if (groupStatistics.getGroupEntity() instanceof SealedEntity entity) {
			adapter.setGroupEntity.accept(EntityConverter.toGrpcSealedEntity(entity, clientVersion));
		}
	}

	@Nonnull
	private static List<GrpcFacetStatistics> buildGrpcFacetStatistics(
		@Nullable SemVer clientVersion,
		@Nonnull Collection<FacetStatistics> originalFacetStatistics
	) {
		final List<GrpcFacetStatistics> facetStatistics = new ArrayList<>(originalFacetStatistics.size());

		for (FacetStatistics facetStatistic : originalFacetStatistics) {
			final GrpcFacetStatistics.Builder statisticsBuilder = GrpcFacetStatistics.newBuilder()
				.setRequested(facetStatistic.isRequested())
				.setCount(facetStatistic.getCount());

			if (facetStatistic.getFacetEntity() instanceof final EntityReference entityReference) {
				statisticsBuilder.setFacetEntityReference(
					GrpcEntityReference.newBuilder()
						.setEntityType(entityReference.getType())
						.setPrimaryKey(entityReference.getPrimaryKey())
				);
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

		return facetStatistics;
	}

	/**
	 * Type-erased bundle of setters that both {@link GrpcFacetGroupStatistics.Builder} and
	 * {@link GrpcReferenceGroupStatistics.Builder} expose under identical names (but on unrelated
	 * generated classes). Lets {@link #populateCommonFields} drive either builder through the same
	 * lambda-based bridge.
	 */
	private record BuilderAdapter(
		@Nonnull Consumer<String> setReferenceName,
		@Nonnull IntConsumer setCount,
		@Nonnull Consumer<Iterable<GrpcFacetStatistics>> addAllFacetStatistics,
		@Nonnull Consumer<GrpcEntityReference.Builder> setGroupEntityReference,
		@Nonnull Consumer<GrpcSealedEntity> setGroupEntity
	) {
	}

}
