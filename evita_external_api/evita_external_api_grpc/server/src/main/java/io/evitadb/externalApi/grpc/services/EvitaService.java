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
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.externalApi.grpc.services;

import com.google.protobuf.ByteString;
import com.google.protobuf.Empty;
import io.evitadb.api.EvitaContract;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.SessionTraits;
import io.evitadb.api.SessionTraits.SessionFlags;
import io.evitadb.api.requestResponse.schema.mutation.TopLevelCatalogSchemaMutation;
import io.evitadb.api.requestResponse.system.SystemStatus;
import io.evitadb.core.Evita;
import io.evitadb.exception.UnexpectedIOException;
import io.evitadb.externalApi.grpc.constants.GrpcHeaders;
import io.evitadb.externalApi.grpc.dataType.EvitaDataTypesConverter;
import io.evitadb.externalApi.grpc.generated.*;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.DelegatingTopLevelCatalogSchemaMutationConverter;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.SchemaMutationConverter;
import io.evitadb.externalApi.grpc.services.interceptors.ServerSessionInterceptor;
import io.evitadb.externalApi.trace.ExternalApiTracingContextProvider;
import io.evitadb.utils.Assert;
import io.evitadb.utils.UUIDUtil;
import io.grpc.Metadata;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter.toGrpcCatalogState;

/**
 * This service contains methods that could be called by gRPC clients on {@link Evita}
 *
 * @author Tomáš Pozler, 2022
 */
@Slf4j
public class EvitaService extends EvitaServiceGrpc.EvitaServiceImplBase {

	private static final SchemaMutationConverter<TopLevelCatalogSchemaMutation, GrpcTopLevelCatalogSchemaMutation> CATALOG_SCHEMA_MUTATION_CONVERTER =
		new DelegatingTopLevelCatalogSchemaMutationConverter();

	/**
	 * Instance of Evita upon which will be executed service calls
	 */
	@Nonnull private final Evita evita;

	/**
	 * Builds array of {@link SessionFlags} based on session type and rollback transaction flag.
	 *
	 * @param sessionType          type of the session
	 * @param rollbackTransactions if true, all transaction will be rolled back on session close
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

	/**
	 * Executes entire lambda function within the scope of a tracing context.
	 */
	private static void executeWithClientContext(@Nonnull Runnable lambda) {
		final Metadata metadata = ServerSessionInterceptor.METADATA.get();
		ExternalApiTracingContextProvider.getContext()
			.executeWithinBlock(
				GrpcHeaders.getGrpcTraceTaskNameWithMethodName(metadata),
				metadata,
				lambda
			);
	}

	/**
	 * Deletes temporary file if it exists.
	 *
	 * @param backupFilePath path to the file to be deleted
	 */
	private static void deleteFileIfExists(@Nullable Path backupFilePath, @Nonnull String purpose) {
		if (backupFilePath != null) {
			try {
				Files.deleteIfExists(backupFilePath);
			} catch (IOException e) {
				log.error("Failed to delete temporary " + purpose + " file: {}", backupFilePath, e);
			}
		}
	}

	public EvitaService(@Nonnull Evita evita) {
		this.evita = evita;
	}

	/**
	 * Retrieves the server status.
	 *
	 * @param request          the request for server status
	 * @param responseObserver the observer for receiving the server status response
	 */
	@Override
	public void serverStatus(Empty request, StreamObserver<GrpcEvitaServerStatusResponse> responseObserver) {
		executeWithClientContext(() -> {
			final SystemStatus systemStatus = evita.getSystemStatus();
			responseObserver.onNext(
				GrpcEvitaServerStatusResponse
					.newBuilder()
					.setVersion(systemStatus.version())
					.setStartedAt(EvitaDataTypesConverter.toGrpcOffsetDateTime(systemStatus.startedAt()))
					.setUptime(systemStatus.uptime().toSeconds())
					.setInstanceId(systemStatus.instanceId())
					.setCatalogsCorrupted(systemStatus.catalogsCorrupted())
					.setCatalogsOk(systemStatus.catalogsOk())
					.build()
			);
			responseObserver.onCompleted();
		});
	}

	/**
	 * Method is used to create read only session by calling {@link Evita#createSession(SessionTraits)}.
	 *
	 * @param request          request containing session type and session id
	 * @param responseObserver observer on which errors might be thrown and result returned
	 */
	@Override
	public void createReadOnlySession(GrpcEvitaSessionRequest request, StreamObserver<GrpcEvitaSessionResponse> responseObserver) {
		createSessionAndBuildResponse(responseObserver, request.getCatalogName(), GrpcSessionType.READ_ONLY, request.getDryRun());
	}

