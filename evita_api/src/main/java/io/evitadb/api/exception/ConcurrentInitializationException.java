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

package io.evitadb.api.exception;

import io.evitadb.api.CatalogState;
import io.evitadb.exception.EvitaInvalidUsageException;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.util.UUID;

/**
 * Exception is used when there is an attempt to create second or additional session in phase
 * of {@link CatalogState#WARMING_UP}.
 *
 * In this phase there is only single session allowed to fill the database. Multiple sessiona are allowed only when
 * the state is changed to {@link CatalogState#ALIVE}.
 *
 * @author Stɇvɇn Kamenik (kamenik.stepan@gmail.cz) (c) 2021
 **/
public class ConcurrentInitializationException extends EvitaInvalidUsageException {
	@Serial private static final long serialVersionUID = -9062588323022507459L;

	public ConcurrentInitializationException(@Nonnull UUID activeSessionId) {
		super(
			"Cannot create more than single session in \"warming up\" state! " +
				"You need to close existing active session `" + activeSessionId + "` first. " +
				"This problem usually occurs when you open the session by `createSession` on Evita instance " +
				"multiple times, or when you create session and subsequently call `update` method on Evita instance " +
				"without closing the existing session first. Parallel sessions are allowed in \"alive\" state which " +
				"applies mutations in separate ACID transaction (considerably slower). You may switch from warming up " +
				"to alive state by calling `goLive` on session contract."
		);
	}

}
