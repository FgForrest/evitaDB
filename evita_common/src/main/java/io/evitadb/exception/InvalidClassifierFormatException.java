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

package io.evitadb.exception;

import io.evitadb.dataType.ClassifierType;
import io.evitadb.utils.ClassifierUtils;

import javax.annotation.Nonnull;
import java.io.Serial;

/**
 * States that passed classifier (entity type, attribute name, reference name, ...) has invalid format defined by
 * {@link ClassifierUtils#validateClassifierFormat(ClassifierType, String)}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public class InvalidClassifierFormatException extends EvitaInvalidUsageException {

	@Serial private static final long serialVersionUID = 3893469163249831754L;

	public InvalidClassifierFormatException(@Nonnull ClassifierType classifierType, @Nonnull String classifier, @Nonnull String reason) {
		super(classifierType.humanReadableName() + " `" + classifier + "` has invalid format. Reason: " + reason + ".");
	}
}
