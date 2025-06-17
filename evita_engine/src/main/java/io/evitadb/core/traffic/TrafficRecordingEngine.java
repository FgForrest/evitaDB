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

package io.evitadb.core.traffic;


import io.evitadb.api.CatalogState;
import io.evitadb.api.LabelIntrospector;
import io.evitadb.api.TrafficRecordingReader;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.configuration.TrafficRecordingOptions;
import io.evitadb.api.exception.IndexNotReady;
import io.evitadb.api.exception.SingletonTaskAlreadyRunningException;
import io.evitadb.api.exception.TemporalDataNotAvailableException;
import io.evitadb.api.observability.trace.TracingBlockReference;
import io.evitadb.api.observability.trace.TracingContext;
import io.evitadb.api.observability.trace.TracingContext.SpanAttribute;
import io.evitadb.api.query.head.Label;
import io.evitadb.api.requestResponse.EntityFetchAwareDecorator;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.mutation.Mutation;
import io.evitadb.api.requestResponse.trafficRecording.TrafficRecording;
import io.evitadb.api.requestResponse.trafficRecording.TrafficRecordingCaptureRequest;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.core.file.ExportFileService;
import io.evitadb.core.query.QueryPlan;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.exception.UnexpectedIOException;
import io.evitadb.store.spi.SessionSink;
import io.evitadb.store.spi.TrafficRecorder;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.IOUtils;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * TrafficRecordingEngine is a class responsible for managing the recording of traffic data,
 * tracing execution within specific contexts, and executing operations related to sessions,
 * queries, fetches, and mutations. It collaborates with a TracingContext to trace operations
 * and a TrafficRecorder to record traffic metrics and details.
 *
 * This class supports operations that involve creating and closing sessions, recording queries,
 * fetches, enrichments, and mutations, and ensures that these operations are executed within a
 * traceable context and recorded for analysis, debugging, or monitoring purposes.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@Slf4j
public class TrafficRecordingEngine implements TrafficRecordingReader {
	public static final String LABEL_TRACE_ID = "trace-id";
	public static final String LABEL_CLIENT_ID = "client-id";
	public static final String LABEL_IP_ADDRESS = "ip-address";
	public static final String LABEL_URI = "uri";
	private final AtomicReference<CatalogInfo> catalogInfo;
	private final StorageOptions storageOptions;
	@Getter private final TrafficRecordingOptions trafficOptions;
	private final ExportFileService exportFileService;
	private final Scheduler scheduler;
	private final TracingContext tracingContext;
	/**
	 * The traffic recorder instance that is currently active and capturing traffic data.
	 */
	private final AtomicReference<TrafficRecorder> trafficRecorder = new AtomicReference<>(NoOpTrafficRecorder.INSTANCE);
	/**
	 * The original traffic recorder instance that was active before calling {@link #startRecording(int, SessionSink)}.
	 * This instance is currently suppressed and will be restored when the recording is stopped.
	 */
	private final AtomicReference<TrafficRecorder> suppressedTrafficRecorder = new AtomicReference<>();
	/**
	 * Flag indicating whether traffic recording is currently active.
	 */
	private final AtomicBoolean recordingActive = new AtomicBoolean();

	/**
	 * Attempts to initialize and return a {@link TrafficRecorder} instance. If no implementation of
	 * {@link TrafficRecorder} is available, an exception is thrown. The method configures the traffic
	 * recorder with the provided parameters.
	 *
	 * @param catalogName       the name of the catalog to associate with the traffic recorder, must not be null
	 * @param exportFileService the service responsible for handling file exports during traffic recording, must not be null
	 * @param scheduler         the scheduler used for executing scheduled tasks within the traffic recorder, must not be null
	 * @param storageOptions    the storageOptions options to be used by the traffic recorder, must not be null
	 * @param recordingOptions  the traffic recording options to be used by the traffic recorder, must not be null
	 * @return a configured instance of {@link TrafficRecorder}
	 * @throws EvitaInvalidUsageException if no implementation of {@link TrafficRecorder} is available
	 */
	@Nonnull
	private static TrafficRecorder getRichTrafficRecorderIfPossible(
		@Nonnull String catalogName,
		@Nonnull ExportFileService exportFileService,
		@Nonnull Scheduler scheduler,
		@Nonnull StorageOptions storageOptions,
		@Nonnull TrafficRecordingOptions recordingOptions
	) {
		final TrafficRecorder trafficRecorderInstance = ServiceLoader.load(TrafficRecorder.class)
			.stream()
			.findFirst()
			.orElseThrow(() -> new EvitaInvalidUsageException("Traffic recorder implementation is not available!"))
			.get();
		trafficRecorderInstance.init(
			catalogName, exportFileService, scheduler,
			storageOptions,
			recordingOptions
		);
		return trafficRecorderInstance;
	}

