/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.externalApi.rest.io.webSocket;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.linecorp.armeria.internal.common.stream.NoopSubscription;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Map;

final class ExecutionResultSubscriber implements Subscriber<Object> {
    private static final Logger logger = LoggerFactory.getLogger(ExecutionResultSubscriber.class);
    private final RestSubProtocol protocol;
    private final String operationId;

    @Nullable
    private Subscription subscription;

    ExecutionResultSubscriber(@Nonnull String operationId, @Nonnull RestSubProtocol protocol) {
        this.operationId = operationId;
        this.protocol = protocol;
    }

    @Override
    public void onSubscribe(Subscription s) {
        if (this.subscription != null) {
            /*
            A Subscriber MUST call Subscription.cancel() on the given Subscription after an onSubscribe signal
            if it already has an active Subscription.
             */
            s.cancel();
            return;
        }
	    this.subscription = s;
        this.requestMore();
    }

    @Override
    public void onNext(Object executionResult) {
        assert this.subscription != null;
        try {
            this.protocol.sendResult(this.operationId, executionResult);
            requestMore();
        } catch (JsonProcessingException e) {
	        this.protocol.completeWithError(e);
            if (this.subscription != null) {
	            this.subscription.cancel();
            }
        }
    }

    @Override
    public void onError(Throwable t) {
        /*
        Subscriber.onComplete() and Subscriber.onError(Throwable t) MUST consider
        the Subscription cancelled after having received the signal.
         */
        logger.trace("onError", t);
	    this.subscription = null;
	    this.protocol.completeWithError(t);
    }

    @Override
    public void onComplete() {
        /*
        Subscriber.onComplete() and Subscriber.onError(Throwable t) MUST consider
        the Subscription cancelled after having received the signal.
         */
        logger.trace("onComplete");
	    this.subscription = null;
	    this.protocol.complete();
    }

    public void setCompleted() {
        if (this.subscription == null) {
	        this.subscription = NoopSubscription.get();
        }
	    this.subscription.cancel();
    }

    private void requestMore() {
        if (this.subscription != null) {
	        this.subscription.request(1);
        }
    }
}
