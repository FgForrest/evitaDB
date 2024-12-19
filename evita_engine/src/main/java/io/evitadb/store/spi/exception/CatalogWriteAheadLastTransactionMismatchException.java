/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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

package io.evitadb.store.spi.exception;


import io.evitadb.exception.EvitaInternalError;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.io.Serial;

/**
 * Exception is thrown from the catalog write-ahead transaction stage when the last transaction version in the catalog
 * doesn't match the current transaction version. This is a critical error that indicates a serious problem with the
 * catalog write-ahead transaction processing.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public class CatalogWriteAheadLastTransactionMismatchException extends EvitaInternalError {
	@Serial private static final long serialVersionUID = 6117942525622800073L;
	@Getter private long currentTransactionVersion;

	public CatalogWriteAheadLastTransactionMismatchException(long currentTransactionVersion, @Nonnull String privateMessage, @Nonnull String publicMessage) {
		super(privateMessage, publicMessage);
		this.currentTransactionVersion = currentTransactionVersion;
	}
}