	public TrafficRecordingEngine(
		@Nonnull String catalogName,
		@Nonnull CatalogState catalogState,
		@Nonnull TracingContext tracingContext,
		@Nonnull EvitaConfiguration configuration,
		@Nonnull ExportFileService exportFileService,
		@Nonnull Scheduler scheduler
	) {
		final CatalogInfo catalogInfo = new CatalogInfo(catalogName, catalogState);
		this.catalogInfo = new AtomicReference<>(catalogInfo);
		this.storageOptions = configuration.storage();
		this.trafficOptions = configuration.server().trafficRecording();
		this.exportFileService = exportFileService;
		this.scheduler = scheduler;
		this.tracingContext = tracingContext;
		initializeTrafficRecorder(catalogInfo);
	}

	/**
	 * Updates the catalog name and initializes the traffic recorder with the new catalog name
	 * if the provided name differs from the current one.
	 *
	 * @param catalogName the new name of the catalog. This value must not be null.
	 */
	public void updateCatalogName(@Nonnull String catalogName, @Nonnull CatalogState state) {
		this.catalogInfo.getAndUpdate(previous -> {
			final CatalogInfo newCatalogInfo = new CatalogInfo(catalogName, state);
			if (!Objects.equals(previous, newCatalogInfo)) {
				initializeTrafficRecorder(newCatalogInfo);
			}
			return newCatalogInfo;
		});
	}

	/**
	 * Starts recording traffic data with the specified sampling rate and session sink.
	 * This method initializes and configures a traffic recorder and ensures that only one instance
	 * of the recording task is active at any given time.
	 *
	 * @param samplingRate the percentage of traffic to be sampled, represented as an integer.
	 * @param sessionSink  the session sink instance where recorded traffic data will be sent, must not be null.
	 * @throws SingletonTaskAlreadyRunningException if a recording task is already active.
	 */
	public void startRecording(int samplingRate, @Nullable SessionSink sessionSink) {
		if (this.recordingActive.compareAndSet(false, true)) {
			final TrafficRecorder defaultTrafficRecorder = this.trafficRecorder.get();
			if (defaultTrafficRecorder instanceof NoOpTrafficRecorder) {
				final TrafficRecorder richTrafficRecorderInstance = getRichTrafficRecorderIfPossible(
					this.catalogInfo.get().catalogName(),
					this.exportFileService, this.scheduler, this.storageOptions, this.trafficOptions
				);
				this.suppressedTrafficRecorder.set(defaultTrafficRecorder);
				this.trafficRecorder.set(richTrafficRecorderInstance);
			}
			final TrafficRecorder theFinalRecorder = this.trafficRecorder.get();
			theFinalRecorder.setSamplingPercentage(samplingRate);
			theFinalRecorder.setSessionSink(sessionSink);
		} else {
			throw new SingletonTaskAlreadyRunningException("Traffic recording is already active!");
		}
	}

	/**
	 * Stops the active traffic recording session if it is currently active. Upon execution:
	 * - Safely closes the current active {@link TrafficRecorder} if it exists and ensures any
	 * {@link UnexpectedIOException} during the closure process is properly handled.
	 * - Replaces the current traffic recorder with a previously suppressed recorder, if applicable.
	 * - Resets the suppressed traffic recorder to null state.
	 * - Updates the final traffic recorder instance with the sampling percentage and resets the session sink.
	 *
	 * The method ensures that the recording process transitions gracefully and handles all necessary
	 * cleanup, including resource management and state updates.
	 */
	public void stopRecording() {
		if (this.recordingActive.compareAndSet(true, false)) {
			try {
				if (this.suppressedTrafficRecorder.get() != null) {
					final TrafficRecorder currentRecorder = this.trafficRecorder.get();
					this.trafficRecorder.set(this.suppressedTrafficRecorder.get());
					this.suppressedTrafficRecorder.set(null);
					IOUtils.close(
						() -> new UnexpectedIOException("Failed to close the traffic recorder properly!"),
						currentRecorder::close
					);
				}
			} finally {
				final TrafficRecorder theFinalRecorder = this.trafficRecorder.get();
				theFinalRecorder.setSamplingPercentage(this.trafficOptions.trafficSamplingPercentage());
				theFinalRecorder.setSessionSink(null);
			}
		}
	}

