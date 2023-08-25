/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.externalApi.grpc.dataType;

import com.google.protobuf.Int32Value;
import io.evitadb.api.requestResponse.cdc.CaptureSite;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCapture;
import io.evitadb.api.requestResponse.cdc.ChangeSystemCapture;
import io.evitadb.api.requestResponse.cdc.DataSite;
import io.evitadb.api.requestResponse.cdc.Operation;
import io.evitadb.api.requestResponse.cdc.SchemaSite;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation;
import io.evitadb.api.requestResponse.data.mutation.LocalMutation;
import io.evitadb.api.requestResponse.mutation.Mutation;
import io.evitadb.api.requestResponse.schema.mutation.EntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.LocalCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.TopLevelCatalogSchemaMutation;
import io.evitadb.dataType.ClassifierType;
import io.evitadb.externalApi.grpc.generated.GrpcChangeDataCapture;
import io.evitadb.externalApi.grpc.generated.GrpcChangeSystemCapture;
import io.evitadb.externalApi.grpc.generated.GrpcDataSite;
import io.evitadb.externalApi.grpc.generated.GrpcEntityMutation;
import io.evitadb.externalApi.grpc.generated.GrpcLocalCatalogSchemaMutation;
import io.evitadb.externalApi.grpc.generated.GrpcRegisterChangeCatalogCaptureRequest;
import io.evitadb.externalApi.grpc.generated.GrpcSchemaSite;
import io.evitadb.externalApi.grpc.generated.GrpcTopLevelCatalogSchemaMutation;
import io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter;
import io.evitadb.externalApi.grpc.requestResponse.data.mutation.DelegatingEntityMutationConverter;
import io.evitadb.externalApi.grpc.requestResponse.data.mutation.DelegatingLocalMutationConverter;
import io.evitadb.externalApi.grpc.requestResponse.data.mutation.EntityMutationConverter;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.DelegatingEntitySchemaMutationConverter;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.DelegatingLocalCatalogSchemaMutationConverter;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.DelegatingTopLevelCatalogSchemaMutationConverter;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.SchemaMutationConverter;

import java.util.Arrays;
import java.util.stream.Collectors;

import static io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter.toCaptureArea;
import static io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter.toOperation;

public class ChangeCatalogCaptureConverter {
	private static final SchemaMutationConverter<TopLevelCatalogSchemaMutation, GrpcTopLevelCatalogSchemaMutation> TOP_LEVEL_CATALOG_SCHEMA_MUTATION_CONVERTER = new DelegatingTopLevelCatalogSchemaMutationConverter();
	private static final SchemaMutationConverter<LocalCatalogSchemaMutation, GrpcLocalCatalogSchemaMutation> LOCAL_CATALOG_SCHEMA_MUTATION_CONVERTER =
		new DelegatingLocalCatalogSchemaMutationConverter();
	private static final EntityMutationConverter<EntityMutation, GrpcEntityMutation> ENTITY_MUTATION_CONVERTER =
		new DelegatingEntityMutationConverter();

	private static final DelegatingEntitySchemaMutationConverter ENTITY_SCHEMA_MUTATION_CONVERTER = new DelegatingEntitySchemaMutationConverter();

	private static final DelegatingLocalMutationConverter ENTITY_LOCAL_MUTATION_CONVERTER = new DelegatingLocalMutationConverter();

	public static GrpcChangeSystemCapture toGrpcChangeSystemCapture(ChangeSystemCapture changeDataCapture) {
		final GrpcChangeSystemCapture.Builder builder = GrpcChangeSystemCapture.newBuilder();
		if (changeDataCapture.body() != null) {
			builder.setMutation(TOP_LEVEL_CATALOG_SCHEMA_MUTATION_CONVERTER.convert((TopLevelCatalogSchemaMutation) changeDataCapture.body()));
		}
		return builder
			.setCatalog(changeDataCapture.catalog())
			.setOperation(EvitaEnumConverter.toGrpcOperation(changeDataCapture.operation()))
			.build();
	}

	public static ChangeSystemCapture toChangeSystemCapture(GrpcChangeSystemCapture grpcChangeSystemCapture) {
		/* TODO TPO - redesign */
		return new ChangeSystemCapture(
//			UUID.randomUUID(),
			0,
			grpcChangeSystemCapture.getCatalog(),
			toOperation(grpcChangeSystemCapture.getOperation()),
			null
			/*grpcChangeSystemCapture.hasMutation() ? TOP_LEVEL_CATALOG_SCHEMA_MUTATION_CONVERTER.convert(grpcChangeSystemCapture.getMutation()) : null*/
		);
	}

	public static GrpcChangeDataCapture toGrpcChangeDataCapture(ChangeCatalogCapture changeCatalogCapture) {
		final GrpcChangeDataCapture.Builder builder = GrpcChangeDataCapture.newBuilder();
		if (changeCatalogCapture.body() instanceof EntityMutation entityMutation) {
			builder.setEntityMutation(ENTITY_MUTATION_CONVERTER.convert(entityMutation));
		} else if (changeCatalogCapture.body() instanceof LocalMutation<?, ?> localMutation) {
			builder.setLocalMutation(ENTITY_LOCAL_MUTATION_CONVERTER.convert(localMutation));
		} else if (changeCatalogCapture.body() instanceof EntitySchemaMutation entitySchemaMutation) {
			builder.setEntitySchemaMutation(ENTITY_SCHEMA_MUTATION_CONVERTER.convert(entitySchemaMutation));
		} else if (changeCatalogCapture.body() instanceof LocalCatalogSchemaMutation localCatalogSchemaMutation) {
			builder.setCatalogSchemaMutation(LOCAL_CATALOG_SCHEMA_MUTATION_CONVERTER.convert(localCatalogSchemaMutation));
		}

		if (changeCatalogCapture.version() != null) {
			builder.setVersion(Int32Value.newBuilder().setValue(changeCatalogCapture.version()).build());
		}

		return builder
			.setCatalog(changeCatalogCapture.catalog())
			.setEntityType(changeCatalogCapture.entityType())
			.setOperation(EvitaEnumConverter.toGrpcOperation(changeCatalogCapture.operation()))
			.build();
	}

