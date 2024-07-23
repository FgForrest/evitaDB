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

import io.evitadb.store.exception.StorageException;

import javax.annotation.Nonnull;
import java.io.Serial;

/**
 * Exception is throw when file sync fails and file contents in a buffer were probably not synced fully to
 * the persistent storage.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class SyncFailedException extends StorageException {
	@Serial private static final long serialVersionUID = 6704506229433286702L;

	public SyncFailedException(@Nonnull Throwable cause) {
		super(
			"OffsetIndex contents were not flushed to disk because of `" + cause.getMessage() + "`.",
			"OffsetIndex contents were not flushed to disk!",
			cause
		);
	}

}