	/**
	 * Function is called when a new session is created.
	 *
	 * @param sessionId      unique identifier of the session
	 * @param catalogVersion snapshot version of the catalog this session is working with
	 * @param created        timestamp when the session was created
	 */
	public void createSession(
		@Nonnull UUID sessionId,
		long catalogVersion,
		@Nonnull OffsetDateTime created
	) {
		try {
			this.trafficRecorder.get().createSession(sessionId, catalogVersion, created);
		} catch (Exception ex) {
			log.error("Failed to create a new session in traffic recorder!", ex);
		}
	}

	/**
	 * Function is called when a session is closed.
	 *
	 * @param sessionId unique identifier of the session
	 */
	public void closeSession(
		@Nonnull UUID sessionId,
		@Nullable String finishedWithError
	) {
		try {
			this.trafficRecorder.get().closeSession(sessionId, finishedWithError);
		} catch (Exception ex) {
			log.error("Failed to close the session in traffic recorder!", ex);
		}
	}

	/**
	 * Records and executes a query within a given session and operation context,
	 * tracing its span attributes and recording the traffic details upon execution.
	 *
	 * @param <S>       the type of the serializable content contained in the response
	 * @param <T>       the type of the EvitaResponse which contains the response data
	 * @param operation the operation name associated with the query
	 * @param sessionId the unique identifier of the session in which the query is executed
	 * @param queryPlan the plan that describes the query to be executed
	 * @return the response from executing the provided query plan
	 */
	@Nonnull
	public <S extends Serializable, T extends EvitaResponse<S>> T recordQuery(
		@Nonnull String operation,
		@Nonnull UUID sessionId,
		@Nonnull QueryPlan queryPlan
	) {
		final String queryDescription = operation + " - " + queryPlan.getDescription();
		T result = null;
		String finishedWithError = null;
		try {
			result = this.tracingContext.executeWithinBlockIfParentContextAvailable(
				queryDescription,
				(Supplier<T>) queryPlan::execute,
				queryPlan::getSpanAttributes
			);
			return result;
		} catch (RuntimeException ex) {
			finishedWithError = ex.getClass().getName() + ": " + (ex.getMessage() == null ? "no message" : ex.getMessage());
			throw ex;
		} finally {
			try {
				this.trafficRecorder.get().recordQuery(
					sessionId,
					queryDescription,
					queryPlan.getSourceQuery(),
					ArrayUtils.mergeArrays(
						queryPlan.getEvitaRequest().getLabels(),
						collectSystemLabels()
					),
					queryPlan.getEvitaRequest().getAlignedNow(),
					result == null ? 0 : result.getTotalRecordCount(),
					result == null ? 0 : result.getIoFetchCount(),
					result == null ? 0 : result.getIoFetchedSizeBytes(),
					result == null ? ArrayUtils.EMPTY_INT_ARRAY : result.getPrimaryKeys(),
					finishedWithError
				);
			} catch (Exception ex) {
				log.error("Failed to execute query in traffic recorder!", ex);
			}
		}
	}

	/**
	 * Executes a fetch operation within a traceable context and records the traffic details upon execution.
	 *
	 * @param <T>          the type of the entity being fetched, which extends EntityContract
	 * @param sessionId    the unique identifier of the session in which the fetch operation is executed
	 * @param evitaRequest the request object containing details of the entity to fetch
	 * @param lambda       a supplier function that executes the fetch operation and returns an Optional containing the result
	 * @return an Optional containing the fetched entity, or an empty Optional if no entity is found
	 */
	@Nonnull
	public <T extends EntityContract> Optional<T> recordFetch(
		@Nonnull UUID sessionId,
		@Nonnull EvitaRequest evitaRequest,
		@Nonnull Supplier<Optional<T>> lambda
	) {
		Optional<T> result = Optional.empty();
		String finishedWithError = null;
		try {
			result = this.tracingContext.executeWithinBlockIfParentContextAvailable(
				"enrich - " + evitaRequest.getEntityType() + " (pk: " + Arrays.toString(evitaRequest.getPrimaryKeys()) + ")",
				lambda,
				() -> SpanAttribute.EMPTY_ARRAY
			);
			return result;
		} catch (RuntimeException ex) {
			finishedWithError = ex.getClass().getName() + ": " + (ex.getMessage() == null ? "no message" : ex.getMessage());
			throw ex;
		} finally {
			recordFetch(
				sessionId,
				evitaRequest,
				result.orElse(null),
				finishedWithError
			);
		}
	}

