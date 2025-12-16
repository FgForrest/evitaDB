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

package io.evitadb.externalApi.grpc.services;


import io.evitadb.api.file.FileForFetch;
import io.evitadb.api.requestResponse.trafficRecording.TrafficRecording;
import io.evitadb.api.requestResponse.trafficRecording.TrafficRecordingCaptureRequest;
import io.evitadb.api.task.ServerTask;
import io.evitadb.api.task.TaskStatus;
import io.evitadb.core.Evita;
import io.evitadb.core.traffic.TrafficRecordingSettings;
import io.evitadb.externalApi.configuration.HeaderOptions;
import io.evitadb.externalApi.grpc.generated.*;
import io.evitadb.externalApi.grpc.services.converter.TrafficCaptureConverter;
import io.evitadb.externalApi.trace.ExternalApiTracingContextProvider;
import io.evitadb.externalApi.utils.ExternalApiTracingContext;
import io.grpc.Metadata;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.BaseStream;
import java.util.stream.Stream;

import static io.evitadb.externalApi.grpc.dataType.EvitaDataTypesConverter.toGrpcTaskStatus;
import static io.evitadb.externalApi.grpc.dataType.EvitaDataTypesConverter.toUuid;
import static io.evitadb.externalApi.grpc.services.EvitaSessionService.executeWithClientContext;
import static java.util.Optional.ofNullable;

/**
 * This service contains methods that could be called by gRPC clients on {@link GrpcEvitaTrafficRecordingAPI}.
 *
 * @author Jan Novotný, FG Forrest a.s. (c) 2024
 */
@Slf4j
@RequiredArgsConstructor
public class EvitaTrafficRecordingService extends GrpcEvitaTrafficRecordingServiceGrpc.GrpcEvitaTrafficRecordingServiceImplBase {
	/**
	 * Instance of Evita upon which will be executed service calls
	 */
	@Nonnull private final Evita evita;
	/**
	 * Tracing context for the gRPC calls.
	 */
	@Nonnull private final ExternalApiTracingContext<Metadata> tracingContext;

	public EvitaTrafficRecordingService(@Nonnull Evita evita, @Nonnull HeaderOptions headerOptions) {
		this.evita = evita;
		this.tracingContext = ExternalApiTracingContextProvider.getContext(headerOptions);
	}

	/**
	 * Method returns list of traffic recording history entries that match given criteria.
	 *
	 * @param request          request containing the criteria
	 * @param responseObserver observer on which errors might be thrown and result returned
	 */
	@Override
	public void getTrafficRecordingHistoryList(GetTrafficHistoryListRequest request, StreamObserver<GetTrafficHistoryListResponse> responseObserver) {
		executeWithClientContext(
			session -> {
				final TrafficRecordingCaptureRequest captureRequest = TrafficCaptureConverter.toTrafficRecordingCaptureRequest(request);
				final GetTrafficHistoryListResponse.Builder builder = GetTrafficHistoryListResponse.newBuilder();
				try (final Stream<TrafficRecording> recordings = session.getRecordings(captureRequest)) {
					recordings
						.limit(request.getLimit())
						.forEach(
							trafficRecording -> builder.addTrafficRecord(
								TrafficCaptureConverter.toGrpcGrpcTrafficRecord(trafficRecording, captureRequest.content())
							)
						);
				}
				responseObserver.onNext(builder.build());
				responseObserver.onCompleted();
			},
			this.evita.getRequestExecutor(),
			responseObserver,
			this.tracingContext
		);
	}

	/**
	 * Method returns list of traffic recording history entries that match given criteria in reversed order.
	 *
	 * @param request          request containing the criteria
	 * @param responseObserver observer on which errors might be thrown and result returned
	 */
	@Override
	public void getTrafficRecordingHistoryListReversed(GetTrafficHistoryListRequest request, StreamObserver<GetTrafficHistoryListResponse> responseObserver) {
		executeWithClientContext(
			session -> {
				final TrafficRecordingCaptureRequest captureRequest = TrafficCaptureConverter.toTrafficRecordingCaptureRequest(request);
				final GetTrafficHistoryListResponse.Builder builder = GetTrafficHistoryListResponse.newBuilder();
				try (final Stream<TrafficRecording> recordings = session.getRecordingsReversed(captureRequest)) {
					recordings
						.limit(request.getLimit())
						.forEach(
							trafficRecording -> builder.addTrafficRecord(
								TrafficCaptureConverter.toGrpcGrpcTrafficRecord(trafficRecording, captureRequest.content())
							)
						);
				}
				responseObserver.onNext(builder.build());
				responseObserver.onCompleted();
			},
			this.evita.getRequestExecutor(),
			responseObserver,
			this.tracingContext
		);
	}

