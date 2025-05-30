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

package io.evitadb.store.exception;

import io.evitadb.exception.EvitaInternalError;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.util.function.IntFunction;

/**
 * Exception indicating that a Write Ahead Log (WAL) has been corrupted.
 * This exception is a subclass of EvitaInternalError and represents a serious problem within EvitaDB.
 * Each occurrence of this exception should be examined and resolved.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public class WriteAheadLogCorruptedException extends EvitaInternalError {
	@Serial private static final long serialVersionUID = -9127477065404573236L;

	public WriteAheadLogCorruptedException(@Nonnull String privateMessage, @Nonnull String publicMessage) {
		super(privateMessage, publicMessage);
	}

	public WriteAheadLogCorruptedException(@Nonnull String privateMessage, @Nonnull String publicMessage, @Nonnull Throwable cause) {
		super(privateMessage, publicMessage, cause);
	}

	public WriteAheadLogCorruptedException(
		int walIndex,
		long lastVersion,
		long firstVersion,
		@Nonnull IntFunction<String> walFileNameProvider
	) {
		super(
			"First version of the WAL file `" + walFileNameProvider.apply(walIndex) + "` doesn't follow up to the last version of the" +
				" previous WAL file `" + walFileNameProvider.apply(walIndex - 1) + "`! Last version found: `" +
				lastVersion + "`, first version of the next WAL file : `" + firstVersion + "`! ",
			"First version of the WAL file doesn't follow up to the last version of the previous WAL file!"
		);
	}
}
