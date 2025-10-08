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
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import java.util.concurrent.CompletionException;

/**
 * Centralized interceptor that handles all kinds of possible exceptions that could be emitted by evitaDB for input
 * requests in a generic way.
 *
 * <a href="https://techdozo.dev/getting-error-handling-right-in-grpc/#-grpc-error-interceptor">Source of inspiration</a>
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@Slf4j
public class GlobalExceptionHandlerInterceptor implements ServerInterceptor {

	/**
	 * Method sends error to the client side using provided response observer the same way as it would if the exception
	 * would be thrown directly in the service implementation and handled by the interceptor.
	 *
	 * @param exception exception that occurred
	 * @param responseObserver response observer to send the error to
	 */
	public static void sendErrorToClient(@Nonnull Throwable exception, @Nonnull StreamObserver<?> responseObserver) {
		final com.google.rpc.Status errorStatus = createErrorStatus(exception);
		final StatusRuntimeException statusRuntimeException = StatusProto.toStatusRuntimeException(errorStatus);
		final Metadata newHeaders = statusRuntimeException.getTrailers();
		final Status newStatus = Status.fromThrowable(statusRuntimeException).withCause(exception);
		responseObserver.onError(newStatus.asRuntimeException(newHeaders));
	}

	/**
	 * Method creates unified error status for the client side.
	 * @param exception exception that occurred
	 * @return unified error status
	 */
	@Nonnull
	private static com.google.rpc.Status createErrorStatus(@Nonnull Throwable exception) {
		final com.google.rpc.Status rpcStatus;

		if (exception instanceof CompletionException completionException) {
			return createErrorStatus(completionException.getCause());
		} else if (exception instanceof EvitaInvalidUsageException invalidUsageException) {
			if (log.isDebugEnabled()) {
				log.debug("Invalid usage exception gRPC call: " + exception.getMessage(), exception);
			}

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
			log.error("Internal error occurred during processing of gRPC call: " + exception.getMessage(), exception);

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
			log.error("Internal error occurred during processing of gRPC call: " + exception.getMessage(), exception);

			rpcStatus = com.google.rpc.Status.newBuilder()
				.setCode(Code.INTERNAL.value())
				.build();
		}
		return rpcStatus;
	}

	@Override
	public <T, R> ServerCall.Listener<T> interceptCall(
		ServerCall<T, R> serverCall, Metadata headers, ServerCallHandler<T, R> serverCallHandler) {
		ServerCall.Listener<T> delegate = serverCallHandler.startCall(serverCall, headers);
		return new ExceptionHandler<>(delegate, serverCall);
	}

	private static class ExceptionHandler<T, R> extends ForwardingServerCallListener.SimpleForwardingServerCallListener<T> {
		private final ServerCall<T, R> delegate;

		ExceptionHandler(@Nonnull ServerCall.Listener<T> listener, @Nonnull ServerCall<T, R> serverCall) {
			super(listener);
			this.delegate = serverCall;
		}

		@Override
		public void onHalfClose() {
			try {
				super.onHalfClose();
			} catch (EvitaInvalidUsageException ex) {
				// log as debug, the problem is probably on the client side
				log.debug("Exception occurred during processing of gRPC call", ex);
				handleException(ex, this.delegate);
				throw ex;
			} catch (RuntimeException ex) {
				// we're responsible for logging the exception here
				log.error("Exception occurred during processing of gRPC call", ex);
				handleException(ex, this.delegate);
				throw ex;
			}
		}

		private void handleException(@Nonnull RuntimeException exception, @Nonnull ServerCall<T, R> serverCall) {
			if (serverCall.isReady()) {
				final com.google.rpc.Status rpcStatus = createErrorStatus(exception);

				final StatusRuntimeException statusRuntimeException = StatusProto.toStatusRuntimeException(rpcStatus);
				final Metadata newHeaders = statusRuntimeException.getTrailers();
				final Status newStatus = Status.fromThrowable(statusRuntimeException).withCause(exception);
				serverCall.close(newStatus, newHeaders);
			}
		}
	}

}
