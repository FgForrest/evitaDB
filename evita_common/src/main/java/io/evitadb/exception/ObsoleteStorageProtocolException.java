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

import javax.annotation.Nonnull;
import java.io.Serial;

/**
 * This exception is thrown when loaded catalog has STORAGE_PROTOCOL_VERSION different from the current one.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class ObsoleteStorageProtocolException extends UnexpectedIOException {
	@Serial private static final long serialVersionUID = 3484826018541951731L;

	public ObsoleteStorageProtocolException(@Nonnull String privateMessage, @Nonnull String publicMessage, @Nonnull Throwable cause) {
		super(privateMessage, publicMessage, cause);
	}

	public ObsoleteStorageProtocolException(@Nonnull String privateMessage, @Nonnull String publicMessage) {
		super(privateMessage, publicMessage);
	}

}
