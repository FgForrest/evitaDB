/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2025
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
import io.evitadb.api.requestResponse.cdc.*;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation;
import io.evitadb.api.requestResponse.data.mutation.LocalMutation;
import io.evitadb.api.requestResponse.mutation.CatalogBoundMutation;
import io.evitadb.api.requestResponse.schema.mutation.EntitySchemaMutation;
import io.evitadb.dataType.ContainerType;
import io.evitadb.externalApi.grpc.generated.*;
import io.evitadb.externalApi.grpc.generated.GrpcChangeCatalogCapture.Builder;
import io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter;
import io.evitadb.externalApi.grpc.requestResponse.data.mutation.DelegatingEntityMutationConverter;
import io.evitadb.externalApi.grpc.requestResponse.data.mutation.DelegatingLocalMutationConverter;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.DelegatingEngineMutationConverter;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.DelegatingEntitySchemaMutationConverter;

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
	 * Converts a {@link ChangeSystemCaptureRequest} to a {@link GrpcRegisterSystemChangeCaptureRequest}.
	 *
	 * @param request the request to convert
	 * @return the converted request
	 */
	@Nonnull
	public static GrpcRegisterSystemChangeCaptureRequest toGrpcChangeSystemCaptureRequest(
		@Nonnull ChangeSystemCaptureRequest request
	) {
		final GrpcRegisterSystemChangeCaptureRequest.Builder builder = GrpcRegisterSystemChangeCaptureRequest
			.newBuilder()
			.setContent(EvitaEnumConverter.toGrpcChangeCaptureContent(request.content()));

		if (request.sinceVersion() != null) {
			builder.setSinceVersion(Int64Value.of(request.sinceVersion()));
		}
		if (request.sinceIndex() != null) {
			builder.setSinceIndex(Int32Value.of(request.sinceIndex()));
		}

		return builder.build();
	}

	/**
	 * Converts a {@link ChangeCatalogCaptureRequest} to a {@link GrpcRegisterChangeCatalogCaptureRequest}.
	 *
	 * @param request the request to convert
	 * @return the converted request
	 */
	@Nonnull
	public static GrpcRegisterChangeCatalogCaptureRequest toGrpcChangeCatalogCaptureRequest(
		@Nonnull ChangeCatalogCaptureRequest request
	) {
		final GrpcRegisterChangeCatalogCaptureRequest.Builder builder = GrpcRegisterChangeCatalogCaptureRequest
			.newBuilder()
			.setContent(EvitaEnumConverter.toGrpcChangeCaptureContent(request.content()));

		if (request.sinceVersion() != null) {
			builder.setSinceVersion(Int64Value.of(request.sinceVersion()));
		}
		if (request.sinceIndex() != null) {
			builder.setSinceIndex(Int32Value.of(request.sinceIndex()));
		}
		if (request.criteria() != null) {
			Arrays.stream(request.criteria())
			      .map(ChangeCaptureConverter::toGrpcChangeCaptureCriteria)
			      .forEach(builder::addCriteria);
		}

		return builder.build();
	}

	/**
	 * Converts a {@link ChangeCatalogCaptureRequest} to a {@link GetMutationsHistoryPageRequest}.
	 *
	 * @param request the request to convert
	 * @return the converted request
	 */
	@Nonnull
	public static GetMutationsHistoryRequest toGrpcChangeCaptureRequest(@Nonnull ChangeCatalogCaptureRequest request) {
		final GetMutationsHistoryRequest.Builder builder = GetMutationsHistoryRequest
			.newBuilder()
			.setContent(
				EvitaEnumConverter.toGrpcChangeCaptureContent(
					request.content()));

		if (request.sinceVersion() != null) {
			builder.setSinceVersion(Int64Value.of(request.sinceVersion()));
		}
		if (request.sinceIndex() != null) {
			builder.setSinceIndex(Int32Value.of(request.sinceIndex()));
		}
		if (request.criteria() != null) {
			Arrays.stream(request.criteria())
			      .map(ChangeCaptureConverter::toGrpcChangeCaptureCriteria)
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
		final CatalogBoundMutation mutation;
		if (changeCatalogCapture.hasEntityMutation()) {
			mutation = DelegatingEntityMutationConverter.INSTANCE.convert(changeCatalogCapture.getEntityMutation());
		} else if (changeCatalogCapture.hasLocalMutation()) {
			mutation = DelegatingLocalMutationConverter.INSTANCE.convert(changeCatalogCapture.getLocalMutation());
		} else if (changeCatalogCapture.hasSchemaMutation()) {
			mutation = DelegatingEntitySchemaMutationConverter.INSTANCE.convert(
				changeCatalogCapture.getSchemaMutation());
		} else {
			mutation = null;
		}
		return new ChangeCatalogCapture(
			changeCatalogCapture.getVersion(),
			changeCatalogCapture.getIndex(),
			EvitaEnumConverter.toCaptureArea(changeCatalogCapture.getArea()),
			changeCatalogCapture.hasEntityType() ? changeCatalogCapture.getEntityType().getValue() : null,
			changeCatalogCapture.hasEntityPrimaryKey() ? changeCatalogCapture.getEntityPrimaryKey().getValue() : null,
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
	public static GrpcChangeCatalogCapture toGrpcChangeCatalogCapture(
		@Nonnull ChangeCatalogCapture changeCatalogCapture
	) {
		final Builder builder = GrpcChangeCatalogCapture
			.newBuilder()
			.setVersion(changeCatalogCapture.version())
			.setIndex(changeCatalogCapture.index())
			.setArea(EvitaEnumConverter.toGrpcChangeCaptureArea(
				changeCatalogCapture.area()))
			.setOperation(EvitaEnumConverter.toGrpcOperation(
				changeCatalogCapture.operation()));
		if (changeCatalogCapture.entityType() != null) {
			builder.setEntityType(StringValue.of(changeCatalogCapture.entityType()));
		}
		if (changeCatalogCapture.entityPrimaryKey() != null) {
			builder.setEntityPrimaryKey(Int32Value.of(changeCatalogCapture.entityPrimaryKey()));
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
			EvitaEnumConverter.toOperation(changeSystemCapture.getOperation()),
			DelegatingEngineMutationConverter.INSTANCE.convert(changeSystemCapture.getSystemMutation())
		);
	}

	/**
	 * Converts a {@link ChangeSystemCapture} to a {@link GrpcChangeSystemCapture}.
	 *
	 * @param capture the capture to convert
	 * @return the converted request
	 */
	@Nonnull
	public static GrpcChangeSystemCapture toGrpcChangeSystemCapture(@Nonnull ChangeSystemCapture capture) {
		final GrpcChangeSystemCapture.Builder builder = GrpcChangeSystemCapture
			.newBuilder()
			.setVersion(capture.version())
			.setIndex(capture.index())
			.setOperation(
				EvitaEnumConverter.toGrpcOperation(
					capture.operation()));
		if (capture.body() != null) {
			builder.setSystemMutation(
				DelegatingEngineMutationConverter.INSTANCE.convert(capture.body())
			);
		}
		return builder.build();
	}

	/**
	 * Converts a {@link GrpcRegisterChangeCatalogCaptureRequest} to a {@link ChangeCatalogCaptureRequest}.
	 *
	 * @param request the gRPC request to convert
	 * @return the converted {@link ChangeCatalogCaptureRequest} instance
	 */
	@Nonnull
	public static ChangeCatalogCaptureRequest toChangeCatalogCaptureRequest(
		@Nonnull GrpcRegisterChangeCatalogCaptureRequest request
	) {
		final ChangeCatalogCaptureRequest.Builder requestBuilder = ChangeCatalogCaptureRequest.builder();
		if (request.hasSinceVersion()) {
			requestBuilder.sinceVersion(request.getSinceVersion().getValue());
		}
		if (request.hasSinceIndex()) {
			requestBuilder.sinceIndex(request.getSinceIndex().getValue());
		}
		final GrpcChangeCaptureContent content = request.getContent();
		if (content == GrpcChangeCaptureContent.CHANGE_BODY) {
			requestBuilder.content(ChangeCaptureContent.BODY);
		} else {
			requestBuilder.content(ChangeCaptureContent.HEADER);
		}
		requestBuilder.criteria(
			request.getCriteriaList().stream()
			       .map(ChangeCaptureConverter::toChangeCaptureCriteria)
			       .toArray(ChangeCatalogCaptureCriteria[]::new)
		);
		return requestBuilder.build();
	}

	/**
	 * Converts a {@link ChangeCatalogCaptureCriteria} to a {@link GrpcChangeCaptureCriteria}.
	 *
	 * @param criteria the criteria to convert
	 * @return the converted request
	 */
	@Nonnull
	private static GrpcChangeCaptureCriteria toGrpcChangeCaptureCriteria(
		@Nonnull ChangeCatalogCaptureCriteria criteria
	) {
		final GrpcChangeCaptureCriteria.Builder builder = GrpcChangeCaptureCriteria.newBuilder();
		if (criteria.area() != null) {
			builder.setArea(EvitaEnumConverter.toGrpcChangeCaptureArea(criteria.area()));
		}
		if (criteria.site() instanceof DataSite dataSite) {
			builder.setDataSite(toGrpcChangeCaptureDataSite(dataSite));
		} else if (criteria.site() instanceof SchemaSite schemaSite) {
			builder.setSchemaSite(toGrpcChangeCaptureSchemaSite(schemaSite));
		}
		return builder.build();
	}

	/**
	 * Converts a {@link GrpcChangeCaptureCriteria} to a {@link ChangeCatalogCaptureCriteria}.
	 *
	 * @param grpcCaptureCriteria the capture criteria to convert
	 * @return the converted request
	 */
	@Nonnull
	private static ChangeCatalogCaptureCriteria toChangeCaptureCriteria(
		@Nonnull GrpcChangeCaptureCriteria grpcCaptureCriteria
	) {
		final CaptureArea captureArea = EvitaEnumConverter.toCaptureArea(grpcCaptureCriteria.getArea());
		return new ChangeCatalogCaptureCriteria(
			captureArea,
			captureArea == CaptureArea.SCHEMA ?
				toSchemaSite(grpcCaptureCriteria.getSchemaSite()) :
				toDataSite(grpcCaptureCriteria.getDataSite())
		);
	}

	/**
	 * Converts a {@link GrpcChangeCaptureDataSite} to a {@link DataSite}.
	 *
	 * @param dataSite the data site to convert
	 * @return the converted request
	 */
	@Nonnull
	private static DataSite toDataSite(@Nonnull GrpcChangeCaptureDataSite dataSite) {
		return new DataSite(
			dataSite.hasEntityType() ? dataSite.getEntityType().getValue() : null,
			dataSite.hasEntityPrimaryKey() ? dataSite.getEntityPrimaryKey().getValue() : null,
			dataSite.getOperationList().stream().map(EvitaEnumConverter::toOperation).toArray(Operation[]::new),
			dataSite.getContainerTypeList()
			        .stream()
			        .map(EvitaEnumConverter::toContainerType)
			        .toArray(ContainerType[]::new),
			dataSite.getContainerNameList().toArray(new String[0])
		);
	}

	/**
	 * Converts a {@link GrpcChangeCaptureDataSite} to a {@link DataSite}.
	 *
	 * @param dataSite the data site to convert
	 * @return the converted request
	 */
	@Nonnull
	private static GrpcChangeCaptureDataSite toGrpcChangeCaptureDataSite(@Nonnull DataSite dataSite) {
		final GrpcChangeCaptureDataSite.Builder builder = GrpcChangeCaptureDataSite.newBuilder();
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
			Arrays.stream(dataSite.containerType()).map(EvitaEnumConverter::toGrpcChangeCaptureContainerType).forEach(
				builder::addContainerType);
		}
		if (dataSite.containerName() != null) {
			builder.addAllContainerName(Arrays.asList(dataSite.containerName()));
		}
		return builder.build();
	}

	/**
	 * Converts a {@link GrpcChangeCaptureSchemaSite} to a {@link SchemaSite}.
	 *
	 * @param schemaSite the schema site to convert
	 * @return the converted request
	 */
	@Nonnull
	private static SchemaSite toSchemaSite(@Nonnull GrpcChangeCaptureSchemaSite schemaSite) {
		return new SchemaSite(
			schemaSite.hasEntityType() ? schemaSite.getEntityType().getValue() : null,
			schemaSite.getOperationList().stream().map(EvitaEnumConverter::toOperation).toArray(Operation[]::new),
			schemaSite.getContainerTypeList()
			          .stream()
			          .map(EvitaEnumConverter::toContainerType)
			          .toArray(ContainerType[]::new)
		);
	}

	/**
	 * Converts a {@link SchemaSite} to a {@link GrpcChangeCaptureSchemaSite}.
	 *
	 * @param schemaSite the schema site to convert
	 * @return the converted request
	 */
	@Nonnull
	private static GrpcChangeCaptureSchemaSite toGrpcChangeCaptureSchemaSite(@Nonnull SchemaSite schemaSite) {
		final GrpcChangeCaptureSchemaSite.Builder builder = GrpcChangeCaptureSchemaSite.newBuilder();
		if (schemaSite.entityType() != null) {
			builder.setEntityType(StringValue.of(schemaSite.entityType()));
		}
		if (schemaSite.operation() != null) {
			Arrays.stream(schemaSite.operation()).map(EvitaEnumConverter::toGrpcOperation).forEach(
				builder::addOperation);
		}
		if (schemaSite.containerType() != null) {
			Arrays.stream(schemaSite.containerType()).map(EvitaEnumConverter::toGrpcChangeCaptureContainerType).forEach(
				builder::addContainerType);
		}
		return builder.build();
	}

}
