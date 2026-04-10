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

package io.evitadb.api.query.require;

import io.evitadb.api.query.ConstraintLeaf;
import io.evitadb.api.query.ConstraintVisitor;
import io.evitadb.api.query.RequireConstraint;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;

/**
 * Base class for all leaf-level require constraints in the EvitaQL require constraint hierarchy. Leaf constraints
 * carry arguments but have no child constraints — they are the terminal nodes of the constraint tree.
 *
 * Require constraints direct the query engine to fetch specific data or compute additional result structures.
 * This abstract class handles the common plumbing: it wires the `RequireConstraint` type token into the
 * `ConstraintLeaf` base, always reports itself as applicable (unlike container constraints that may become
 * inapplicable when all children are stripped), and dispatches to the visitor without recursing into children
 * (since leaves have none).
 *
 * Concrete subclasses include, for example:
 * - {@link AttributeContent} — requests named attributes to be fetched for each entity
 * - {@link AssociatedDataContent} — requests associated-data blobs to be loaded
 * - {@link PriceContent} — controls which price records are included in the response
 * - {@link DataInLocales} — specifies the locales for which localized data should be fetched
 * - {@link Page} and {@link Strip} — pagination / offset-limit chunking constraints
 * - {@link HierarchyStatistics} — requests per-node statistics for hierarchy output
 *
 * All subclasses are immutable. The protected `concat` helper method is provided for constructors that need to
 * prepend a mandatory leading argument to a varargs tail (a common pattern in require constraint constructors).
 *
 * @author Jan Novotný, FG Forrest a.s. (c) 2021
 */
abstract class AbstractRequireConstraintLeaf extends ConstraintLeaf<RequireConstraint> implements RequireConstraint {
	@Serial private static final long serialVersionUID = -8275346133230475050L;

	protected AbstractRequireConstraintLeaf(@Nonnull String name, @Nonnull Serializable... arguments) {
		super(name, arguments);
	}

	protected AbstractRequireConstraintLeaf(@Nonnull Serializable... arguments) {
		super(arguments);
	}

	@Nonnull
	@Override
	public Class<RequireConstraint> getType() {
		return RequireConstraint.class;
	}

	@Override
	public boolean isApplicable() {
		return true;
	}

	@Override
	public void accept(@Nonnull ConstraintVisitor visitor) {
		visitor.visit(this);
	}

	/**
	 * Helper method for creating serializable arrays by concatenating a first argument with an array of rest arguments.
	 *
	 * @param firstArg the first argument to be placed at the beginning of the array
	 * @param rest the remaining arguments to be appended after the first argument
	 * @param <T> the type of the arguments, must extend Serializable
	 * @return a new Serializable array containing the first argument followed by the rest arguments
	 */
	@Nonnull
	protected static <T extends Serializable> Serializable[] concat(@Nonnull T firstArg, @Nonnull T[] rest) {
		final Serializable[] result = new Serializable[rest.length + 1];
		result[0] = firstArg;
		System.arraycopy(rest, 0, result, 1, rest.length);
		return result;
	}

}
