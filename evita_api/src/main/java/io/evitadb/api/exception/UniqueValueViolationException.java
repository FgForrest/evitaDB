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

import io.evitadb.exception.EvitaInvalidUsageException;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.Locale;

/**
 * Exception is thrown when there is attempt to index entity with conflicting attribute which violates unique query.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2020
 */
public class UniqueValueViolationException extends EvitaInvalidUsageException {
	@Serial private static final long serialVersionUID = -3516490780028476047L;
	@Getter private final String attributeName;
	@Getter private final Serializable value;
	@Getter private final String existingRecordType;
	@Getter private final int existingRecordId;
	@Getter private final String newRecordType;
	@Getter private final int newRecordId;

	public UniqueValueViolationException(
		@Nonnull String attributeName,
		@Nullable Locale locale,
		@Nonnull Serializable value,
		@Nonnull String existingRecordType,
		int existingRecordId,
		@Nonnull String newRecordType,
		int newRecordId
	) {
		super(
			"Unique constraint violation: attribute `" + attributeName + "` value " + value + "`" + (locale == null ? "" : " in locale `" + locale.toLanguageTag() + "`") +
				" is already present for entity `" + existingRecordType + "` (existing entity PK: " + existingRecordId + ", " +
				"newly inserted " + (existingRecordType.compareTo(newRecordType) == 0 ? "" : "`" + newRecordType + "`") + " entity PK: " + newRecordId + ")!"
		);
		this.attributeName = attributeName;
		this.value = value;
		this.existingRecordId = existingRecordId;
		this.existingRecordType = existingRecordType;
		this.newRecordId = newRecordId;
		this.newRecordType = newRecordType;
	}
}
