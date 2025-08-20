/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2025
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

package io.evitadb.externalApi.exception;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;

public class UrlDecodeException extends ExternalApiInternalError {
	@Serial private static final long serialVersionUID = 1351003149802542253L;

	public UrlDecodeException(@Nonnull String publicMessage) {
		super(publicMessage);
	}

	public UrlDecodeException(@Nonnull String publicMessage, @Nonnull Throwable throwable) {
		super(publicMessage, throwable);
	}

	/**
	 * Creates a new {@link UrlDecodeException} with a message indicating the failure to decode a URL using
	 * a specific charset encoding.
	 *
	 * @param url the URL that failed to decode
	 * @param encoding the character encoding used for decoding the URL
	 * @param throwable an optional cause of the decoding failure, or {@code null} if the cause is not specified
	 * @return a new instance of {@link UrlDecodeException} with a detailed message
	 */
	@Nonnull
	public static UrlDecodeException failedToDecodeURL(@Nonnull String url, @Nonnull String encoding, @Nullable Throwable throwable) {
		return throwable == null ?
			new UrlDecodeException("Failed to decode url %s to charset %s".formatted(url, encoding)) :
			new UrlDecodeException("Failed to decode url %s to charset %s".formatted(url, encoding), throwable);
	}
}
