/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2026
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
import io.evitadb.api.requestResponse.mutation.EngineMutation;
import io.evitadb.api.requestResponse.mutation.Mutation;
import io.evitadb.api.requestResponse.mutation.StreamDirection;
import io.evitadb.api.requestResponse.schema.mutation.EntitySchemaMutation;
import io.evitadb.api.requestResponse.mutation.infrastructure.TransactionMutation;
import io.evitadb.dataType.ContainerType;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.externalApi.grpc.dataType.EvitaDataTypesConverter;
import io.evitadb.externalApi.grpc.generated.*;
import io.evitadb.externalApi.grpc.generated.GrpcChangeCatalogCapture.Builder;
import io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter;
import io.evitadb.externalApi.grpc.requestResponse.data.mutation.DelegatingEntityMutationConverter;
import io.evitadb.externalApi.grpc.requestResponse.data.mutation.DelegatingLocalMutationConverter;
import io.evitadb.externalApi.grpc.requestResponse.data.mutation.associatedData.AssociatedDataMutationConverter;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.DelegatingEngineMutationConverter;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.DelegatingEntitySchemaMutationConverter;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.DelegatingInfrastructureMutationConverter;
import io.evitadb.utils.Assert;
import io.evitadb.utils.VersionUtils.SemVer;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;

