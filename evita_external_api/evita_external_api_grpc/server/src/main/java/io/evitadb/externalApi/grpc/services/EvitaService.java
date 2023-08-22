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

package io.evitadb.externalApi.grpc.services;

import com.google.protobuf.Empty;
import io.evitadb.api.EvitaContract;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.SessionTraits;
import io.evitadb.api.SessionTraits.SessionFlags;
import io.evitadb.api.requestResponse.cdc.ChangeSystemCapture;
import io.evitadb.api.requestResponse.cdc.ChangeSystemCaptureRequest;
import io.evitadb.api.requestResponse.cdc.NamedSubscription;
import io.evitadb.api.requestResponse.schema.mutation.TopLevelCatalogSchemaMutation;
import io.evitadb.core.Evita;
import io.evitadb.externalApi.grpc.cdc.ServerSystemResponseObserver;
import io.evitadb.externalApi.grpc.cdc.SystemChangeSubscriber;
import io.evitadb.externalApi.grpc.dataType.ChangeDataCaptureConverter;
import io.evitadb.externalApi.grpc.generated.*;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.DelegatingTopLevelCatalogSchemaMutationConverter;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.SchemaMutationConverter;
import io.evitadb.utils.UUIDUtil;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Flow;
import java.util.concurrent.Flow.Publisher;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.Flow.Subscription;

import static io.evitadb.externalApi.grpc.dataType.ChangeDataCaptureConverter.toChangeSystemCapture;
import static io.evitadb.externalApi.grpc.dataType.ChangeDataCaptureConverter.toGrpcChangeSystemCapture;
import static io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter.toCaptureContent;
import static io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter.toGrpcCatalogState;

/**
 * This service contains methods that could be called by gRPC clients on {@link Evita}
 *
 * @author Tomáš Pozler, 2022
 */
public class EvitaService extends EvitaServiceGrpc.EvitaServiceImplBase {

	private static final SchemaMutationConverter<TopLevelCatalogSchemaMutation, GrpcTopLevelCatalogSchemaMutation> CATALOG_SCHEMA_MUTATION_CONVERTER =
		new DelegatingTopLevelCatalogSchemaMutationConverter();

	/**
	 * Instance of Evita upon which will be executed service calls
	 */
	private final Evita evita;

	/**
	 * Builds array of {@link SessionFlags} based on session type and rollback transactions flag.
	 *
	 * @param sessionType          type of the session
	 * @param rollbackTransactions if true, all transactions will be rolled back on session close
	 * @return built array of {@link SessionFlags}
	 */
	@Nullable
	private static SessionFlags[] getSessionFlags(GrpcSessionType sessionType, boolean rollbackTransactions) {
		final List<SessionFlags> flags = new ArrayList<>(3);
		if (rollbackTransactions) {
			flags.add(SessionFlags.DRY_RUN);
		}
		if (sessionType == GrpcSessionType.READ_WRITE || sessionType == GrpcSessionType.BINARY_READ_WRITE) {
			flags.add(SessionFlags.READ_WRITE);
		}
		if (sessionType == GrpcSessionType.BINARY_READ_ONLY || sessionType == GrpcSessionType.BINARY_READ_WRITE) {
				flags.add(SessionFlags.BINARY);
		}
		return flags.isEmpty() ? null : flags.toArray(new SessionFlags[0]);
	}

	public EvitaService(@Nonnull Evita evita) {
		this.evita = evita;
	}

	/**
	 * Method is used to create read only session by calling {@link Evita#createSession(SessionTraits)}.
	 *
	 * @param request          request containing session type and session id
	 * @param responseObserver observer on which errors might be thrown and result returned
	 */
	@Override
	public void createReadOnlySession(@Nonnull GrpcEvitaSessionRequest request, @Nonnull StreamObserver<GrpcEvitaSessionResponse> responseObserver) {
		createSessionAndBuildResponse(responseObserver, request.getCatalogName(), GrpcSessionType.READ_ONLY, request.getDryRun());
	}

	/**
	 * Method is used to create read-write session by calling {@link Evita#createSession(SessionTraits)}.
	 *
	 * @param request          request containing session type and session id
	 * @param responseObserver observer on which errors might be thrown and result returned
	 */
	@Override
	public void createReadWriteSession(@Nonnull GrpcEvitaSessionRequest request, @Nonnull StreamObserver<GrpcEvitaSessionResponse> responseObserver) {
		createSessionAndBuildResponse(responseObserver, request.getCatalogName(), GrpcSessionType.READ_WRITE, request.getDryRun());
	}

