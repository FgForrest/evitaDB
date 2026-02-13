/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025-2026
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
import java.nio.file.Path;

/**
 * Exception indicating that the Engine Mutation Log has been corrupted.
 * This is separate from {@link WriteAheadLogCorruptedException} which is used for catalog WAL.
 * This exception represents a serious problem within evitaDB engine that requires examination and resolution.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public class EngineMutationLogCorruptedException extends EvitaInternalError {
	@Serial private static final long serialVersionUID = -8127477065404573236L;

	/**
	 * Creates an exception with custom private and public messages.
	 *
	 * @param privateMessage detailed technical message for internal logs
	 * @param publicMessage  user-friendly message suitable for external communication
	 */
	public EngineMutationLogCorruptedException(@Nonnull String privateMessage, @Nonnull String publicMessage) {
		super(privateMessage, publicMessage);
	}

	/**
	 * Creates an exception with custom private and public messages and a root cause.
	 *
	 * @param privateMessage detailed technical message for internal logs
	 * @param publicMessage  user-friendly message suitable for external communication
	 * @param cause          the underlying cause of the corruption
	 */
	public EngineMutationLogCorruptedException(@Nonnull String privateMessage, @Nonnull String publicMessage, @Nonnull Throwable cause) {
		super(privateMessage, publicMessage, cause);
	}

	/**
	 * Creates an exception indicating a cumulative CRC32C checksum mismatch in the engine WAL file.
	 *
	 * @param walFilePath      the path to the engine WAL file where the mismatch was detected
	 * @param position         the byte position in the file where the mismatch was detected
	 * @param expectedChecksum the checksum value stored in the WAL file
	 * @param actualChecksum   the checksum value computed from the data
	 */
	public EngineMutationLogCorruptedException(
		@Nonnull Path walFilePath,
		long position,
		long expectedChecksum,
		long actualChecksum
	) {
		super(
			"Cumulative CRC32C mismatch at position " + position +
				" in engine WAL file `" + walFilePath + "`. Expected: " + expectedChecksum +
				", actual: " + actualChecksum,
			"Engine mutation log corrupted: checksum verification failed"
		);
	}
}
