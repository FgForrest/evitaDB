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

package io.evitadb.store.exception;

import javax.annotation.Nonnull;
import java.io.Serial;

/**
 * Exception is thrown when there is no way how to convert attempted name to non-empty ASCII file name.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class InvalidFileNameException extends EvitaIOException {
	@Serial private static final long serialVersionUID = -5790924522376085093L;

	public InvalidFileNameException(String message) {
		super(message);
	}

	public InvalidFileNameException(@Nonnull String privateMessage, @Nonnull String publicMessage) {
		super(privateMessage, publicMessage);
	}
}
