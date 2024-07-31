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
import com.google.protobuf.StringValue;
import io.evitadb.api.requestResponse.cdc.CaptureArea;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCapture;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCaptureRequest;
import io.evitadb.api.requestResponse.cdc.DataSite;
import io.evitadb.api.requestResponse.cdc.Operation;
import io.evitadb.api.requestResponse.cdc.SchemaSite;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation;
import io.evitadb.api.requestResponse.data.mutation.LocalMutation;
import io.evitadb.api.requestResponse.mutation.Mutation;
import io.evitadb.api.requestResponse.schema.mutation.EntitySchemaMutation;
import io.evitadb.dataType.ContainerType;
import io.evitadb.externalApi.grpc.generated.GetMutationsHistoryPageRequest;
import io.evitadb.externalApi.grpc.generated.GetMutationsHistoryRequest;
import io.evitadb.externalApi.grpc.generated.GrpcChangeCatalogCapture;
import io.evitadb.externalApi.grpc.generated.GrpcChangeCatalogCapture.Builder;
import io.evitadb.externalApi.grpc.generated.GrpcDataSite;
import io.evitadb.externalApi.grpc.generated.GrpcSchemaSite;
import io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter;
import io.evitadb.externalApi.grpc.requestResponse.data.mutation.DelegatingEntityMutationConverter;
import io.evitadb.externalApi.grpc.requestResponse.data.mutation.DelegatingLocalMutationConverter;
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
		final CaptureArea captureArea = EvitaEnumConverter.toCaptureArea(request.getArea());
		return new ChangeCatalogCaptureRequest(
			captureArea,
			captureArea == CaptureArea.SCHEMA ? toSchemaSite(request.getSchemaSite()) : toDataSite(request.getDataSite()),
			EvitaEnumConverter.toCaptureContent(request.getContent()),
			request.getSinceVersion(),
			request.getSinceIndex()
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
		final CaptureArea captureArea = EvitaEnumConverter.toCaptureArea(request.getArea());
		return new ChangeCatalogCaptureRequest(
			captureArea,
			captureArea == CaptureArea.SCHEMA ? toSchemaSite(request.getSchemaSite()) : toDataSite(request.getDataSite()),
			EvitaEnumConverter.toCaptureContent(request.getContent()),
			request.getSinceVersion(),
			request.getSinceIndex()
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
			.setSinceVersion(request.sinceVersion())
			.setContent(EvitaEnumConverter.toGrpcCaptureContent(request.content()));

		if (request.area() != null) {
			builder.setArea(EvitaEnumConverter.toGrpcCaptureArea(request.area()));
		}
		if (request.sinceIndex() != null) {
			builder.setSinceIndex(request.sinceIndex());
		}
		if (request.site() instanceof DataSite dataSite) {
			builder.setDataSite(toGrpcDataSite(dataSite));
		} else if (request.site() instanceof SchemaSite schemaSite) {
			builder.setSchemaSite(toGrpcSchemaSite(schemaSite));
		}
		return builder.build();
	}

	/**
	 * Converts {@link GrpcChangeCatalogCapture} to {@link ChangeCatalogCapture}.
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
	 * Converts a {@link GrpcDataSite} to a {@link DataSite}.
	 *
	 * @param dataSite the data site to convert
	 * @return the converted request
	 */
	@Nonnull
	private static DataSite toDataSite(@Nonnull GrpcDataSite dataSite) {
		return new DataSite(
			dataSite.hasEntityType() ? dataSite.getEntityType().getValue() : null,
			dataSite.hasEntityPrimaryKey() ? dataSite.getEntityPrimaryKey().getValue() : null,
			dataSite.getOperationList().stream().map(EvitaEnumConverter::toOperation).toArray(Operation[]::new),
			dataSite.getContainerTypeList().stream().map(EvitaEnumConverter::toContainerType).toArray(ContainerType[]::new),
			dataSite.getClassifierNameList().toArray(new String[0])
		);
	}

	/**
	 * Converts a {@link GrpcDataSite} to a {@link DataSite}.
	 *
	 * @param dataSite the data site to convert
	 * @return the converted request
	 */
	@Nonnull
	private static GrpcDataSite toGrpcDataSite(@Nonnull DataSite dataSite) {
		final GrpcDataSite.Builder builder = GrpcDataSite.newBuilder();
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
			Arrays.stream(dataSite.containerType()).map(EvitaEnumConverter::toGrpcContainerType).forEach(builder::addContainerType);
		}
		if (dataSite.classifierName() != null) {
			builder.addAllClassifierName(Arrays.asList(dataSite.classifierName()));
		}
		return builder.build();
	}

	/**
	 * Converts a {@link GrpcSchemaSite} to a {@link SchemaSite}.
	 *
	 * @param schemaSite the schema site to convert
	 * @return the converted request
	 */
	@Nonnull
	private static SchemaSite toSchemaSite(@Nonnull GrpcSchemaSite schemaSite) {
		return new SchemaSite(
			schemaSite.hasEntityType() ? schemaSite.getEntityType().getValue() : null,
			schemaSite.getOperationList().stream().map(EvitaEnumConverter::toOperation).toArray(Operation[]::new)
		);
	}

	/**
	 * Converts a {@link SchemaSite} to a {@link GrpcSchemaSite}.
	 * @param schemaSite the schema site to convert
	 * @return the converted request
	 */
	@Nonnull
	private static GrpcSchemaSite toGrpcSchemaSite(@Nonnull SchemaSite schemaSite) {
		final GrpcSchemaSite.Builder builder = GrpcSchemaSite.newBuilder();
		if (schemaSite.entityType() != null) {
			builder.setEntityType(StringValue.of(schemaSite.entityType()));
		}
		if (schemaSite.operation() != null) {
			Arrays.stream(schemaSite.operation()).map(EvitaEnumConverter::toGrpcOperation).forEach(builder::addOperation);
		}
		return builder.build();
	}

}
