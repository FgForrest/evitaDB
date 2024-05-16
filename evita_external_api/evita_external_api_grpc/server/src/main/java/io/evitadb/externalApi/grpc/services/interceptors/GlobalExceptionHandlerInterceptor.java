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
 *   https://github.com/FgForrest/evitaDB/blob/master/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.externalApi.grpc.services.interceptors;

import com.google.protobuf.Any;
import com.google.rpc.ErrorInfo;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.grpc.ForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.grpc.Status.Code;
import io.grpc.StatusRuntimeException;
import io.grpc.protobuf.StatusProto;

import javax.annotation.Nonnull;

/**
 * Centralized interceptor that handles all kinds of possible exceptions that could be emitted by evitaDB for input
 * requests in a generic way.
 *
 * <a href="https://techdozo.dev/getting-error-handling-right-in-grpc/#-grpc-error-interceptor">Source of inspiration</a>
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class GlobalExceptionHandlerInterceptor implements ServerInterceptor {

	@Override
	public <T, R> ServerCall.Listener<T> interceptCall(
		ServerCall<T, R> serverCall, Metadata headers, ServerCallHandler<T, R> serverCallHandler) {
		ServerCall.Listener<T> delegate = serverCallHandler.startCall(serverCall, headers);
		return new ExceptionHandler<>(delegate, serverCall, headers);
	}

	private static class ExceptionHandler<T, R> extends ForwardingServerCallListener.SimpleForwardingServerCallListener<T> {
		private final ServerCall<T, R> delegate;
		private final Metadata headers;

		ExceptionHandler(@Nonnull ServerCall.Listener<T> listener, @Nonnull ServerCall<T, R> serverCall, @Nonnull Metadata headers) {
			super(listener);
			this.delegate = serverCall;
			this.headers = headers;
		}

		@Override
		public void onHalfClose() {
			try {
				super.onHalfClose();
			} catch (RuntimeException ex) {
				handleException(ex, delegate, headers);
				throw ex;
			}
		}

		private void handleException(@Nonnull RuntimeException exception, @Nonnull ServerCall<T, R> serverCall, @Nonnull Metadata headers) {
			if (serverCall.isReady()) {
				final com.google.rpc.Status rpcStatus;
				if (exception instanceof EvitaInvalidUsageException invalidUsageException) {
					final ErrorInfo errorInfo = ErrorInfo.newBuilder()
						.setReason(invalidUsageException.getErrorCode() + ": " + invalidUsageException.getPublicMessage())
						.setDomain(invalidUsageException.getClass().getSimpleName())
						.build();

					rpcStatus = com.google.rpc.Status.newBuilder()
						.setCode(Code.INVALID_ARGUMENT.value())
						.setMessage(invalidUsageException.getErrorCode() + ": " + invalidUsageException.getPublicMessage())
						.addDetails(Any.pack(errorInfo))
						.build();
				} else if (exception instanceof EvitaInternalError internalError) {
					final ErrorInfo errorInfo = ErrorInfo.newBuilder()
						.setReason(internalError.getErrorCode() + ": " + internalError.getPublicMessage())
						.setDomain(internalError.getClass().getSimpleName())
						.build();

					rpcStatus = com.google.rpc.Status.newBuilder()
						.setCode(Code.INTERNAL.value())
						.setMessage(internalError.getErrorCode() + ": " + internalError.getPublicMessage())
						.addDetails(Any.pack(errorInfo))
						.build();
				} else {
					rpcStatus = com.google.rpc.Status.newBuilder()
						.setCode(Code.INTERNAL.value())
						.build();
				}

				final StatusRuntimeException statusRuntimeException = StatusProto.toStatusRuntimeException(rpcStatus);
				final Status newStatus = Status.fromThrowable(statusRuntimeException);
				final Metadata newHeaders = statusRuntimeException.getTrailers();
				serverCall.close(newStatus, newHeaders);
			}
		}
	}
}
