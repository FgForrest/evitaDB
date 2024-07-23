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

import java.io.Serial;

/**
 * Exception is thrown when new reference is set on entity without name and cardinality specification that are expected
 * to be looked up in the reference schema. Unfortunately, reference schema of such name is not present in entity schema.
 * This would otherwise lead to new automatic reference schema setup, but this automatic setup requires to know
 * the reference name and cardinality in order to succeed.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class ReferenceNotKnownException extends EvitaInvalidUsageException {
	@Serial private static final long serialVersionUID = -7527988031906578241L;
	@Getter private final String referenceName;

	public ReferenceNotKnownException(String referenceName) {
		super("Reference schema for name `" + referenceName + "` doesn't exist." +
			" Use method that specifies target entity type and cardinality instead!");
		this.referenceName = referenceName;
	}
}
