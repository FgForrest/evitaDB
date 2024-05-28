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
import io.evitadb.utils.ArrayUtils;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.io.Serial;

/**
 * Exception is thrown when there is attempt to query entity localized attributes or associated data without providing
 * a locale information.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2020
 */
public class EntityLocaleMissingException extends EvitaInvalidUsageException {
	@Serial private static final long serialVersionUID = -252413231677437811L;
	@Getter private final String[] attributeNames;
	@Getter private final String[] associatedDataNames;

	public EntityLocaleMissingException(
		@Nonnull String... attributeNames
	) {
		super(
			"Query requires localized attributes: `" + String.join(", ", attributeNames) + "`" +
			", and doesn't provide information about the locale!"
		);
		this.attributeNames = attributeNames;
		this.associatedDataNames = new String[0];
	}

	public EntityLocaleMissingException(
		@Nonnull String[] attributeNames,
		@Nonnull String[] associatedDataNames
	) {
		super(
			"Query requires localized " +
				(ArrayUtils.isEmpty(attributeNames) ? "" : "attributes: `" + String.join(", ", attributeNames) + "`") +
				(ArrayUtils.isEmpty(attributeNames) || ArrayUtils.isEmpty(associatedDataNames) ? "" : " and ") +
				(ArrayUtils.isEmpty(associatedDataNames) ? "" : "associated data: `" + String.join(", ", associatedDataNames) + "`") +
				", and doesn't provide information about the locale!"
		);
		this.attributeNames = attributeNames;
		this.associatedDataNames = associatedDataNames;
	}

}
