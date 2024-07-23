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
import java.io.Serial;

/**
 * Exception is thrown when method that is expected to return either one or zero results matches multiple results.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class UnexpectedResultCountException extends EvitaInvalidUsageException {
	@Serial private static final long serialVersionUID = 4687397430088399013L;
	@Getter private final int matchedCount;

	public UnexpectedResultCountException(int matchedCount) {
		super("Call is expected to return either one or none record, but matched " + matchedCount + " records!");
		this.matchedCount = matchedCount;
	}

	public UnexpectedResultCountException(int matchedCount, @Nonnull String publicMessage) {
		super(publicMessage);
		this.matchedCount = matchedCount;
	}
}
