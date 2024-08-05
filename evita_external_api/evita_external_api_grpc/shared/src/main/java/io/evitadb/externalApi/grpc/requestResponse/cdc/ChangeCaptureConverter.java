/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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

package io.evitadb.externalApi.grpc.requestResponse.cdc;

import com.google.protobuf.Int32Value;
import com.google.protobuf.Int64Value;
import com.google.protobuf.StringValue;
import io.evitadb.api.requestResponse.cdc.CaptureArea;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCapture;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCaptureCriteria;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCaptureRequest;
import io.evitadb.api.requestResponse.cdc.ChangeSystemCapture;
import io.evitadb.api.requestResponse.cdc.DataSite;
import io.evitadb.api.requestResponse.cdc.Operation;
import io.evitadb.api.requestResponse.cdc.SchemaSite;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation;
import io.evitadb.api.requestResponse.data.mutation.LocalMutation;
import io.evitadb.api.requestResponse.mutation.Mutation;
import io.evitadb.api.requestResponse.schema.mutation.EntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.TopLevelCatalogSchemaMutation;
import io.evitadb.dataType.ContainerType;
import io.evitadb.externalApi.grpc.generated.GetMutationsHistoryPageRequest;
import io.evitadb.externalApi.grpc.generated.GetMutationsHistoryRequest;
import io.evitadb.externalApi.grpc.generated.GrpcCaptureCriteria;
import io.evitadb.externalApi.grpc.generated.GrpcCaptureDataSite;
import io.evitadb.externalApi.grpc.generated.GrpcCaptureSchemaSite;
import io.evitadb.externalApi.grpc.generated.GrpcChangeCatalogCapture;
import io.evitadb.externalApi.grpc.generated.GrpcChangeCatalogCapture.Builder;
import io.evitadb.externalApi.grpc.generated.GrpcChangeSystemCapture;
import io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter;
import io.evitadb.externalApi.grpc.requestResponse.data.mutation.DelegatingEntityMutationConverter;
import io.evitadb.externalApi.grpc.requestResponse.data.mutation.DelegatingLocalMutationConverter;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.DelegatingEntitySchemaMutationConverter;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.DelegatingTopLevelCatalogSchemaMutationConverter;

import javax.annotation.Nonnull;
import java.util.Arrays;

