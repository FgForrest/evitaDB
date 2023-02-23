/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.driver.exception;

import io.evitadb.driver.config.EvitaClientConfiguration;
import io.evitadb.exception.EvitaInvalidUsageException;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.util.concurrent.TimeUnit;

/**
 * Exception is thrown when the client is ordered to be shut down, but there are still active and working connections
 * that fail to terminate within specified {@link EvitaClientConfiguration#waitForClose() interval}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class EvitaClientNotTerminatedInTimeException extends EvitaInvalidUsageException {
	@Serial private static final long serialVersionUID = 2851586704630027921L;

	public EvitaClientNotTerminatedInTimeException(long waitOnClose, @Nonnull TimeUnit unit) {
		super("Evita client hasn't finished in " + waitOnClose + " " + unit.name().toLowerCase() + "!");
	}
}
