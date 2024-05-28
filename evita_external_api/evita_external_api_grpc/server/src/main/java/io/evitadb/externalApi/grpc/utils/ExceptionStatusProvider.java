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

package io.evitadb.externalApi.grpc.utils;

import com.google.protobuf.Any;
import com.google.rpc.Code;
import com.google.rpc.ErrorInfo;
import com.google.rpc.Status;
import io.evitadb.exception.EvitaError;
import io.grpc.StatusRuntimeException;
import io.grpc.protobuf.StatusProto;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import java.util.Map;

/**
 * Class containing {@link StatusRuntimeException} builder methods.
 *
 * @author Tomáš Pozler, 2022
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class ExceptionStatusProvider {

	private static final String ERROR_DOMAIN = "io.evitadb.externalApi.grpc.services";

	/**
	 * Creates {@link StatusRuntimeException} with provided {@link Throwable} as cause.
	 *
	 * @param exception from which will error message be taken and wrapped in {@link StatusRuntimeException}
	 * @param errorCode to be wrapped in {@link StatusRuntimeException}
	 * @param reason    to be wrapped in {@link StatusRuntimeException}
	 * @return {@link StatusRuntimeException} with {@link EvitaError} as cause
	 */
	public static StatusRuntimeException getStatus(@Nonnull Throwable exception, @Nonnull Code errorCode, @Nonnull String reason) {
		final String errorMessage = exception instanceof EvitaError evitaError ? evitaError.getPublicMessage() : exception.toString();
		return StatusProto.toStatusRuntimeException(Status.newBuilder()
			.setCode(errorCode.getNumber())
			.setMessage(errorMessage)
			.addDetails(Any.pack(ErrorInfo.newBuilder()
				.setReason(reason)
				.setDomain(ERROR_DOMAIN)
				.build())
			)
			.build());
	}

	/**
	 * Creates {@link StatusRuntimeException} with provided error message cause and metadata.
	 *
	 * @param errorMessage to be wrapped in {@link StatusRuntimeException}
	 * @param errorCode    to be wrapped in {@link StatusRuntimeException}
	 * @param reason       to be wrapped in {@link StatusRuntimeException}
	 * @param metadata     to be wrapped in {@link StatusRuntimeException}
	 * @return {@link StatusRuntimeException} with {@link EvitaError} as cause
	 */
	public static StatusRuntimeException getStatus(@Nonnull String errorMessage, @Nonnull Code errorCode, @Nonnull String reason, @Nonnull Map<String, String> metadata) {
		return StatusProto.toStatusRuntimeException(Status.newBuilder()
			.setCode(errorCode.getNumber())
			.setMessage(errorMessage)
			.addDetails(Any.pack(ErrorInfo.newBuilder()
				.setReason(reason)
				.setDomain(ERROR_DOMAIN)
				.putAllMetadata(metadata)
				.build())
			)
			.build());
	}

	/**
	 * Creates {@link StatusRuntimeException} with provided error message cause without metadata.
	 *
	 * @param errorMessage to be wrapped in {@link StatusRuntimeException}
	 * @param errorCode    to be wrapped in {@link StatusRuntimeException}
	 * @param reason       to be wrapped in {@link StatusRuntimeException}
	 * @return {@link StatusRuntimeException} with {@link EvitaError} as cause
	 */
	public static StatusRuntimeException getStatus(@Nonnull String errorMessage, @Nonnull Code errorCode, @Nonnull String reason) {
		return StatusProto.toStatusRuntimeException(Status.newBuilder()
			.setCode(errorCode.getNumber())
			.setMessage(errorMessage)
			.addDetails(Any.pack(ErrorInfo.newBuilder()
				.setReason(reason)
				.setDomain(ERROR_DOMAIN)
				.build())
			)
			.build());
	}
}
