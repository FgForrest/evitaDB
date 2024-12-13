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

package io.evitadb.core.traffic;


import io.evitadb.api.observability.trace.TracingBlockReference;
import io.evitadb.api.observability.trace.TracingContext;
import io.evitadb.api.observability.trace.TracingContext.SpanAttribute;
import io.evitadb.api.query.head.Label;
import io.evitadb.api.requestResponse.EntityFetchAwareDecorator;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.mutation.Mutation;
import io.evitadb.core.query.QueryPlan;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

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
@RequiredArgsConstructor
public class TrafficRecordingEngine {
	private final TracingContext tracingContext;
	private final TrafficRecorder trafficRecorder;

	/**
	 * Function is called when a new session is created.
	 *
	 * @param sessionId      unique identifier of the session
	 * @param catalogVersion snapshot version of the catalog this session is working with
	 * @param created        timestamp when the session was created
	 */
	public void createSession(@Nonnull UUID sessionId, long catalogVersion, @Nonnull OffsetDateTime created) {
		this.trafficRecorder.createSession(sessionId, catalogVersion, created);
	}

	/**
	 * Function is called when a session is closed.
	 *
	 * @param sessionId unique identifier of the session
	 */
	public void closeSession(@Nonnull UUID sessionId) {
		this.trafficRecorder.closeSession(sessionId);
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
		final T result = this.tracingContext.executeWithinBlockIfParentContextAvailable(
			operation + " - " + queryPlan.getDescription(),
			(Supplier<T>) queryPlan::execute,
			queryPlan::getSpanAttributes
		);
		this.trafficRecorder.recordQuery(
			sessionId,
			result.getSourceQuery(),
			queryPlan.getEvitaRequest().getLabels(),
			queryPlan.getEvitaRequest().getAlignedNow(),
			result.getTotalRecordCount(),
			result.getIoFetchCount(),
			result.getIoFetchedSizeBytes(),
			result.getPrimaryKeys()
		);
		return result;
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
		return this.tracingContext.executeWithinBlockIfParentContextAvailable(
				"enrich - " + evitaRequest.getEntityType() + " (pk: " + Arrays.toString(evitaRequest.getPrimaryKeys()) + ")",
				lambda,
				() -> SpanAttribute.EMPTY_ARRAY
			)
			.map(entity -> {
				recordFetch(sessionId, evitaRequest, entity);
				return entity;
			});
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
		final T result = this.tracingContext.executeWithinBlockIfParentContextAvailable(
			"enrich - " + evitaRequest.getEntityType() + " (pk: " + entity.getPrimaryKeyOrThrowException() + ")",
			lambda,
			() -> SpanAttribute.EMPTY_ARRAY
		);
		final int ioFetchCount;
		final int ioFetchedBytes;
		if (entity instanceof EntityFetchAwareDecorator efad) {
			ioFetchCount = efad.getIoFetchCount();
			ioFetchedBytes = efad.getIoFetchedBytes();
		} else {
			ioFetchCount = -1;
			ioFetchedBytes = -1;
		}
		this.trafficRecorder.recordEnrichment(
			sessionId,
			evitaRequest.getQuery(),
			evitaRequest.getAlignedNow(),
			ioFetchCount,
			ioFetchedBytes,
			entity.getPrimaryKeyOrThrowException()
		);
		return result;
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
	public MutationApplicationRecord recordMutation(@Nonnull UUID sessionId, @Nonnull OffsetDateTime now, @Nonnull Mutation mutation) {
		return new MutationApplicationRecord(
			this.tracingContext.createAndActivateBlock(
				"mutation - " + mutation,
				SpanAttribute.EMPTY_ARRAY
			),
			this.trafficRecorder,
			sessionId,
			now,
			mutation
		);
	}

	/**
	 * Method registers RAW input query and assigns a unique identifier to it. All queries in this session that are
	 * labeled with {@link Label#LABEL_SOURCE_QUERY} will be registered as sub-queries of this source query.
	 *
	 * @param sessionId unique identifier of the session the mutation belongs to
	 * @param sourceQueryId unique identifier of the source query
	 * @param sourceQuery   unparsed, raw source query in particular format
	 * @param queryType     type of the query (e.g. GraphQL, REST, etc.)
	 */
	public void setupSourceQuery(
		@Nonnull UUID sessionId,
		@Nonnull UUID sourceQueryId,
		@Nonnull String sourceQuery,
		@Nonnull String queryType
	) {
		this.trafficRecorder.setupSourceQuery(
			sessionId, sourceQueryId, OffsetDateTime.now(), sourceQuery, queryType
		);
	}

	/**
	 * Method closes the source query and marks it as finalized. Overall statistics for all registered sub-queries
	 * will be aggregated and stored in the traffic recording along with this record.
	 *
	 * @param sessionId unique identifier of the session the mutation belongs to
	 * @param sourceQueryId unique identifier of the source query
	 */
	public void closeSourceQuery(
		@Nonnull UUID sessionId,
		@Nonnull UUID sourceQueryId
	) {
		this.trafficRecorder.closeSourceQuery(sessionId, sourceQueryId);
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
	private <T extends EntityContract> void recordFetch(@Nonnull UUID sessionId, @Nonnull EvitaRequest evitaRequest, @Nonnull T entity) {
		final int ioFetchCount;
		final int ioFetchedBytes;
		if (entity instanceof EntityFetchAwareDecorator efad) {
			ioFetchCount = efad.getIoFetchCount();
			ioFetchedBytes = efad.getIoFetchedBytes();
		} else {
			ioFetchCount = -1;
			ioFetchedBytes = -1;
		}
		this.trafficRecorder.recordFetch(
			sessionId,
			evitaRequest.getQuery(),
			evitaRequest.getAlignedNow(),
			ioFetchCount,
			ioFetchedBytes,
			entity.getPrimaryKeyOrThrowException()
		);
	}

	/**
	 * The MutationApplicationRecord class is responsible for managing the lifecycle of a mutation application,
	 * utilizing tracing and traffic recording mechanisms. It ensures that a mutation is traced and its execution
	 * is duly recorded for further analysis or debugging purposes.
	 */
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
			this.trafficRecorder.recordMutation(this.sessionId, this.now, this.mutation);
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
			this.trafficRecorder.recordMutation(this.sessionId, this.now, this.mutation);
		}

	}

}
