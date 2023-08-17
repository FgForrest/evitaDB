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

import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.Flow;

public class SystemChangePublisher implements Flow.Publisher<ChangeSystemCapture> {
    private final ClientSystemResponseObserver clientResponseObserver;
    private final List<Flow.Subscriber<? super ChangeSystemCapture>> subscribers;
    public SystemChangePublisher(ClientSystemResponseObserver clientResponseObserver) {
        this.clientResponseObserver = clientResponseObserver;
        subscribers = new LinkedList<>();
        this.clientResponseObserver.setSubscribers(subscribers);
    }

    @Override
    public void subscribe(Flow.Subscriber<? super ChangeSystemCapture> subscriber) {
        subscribers.add(subscriber);
    }
}