	/**
	 * Method is used to create read-only session which will return data in binary format by calling {@link Evita#createSession(SessionTraits)}.
	 *
	 * @param request          request containing session type and session id
	 * @param responseObserver observer on which errors might be thrown and result returned
	 */
	@Override
	public void createBinaryReadOnlySession(@Nonnull GrpcEvitaSessionRequest request, @Nonnull StreamObserver<GrpcEvitaSessionResponse> responseObserver) {
		createSessionAndBuildResponse(responseObserver, request.getCatalogName(), GrpcSessionType.BINARY_READ_ONLY, request.getDryRun());
	}

	/**
	 * Method is used to create read-write session which will return data in binary format by calling {@link Evita#createSession(SessionTraits)}.
	 *
	 * @param request          request containing session type and session id
	 * @param responseObserver observer on which errors might be thrown and result returned
	 */
	@Override
	public void createBinaryReadWriteSession(@Nonnull GrpcEvitaSessionRequest request, @Nonnull StreamObserver<GrpcEvitaSessionResponse> responseObserver) {
		createSessionAndBuildResponse(responseObserver, request.getCatalogName(), GrpcSessionType.BINARY_READ_WRITE, request.getDryRun());
	}

	/**
	 * Method is used to terminate existing session.
	 *
	 * @param request          request containing catalog name and session id
	 * @param responseObserver observer on which errors might be thrown and result returned
	 */
	@Override
	public void terminateSession(@Nonnull GrpcEvitaSessionTerminationRequest request, @Nonnull StreamObserver<GrpcEvitaSessionTerminationResponse> responseObserver) {
		final boolean terminated = evita.getSessionById(request.getCatalogName(), UUIDUtil.uuid(request.getSessionId()))
			.map(session -> {
				evita.terminateSession(session);
				return true;
			})
			.orElse(false);

		responseObserver.onNext(GrpcEvitaSessionTerminationResponse.newBuilder()
			.setTerminated(terminated)
			.build());
		responseObserver.onCompleted();
	}

	/**
	 * Returns all names of catalogs stored in Evita.
	 *
	 * @param request          empty message
	 * @param responseObserver observer on which errors might be thrown and result returned
	 * @see EvitaContract#getCatalogNames() (String)
	 */
	@Override
	public void getCatalogNames(@Nonnull Empty request, @Nonnull StreamObserver<GrpcCatalogNamesResponse> responseObserver) {
		responseObserver.onNext(GrpcCatalogNamesResponse.newBuilder()
			.addAllCatalogNames(evita.getCatalogNames())
			.build());
		responseObserver.onCompleted();
	}

	/**
	 * Creates new catalog with a name specified in a request.
	 *
	 * @param request          containing name of the catalog to be created
	 * @param responseObserver observer on which errors might be thrown and result returned
	 * @see EvitaContract#defineCatalog(String)
	 */
	@Override
	public void defineCatalog(GrpcDefineCatalogRequest request, StreamObserver<GrpcDefineCatalogResponse> responseObserver) {
		evita.defineCatalog(request.getCatalogName());
		responseObserver.onNext(GrpcDefineCatalogResponse.newBuilder().setSuccess(true).build());
		responseObserver.onCompleted();
	}

	/**
	 * Renames existing catalog to a name specified in a request.
	 *
	 * @param request          containing names of the catalogs involved
	 * @param responseObserver observer on which errors might be thrown and result returned
	 * @see EvitaContract#renameCatalog(String, String)
	 */
	@Override
	public void renameCatalog(GrpcRenameCatalogRequest request, StreamObserver<GrpcRenameCatalogResponse> responseObserver) {
		evita.renameCatalog(request.getCatalogName(), request.getNewCatalogName());
		responseObserver.onNext(GrpcRenameCatalogResponse.newBuilder().setSuccess(true).build());
		responseObserver.onCompleted();
	}

	/**
	 * Replaces existing catalog with a different existing catalog and its contents.
	 *
	 * @param request          containing names of the catalogs involved
	 * @param responseObserver observer on which errors might be thrown and result returned
	 * @see EvitaContract#replaceCatalog(String, String)
	 */
	@Override
	public void replaceCatalog(GrpcReplaceCatalogRequest request, StreamObserver<GrpcReplaceCatalogResponse> responseObserver) {
		evita.replaceCatalog(request.getCatalogNameToBeReplacedWith(), request.getCatalogNameToBeReplaced());
		responseObserver.onNext(GrpcReplaceCatalogResponse.newBuilder().setSuccess(true).build());
		responseObserver.onCompleted();
	}

