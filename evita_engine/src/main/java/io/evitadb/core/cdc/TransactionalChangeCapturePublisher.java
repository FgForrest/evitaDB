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

package io.evitadb.core.cdc;

import io.evitadb.api.requestResponse.cdc.ChangeCapture;
import io.evitadb.api.requestResponse.cdc.ChangeCaptureRequest;
import io.evitadb.exception.EvitaInternalError;

import javax.annotation.Nonnull;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * TODO lho docs
 *
 * @author Lukáš Hornych, 2023
 */
// todo jno: use this to implement the transactional catalog capture publisher which will be reading the data directly from this
public class TransactionalChangeCapturePublisher
	<C extends ChangeCapture, R extends ChangeCaptureRequest>
	extends AbstractChangeCapturePublisher<TransactionalChangeCapturePublisher<C, R>, C, R> {

	public TransactionalChangeCapturePublisher(@Nonnull Executor executor, @Nonnull R request) {
		super(executor, request);
	}

	public TransactionalChangeCapturePublisher(@Nonnull Executor executor,
	                                           @Nonnull R request,
	                                           @Nonnull Consumer<TransactionalChangeCapturePublisher<C, R>> terminationCallback) {
		super(executor, request, terminationCallback);
	}

	// todo jno: implement this, this is just a mockup
	public void submitTransaction(long transactionId) {
		// this will trigger reading of captures from the WAL and passing them to the `delegate` publisher
		throw new EvitaInternalError("Not implemented yet");
	}
}
