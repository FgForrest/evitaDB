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

package io.evitadb.spi.store.catalog.exception;

import io.evitadb.exception.EvitaInternalError;
import io.evitadb.spi.store.catalog.persistence.storageParts.KeyCompressor;

import javax.annotation.Nonnull;
import java.io.Serial;

/**
 * Exception is thrown by a {@link KeyCompressor} implementation when `getId(key)` is called for a key that has no
 * registered integer mapping, and the implementation is not permitted to register new keys.
 *
 * The {@link KeyCompressor} subsystem reduces serialized data size by replacing frequently repeated, structurally
 * complex keys — such as `AttributeKey`, `AssociatedDataKey`, or `PriceKey` — with compact integer identifiers.
 * Two flavours of the compressor exist:
 *
 * - **Read-only** (`ReadOnlyKeyCompressor`): loaded from persisted state and never extends its dictionary. Calling
 *   `getId` for an unregistered key is a hard error, because it would indicate that the data being serialized
 *   references a key that was never persisted, which signals a corruption or logic bug.
 * - **Aggregated** (`AggregatedKeyCompressor`): chains multiple compressors and similarly throws this exception when
 *   none of the delegates recognise the key.
 *
 * The message passed to the constructor should identify the unknown key so that the root cause can be diagnosed.
 * Because this exception extends {@link EvitaInternalError}, it is treated as an unrecoverable internal invariant
 * violation rather than a client-observable error.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class CompressionKeyUnknownException extends EvitaInternalError {
	@Serial private static final long serialVersionUID = -601305213322960436L;

	/**
	 * @param message diagnostic message identifying the key for which no integer mapping could be found
	 */
	public CompressionKeyUnknownException(@Nonnull String message) {
		super(message);
	}
}
