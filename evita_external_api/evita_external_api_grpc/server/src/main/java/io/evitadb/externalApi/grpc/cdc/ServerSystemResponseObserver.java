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

package io.evitadb.externalApi.grpc.cdc;

import io.evitadb.api.requestResponse.cdc.ChangeSystemCapture;
import io.evitadb.api.requestResponse.cdc.ChangeSystemCaptureRequest;
import io.evitadb.core.Evita;
import io.evitadb.externalApi.grpc.generated.GrpcRegisterSystemChangeCaptureRequest;
import io.evitadb.externalApi.grpc.generated.GrpcRegisterSystemChangeCaptureResponse;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;

import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.Flow;

import static io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter.toCaptureContent;

public class ServerSystemResponseObserver implements StreamObserver<GrpcRegisterSystemChangeCaptureRequest> {
    private final ServerCallStreamObserver<GrpcRegisterSystemChangeCaptureResponse> serverCallStreamObserver;
    private final Evita evita;
    private final List<Flow.Publisher<? extends ChangeSystemCapture>> publishers;
    private final SystemChangeSubscriber systemChangeSubscriber;

    public ServerSystemResponseObserver(ServerCallStreamObserver<GrpcRegisterSystemChangeCaptureResponse> serverCallStreamObserver, Evita evita) {
        this.publishers = new LinkedList<>();
        this.evita = evita;
        this.serverCallStreamObserver = serverCallStreamObserver;
        this.serverCallStreamObserver.disableAutoRequest();
        this.serverCallStreamObserver.setOnReadyHandler(new OnReadyHandler(serverCallStreamObserver));
        this.serverCallStreamObserver.setOnCancelHandler(serverCallStreamObserver::onCompleted);
        this.systemChangeSubscriber = new SystemChangeSubscriber();
        this.systemChangeSubscriber.setPublishers(this.publishers);
    }

    @Override
    public void onNext(GrpcRegisterSystemChangeCaptureRequest grpcRegisterSystemChangeCaptureRequest) {
        final Flow.Publisher<ChangeSystemCapture> publisher = this.evita.registerSystemChangeCapture(
                new ChangeSystemCaptureRequest(null, null, toCaptureContent(grpcRegisterSystemChangeCaptureRequest.getContent()))
        );
	    this.publishers.add(publisher);
    }

    @Override
    public void onError(Throwable throwable) {

    }

    @Override
    public void onCompleted() {

    }

    @RequiredArgsConstructor
    private static class OnReadyHandler implements Runnable {
        private final ServerCallStreamObserver<GrpcRegisterSystemChangeCaptureResponse> serverCallStreamObserver;

        // Guard against spurious onReady() calls caused by a race between onNext() and onReady(). If the transport
        // toggles isReady() from false to true while onNext() is executing, but before onNext() checks isReady(),
        // request(1) would be called twice - once by onNext() and once by the onReady() scheduled during onNext()'s
        // execution.
        private boolean wasReady = false;

        @Override
        public void run() {
            if (this.serverCallStreamObserver.isReady() && !this.wasReady) {
	            this.wasReady = true;
                // Signal the request sender to send one message. This happens when isReady() turns true, signaling that
                // the receive buffer has enough free space to receive more messages. Calling request() serves to prime
                // the message pump.
	            this.serverCallStreamObserver.request(1);
            }
        }
    }
}