	/**
	 * Records and executes an enrichment operation within the given session and operation context.
	 * It traces the operation's span attributes and records traffic details upon execution.
	 *
	 * @param <T>          the return type expected from the lambda execution
	 * @param sessionId    the unique identifier of the session in which the enrichment operation is executed
	 * @param entity       the entity that is being enriched, which provides access to its primary key
	 * @param evitaRequest the request object containing details of the enrichment operation
	 * @param lambda       a supplier function that executes the enrichment operation and returns the result
	 * @return the result obtained from executing the provided lambda function within a traceable context
	 */
	@Nonnull
	public <T> T recordEnrichment(
		@Nonnull UUID sessionId,
		@Nonnull EntityContract entity,
		@Nonnull EvitaRequest evitaRequest,
		@Nonnull Supplier<T> lambda
	) {
		T result;
		String finishedWithError = null;
		try {
			result = this.tracingContext.executeWithinBlockIfParentContextAvailable(
				"enrich - " + evitaRequest.getEntityType() + " (pk: " + entity.getPrimaryKeyOrThrowException() + ")",
				lambda,
				() -> SpanAttribute.EMPTY_ARRAY
			);
			return result;
		} catch (RuntimeException ex) {
			finishedWithError = ex.getClass().getName() + ": " + (ex.getMessage() == null ? "no message" : ex.getMessage());
			throw ex;
		} finally {
			try {
				final int ioFetchCount;
				final int ioFetchedBytes;
				if (entity instanceof EntityFetchAwareDecorator efad) {
					ioFetchCount = efad.getIoFetchCount();
					ioFetchedBytes = efad.getIoFetchedBytes();
				} else {
					ioFetchCount = -1;
					ioFetchedBytes = -1;
				}
				this.trafficRecorder.get().recordEnrichment(
					sessionId,
					evitaRequest.getQuery(),
					evitaRequest.getAlignedNow(),
					ioFetchCount,
					ioFetchedBytes,
					entity.getPrimaryKeyOrThrowException(),
					finishedWithError
				);
			} catch (Exception ex) {
				log.error("Failed to record enrichment in traffic recorder!", ex);
			}
		}
	}

	/**
	 * Records a mutation operation in the system, associating it with a specific session.
	 * This method activates a trace block to monitor the mutation's effect and logs
	 * the details for further analysis or debugging purposes.
	 *
	 * Beware! Returned object must be finished in order to record the mutation!
	 *
	 * @param sessionId the unique identifier of the session with which this mutation is associated
	 * @param mutation  the mutation operation to be recorded and executed, must be non-null
	 * @return a MutationApplicationRecord object that tracks the lifecycle of the mutation, including its execution tracing and logging
	 */
	@Nonnull
	public MutationApplicationRecord recordMutation(
		@Nonnull UUID sessionId,
		@Nonnull OffsetDateTime now,
		@Nonnull Mutation mutation
	) {
		return new MutationApplicationRecord(
			this.tracingContext.createAndActivateBlock(
				"mutation - " + mutation,
				SpanAttribute.EMPTY_ARRAY
			),
			this.trafficRecorder.get(),
			sessionId,
			now,
			mutation
		);
	}

	/**
	 * Method registers RAW input query and assigns a unique identifier to it. All queries in this session that are
	 * labeled with {@link Label#LABEL_SOURCE_QUERY} will be registered as sub-queries of this source query.
	 *
	 * @param sessionId         unique identifier of the session the mutation belongs to
	 * @param sourceQueryId     unique identifier of the source query
	 * @param sourceQuery       unparsed, raw source query in particular format
	 * @param queryType         type of the query (e.g. REST, GraphQL, etc.)
	 * @param finishedWithError error message if the query finished with an error or NULL if it finished successfully
	 */
	public void setupSourceQuery(
		@Nonnull UUID sessionId,
		@Nonnull UUID sourceQueryId,
		@Nonnull String sourceQuery,
		@Nonnull String queryType,
		@Nullable String finishedWithError
	) {
		try {
			this.trafficRecorder.get().setupSourceQuery(
				sessionId,
				sourceQueryId,
				OffsetDateTime.now(),
				sourceQuery,
				ArrayUtils.mergeArrays(
					new Label[]{
						new Label(Label.LABEL_SOURCE_TYPE, queryType),
						new Label(Label.LABEL_SOURCE_QUERY, sourceQueryId)
					},
					collectSystemLabels()
				),
				finishedWithError
			);
		} catch (Exception e) {
			log.error("Failed to setup source query in traffic recorder!", e);
		}
	}

