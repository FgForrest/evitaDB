/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

package io.evitadb.exception;

import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;

/**
 * This exception represents an internal error inside evitaDB that is not caused by a client side but rather represents
 * a serious problem inside Evita itself. Each occurrence of this exception is worth examination and solving. The client
 * side could rarely avoid or correct these types of error.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public abstract class EvitaInternalError extends IllegalStateException implements EvitaError {
	@Serial private static final long serialVersionUID = -1040832658535384105L;
	@Getter private final String publicMessage;
	/**
	 * Lazily resolved {@link #getErrorCode()}. Non-final and computed on first read rather than in the constructor:
	 * resolving it walks a stack trace and MD5-hashes two strings, and the overwhelming majority of exceptions are
	 * thrown, handled and discarded without anybody ever asking for their code.
	 *
	 * A data race on this field is benign - every thread derives the same value from the same immutable stack trace,
	 * and `String` is safely publishable through a race because all of its fields are final. This is the same
	 * pattern `String#hashCode` uses, and it is why the field needs no `volatile`.
	 *
	 * Non-null from construction only when the code was supplied explicitly, which happens when an exception is
	 * rebuilt on the client from data that crossed the wire.
	 */
	@Nullable private String errorCode;

	protected EvitaInternalError(@Nonnull String privateMessage, @Nonnull String publicMessage) {
		super(privateMessage);
		this.publicMessage = publicMessage;
	}

	protected EvitaInternalError(@Nonnull String publicMessage) {
		this(publicMessage, publicMessage);
	}

	protected EvitaInternalError(@Nonnull String privateMessage, @Nonnull String publicMessage, @Nonnull Throwable cause) {
		super(privateMessage, cause);
		this.publicMessage = publicMessage;
	}

	protected EvitaInternalError(@Nonnull String publicMessage, @Nonnull Throwable cause) {
		this(publicMessage, publicMessage, cause);
	}

	protected EvitaInternalError(@Nonnull String privateMessage, @Nonnull String publicMessage, @Nonnull String errorCode) {
		super(privateMessage);
		this.publicMessage = publicMessage;
		this.errorCode = errorCode;
	}

	@Nonnull
	@Override
	public String getPrivateMessage() {
		return getMessage();
	}

	@Nonnull
	@Override
	public String getErrorCode() {
		String theErrorCode = this.errorCode;
		if (theErrorCode == null) {
			theErrorCode = ErrorCodeResolver.resolveErrorCode(this);
			this.errorCode = theErrorCode;
		}
		return theErrorCode;
	}

}
