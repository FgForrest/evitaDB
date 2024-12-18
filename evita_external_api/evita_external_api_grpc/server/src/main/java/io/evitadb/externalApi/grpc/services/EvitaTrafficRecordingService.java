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

package io.evitadb.externalApi.grpc.services;


import io.evitadb.api.requestResponse.trafficRecording.TrafficRecording;
import io.evitadb.api.requestResponse.trafficRecording.TrafficRecordingCaptureRequest;
import io.evitadb.core.Evita;
import io.evitadb.externalApi.grpc.generated.*;
import io.evitadb.externalApi.grpc.requestResponse.traffic.TrafficCaptureConverter;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import java.util.stream.Stream;

import static io.evitadb.externalApi.grpc.services.EvitaSessionService.executeWithClientContext;

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
				session.getRecordings(captureRequest)
					.limit(request.getLimit())
					.forEach(
						trafficRecording -> builder.addTrafficRecord(
							TrafficCaptureConverter.toGrpcGrpcTrafficRecord(trafficRecording, captureRequest.content())
						)
					);
				responseObserver.onNext(builder.build());
				responseObserver.onCompleted();
			},
			evita.getRequestExecutor(),
			responseObserver
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

		// avoid returning error when client cancels the stream
		serverCallStreamObserver.setOnCancelHandler(() -> log.info("Client cancelled the traffic history request."));

		executeWithClientContext(
			session -> {
				final TrafficRecordingCaptureRequest captureRequest = TrafficCaptureConverter.toTrafficRecordingCaptureRequest(request);
				final Stream<TrafficRecording> trafficHistoryStream = session.getRecordings(captureRequest);
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
			},
			evita.getRequestExecutor(),
			responseObserver
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
						request.hasNameStartsWith() ? request.getNameStartsWith().getValue() : null
					).limit(request.getLimit())
					.forEach(builder::addLabelName);
				responseObserver.onNext(builder.build());
				responseObserver.onCompleted();
			},
			evita.getRequestExecutor(),
			responseObserver
		);
	}

	/**
	 * Method returns top X traffic recording label values ordered by cardinality matching given criteria.
	 *
	 * @param request          request containing the criteria
	 * @param responseObserver observer on which errors might be thrown and result returned
	 */
	@Override
	public void getTrafficRecordingLabelsValuesOrderedByCardinality(GetTrafficRecordingValuesNamesRequest request, StreamObserver<GetTrafficRecordingValuesNamesResponse> responseObserver) {
		executeWithClientContext(
			session -> {
				GetTrafficRecordingValuesNamesResponse.Builder builder = GetTrafficRecordingValuesNamesResponse.newBuilder();
				session.getLabelValuesOrderedByCardinality(
						request.getLabelName(),
						request.hasValueStartsWith() ? request.getValueStartsWith().getValue() : null
					).limit(request.getLimit())
					.forEach(builder::addLabelValue);
				responseObserver.onNext(builder.build());
				responseObserver.onCompleted();
			},
			evita.getRequestExecutor(),
			responseObserver
		);
	}

}