/**
 * This class contains conversion methods for CDC (Change Data Capture) requests and responses.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public class ChangeCaptureConverter {

	/**
	 * Converts a {@link GetMutationsHistoryPageRequest} to a {@link ChangeCatalogCaptureRequest}.
	 *
	 * @param request the request to convert
	 * @return the converted request
	 */
	@Nonnull
	public static ChangeCatalogCaptureRequest toChangeCaptureRequest(@Nonnull GetMutationsHistoryPageRequest request) {
		return new ChangeCatalogCaptureRequest(
			request.getSinceVersion(),
			request.getSinceIndex(),
			request.getCriteriaList()
				.stream()
				.map(ChangeCaptureConverter::toChangeCaptureCriteria)
				.toArray(ChangeCatalogCaptureCriteria[]::new),
			EvitaEnumConverter.toCaptureContent(request.getContent())
		);
	}

	/**
	 * Converts a {@link GetMutationsHistoryRequest} to a {@link ChangeCatalogCaptureRequest}.
	 *
	 * @param request the request to convert
	 * @return the converted request
	 */
	@Nonnull
	public static ChangeCatalogCaptureRequest toChangeCaptureRequest(@Nonnull GetMutationsHistoryRequest request) {
		return new ChangeCatalogCaptureRequest(
			request.hasSinceVersion() ? request.getSinceVersion().getValue() : null,
			request.hasSinceIndex() ? request.getSinceIndex().getValue() : null,
			request.getCriteriaList()
				.stream()
				.map(ChangeCaptureConverter::toChangeCaptureCriteria)
				.toArray(ChangeCatalogCaptureCriteria[]::new),
			EvitaEnumConverter.toCaptureContent(request.getContent())
		);
	}

	/**
	 * Converts a {@link ChangeCatalogCaptureRequest} to a {@link GetMutationsHistoryPageRequest}.
	 *
	 * @param request the request to convert
	 * @return the converted request
	 */
	@Nonnull
	public static GetMutationsHistoryRequest toGrpcChangeCaptureRequest(@Nonnull ChangeCatalogCaptureRequest request) {
		final GetMutationsHistoryRequest.Builder builder = GetMutationsHistoryRequest.newBuilder()
			.setContent(EvitaEnumConverter.toGrpcCaptureContent(request.content()));

		if (request.sinceVersion() != null) {
			builder.setSinceVersion(Int64Value.of(request.sinceVersion()));
		}
		if (request.sinceIndex() != null) {
			builder.setSinceIndex(Int32Value.of(request.sinceIndex()));
		}
		if (request.criteria() != null) {
			Arrays.stream(request.criteria())
				.map(ChangeCaptureConverter::toGrpcCaptureCriteria)
				.forEach(builder::addCriteria);
		}

		return builder.build();
	}

	/**
	 * Converts {@link GrpcChangeCatalogCapture} to {@link ChangeCatalogCapture}.
	 *
	 * @param changeCatalogCapture the change catalog capture to convert
	 * @return the converted request
	 */
	@Nonnull
	public static ChangeCatalogCapture toChangeCatalogCapture(@Nonnull GrpcChangeCatalogCapture changeCatalogCapture) {
		final Mutation mutation;
		if (changeCatalogCapture.hasEntityMutation()) {
			mutation = DelegatingEntityMutationConverter.INSTANCE.convert(changeCatalogCapture.getEntityMutation());
		} else if (changeCatalogCapture.hasLocalMutation()) {
			mutation = DelegatingLocalMutationConverter.INSTANCE.convert(changeCatalogCapture.getLocalMutation());
		} else if (changeCatalogCapture.hasSchemaMutation()) {
			mutation = DelegatingEntitySchemaMutationConverter.INSTANCE.convert(changeCatalogCapture.getSchemaMutation());
		} else {
			mutation = null;
		}
		return new ChangeCatalogCapture(
			changeCatalogCapture.getVersion(),
			changeCatalogCapture.getIndex(),
			EvitaEnumConverter.toCaptureArea(changeCatalogCapture.getArea()),
			changeCatalogCapture.hasEntityType() ? changeCatalogCapture.getEntityType().getValue() : null,
			EvitaEnumConverter.toOperation(changeCatalogCapture.getOperation()),
			mutation
		);
	}

	/**
	 * Converts a {@link GrpcChangeCatalogCapture} to a {@link ChangeCatalogCapture}.
	 *
	 * @param changeCatalogCapture the change catalog capture to convert
	 * @return the converted request
	 */
	@Nonnull
	public static GrpcChangeCatalogCapture toGrpcChangeCatalogCapture(@Nonnull ChangeCatalogCapture changeCatalogCapture) {
		final Builder builder = GrpcChangeCatalogCapture.newBuilder()
			.setVersion(changeCatalogCapture.version())
			.setIndex(changeCatalogCapture.index())
			.setArea(EvitaEnumConverter.toGrpcCaptureArea(changeCatalogCapture.area()))
			.setOperation(EvitaEnumConverter.toGrpcOperation(changeCatalogCapture.operation()));
		if (changeCatalogCapture.entityType() != null) {
			builder.setEntityType(StringValue.of(changeCatalogCapture.entityType()));
		}
		if (changeCatalogCapture.body() instanceof EntityMutation entityMutation) {
			builder.setEntityMutation(DelegatingEntityMutationConverter.INSTANCE.convert(entityMutation));
		} else if (changeCatalogCapture.body() instanceof LocalMutation<?, ?> localMutation) {
			builder.setLocalMutation(DelegatingLocalMutationConverter.INSTANCE.convert(localMutation));
		} else if (changeCatalogCapture.body() instanceof EntitySchemaMutation schemaMutation) {
			builder.setSchemaMutation(DelegatingEntitySchemaMutationConverter.INSTANCE.convert(schemaMutation));
		}
		return builder.build();
	}

	/**
	 * Converts a {@link GrpcChangeSystemCapture} to a {@link ChangeSystemCapture}.
	 *
	 * @param changeSystemCapture the capture to convert
	 * @return the converted request
	 */
	@Nonnull
	public static ChangeSystemCapture toChangeSystemCapture(@Nonnull GrpcChangeSystemCapture changeSystemCapture) {
		return new ChangeSystemCapture(
			changeSystemCapture.getVersion(),
			changeSystemCapture.getIndex(),
			changeSystemCapture.hasCatalog() ? changeSystemCapture.getCatalog().getValue() : null,
			EvitaEnumConverter.toOperation(changeSystemCapture.getOperation()),
			DelegatingTopLevelCatalogSchemaMutationConverter.INSTANCE.convert(changeSystemCapture.getSystemMutation())
		);
	}

	/**
	 * Converts a {@link ChangeSystemCapture} to a {@link GrpcChangeSystemCapture}.
	 * @param capture the capture to convert
	 * @return the converted request
	 */
	@Nonnull
	public static GrpcChangeSystemCapture toGrpcChangeSystemCapture(@Nonnull ChangeSystemCapture capture) {
		final GrpcChangeSystemCapture.Builder builder = GrpcChangeSystemCapture.newBuilder()
			.setVersion(capture.version())
			.setIndex(capture.index())
			.setOperation(EvitaEnumConverter.toGrpcOperation(capture.operation()));
		if (capture.catalog() != null) {
			builder.setCatalog(StringValue.of(capture.catalog()));
		}
		if (capture.body() instanceof TopLevelCatalogSchemaMutation topLevelCatalogSchemaMutation) {
			builder.setSystemMutation(
				DelegatingTopLevelCatalogSchemaMutationConverter.INSTANCE.convert(topLevelCatalogSchemaMutation)
			);
		}
		return builder.build();
	}

	/**
	 * Converts a {@link ChangeCatalogCaptureCriteria} to a {@link GrpcCaptureCriteria}.
	 *
	 * @param criteria the criteria to convert
	 * @return the converted request
	 */
	@Nonnull
	private static GrpcCaptureCriteria toGrpcCaptureCriteria(@Nonnull ChangeCatalogCaptureCriteria criteria) {
		final GrpcCaptureCriteria.Builder builder = GrpcCaptureCriteria.newBuilder();
		if (criteria.area() != null) {
			builder.setArea(EvitaEnumConverter.toGrpcCaptureArea(criteria.area()));
		}
		if (criteria.site() instanceof DataSite dataSite) {
			builder.setDataSite(toGrpcCaptureDataSite(dataSite));
		} else if (criteria.site() instanceof SchemaSite schemaSite) {
			builder.setSchemaSite(toGrpcCaptureSchemaSite(schemaSite));
		}
		return builder.build();
	}

	/**
	 * Converts a {@link GrpcCaptureCriteria} to a {@link ChangeCatalogCaptureCriteria}.
	 *
	 * @param grpcCaptureCriteria the capture criteria to convert
	 * @return the converted request
	 */
	@Nonnull
	private static ChangeCatalogCaptureCriteria toChangeCaptureCriteria(@Nonnull GrpcCaptureCriteria grpcCaptureCriteria) {
		final CaptureArea captureArea = EvitaEnumConverter.toCaptureArea(grpcCaptureCriteria.getArea());
		return new ChangeCatalogCaptureCriteria(
			captureArea,
			captureArea == CaptureArea.SCHEMA ? toSchemaSite(grpcCaptureCriteria.getSchemaSite()) : toDataSite(grpcCaptureCriteria.getDataSite())
		);
	}

	/**
	 * Converts a {@link GrpcCaptureDataSite} to a {@link DataSite}.
	 *
	 * @param dataSite the data site to convert
	 * @return the converted request
	 */
	@Nonnull
	private static DataSite toDataSite(@Nonnull GrpcCaptureDataSite dataSite) {
		return new DataSite(
			dataSite.hasEntityType() ? dataSite.getEntityType().getValue() : null,
			dataSite.hasEntityPrimaryKey() ? dataSite.getEntityPrimaryKey().getValue() : null,
			dataSite.getOperationList().stream().map(EvitaEnumConverter::toOperation).toArray(Operation[]::new),
			dataSite.getContainerTypeList().stream().map(EvitaEnumConverter::toContainerType).toArray(ContainerType[]::new),
			dataSite.getContainerNameList().toArray(new String[0])
		);
	}

	/**
	 * Converts a {@link GrpcCaptureDataSite} to a {@link DataSite}.
	 *
	 * @param dataSite the data site to convert
	 * @return the converted request
	 */
	@Nonnull
	private static GrpcCaptureDataSite toGrpcCaptureDataSite(@Nonnull DataSite dataSite) {
		final GrpcCaptureDataSite.Builder builder = GrpcCaptureDataSite.newBuilder();
		if (dataSite.entityType() != null) {
			builder.setEntityType(StringValue.of(dataSite.entityType()));
		}
		if (dataSite.entityPrimaryKey() != null) {
			builder.setEntityPrimaryKey(Int32Value.of(dataSite.entityPrimaryKey()));
		}
		if (dataSite.operation() != null) {
			Arrays.stream(dataSite.operation()).map(EvitaEnumConverter::toGrpcOperation).forEach(builder::addOperation);
		}
		if (dataSite.containerType() != null) {
			Arrays.stream(dataSite.containerType()).map(EvitaEnumConverter::toGrpcCaptureContainerType).forEach(builder::addContainerType);
		}
		if (dataSite.containerName() != null) {
			builder.addAllContainerName(Arrays.asList(dataSite.containerName()));
		}
		return builder.build();
	}

	/**
	 * Converts a {@link GrpcCaptureSchemaSite} to a {@link SchemaSite}.
	 *
	 * @param schemaSite the schema site to convert
	 * @return the converted request
	 */
	@Nonnull
	private static SchemaSite toSchemaSite(@Nonnull GrpcCaptureSchemaSite schemaSite) {
		return new SchemaSite(
			schemaSite.hasEntityType() ? schemaSite.getEntityType().getValue() : null,
			schemaSite.getOperationList().stream().map(EvitaEnumConverter::toOperation).toArray(Operation[]::new),
			schemaSite.getContainerTypeList().stream().map(EvitaEnumConverter::toContainerType).toArray(ContainerType[]::new)
		);
	}

	/**
	 * Converts a {@link SchemaSite} to a {@link GrpcCaptureSchemaSite}.
	 *
	 * @param schemaSite the schema site to convert
	 * @return the converted request
	 */
	@Nonnull
	private static GrpcCaptureSchemaSite toGrpcCaptureSchemaSite(@Nonnull SchemaSite schemaSite) {
		final GrpcCaptureSchemaSite.Builder builder = GrpcCaptureSchemaSite.newBuilder();
		if (schemaSite.entityType() != null) {
			builder.setEntityType(StringValue.of(schemaSite.entityType()));
		}
		if (schemaSite.operation() != null) {
			Arrays.stream(schemaSite.operation()).map(EvitaEnumConverter::toGrpcOperation).forEach(builder::addOperation);
		}
		if (schemaSite.containerType() != null) {
			Arrays.stream(schemaSite.containerType()).map(EvitaEnumConverter::toGrpcCaptureContainerType).forEach(builder::addContainerType);
		}
		return builder.build();
	}

}
