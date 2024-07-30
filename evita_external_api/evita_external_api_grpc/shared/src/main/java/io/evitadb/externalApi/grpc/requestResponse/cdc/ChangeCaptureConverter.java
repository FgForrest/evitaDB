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

import com.google.protobuf.StringValue;
import io.evitadb.api.requestResponse.cdc.CaptureArea;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCapture;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCaptureRequest;
import io.evitadb.api.requestResponse.cdc.DataSite;
import io.evitadb.api.requestResponse.cdc.Operation;
import io.evitadb.api.requestResponse.cdc.SchemaSite;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation;
import io.evitadb.api.requestResponse.data.mutation.LocalMutation;
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
		} else if (changeCatalogCapture.body() instanceof LocalMutation<?,?> localMutation) {
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
	 * Converts a {@link GrpcSchemaSite} to a {@link SchemaSite}.
	 *
	 * @param schemaSite the schema site to convert
	 * @return the converted request
	 */
	@Nonnull
	private static SchemaSite toSchemaSite(GrpcSchemaSite schemaSite) {
		return new SchemaSite(
			schemaSite.hasEntityType() ? schemaSite.getEntityType().getValue() : null,
			schemaSite.getOperationList().stream().map(EvitaEnumConverter::toOperation).toArray(Operation[]::new)
		);
	}

}
