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

package io.evitadb.driver.cdc;

import io.evitadb.api.requestResponse.cdc.ChangeSystemCapture;
import io.evitadb.externalApi.grpc.generated.GrpcRegisterSystemChangeCaptureRequest;
import io.evitadb.externalApi.grpc.generated.GrpcRegisterSystemChangeCaptureResponse;
import io.grpc.stub.ClientCallStreamObserver;
import io.grpc.stub.ClientResponseObserver;
import lombok.Setter;

import java.util.List;
import java.util.concurrent.Flow;

import static io.evitadb.externalApi.grpc.dataType.ChangeDataCaptureConverter.toChangeSystemCapture;

public class ClientSystemResponseObserver implements ClientResponseObserver<GrpcRegisterSystemChangeCaptureRequest, GrpcRegisterSystemChangeCaptureResponse> {
    private ClientCallStreamObserver<GrpcRegisterSystemChangeCaptureRequest> clientCallStreamObserver;
    @Setter
    private List<Flow.Subscriber<? super ChangeSystemCapture>> subscribers;

    @Override
    public void beforeStart(ClientCallStreamObserver<GrpcRegisterSystemChangeCaptureRequest> clientCallStreamObserver) {
        this.clientCallStreamObserver = clientCallStreamObserver;
        clientCallStreamObserver.disableAutoRequestWithInitial(1);
    }

    @Override
    public void onNext(GrpcRegisterSystemChangeCaptureResponse grpcRegisterSystemChangeCaptureResponse) {
        for (Flow.Subscriber<? super ChangeSystemCapture> subscriber : subscribers) {
            subscriber.onNext(toChangeSystemCapture(grpcRegisterSystemChangeCaptureResponse.getCapture()));
        }
        this.clientCallStreamObserver.request(1);
    }

    @Override
    public void onError(Throwable throwable) {

    }

    @Override
    public void onCompleted() {

    }
}