	/**
	 * Method closes the source query and marks it as finalized. Overall statistics for all registered sub-queries
	 * will be aggregated and stored in the traffic recording along with this record.
	 *
	 * @param sessionId         unique identifier of the session the mutation belongs to
	 * @param sourceQueryId     unique identifier of the source query
	 * @param finishedWithError error message if the query finished with an error or NULL if it finished successfully
	 */
	public void closeSourceQuery(
		@Nonnull UUID sessionId,
		@Nonnull UUID sourceQueryId,
		@Nullable String finishedWithError
	) {
		try {
			this.trafficRecorder.get().closeSourceQuery(
				sessionId,
				sourceQueryId,
				finishedWithError
			);
		} catch (Exception e) {
			log.error("Failed to close source query in traffic recorder!", e);
		}
	}

	@Nonnull
	@Override
	public Stream<TrafficRecording> getRecordings(@Nonnull TrafficRecordingCaptureRequest request) throws TemporalDataNotAvailableException {
		if (this.trafficRecorder.get() instanceof TrafficRecordingReader trr) {
			return trr.getRecordings(request);
		} else {
			throw new EvitaInvalidUsageException(
				"Traffic recording is disabled in configuration settings and no on-demand traffic recording has been started!"
			);
		}
	}

	@Nonnull
	@Override
	public Stream<TrafficRecording> getRecordingsReversed(@Nonnull TrafficRecordingCaptureRequest request) throws TemporalDataNotAvailableException, IndexNotReady {
		if (this.trafficRecorder.get() instanceof TrafficRecordingReader trr) {
			return trr.getRecordingsReversed(request);
		} else {
			throw new EvitaInvalidUsageException(
				"Traffic recording is disabled in configuration settings and no on-demand traffic recording has been started!"
			);
		}
	}

	/**
	 * Returns a stream of all unique labels names ordered by cardinality of their values present in the traffic recording.
	 *
	 * @param nameStartingWith optional prefix to filter the labels by
	 * @return stream of unique label names ordered by cardinality of their values
	 */
	@Nonnull
	public Collection<String> getLabelsNamesOrderedByCardinality(@Nullable String nameStartingWith, int limit) {
		return this.trafficRecorder.get() instanceof LabelIntrospector li ?
			li.getLabelsNamesOrderedByCardinality(nameStartingWith, limit) :
			List.of();
	}

	/**
	 * Returns a stream of all unique label values ordered by cardinality present in the traffic recording.
	 *
	 * @param labelName         name of the label to get values for
	 * @param valueStartingWith optional prefix to filter the labels by
	 * @return stream of unique label values ordered by cardinality
	 */
	@Nonnull
	public Collection<String> getLabelValuesOrderedByCardinality(@Nonnull String labelName, @Nullable String valueStartingWith, int limit) {
		return this.trafficRecorder.get() instanceof LabelIntrospector li ?
			li.getLabelValuesOrderedByCardinality(labelName, valueStartingWith, limit) :
			List.of();
	}

	/**
	 * Initializes the traffic recorder for the specified catalog. The traffic recorder can be enabled or disabled
	 * based on the configuration provided. When enabled, a specific traffic recorder instance is initialized;
	 * otherwise, a no-operation (NoOp) traffic recorder is set.
	 *
	 * @param catalogInfo The name and state of the catalog for which the traffic recorder should be initialized.
	 *                    This parameter must not be null.
	 */
	private void initializeTrafficRecorder(@Nonnull CatalogInfo catalogInfo) {
		final TrafficRecorder existingTrafficRecorder = this.trafficRecorder.get();
		if (existingTrafficRecorder != null) {
			IOUtils.closeQuietly(existingTrafficRecorder::close);
		}
		if (this.trafficOptions.enabled() && catalogInfo.state() == CatalogState.ALIVE) {
			final TrafficRecorder trafficRecorderInstance = getRichTrafficRecorderIfPossible(
				catalogInfo.catalogName(),
				this.exportFileService, this.scheduler, this.storageOptions, this.trafficOptions
			);
			this.trafficRecorder.set(trafficRecorderInstance);
		} else {
			this.trafficRecorder.set(NoOpTrafficRecorder.INSTANCE);
		}
	}