	/**
	 * Method is used to create read-write session by calling {@link Evita#createSession(SessionTraits)}.
	 *
	 * @param request          request containing session type and session id
	 * @param responseObserver observer on which errors might be thrown and result returned
	 */
	@Override
	public void createReadWriteSession(GrpcEvitaSessionRequest request, StreamObserver<GrpcEvitaSessionResponse> responseObserver) {
		createSessionAndBuildResponse(responseObserver, request.getCatalogName(), GrpcSessionType.READ_WRITE, request.getDryRun());
	}

	/**
	 * Method is used to create read-only session which will return data in binary format by calling {@link Evita#createSession(SessionTraits)}.
	 *
	 * @param request          request containing session type and session id
	 * @param responseObserver observer on which errors might be thrown and result returned
	 */
	@Override
	public void createBinaryReadOnlySession(GrpcEvitaSessionRequest request, StreamObserver<GrpcEvitaSessionResponse> responseObserver) {
		createSessionAndBuildResponse(responseObserver, request.getCatalogName(), GrpcSessionType.BINARY_READ_ONLY, request.getDryRun());
	}

	/**
	 * Method is used to create read-write session which will return data in binary format by calling {@link Evita#createSession(SessionTraits)}.
	 *
	 * @param request          request containing session type and session id
	 * @param responseObserver observer on which errors might be thrown and result returned
	 */
	@Override
	public void createBinaryReadWriteSession(GrpcEvitaSessionRequest request, StreamObserver<GrpcEvitaSessionResponse> responseObserver) {
		createSessionAndBuildResponse(responseObserver, request.getCatalogName(), GrpcSessionType.BINARY_READ_WRITE, request.getDryRun());
	}

	/**
	 * Method is used to terminate existing session.
	 *
	 * @param request          request containing catalog name and session id
	 * @param responseObserver observer on which errors might be thrown and result returned
	 */
	@Override
	public void terminateSession(GrpcEvitaSessionTerminationRequest request, StreamObserver<GrpcEvitaSessionTerminationResponse> responseObserver) {
		executeWithClientContext(() -> {
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
		});
	}

	/**
	 * Returns all names of catalogs stored in Evita.
	 *
	 * @param request          empty message
	 * @param responseObserver observer on which errors might be thrown and result returned
	 * @see EvitaContract#getCatalogNames() (String)
	 */
	@Override
	public void getCatalogNames(Empty request, StreamObserver<GrpcCatalogNamesResponse> responseObserver) {
		executeWithClientContext(() -> {
			responseObserver.onNext(GrpcCatalogNamesResponse.newBuilder()
				.addAllCatalogNames(evita.getCatalogNames())
				.build());
			responseObserver.onCompleted();
		});
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
		executeWithClientContext(() -> {
			evita.defineCatalog(request.getCatalogName());
			responseObserver.onNext(GrpcDefineCatalogResponse.newBuilder().setSuccess(true).build());
			responseObserver.onCompleted();
		});
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
		executeWithClientContext(() -> {
			evita.renameCatalog(request.getCatalogName(), request.getNewCatalogName());
			responseObserver.onNext(GrpcRenameCatalogResponse.newBuilder().setSuccess(true).build());
			responseObserver.onCompleted();
		});
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
		executeWithClientContext(() -> {
			evita.replaceCatalog(request.getCatalogNameToBeReplacedWith(), request.getCatalogNameToBeReplaced());
			responseObserver.onNext(GrpcReplaceCatalogResponse.newBuilder().setSuccess(true).build());
			responseObserver.onCompleted();
		});
	}

