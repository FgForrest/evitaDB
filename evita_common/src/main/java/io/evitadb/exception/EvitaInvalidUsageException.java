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

import io.evitadb.utils.StringUtils;
import lombok.Getter;

import javax.annotation.Nonnull;
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
	@Getter private final String errorCode;

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
		final StackTraceElement stackTraceElement = getProperStackLine();
		this.errorCode = StringUtils.hashChars(stackTraceElement.getClassName()) + ":" +
			StringUtils.hashChars(stackTraceElement.getMethodName()) + ":" +
			stackTraceElement.getLineNumber();
	}

	public EvitaInvalidUsageException(@Nonnull String publicMessage) {
		this(publicMessage, publicMessage);
	}

	public EvitaInvalidUsageException(@Nonnull String privateMessage, @Nonnull String publicMessage, @Nonnull Throwable cause) {
		super(privateMessage, cause);
		this.publicMessage = publicMessage;
		final StackTraceElement stackTraceElement = getProperStackLine();
		this.errorCode = StringUtils.hashChars(stackTraceElement.getClassName()) + ":" +
			StringUtils.hashChars(stackTraceElement.getMethodName()) + ":" +
			stackTraceElement.getLineNumber();
	}

	public EvitaInvalidUsageException(@Nonnull String publicMessage, @Nonnull Throwable cause) {
		this(publicMessage, publicMessage, cause);
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

	private StackTraceElement getProperStackLine() {
		final StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
		int index = 1;
		while (index < stackTrace.length && this.getClass().getName().equals(stackTrace[index].getClassName())) {
			index++;
		}
		return stackTrace[index];
	}

}