	/**
	 * Collects system labels associated with the current tracing context. These labels encapsulate
	 * metadata, such as trace ID, client ID, and client IP address, if available. If a specific
	 * value is unavailable in the tracing context, the corresponding label is omitted.
	 *
	 * @return an array of {@link Label} objects containing system metadata; the array will not
	 * contain null elements and will always be non-null, though it may be empty.
	 */
	@Nonnull
	private Label[] collectSystemLabels() {
		return Stream.concat(
				Stream.of(
					this.tracingContext.getTraceId()
						.map(it -> new Label(LABEL_TRACE_ID, it))
						.orElse(null),
					this.tracingContext.getClientId()
						.map(it -> new Label(LABEL_CLIENT_ID, it))
						.orElse(null),
					this.tracingContext.getClientIpAddress()
						.map(it -> new Label(LABEL_IP_ADDRESS, it))
						.orElse(null),
					this.tracingContext.getClientUri()
						.map(it -> new Label(LABEL_URI, it))
						.orElse(null)
				),
				Arrays.stream(this.tracingContext.getClientLabels())
			)
			.filter(Objects::nonNull)
			.toArray(Label[]::new);
	}

	/**
	 * Records the details of a fetch operation including IO fetch count and bytes fetched,
	 * and logs them in association with a session and a specific request.
	 *
	 * @param <T>          the type of entity being fetched, which extends EntityContract
	 * @param sessionId    the unique identifier of the session in which the fetch is conducted
	 * @param evitaRequest the request object containing the details of the fetch
	 * @param entity       the entity that has been fetched, used to extract metrics and primary key information
	 */
	private <T extends EntityContract> void recordFetch(
		@Nonnull UUID sessionId,
		@Nonnull EvitaRequest evitaRequest,
		@Nullable T entity,
		@Nullable String finishedWithError
	) {
		try {
			final int ioFetchCount;
			final int ioFetchedBytes;
			if (entity instanceof EntityFetchAwareDecorator efad) {
				ioFetchCount = efad.getIoFetchCount();
				ioFetchedBytes = efad.getIoFetchedBytes();
			} else {
				ioFetchCount = -1;
				ioFetchedBytes = -1;
			}
			this.trafficRecorder.get().recordFetch(
				sessionId,
				evitaRequest.getQuery(),
				evitaRequest.getAlignedNow(),
				ioFetchCount,
				ioFetchedBytes,
				entity == null || entity.getPrimaryKey() == null ? -1 : entity.getPrimaryKey(),
				finishedWithError
			);
		} catch (Exception ex) {
			log.error("Failed to record fetch in traffic recorder!", ex);
		}
	}

	/**
	 * The MutationApplicationRecord class is responsible for managing the lifecycle of a mutation application,
	 * utilizing tracing and traffic recording mechanisms. It ensures that a mutation is traced and its execution
	 * is duly recorded for further analysis or debugging purposes.
	 */
	@Slf4j
	@RequiredArgsConstructor
	public static class MutationApplicationRecord {
		private final TracingBlockReference ref;
		private final TrafficRecorder trafficRecorder;
		private final UUID sessionId;
		private final OffsetDateTime now;
		private final Mutation mutation;

		/**
		 * Completes the mutation application lifecycle by handling an exception, closing
		 * the tracing block, and recording the mutation execution for tracking and analysis.
		 */
		public void finish() {
			this.ref.close();
			try {
				this.trafficRecorder.recordMutation(this.sessionId, this.now, this.mutation, null);
			} catch (Exception ex) {
				log.error("Failed to record mutation in traffic recorder!", ex);
			}
		}

		/**
		 * Completes the mutation application lifecycle by handling an exception, closing
		 * the tracing block, and recording the mutation execution for tracking and analysis.
		 *
		 * @param ex the exception to be handled and recorded with the tracing block; must not be null
		 */
		public void finishWithException(@Nonnull Exception ex) {
			this.ref.setError(ex);
			this.ref.close();
			try {
				this.trafficRecorder.recordMutation(
					this.sessionId, this.now, this.mutation,
					ex.getClass().getName() + ": " + (ex.getMessage() == null ? "no message" : ex.getMessage())
				);
			} catch (Exception e) {
				log.error("Failed to record mutation in traffic recorder!", e);
			}
		}

	}

	/**
	 * Represents a catalog name and its state.
	 *
	 * @param catalogName the name of the catalog
	 * @param state       the state of the catalog
	 */
	private record CatalogInfo(
		@Nonnull String catalogName,
		@Nonnull CatalogState state
	) {
	}

}
