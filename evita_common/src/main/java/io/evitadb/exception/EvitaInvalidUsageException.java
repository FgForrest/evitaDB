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
 * This exception represents an error that is caused by the client data or usage of evitaDB. All exceptions of this
 * type can be solved by the client side by changing its behaviour.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class EvitaInvalidUsageException extends IllegalArgumentException implements EvitaError {

	@Serial private static final long serialVersionUID = 2004640911160330154L;

	@Getter private final String publicMessage;
	/**
	 * Lazily resolved {@link #getErrorCode()} - see the identical field on {@link EvitaInternalError} for why it is
	 * computed on first read and why the data race on it is benign. Client-side exceptions are constructed on
	 * ordinary rejection paths, so keeping the stack walk and two MD5 hashes off construction matters most here.
	 *
	 * Non-null from construction only when the code was supplied explicitly, which happens when an exception is
	 * rebuilt on the client from data that crossed the wire.
	 */
	@Nullable private String errorCode;

	/**
	 * Method is targeted to be used on the client.
	 */
	@Nonnull
	public static EvitaInvalidUsageException createExceptionWithErrorCode(@Nonnull String publicMessage, @Nonnull String errorCode) {
		return new EvitaInvalidUsageException(publicMessage, publicMessage, errorCode);
	}

	public EvitaInvalidUsageException(@Nonnull String privateMessage, @Nonnull String publicMessage) {
		super(privateMessage);
		this.publicMessage = publicMessage;
	}

	public EvitaInvalidUsageException(@Nonnull String publicMessage) {
		// deliberately calls `super(...)` rather than delegating to the two-argument constructor - see the note on
		// constructor delegation at the bottom of this class
		super(publicMessage);
		this.publicMessage = publicMessage;
	}

	public EvitaInvalidUsageException(@Nonnull String privateMessage, @Nonnull String publicMessage, @Nonnull Throwable cause) {
		super(privateMessage, cause);
		this.publicMessage = publicMessage;
	}

	public EvitaInvalidUsageException(@Nonnull String publicMessage, @Nonnull Throwable cause) {
		// deliberately calls `super(...)` rather than delegating to the three-argument constructor - see the note on
		// constructor delegation at the bottom of this class
		super(publicMessage, cause);
		this.publicMessage = publicMessage;
	}

	private EvitaInvalidUsageException(@Nonnull String privateMessage, @Nonnull String publicMessage, @Nonnull String errorCode) {
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

	/*
	 * ## No constructor of this class may delegate to another one
	 *
	 * The observability agent (`ErrorMonitoringAgent`) counts every client error by instrumenting the constructors
	 * of this class - the single root that all 122 concrete client-error types pass through exactly once. That
	 * "exactly once" holds only while no constructor here calls `this(...)`: a delegating constructor enters two
	 * instrumented constructors for one object, and `io_evitadb_client_errors_total` silently counts it twice. That
	 * is precisely what used to happen here, and it went unnoticed because nothing fails - the number is merely
	 * wrong.
	 *
	 * `EvitaErrorMonitoringTest` fails if this is reintroduced.
	 */

}