/**
 * This class contains conversion methods for CDC (Change Data Capture) requests and responses.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@Slf4j
public class ChangeCaptureConverter {

	/**
	 * Converts a {@link GetMutationsHistoryPageRequest} to a {@link ChangeCatalogCaptureRequest}.
	 *
	 * @param request the request to convert
	 * @return the converted request
	 */
	@Nonnull
	public static ChangeCatalogCaptureRequest toChangeCaptureRequest(
		@Nonnull GetMutationsHistoryPageRequest request,
		long requestedCatalogVersion,
		@Nonnull StreamDirection direction
	) {
		return new ChangeCatalogCaptureRequest(
			request.hasSinceVersion() && request.getSinceVersion().getValue() <= requestedCatalogVersion ?
				request.getSinceVersion().getValue() : requestedCatalogVersion,
			// the index default must be derived from `sinceIndex` presence alone - deriving it from `sinceVersion`
			// would collapse an unset index to 0, which is the slot reserved for the transaction header
			request.hasSinceIndex()
				? request.getSinceIndex().getValue()
				: (direction == StreamDirection.FORWARD ? 0 : Integer.MAX_VALUE),
			request.getCriteriaList()
			       .stream()
			       .map(ChangeCaptureConverter::toChangeCaptureCriteria)
			       .toArray(ChangeCatalogCaptureCriteria[]::new),
			EvitaEnumConverter.toCaptureContent(request.getContent())
		);
	}

	/**
	 * Converts a {@link GetMutationsHistoryPageRequest} to a {@link ChangeCatalogCaptureRequest} anchored at a
	 * lower-bound (floor) catalog version, for use with
	 * {@link io.evitadb.api.EvitaSessionContract#getMutationsHistoryForward}.
	 * Unlike the reverse overload above, an explicit {@code sinceVersion} above the floor is not clamped down -
	 * it is a legitimate, narrower request (e.g. "start me at version 900, not at the oldest"). Only a value
	 * below the floor is clamped up to it; there is no clamp at the other end, since a floor past the newest
	 * available version simply yields an empty result rather than being an error. Whenever the version gets
	 * clamped up this way (including when {@code sinceVersion} was left unset entirely), {@code sinceIndex} is
	 * reset to `0` - a client-supplied index refers to a position within the client's originally requested
	 * version, so carrying it over onto a different, clamped-up version would silently skip that version's
	 * header and early records.
	 *
	 * @param request             the request to convert
	 * @param floorCatalogVersion the lower bound to anchor at when the request leaves {@code sinceVersion} unset,
	 *                            or when the requested value falls below it
	 * @return the converted request
	 */
	@Nonnull
	public static ChangeCatalogCaptureRequest toChangeCaptureRequestForward(
		@Nonnull GetMutationsHistoryPageRequest request,
		long floorCatalogVersion
	) {
		final boolean versionClamped = !request.hasSinceVersion() ||
			request.getSinceVersion().getValue() < floorCatalogVersion;
		return new ChangeCatalogCaptureRequest(
			versionClamped ? floorCatalogVersion : request.getSinceVersion().getValue(),
			!versionClamped && request.hasSinceIndex() ? request.getSinceIndex().getValue() : 0,
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
		final ChangeSystemCaptureCriteria[] criteria = request.criteria();
		if (criteria != null) {
			for (final ChangeSystemCaptureCriteria criterion : criteria) {
				builder.addCriteria(toGrpcChangeSystemCaptureCriteria(criterion));
			}
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
	 * Converts a {@link GrpcUuid} and a {@link GrpcHeartBeat} into a {@link HeartBeat}.
	 *
	 * @param grpcUuid the unique identifier of the subscription in gRPC format
	 * @param grpcHeartBeat the gRPC representation of the heartbeat event containing metadata about the event
	 * @return the converted {@link HeartBeat} instance
	 */
	@Nonnull
	public static HeartBeat toHeartBeat(@Nonnull GrpcUuid grpcUuid, @Nonnull GrpcHeartBeat grpcHeartBeat) {
		return new HeartBeat(
			EvitaDataTypesConverter.toUuid(grpcUuid),
			grpcHeartBeat.getIndex(),
			EvitaDataTypesConverter.toOffsetDateTime(grpcHeartBeat.getTimestamp()),
			grpcHeartBeat.getLastObservedVersion(),
			grpcHeartBeat.getMillisToNextHeartbeat()
		);
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
		} else if (changeCatalogCapture.hasInfrastructureMutation()) {
			final Mutation infrastructureMutation = DelegatingInfrastructureMutationConverter.INSTANCE.convert(
				changeCatalogCapture.getInfrastructureMutation());
			Assert.isPremiseValid(
				infrastructureMutation instanceof CatalogBoundMutation,
				() -> new GenericEvitaInternalError(
					"Infrastructure mutation `" + infrastructureMutation.getClass().getName() +
						"` is not bound to a catalog and cannot be carried by a change catalog capture!"
				)
			);
			mutation = (CatalogBoundMutation) infrastructureMutation;
		} else {
			// no body arm is set - this is the `CHANGE_HEADER` content mode, where the capture carries
			// only its (version, index, area, operation) header and no mutation body at all
			mutation = null;
		}
		Assert.isPremiseValid(
			changeCatalogCapture.hasVersion(),
			"Change catalog capture must have version!"
		);
		Assert.isPremiseValid(
			changeCatalogCapture.hasIndex(),
			"Change catalog capture must have index!"
		);
		return new ChangeCatalogCapture(
			changeCatalogCapture.getVersion().getValue(),
			changeCatalogCapture.getIndex().getValue(),
			EvitaDataTypesConverter.toOffsetDateTime(changeCatalogCapture.getTimestamp()),
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
		@Nonnull ChangeCatalogCapture changeCatalogCapture,
		@Nullable SemVer clientVersion
	) {
		final Builder builder = GrpcChangeCatalogCapture
			.newBuilder()
			.setVersion(Int64Value.of(changeCatalogCapture.version()))
			.setIndex(Int32Value.of(changeCatalogCapture.index()))
			.setTimestamp(EvitaDataTypesConverter.toGrpcOffsetDateTime(changeCatalogCapture.timestamp()))
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

		AssociatedDataMutationConverter.doWithClientVersion(
			clientVersion,
			() -> {
				if (changeCatalogCapture.body() instanceof EntityMutation entityMutation) {
					builder.setEntityMutation(DelegatingEntityMutationConverter.INSTANCE.convert(entityMutation));
				} else if (changeCatalogCapture.body() instanceof LocalMutation<?, ?> localMutation) {
					builder.setLocalMutation(DelegatingLocalMutationConverter.INSTANCE.convert(localMutation));
				} else if (changeCatalogCapture.body() instanceof EntitySchemaMutation schemaMutation) {
					builder.setSchemaMutation(DelegatingEntitySchemaMutationConverter.INSTANCE.convert(schemaMutation));
				} else if (changeCatalogCapture.body() instanceof TransactionMutation transactionMutation) {
					builder.setInfrastructureMutation(DelegatingInfrastructureMutationConverter.INSTANCE.convert(transactionMutation));
				}
			}
		);
		return builder.build();
	}

	/**
	 * Converts a {@link GrpcChangeSystemCapture} to a {@link ChangeSystemCapture}.
	 *
	 * Handles the `body` oneof discriminator:
	 * - `SYSTEMMUTATION` → engine mutation body (ENGINE area).
	 * - `HOSTEVENT` → host system event body (HOST area, opt-in only).
	 * - `BODY_NOT_SET` → header-only capture; body is `null`.
	 *
	 * @param changeSystemCapture the capture to convert
	 * @return the converted request
	 */
	@Nonnull
	public static ChangeSystemCapture toChangeSystemCapture(@Nonnull GrpcChangeSystemCapture changeSystemCapture) {
		final SystemCaptureBody body = switch (changeSystemCapture.getBodyCase()) {
			case SYSTEMMUTATION ->
				DelegatingEngineMutationConverter.INSTANCE.convert(changeSystemCapture.getSystemMutation());
			case HOSTEVENT -> toHostSystemEvent(changeSystemCapture.getHostEvent());
			case BODY_NOT_SET -> null;
		};
		return new ChangeSystemCapture(
			changeSystemCapture.getVersion(),
			changeSystemCapture.getIndex(),
			EvitaDataTypesConverter.toOffsetDateTime(changeSystemCapture.getTimestamp()),
			EvitaEnumConverter.toOperation(changeSystemCapture.getOperation()),
			body
		);
	}

	/**
	 * Converts a {@link ChangeSystemCapture} to a {@link GrpcChangeSystemCapture}.
	 *
	 * Picks the correct body oneof branch based on the runtime body type:
	 * - {@link EngineMutation} → `systemMutation` (ENGINE area).
	 * - {@link HostSystemEvent} → `hostEvent` (HOST area).
	 * - `null` (HEADER content) → leaves the oneof unset.
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
			.setTimestamp(EvitaDataTypesConverter.toGrpcOffsetDateTime(capture.timestamp()))
			.setOperation(
				EvitaEnumConverter.toGrpcOperation(
					capture.operation()));
		final SystemCaptureBody body = capture.body();
		if (body instanceof final EngineMutation<?> engineMutation) {
			builder.setSystemMutation(
				DelegatingEngineMutationConverter.INSTANCE.convert(engineMutation)
			);
		} else if (body instanceof final HostSystemEvent hostEvent) {
			builder.setHostEvent(toGrpcHostSystemEvent(hostEvent));
		} else if (body != null) {
			throw new GenericEvitaInternalError(
				"Unsupported SystemCaptureBody type: " + body.getClass().getName()
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
		final CaptureArea captureArea;
		if (grpcCaptureCriteria.hasSchemaSite()) {
			captureArea = CaptureArea.SCHEMA;
		} else if (grpcCaptureCriteria.hasDataSite()) {
			captureArea = CaptureArea.DATA;
		} else {
			captureArea = EvitaEnumConverter.toCaptureArea(grpcCaptureCriteria.getArea());
		}
		final CaptureSite<?> captureSite = switch (captureArea) {
			case SCHEMA -> toSchemaSite(grpcCaptureCriteria.getSchemaSite());
			case DATA -> toDataSite(grpcCaptureCriteria.getDataSite());
			case INFRASTRUCTURE -> null;
		};
		return new ChangeCatalogCaptureCriteria(
			captureArea, captureSite
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
			          .toArray(ContainerType[]::new),
			schemaSite.getContainerNameList().toArray(new String[0])
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
		if (schemaSite.containerName() != null) {
			builder.addAllContainerName(Arrays.asList(schemaSite.containerName()));
		}
		return builder.build();
	}

	/**
	 * Converts a {@link HostSystemEvent} to a {@link GrpcHostSystemEvent}.
	 *
	 * Pattern-switches over the sealed variant set; defensively rejects any unknown
	 * subtype with a {@link GenericEvitaInternalError} (the sealed contract makes this
	 * unreachable, but the defensive-design rule applies anyway).
	 *
	 * @param event the host event to convert
	 * @return the converted gRPC representation
	 */
	@Nonnull
	public static GrpcHostSystemEvent toGrpcHostSystemEvent(@Nonnull HostSystemEvent event) {
		final GrpcHostSystemEvent.Builder builder = GrpcHostSystemEvent.newBuilder();
		// pattern-matching switch on sealed types is a Java 21 feature; evitaDB targets Java 17
		// so we use an `instanceof`-pattern chain that the compiler still supports here
		if (event instanceof HostSystemEvent.CatalogInstalledIntoLiveView installed) {
			builder.setCatalogInstalled(
				GrpcCatalogInstalledIntoLiveView.newBuilder()
					.setCatalogName(installed.catalogName())
					.setObservedState(EvitaEnumConverter.toGrpcCatalogState(installed.observedState()))
					.setCurrentEngineVersion(installed.currentEngineVersion())
					.build()
			);
		} else if (event instanceof HostSystemEvent.CatalogRemovedFromLiveView removed) {
			builder.setCatalogRemoved(
				GrpcCatalogRemovedFromLiveView.newBuilder()
					.setCatalogName(removed.catalogName())
					.setCurrentEngineVersion(removed.currentEngineVersion())
					.build()
			);
		} else if (event instanceof HostSystemEvent.CatalogSchemaUpdated schemaUpdated) {
			builder.setCatalogSchemaUpdated(
				GrpcCatalogSchemaUpdated.newBuilder()
					.setCatalogName(schemaUpdated.catalogName())
					.setNewSchemaVersion(schemaUpdated.newSchemaVersion())
					.setCurrentEngineVersion(schemaUpdated.currentEngineVersion())
					.build()
			);
		} else {
			throw new GenericEvitaInternalError(
				"Unsupported HostSystemEvent type: " + event.getClass().getName()
			);
		}
		return builder.build();
	}

	/**
	 * Converts a {@link GrpcHostSystemEvent} to a {@link HostSystemEvent}.
	 *
	 * Switches on the oneof event-case discriminator. {@code EVENT_NOT_SET} is treated as a
	 * forward-compat sentinel — when an older client receives a tag from a newer server whose
	 * variant the client does not yet know, the parser drops the field and the discriminator
	 * collapses to {@code EVENT_NOT_SET}. Returning {@code null} (rather than throwing) lets the
	 * subscription stream survive: the caller drops captures with a {@code null} body.
	 *
	 * @param grpc the gRPC host event to convert
	 * @return the converted domain host event, or {@code null} when the oneof is not set
	 *         (forward-compat with newer servers carrying unknown variants)
	 */
	@Nullable
	public static HostSystemEvent toHostSystemEvent(@Nonnull GrpcHostSystemEvent grpc) {
		return switch (grpc.getEventCase()) {
			case CATALOGINSTALLED -> {
				final GrpcCatalogInstalledIntoLiveView installed = grpc.getCatalogInstalled();
				yield new HostSystemEvent.CatalogInstalledIntoLiveView(
					installed.getCatalogName(),
					EvitaEnumConverter.toCatalogState(installed.getObservedState()),
					installed.getCurrentEngineVersion()
				);
			}
			case CATALOGREMOVED -> {
				final GrpcCatalogRemovedFromLiveView removed = grpc.getCatalogRemoved();
				yield new HostSystemEvent.CatalogRemovedFromLiveView(
					removed.getCatalogName(),
					removed.getCurrentEngineVersion()
				);
			}
			case CATALOGSCHEMAUPDATED -> {
				final GrpcCatalogSchemaUpdated schemaUpdated = grpc.getCatalogSchemaUpdated();
				yield new HostSystemEvent.CatalogSchemaUpdated(
					schemaUpdated.getCatalogName(),
					schemaUpdated.getNewSchemaVersion(),
					schemaUpdated.getCurrentEngineVersion()
				);
			}
			case EVENT_NOT_SET -> {
				// forward-compat: server emitted a host-event variant unknown to this client.
				// Soft-fail (return null) instead of tearing the subscription. Caller drops
				// captures that have a null body.
				log.debug(
					"Received GrpcHostSystemEvent with no oneof variant set — likely a forward-compat tag from a newer server; dropping body."
				);
				yield null;
			}
		};
	}

	/**
	 * Converts a {@link SystemCaptureArea} to a {@link GrpcSystemCaptureArea}.
	 *
	 * @param area the system capture area to convert
	 * @return the converted gRPC representation
	 */
	@Nonnull
	public static GrpcSystemCaptureArea toGrpcSystemCaptureArea(@Nonnull SystemCaptureArea area) {
		return switch (area) {
			case ENGINE -> GrpcSystemCaptureArea.SYSTEM_AREA_ENGINE;
			case HOST -> GrpcSystemCaptureArea.SYSTEM_AREA_HOST;
		};
	}

	/**
	 * Converts a {@link GrpcSystemCaptureArea} to a {@link SystemCaptureArea}.
	 *
	 * The proto3 default-value sentinel {@link GrpcSystemCaptureArea#SYSTEM_AREA_UNSPECIFIED}
	 * round-trips a domain `null` — see {@link #toChangeSystemCaptureCriteria}. This method
	 * therefore returns `null` for `SYSTEM_AREA_UNSPECIFIED` instead of throwing, so the
	 * criterion can carry the match-any semantic across the wire.
	 *
	 * @param grpc the gRPC system capture area to convert
	 * @return the converted domain system capture area, or `null` for the unspecified sentinel
	 */
	@Nullable
	public static SystemCaptureArea toSystemCaptureArea(@Nonnull GrpcSystemCaptureArea grpc) {
		return switch (grpc) {
			case SYSTEM_AREA_UNSPECIFIED -> null;
			case SYSTEM_AREA_ENGINE -> SystemCaptureArea.ENGINE;
			case SYSTEM_AREA_HOST -> SystemCaptureArea.HOST;
			case UNRECOGNIZED -> throw new GenericEvitaInternalError(
				"Unrecognized GrpcSystemCaptureArea: " + grpc
			);
		};
	}

	/**
	 * Converts a {@link ChangeSystemCaptureCriteria} to a {@link GrpcChangeSystemCaptureCriteria}.
	 *
	 * A `null` `area` on the input is preserved across the wire by leaving the gRPC `area` field
	 * at its proto3 default value ({@link GrpcSystemCaptureArea#SYSTEM_AREA_UNSPECIFIED}, value
	 * 0) — the receiving converter then maps that sentinel back to a `null` area, preserving the
	 * "match-any" semantics. See issue #1151 for the rationale behind the dedicated sentinel.
	 *
	 * @param criteria the criteria to convert
	 * @return the converted gRPC representation
	 */
	@Nonnull
	public static GrpcChangeSystemCaptureCriteria toGrpcChangeSystemCaptureCriteria(
		@Nonnull ChangeSystemCaptureCriteria criteria
	) {
		final GrpcChangeSystemCaptureCriteria.Builder builder = GrpcChangeSystemCaptureCriteria.newBuilder();
		if (criteria.area() != null) {
			builder.setArea(toGrpcSystemCaptureArea(criteria.area()));
		}
		// When `criteria.area()` is null, leave the area field at its default zero value
		// (`SYSTEM_AREA_UNSPECIFIED`) so the receiver can reconstruct `null` on deserialization.
		return builder.build();
	}

	/**
	 * Converts a {@link GrpcChangeSystemCaptureCriteria} to a {@link ChangeSystemCaptureCriteria}.
	 *
	 * @param grpc the gRPC criteria to convert
	 * @return the converted domain criteria
	 */
	@Nonnull
	public static ChangeSystemCaptureCriteria toChangeSystemCaptureCriteria(
		@Nonnull GrpcChangeSystemCaptureCriteria grpc
	) {
		return new ChangeSystemCaptureCriteria(toSystemCaptureArea(grpc.getArea()));
	}

}