	/**
	 * Method streams traffic recording history entries that match given criteria.
	 *
	 * @param request          request containing the criteria
	 * @param responseObserver observer on which errors might be thrown and result returned
	 */
	@Override
	public void getTrafficRecordingHistory(GetTrafficHistoryRequest request, StreamObserver<GetTrafficHistoryResponse> responseObserver) {
		ServerCallStreamObserver<GetTrafficHistoryResponse> serverCallStreamObserver =
			(ServerCallStreamObserver<GetTrafficHistoryResponse>) responseObserver;

		final AtomicReference<Stream<TrafficRecording>> trafficHistoryStreamRef = new AtomicReference<>();

		// avoid returning error when client cancels the stream
		serverCallStreamObserver.setOnCancelHandler(
			() -> {
				log.info("Client cancelled the traffic history request.");
				ofNullable(trafficHistoryStreamRef.get())
					.ifPresent(BaseStream::close);
			}
		);

		executeWithClientContext(
			session -> {
				final TrafficRecordingCaptureRequest captureRequest = TrafficCaptureConverter.toTrafficRecordingCaptureRequest(request);
				final Stream<TrafficRecording> trafficHistoryStream = session.getRecordings(captureRequest);
				trafficHistoryStreamRef.set(trafficHistoryStream);

				trafficHistoryStream.forEach(
					trafficRecording -> {
						final GetTrafficHistoryResponse.Builder builder = GetTrafficHistoryResponse.newBuilder();
						final GrpcTrafficRecord event = TrafficCaptureConverter.toGrpcGrpcTrafficRecord(trafficRecording, captureRequest.content());
						// we send mutations one by one, but we may want to send them in batches in the future
						builder.addTrafficRecord(event);
						responseObserver.onNext(builder.build());
					}
				);
				responseObserver.onCompleted();
				trafficHistoryStream.close();
			},
			this.evita.getRequestExecutor(),
			responseObserver,
			this.tracingContext
		);
	}

	/**
	 * Method returns top X traffic recording labels ordered by cardinality matching given criteria.
	 *
	 * @param request          request containing the criteria
	 * @param responseObserver observer on which errors might be thrown and result returned
	 */
	@Override
	public void getTrafficRecordingLabelsNamesOrderedByCardinality(GetTrafficRecordingLabelNamesRequest request, StreamObserver<GetTrafficRecordingLabelNamesResponse> responseObserver) {
		executeWithClientContext(
			session -> {
				GetTrafficRecordingLabelNamesResponse.Builder builder = GetTrafficRecordingLabelNamesResponse.newBuilder();
				session.getLabelsNamesOrderedByCardinality(
						request.hasNameStartsWith() ? request.getNameStartsWith().getValue() : null,
						request.getLimit()
					)
					.forEach(builder::addLabelName);
				responseObserver.onNext(builder.build());
				responseObserver.onCompleted();
			},
			this.evita.getRequestExecutor(),
			responseObserver,
			this.tracingContext
		);
	}

	/**
	 * Method returns top X traffic recording label values ordered by cardinality matching given criteria.
	 *
	 * @param request          request containing the criteria
	 * @param responseObserver observer on which errors might be thrown and result returned
	 */
	@Override
	public void getTrafficRecordingLabelValuesOrderedByCardinality(GetTrafficRecordingValuesNamesRequest request, StreamObserver<GetTrafficRecordingValuesNamesResponse> responseObserver) {
		executeWithClientContext(
			session -> {
				GetTrafficRecordingValuesNamesResponse.Builder builder = GetTrafficRecordingValuesNamesResponse.newBuilder();
				session.getLabelValuesOrderedByCardinality(
						request.getLabelName(),
						request.hasValueStartsWith() ? request.getValueStartsWith().getValue() : null,
						request.getLimit()
					)
					.forEach(builder::addLabelValue);
				responseObserver.onNext(builder.build());
				responseObserver.onCompleted();
			},
			this.evita.getRequestExecutor(),
			responseObserver,
			this.tracingContext
		);
	}

	/**
	 * Starts the traffic recording process based on the provided request and sends the recording status response.
	 * There could be only one recording in progress at a time.
	 *
	 * @param request The request object containing the parameters required to start traffic recording.
	 * @param responseObserver The stream observer used to send the recording status response back to the client.
	 */
	@Override
	public void startTrafficRecording(GrpcStartTrafficRecordingRequest request, StreamObserver<GetTrafficRecordingStatusResponse> responseObserver) {
		executeWithClientContext(
			session -> {
				GetTrafficRecordingStatusResponse.Builder builder = GetTrafficRecordingStatusResponse.newBuilder();
				final ServerTask<TrafficRecordingSettings, FileForFetch> task = session.startRecording(
					request.getSamplingRate(),
					request.getExportFile(),
					request.hasMaxDurationInMilliseconds() ?
						Duration.ofMillis(request.getMaxDurationInMilliseconds().getValue()) : null,
					request.hasMaxFileSizeInBytes() ?
						request.getMaxFileSizeInBytes().getValue() : null,
					request.hasChunkFileSizeInBytes() ?
						request.getChunkFileSizeInBytes().getValue() :
						this.evita.getConfiguration().server().trafficRecording().exportFileChunkSizeInBytes()
				);
				responseObserver.onNext(
					builder
						.setTaskStatus(toGrpcTaskStatus(task.getStatus()))
						.build()
				);
				responseObserver.onCompleted();
			},
			this.evita.getRequestExecutor(),
			responseObserver,
			this.tracingContext
		);
	}

	/**
	 * Stops the ongoing traffic recording process based on the provided request.
	 *
	 * @param request The request object containing the necessary details to stop traffic recording.
	 * @param responseObserver The response observer to send the status of the operation.
	 */
	@Override
	public void stopTrafficRecording(GrpcStopTrafficRecordingRequest request, StreamObserver<GetTrafficRecordingStatusResponse> responseObserver) {
		executeWithClientContext(
			session -> {
				GetTrafficRecordingStatusResponse.Builder builder = GetTrafficRecordingStatusResponse.newBuilder();
				final TaskStatus<TrafficRecordingSettings, FileForFetch> taskStatus = session.stopRecording(
					toUuid(request.getTaskStatusId())
				);
				responseObserver.onNext(
					builder
						.setTaskStatus(toGrpcTaskStatus(taskStatus))
						.build()
				);
				responseObserver.onNext(builder.build());
				responseObserver.onCompleted();
			},
			this.evita.getRequestExecutor(),
			responseObserver,
			this.tracingContext
		);
	}

}
