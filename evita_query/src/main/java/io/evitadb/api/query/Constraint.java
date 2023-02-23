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

package io.evitadb.api.query;

import javax.annotation.Nonnull;
import java.io.Serializable;

/**
 * Represents single unit of the query language. It could mean some filtering condition, ordering setting or any other
 * part of the query to be expressed.
 * <p>
 * All constraints are required to be immutable!
 *
 * @author Jan Novotný, FG Forrest a.s. (c) 2021
 */
public interface Constraint<T extends Constraint<T>> extends Serializable {
	String ARG_OPENING = "(";
	String ARG_CLOSING = ")";

	/**
	 * Returns query type name.
	 */
	@Nonnull
	String getName();

	/**
	 * Returns generic tape for verifications.
	 */
	@Nonnull
	Class<T> getType();

	/**
	 * Returns list of all query arguments.
	 */
	@Nonnull
	Serializable[] getArguments();

	/**
	 * Returns true if query has enough data to be used in query.
	 * False in case query has no sense - because it couldn't be processed in current state (for
	 * example significant arguments are missing or are invalid).
	 */
	boolean isApplicable();

	/**
	 * For purpose of this method look at GOF <a href="https://en.wikipedia.org/wiki/Visitor_pattern">visitor pattern</a>.
	 * Every query should call method visit on a visitor. Containers are NOT responsible for iterating
	 * over children and calling accept on them. Iterating is responsibility of the visitor implementation.
	 */
	void accept(@Nonnull ConstraintVisitor visitor);

	/**
	 * Reproduces representative String of this query.
	 * Should imitate standard string parser conversion.
	 */
	@Nonnull
	String toString();

	/**
	 * Creates clone of this query using new set of arguments.
	 */
	@Nonnull
	T cloneWithArguments(@Nonnull Serializable[] newArguments);

}
