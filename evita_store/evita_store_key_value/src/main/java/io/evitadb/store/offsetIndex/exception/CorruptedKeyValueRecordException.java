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

package io.evitadb.store.offsetIndex.exception;

import io.evitadb.store.exception.StorageException;
import lombok.Getter;

import java.io.Serial;
import java.io.Serializable;

/**
 * This exception is used to swallow {@link CorruptedRecordException} and add data that will extend the error context
 * for the catcher.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class CorruptedKeyValueRecordException extends StorageException {
	@Serial private static final long serialVersionUID = -1397355818882319526L;
	@Getter private final Class<? extends Serializable> recordType;
	@Getter private final long primaryKey;

	public CorruptedKeyValueRecordException(String message, Class<? extends Serializable> recordType, long primaryKey, CorruptedRecordException cause) {
		super(message, cause);
		this.recordType = recordType;
		this.primaryKey = primaryKey;
	}
}