	public static ChangeCatalogCapture toChangeDataCapture(GrpcChangeDataCapture grpcChangeDataCapture) {
		final Mutation mutation = switch (grpcChangeDataCapture.getBodyCase()) {
			case ENTITYMUTATION ->
				ENTITY_MUTATION_CONVERTER.convert(grpcChangeDataCapture.getEntityMutation());
			case LOCALMUTATION ->
				ENTITY_LOCAL_MUTATION_CONVERTER.convert(grpcChangeDataCapture.getLocalMutation());
			case ENTITYSCHEMAMUTATION ->
				ENTITY_SCHEMA_MUTATION_CONVERTER.convert(grpcChangeDataCapture.getEntitySchemaMutation());
			case CATALOGSCHEMAMUTATION ->
				LOCAL_CATALOG_SCHEMA_MUTATION_CONVERTER.convert(grpcChangeDataCapture.getCatalogSchemaMutation());
			case BODY_NOT_SET -> null;
		};
		return new ChangeCatalogCapture(
			// todo jno/tpo: implement counter
			0,
			toCaptureArea(grpcChangeDataCapture.getArea()),
			grpcChangeDataCapture.getCatalog(),
			grpcChangeDataCapture.getEntityType(),
			grpcChangeDataCapture.getVersion().equals(Int32Value.getDefaultInstance()) ? null : grpcChangeDataCapture.getVersion().getValue(),
			toOperation(grpcChangeDataCapture.getOperation()),
			mutation
		);
	}

	public static CaptureSite toCaptureSite(GrpcRegisterChangeCatalogCaptureRequest grpcRegisterChangeDataCaptureRequest) {
		return switch (grpcRegisterChangeDataCaptureRequest.getSiteCase()) {
			case DATASITE -> new DataSite(
				grpcRegisterChangeDataCaptureRequest.getDataSite().getEntityType(),
				grpcRegisterChangeDataCaptureRequest.getDataSite().getEntityPrimaryKey().equals(Int32Value.getDefaultInstance()) ? null : grpcRegisterChangeDataCaptureRequest.getDataSite().getEntityPrimaryKey().getValue(),
				grpcRegisterChangeDataCaptureRequest.getDataSite().getClassifierTypesList().stream().map(EvitaEnumConverter::toClassifierType).toArray(ClassifierType[]::new),
				grpcRegisterChangeDataCaptureRequest.getDataSite().getOperationsList().stream().map(EvitaEnumConverter::toOperation).toArray(Operation[]::new)
			);
			case SCHEMASITE -> new SchemaSite(
				grpcRegisterChangeDataCaptureRequest.getSchemaSite().getEntityType(),
				grpcRegisterChangeDataCaptureRequest.getSchemaSite().getOperationsList().stream().map(EvitaEnumConverter::toOperation).toArray(Operation[]::new)
			);
			default ->
				throw new IllegalArgumentException("Unknown capture site type: " + grpcRegisterChangeDataCaptureRequest.getSiteCase());
		};
	}

	public static GrpcDataSite toGrpcDataSite(DataSite dataSite) {
		return GrpcDataSite.newBuilder()
			.setEntityType(dataSite.entityType())
			.setEntityPrimaryKey(dataSite.entityPrimaryKey() == null ? Int32Value.getDefaultInstance() : Int32Value.newBuilder().setValue(dataSite.entityPrimaryKey()).build())
			.addAllClassifierTypes(Arrays.stream(dataSite.classifierType()).map(EvitaEnumConverter::toGrpcClassifierType).collect(Collectors.toList()))
			.addAllOperations(Arrays.stream(dataSite.operation()).map(EvitaEnumConverter::toGrpcOperation).collect(Collectors.toList()))
			.build();
	}

	public static GrpcSchemaSite toGrpcSchemaSite(SchemaSite dataSite) {
		return GrpcSchemaSite.newBuilder()
			.setEntityType(dataSite.entityType())
			.addAllOperations(Arrays.stream(dataSite.operation()).map(EvitaEnumConverter::toGrpcOperation).collect(Collectors.toList()))
			.build();
	}

	/* TODO TPO - this has been flattened */
	/*public static CaptureSince toCaptureSince(@Nonnull GrpcCaptureSince grpcCaptureSince) {
		return new CaptureSince(
			grpcCaptureSince.getVersion().equals(Int32Value.getDefaultInstance()) ? null : grpcCaptureSince.getVersion().getValue(),
			grpcCaptureSince.getTransactionId()
		);
	}

	public static GrpcCaptureSince toGrpcCaptureSince(@Nonnull CaptureSince captureSince) {
		GrpcCaptureSince.Builder builder = GrpcCaptureSince.newBuilder()
			.setTransactionId(captureSince.transactionId());
		if (captureSince.version() != null) {
			builder.setVersion(Int32Value.newBuilder().setValue(captureSince.version()).build());
		}
		return builder.build();
	}*/

}
