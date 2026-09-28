/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

package io.evitadb.core.exception;

import io.evitadb.exception.EvitaInvalidUsageException;

import javax.annotation.Nonnull;
import java.io.Serial;

/**
 * Exception is thrown when a query needs an index family that the reference is indexed without in a queried scope -
 * the reduced entity indexes of `REFERENCED_ENTITY`, or the reduced group indexes of `REFERENCED_GROUP_ENTITY`.
 *
 * The reference reports itself indexed in such a scope, so without the refusal the query would read an index that
 * was never built and answer with an empty result indistinguishable from a genuine one. The schema rule refusing a
 * scope indexed without `REFERENCED_ENTITY` runs only when a schema changes, so a catalog stored before the rule
 * existed still reaches this exception.
 *
 * It has its own type, rather than being a plain {@link EvitaInvalidUsageException}, because one caller must not
 * propagate it: the index maintenance of conditional facets and histograms evaluates its conditions through the query
 * engine while an entity is being written, and a refusal meant for a query must not abort that write.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class ReferenceComponentNotIndexedException extends EvitaInvalidUsageException {
	@Serial private static final long serialVersionUID = -6840802897230402393L;

	public ReferenceComponentNotIndexedException(@Nonnull String message) {
		super(message);
	}

}
