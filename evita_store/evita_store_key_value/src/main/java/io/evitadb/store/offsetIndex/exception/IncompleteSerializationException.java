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

package io.evitadb.store.offsetIndex.exception;

import io.evitadb.exception.EvitaInternalError;
import io.evitadb.store.offsetIndex.OffsetIndexSerializationService;

import java.io.Serial;

/**
 * Exception is thrown by {@link OffsetIndexSerializationService} when there are still
 * pending updates after the flush have happened. This is surely unexpected situation.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class IncompleteSerializationException extends EvitaInternalError {
	@Serial private static final long serialVersionUID = -4768609495278900538L;

	public IncompleteSerializationException(int entryCount) {
		super("OffsetIndex changes were not written completely - there is: " + entryCount + " left unsaved!");
	}
}
