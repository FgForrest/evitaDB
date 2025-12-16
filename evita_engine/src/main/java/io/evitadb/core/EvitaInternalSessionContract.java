/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

package io.evitadb.core;

import io.evitadb.api.CatalogContract;
import io.evitadb.api.CommitProgressRecord;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.TrafficRecordingReader;
import io.evitadb.api.TransactionContract.CommitBehavior;
import io.evitadb.api.exception.IndexNotReady;
import io.evitadb.api.exception.InstanceTerminatedException;
import io.evitadb.api.exception.SingletonTaskAlreadyRunningException;
import io.evitadb.api.exception.TransactionException;
import io.evitadb.api.exception.UnexpectedResultCountException;
import io.evitadb.api.exception.UnexpectedResultException;
import io.evitadb.api.file.FileForFetch;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.head.Label;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.system.WriteAheadLogVersionDescriptor;
import io.evitadb.api.task.ServerTask;
import io.evitadb.api.task.TaskStatus;
import io.evitadb.core.traffic.TrafficRecordingSettings;
import io.evitadb.exception.EvitaInvalidUsageException;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * This interface extends the public interface and adds a few methods that are targeted for internal use of EvitaDB
 * API only.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public interface EvitaInternalSessionContract extends EvitaSessionContract, TrafficRecordingReader {

	/**
	 * Returns date and time this session was created.
	 *
	 * @return date and time this session was created
	 */
	@Nonnull
	OffsetDateTime getCreated();

	/**
	 * Method executes query on {@link CatalogContract} data and returns zero or exactly one entity result. Method
	 * behaves exactly the same as {@link #query(Query, Class)} but verifies the count of returned results and
	 * translates it to simplified return type.
	 *
	 * Because result is generic and may contain different data as its contents (based on input query), additional
	 * parameter `expectedType` is passed. This parameter allows to check whether passed response contains the expected
	 * type of data before returning it back to the client. This should prevent late ClassCastExceptions on the client
	 * side.
	 *
	 * @param evitaRequest externally created evita request
	 * @throws UnexpectedResultException      when result object is not assignable to `expectedType`
	 * @throws UnexpectedResultCountException when query matched more than single record
	 * @throws InstanceTerminatedException    when session has been already terminated
	 */
	@Nonnull
	<S extends Serializable> Optional<S> queryOne(@Nonnull EvitaRequest evitaRequest)
		throws UnexpectedResultException, UnexpectedResultCountException, InstanceTerminatedException;

	/**
	 * Method executes query on {@link CatalogContract} data and returns simplified list of results. Method behaves
	 * exactly the same as {@link #query(Query, Class)} but verifies the count of returned results and translates
	 * it to simplified return type. This method will throw out all possible extra results from, because there is
	 * no way how to propagate them in return value. If you require extra results or paginated list use
	 * the {@link #query(Query, Class)} method.
	 *
	 * Because result is generic and may contain different data as its contents (based on input query), additional
	 * parameter `expectedType` is passed. This parameter allows to check whether passed response contains the expected
	 * type of data before returning it back to the client. This should prevent late ClassCastExceptions on the client
	 * side.
	 *
	 * @param evitaRequest externally created evita request
	 * @throws UnexpectedResultException   when result object is not assignable to `expectedType`
	 * @throws InstanceTerminatedException when session has been already terminated
	 */
	@Nonnull
	<S extends Serializable> List<S> queryList(@Nonnull EvitaRequest evitaRequest)
		throws UnexpectedResultException, InstanceTerminatedException;

	/**
	 * Method executes query on {@link CatalogContract} data and returns result. Because result is generic and may contain
	 * different data as its contents (based on input query), additional parameter `expectedType` is passed. This parameter
	 * allows to check whether passed response contains the expected type of data before returning it back to the client.
	 * This should prevent late ClassCastExceptions on the client side.
	 *
	 * @param evitaRequest externally created evita request
	 * @throws UnexpectedResultException   when {@link EvitaResponse#getRecordPage()} contains data that are not assignable to `expectedType`
	 * @throws InstanceTerminatedException when session has been already terminated
	 */
	@Nonnull
	<S extends Serializable, T extends EvitaResponse<S>> T query(@Nonnull EvitaRequest evitaRequest)
		throws UnexpectedResultException, InstanceTerminatedException;

	/**
	 * If {@link CatalogContract} supports transactions (see {@link CatalogContract#supportsTransaction()}) method
	 * executes application `logic` in current session and commits the transaction at the end. Transaction is
	 * automatically roll-backed when exception is thrown from the `logic` scope. Changes made by the updating logic are
	 * visible only within update function. Other threads outside the logic function work with non-changed data until
	 * transaction is committed to the index.
	 *
	 * When catalog doesn't support transactions application `logic` is immediately applied to the index data and logic
	 * operates in a <a href="https://en.wikipedia.org/wiki/Isolation_(database_systems)#Read_uncommitted">read
	 * uncommitted</a> mode. Application `logic` can only append new entities in non-transactional mode.
	 *
	 * @throws TransactionException when lambda function throws exception causing the transaction to be rolled back
	 */
	@Nullable
	<T> T execute(@Nonnull Function<EvitaSessionContract, T> logic) throws TransactionException;

	/**
	 * If {@link CatalogContract} supports transactions (see {@link CatalogContract#supportsTransaction()}) method
	 * executes application `logic` in current session and commits the transaction at the end. Transaction is
	 * automatically roll-backed when exception is thrown from the `logic` scope. Changes made by the updating logic are
	 * visible only within update function. Other threads outside the logic function work with non-changed data until
	 * transaction is committed to the index.
	 *
	 * When catalog doesn't support transactions application `logic` is immediately applied to the index data and logic
	 * operates in a <a href="https://en.wikipedia.org/wiki/Isolation_(database_systems)#Read_uncommitted">read
	 * uncommitted</a> mode. Application `logic` can only append new entities in non-transactional mode.
	 *
	 * @throws TransactionException when lambda function throws exception causing the transaction to be rolled back
	 */
	void execute(@Nonnull Consumer<EvitaSessionContract> logic) throws TransactionException;

	/**
	 * Returns true if there is active method invocation in place. When method is running, it is not possible to
	 * kill session due to inactivity.
	 *
	 * @return true if there is active method invocation in place
	 */
	boolean methodIsRunning();

	/**
	 * Invokes lambda once no method on the current object is running or immediately if there is no method running.
	 * This method allows to execute code that needs to be executed when the session is not busy with any method.
	 * When lambda is invoked, it is guaranteed that no other method starts running on the current object.
	 *
	 * @param lambda the lambda to be executed when no method is running
	 */
	void executeWhenMethodIsNotRunning(@Nonnull Runnable lambda);

	/**
	 * Retrieves a CompletableFuture that represents the finalization status of a session. If the catalog is in
	 * transactional mode, the future will respect the requested {@link CommitBehavior} bound to the current transaction.
	 *
	 * @return completable future returning new catalog version introduced by this session
	 */
	@Nonnull
	CommitProgressRecord getCommitProgress();

	/**
	 * Returns a stream of {@link WriteAheadLogVersionDescriptor} instances for the given catalog versions. Descriptors will
	 * be ordered the same way as the input catalog versions, but may be missing some versions if they are not known in
	 * history. Creating a descriptor could be an expensive operation, so it's recommended to stream changes to clients
	 * gradually as the stream provides the data.
	 *
	 * @param catalogVersion the catalog versions to get descriptors for
	 * @return a list of {@link WriteAheadLogVersionDescriptor} instances
	 */
	@Nonnull
	List<WriteAheadLogVersionDescriptor> getCatalogVersionDescriptors(long... catalogVersion);

	/**
	 * Method registers RAW input query and assigns a unique identifier to it. All queries in this session that are
	 * labeled with {@link Label#LABEL_SOURCE_QUERY} will be registered as sub-queries of this source query.
	 *
	 * @param sourceQuery unparsed, raw source query in particular format
	 * @param queryType   type of the query (e.g. GraphQL, REST, etc.)
	 * @param finishedWithError error message if the source was not parsed properly
	 * @return unique identifier of the source query
	 */
	@Nonnull
	UUID recordSourceQuery(@Nonnull String sourceQuery, @Nonnull String queryType, @Nullable String finishedWithError);

	/**
	 * Method closes the source query and marks it as finalized. Overall statistics for all registered sub-queries
	 * will be aggregated and stored in the traffic recording along with this record.
	 *
	 * @param sourceQueryId unique identifier of the source query
	 * @param finishedWithError error message if the source query was not finished properly
	 */
	void finalizeSourceQuery(@Nonnull UUID sourceQueryId, @Nullable String finishedWithError);

	/**
	 * Returns a stream of all unique labels names ordered by cardinality of their values present in the traffic recording.
	 *
	 * @param nameStartingWith optional prefix to filter the labels by
	 * @param limit            maximum number of labels to return
	 * @return collection of unique label names ordered by cardinality of their values
	 */
	@Nonnull
	Collection<String> getLabelsNamesOrderedByCardinality(@Nullable String nameStartingWith, int limit) throws IndexNotReady;

	/**
	 * Returns a stream of all unique label values ordered by cardinality present in the traffic recording.
	 *
	 * @param labelName         name of the label to get values for
	 * @param valueStartingWith optional prefix to filter the labels by
	 * @return collection of unique label values ordered by cardinality
	 */
	@Nonnull
	Collection<String> getLabelValuesOrderedByCardinality(@Nonnull String labelName, @Nullable String valueStartingWith, int limit) throws IndexNotReady;

	/**
	 * Initiates a recording session for traffic data with the specified parameters.
	 *
	 * @param samplingRate              Defines the rate at which traffic samples are recorded. The value
	 *                                  is provided as a percentage (e.g., 1 to 100), where 100 represents
	 *                                  all traffic being recorded and lower values represent partial sampling.
	 * @param exportFile                Specifies whether recorded traffic data should be exported to a file.
	 * @param recordingDuration         Specifies the duration for which traffic recording should occur.
	 *                                  Can be null, indicating that recording is not time-bound. When
	 *                                  provided, it ensures that traffic recording will not exceed
	 *                                  the defined duration.
	 * @param recordingSizeLimitInBytes Specifies the maximum size of the traffic recording in bytes.
	 *                                  This parameter is optional and can be null. When provided,
	 *                                  it serves as an upper limit for traffic recording size, ensuring
	 *                                  that recorded data does not exceed the specified size.
	 * @param chunkFileSizeInBytes      Defines the size of each chunk file used to store recorded traffic
	 *                                  data. Recorded data is divided into files of this size, aiding in
	 *                                  data management and processing efficiency.
	 * @return A {@code ServerTask} instance containing the configuration of the recording session and an object to fetch the recorded file.
	 * @throws SingletonTaskAlreadyRunningException if the task is already running
	 */
	@Nonnull
	ServerTask<TrafficRecordingSettings, FileForFetch> startRecording(
		int samplingRate,
		boolean exportFile,
		@Nullable Duration recordingDuration,
		@Nullable Long recordingSizeLimitInBytes,
		long chunkFileSizeInBytes
	) throws SingletonTaskAlreadyRunningException;

	/**
	 * Stops the ongoing recording task identified by the provided task ID.
	 *
	 * @param taskId The unique identifier of the recording task to be stopped.
	 *               Must not be null.
	 * @return A TaskStatus object containing the final state of the recording task,
	 * along with details of the traffic recording settings and a file reference
	 * for fetching the recorded data.
	 * @throws EvitaInvalidUsageException if the task of particular id is not found
	 */
	@Nonnull
	TaskStatus<TrafficRecordingSettings, FileForFetch> stopRecording(@Nonnull UUID taskId) throws EvitaInvalidUsageException;

}