	/**
	 * Deletes catalog with a name specified in a request.
	 *
	 * @param request          containing name of the catalog to be deleted
	 * @param responseObserver observer on which errors might be thrown and result returned
	 * @see EvitaContract#deleteCatalogIfExists(String)
	 */
	@Override
	public void deleteCatalogIfExists(@Nonnull GrpcDeleteCatalogIfExistsRequest request, @Nonnull StreamObserver<GrpcDeleteCatalogIfExistsResponse> responseObserver) {
		final boolean success = evita.deleteCatalogIfExists(request.getCatalogName());
		responseObserver.onNext(GrpcDeleteCatalogIfExistsResponse.newBuilder().setSuccess(success).build());
		responseObserver.onCompleted();
	}


	/**
	 * Applies catalog mutation affecting entire catalog.
	 */
	@Override
	public void update(@Nonnull GrpcUpdateEvitaRequest request, @Nonnull StreamObserver<Empty> responseObserver) {
		final TopLevelCatalogSchemaMutation[] schemaMutations = request.getSchemaMutationsList()
			.stream()
			.map(CATALOG_SCHEMA_MUTATION_CONVERTER::convert)
			.toArray(TopLevelCatalogSchemaMutation[]::new);
		evita.update(schemaMutations);

		responseObserver.onNext(Empty.getDefaultInstance());
		responseObserver.onCompleted();
	}

	/**
	 * This method is used to create session and build a {@link GrpcEvitaSessionResponse} object.
	 *
	 * @param responseObserver     observer on which errors might be thrown and result returned
	 * @param catalogName          name of the catalog on which should be session created
	 * @param sessionType          type of the session
	 * @param rollbackTransactions if true, all transactions will be rolled back on session close
	 */
	private void createSessionAndBuildResponse(@Nonnull StreamObserver<GrpcEvitaSessionResponse> responseObserver, @Nonnull String catalogName, @Nonnull GrpcSessionType sessionType, boolean rollbackTransactions) {
		final SessionFlags[] flags = getSessionFlags(sessionType, rollbackTransactions);
		final EvitaSessionContract session = evita.createSession(
			new SessionTraits(
				catalogName,
				flags
			)
		);
		responseObserver.onNext(GrpcEvitaSessionResponse.newBuilder()
			.setSessionId(session.getId().toString())
			.setCatalogState(toGrpcCatalogState(session.getCatalogState()))
			.setSessionType(sessionType)
			.build());
		responseObserver.onCompleted();
	}

	@Override
	public void registerSystemChangeCapture(GrpcRegisterSystemChangeCaptureRequest request, StreamObserver<GrpcRegisterSystemChangeCaptureResponse> responseObserver) {
		final Publisher<ChangeSystemCapture> publisher = evita.registerSystemChangeCapture(new ChangeSystemCaptureRequest(toCaptureContent(request.getContent())));

		final ServerCallStreamObserver<GrpcRegisterSystemChangeCaptureResponse> observer = ((ServerCallStreamObserver<GrpcRegisterSystemChangeCaptureResponse>) responseObserver);

		publisher.subscribe(new Subscriber<>() {

			private Subscription subscription;

			@Override
			public void onSubscribe(Subscription subscription) {
				this.subscription = subscription;
				// netty channel builder doesn't allow for manual flow control using these requests, but we need to initialize somehow
				/*observer.setOnReadyHandler(() -> */subscription.request(1)/*)*/;
				observer.setOnCancelHandler(subscription::cancel);
			}

			@Override
			public void onNext(ChangeSystemCapture item) {
				observer.onNext(GrpcRegisterSystemChangeCaptureResponse.newBuilder()
					.setCapture(ChangeDataCaptureConverter.toGrpcChangeSystemCapture(item))
					.build());
				// netty channel builder doesn't allow for manual flow control using these requests
				subscription.request(1);
			}

			@Override
			public void onError(Throwable throwable) {
				observer.onError(throwable);
			}

			@Override
			public void onComplete() {
				observer.onCompleted();
			}
		});
	}
}