	/**
	 * Restores catalog from uploaded backup binary file into a new catalog.
	 *
	 * @param responseObserver observer on which errors might be thrown and result returned
	 * @see EvitaContract#restoreCatalog(String, InputStream)
	 */
	@Override
	public StreamObserver<GrpcRestoreCatalogRequest> restoreCatalog(StreamObserver<GrpcRestoreCatalogResponse> responseObserver) {
		Path backupFilePath = null;
		try {
			try {
				final Path workDirectory = evita.getConfiguration().transaction().transactionWorkDirectory();
				backupFilePath = Files.createTempFile(workDirectory, "catalog_backup_for_restore-", ".zip");
				final Path finalBackupFilePath = backupFilePath;
				@SuppressWarnings("resource")
				final OutputStream outputStream = Files.newOutputStream(finalBackupFilePath, StandardOpenOption.APPEND);
				final AtomicLong bytesRead = new AtomicLong(0);

				return new StreamObserver<>() {
					private String catalogNameToRestore;

					@Override
					public void onNext(GrpcRestoreCatalogRequest request) {
						this.catalogNameToRestore = request.getCatalogName();
						try {
							final ByteString backupFile = request.getBackupFile();
							backupFile.writeTo(outputStream);
							bytesRead.addAndGet(backupFile.size());
						} catch (IOException e) {
							throw new UnexpectedIOException(
								"Failed to write backup file to temporary file.",
								"Failed to write backup file to temporary file.",
								e
							);
						}
					}

					@Override
					public void onError(Throwable t) {
						try {
							outputStream.close();
						} catch (IOException e) {
							log.error("Failed to close output stream for backup file: {}", finalBackupFilePath, e);
						} finally {
							deleteFileIfExists(finalBackupFilePath, "restore");
							responseObserver.onError(t);
						}
					}

					@Override
					public void onCompleted() {
						try {
							outputStream.close();
							Assert.isPremiseValid(catalogNameToRestore != null, "Catalog name to restore must be provided.");
							evita.restoreCatalog(catalogNameToRestore, Files.newInputStream(finalBackupFilePath, StandardOpenOption.READ));
							responseObserver.onNext(GrpcRestoreCatalogResponse.newBuilder().setRead(bytesRead.get()).build());
							responseObserver.onCompleted();
						} catch (Exception e) {
							responseObserver.onError(e);
							deleteFileIfExists(finalBackupFilePath, "restore");
						}
					}
				};
			} catch (IOException e) {
				responseObserver.onError(e);
				throw e;
			}
		} catch (Exception e) {
			if (backupFilePath != null) {
				deleteFileIfExists(backupFilePath, "restore");
			}
			return new NoopStreamObserver<>();
		}
	}

	/**
	 * Deletes catalog with a name specified in a request.
	 *
	 * @param request          containing name of the catalog to be deleted
	 * @param responseObserver observer on which errors might be thrown and result returned
	 * @see EvitaContract#deleteCatalogIfExists(String)
	 */
	@Override
	public void deleteCatalogIfExists(GrpcDeleteCatalogIfExistsRequest
		                                  request, StreamObserver<GrpcDeleteCatalogIfExistsResponse> responseObserver) {
		executeWithClientContext(() -> {
			boolean success = evita.deleteCatalogIfExists(request.getCatalogName());
			responseObserver.onNext(GrpcDeleteCatalogIfExistsResponse.newBuilder().setSuccess(success).build());
			responseObserver.onCompleted();
		});
	}

	/**
	 * Applies catalog mutation affecting entire catalog.
	 */
	@Override
	public void update(GrpcUpdateEvitaRequest request, StreamObserver<Empty> responseObserver) {
		executeWithClientContext(() -> {
			final TopLevelCatalogSchemaMutation[] schemaMutations = request.getSchemaMutationsList()
				.stream()
				.map(CATALOG_SCHEMA_MUTATION_CONVERTER::convert)
				.toArray(TopLevelCatalogSchemaMutation[]::new);

			evita.update(schemaMutations);
			responseObserver.onNext(Empty.getDefaultInstance());
			responseObserver.onCompleted();
		});
	}

	/**
	 * This method is used to create session and build a {@link GrpcEvitaSessionResponse} object.
	 *
	 * @param responseObserver     observer on which errors might be thrown and result returned
	 * @param catalogName          name of the catalog on which should be session created
	 * @param sessionType          type of the session
	 * @param rollbackTransactions if true, all transaction will be rolled back on session close
	 */
	private void createSessionAndBuildResponse
	(@Nonnull StreamObserver<GrpcEvitaSessionResponse> responseObserver, @Nonnull String
		catalogName, @Nonnull GrpcSessionType sessionType, boolean rollbackTransactions) {
		executeWithClientContext(() -> {
			final SessionFlags[] flags = getSessionFlags(sessionType, rollbackTransactions);
			final EvitaSessionContract session = evita.createSession(new SessionTraits(catalogName, flags));
			responseObserver.onNext(GrpcEvitaSessionResponse.newBuilder()
				.setSessionId(session.getId().toString())
				.setCatalogState(toGrpcCatalogState(session.getCatalogState()))
				.setSessionType(sessionType)
				.build());
			responseObserver.onCompleted();
		});
	}

	/**
	 * No-op implementation of StreamObserver. Used in case the proper observer could not be created.
	 */
	private static class NoopStreamObserver<V> implements StreamObserver<V> {

		@Override
		public void onNext(V value) {
		}

		@Override
		public void onError(Throwable t) {
		}

		@Override
		public void onCompleted() {
		}
	}

}
