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
import lombok.Setter;

import java.util.List;
import java.util.concurrent.Flow;

public class SystemChangeSubscriber implements Flow.Subscriber<ChangeSystemCapture> {
    @Setter
    private List<Flow.Publisher<? extends ChangeSystemCapture>> publishers;

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
        subscription.request(1);
        for (Flow.Publisher<? extends ChangeSystemCapture> publisher : this.publishers) {
            publisher.subscribe(this);
        }
    }

    @Override
    public void onNext(ChangeSystemCapture item) {

    }

    @Override
    public void onError(Throwable throwable) {

    }

    @Override
    public void onComplete() {

    }
}
