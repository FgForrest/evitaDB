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

package io.evitadb.api.query.filter;

import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.GenericConstraint;
import io.evitadb.api.query.descriptor.ConstraintDomain;
import io.evitadb.api.query.descriptor.annotation.Child;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.Creator;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;

/**
 * The `textMatches` constraint is the fulltext search filter. It accepts the text the user typed into a search box and
 * matches entities whose searchable fields contain every word of that text — after the text has been analyzed by the
 * same analyzer that indexed the fields (tokenization, normalization, stemming).
 *
 * **Syntax**
 *
 * ```evitaql
 * textMatches(
 *     argument:string!,
 *     filterConstraint:inField*
 * )
 * ```
 *
 * **Arguments**
 *
 * - `argument:string!`: the text to search for, mandatory and non-blank
 * - `filterConstraint:inField*`: optional {@link InField} children restricting the search to the named searchable
 *   fields; without any child, every searchable field of the queried collection is searched
 *
 * **Examples**
 *
 * ```evitaql
 * query(
 *     collection("Product"),
 *     filterBy(
 *         entityLocaleEquals("en"),
 *         textMatches("black leather jacket")
 *     ),
 *     orderBy(
 *         relevance()
 *     )
 * )
 * ```
 *
 * ```evitaql
 * textMatches(
 *     "black leather jacket",
 *     inField("name"),
 *     inField("description")
 * )
 * ```
 *
 * **Constraints on usage**
 *
 * - the fulltext index is maintained per locale, so the query must declare its locale by `entityLocaleEquals`
 * - the constraint is forbidden inside {@link UserFilter}: it has no relaxation semantics a facet or histogram
 *   computation could peel off, and facet counts are expected to describe the search result
 * - only {@link TextMatchesSpecificationFilterConstraint} children (such as `inField`) are accepted
 *
 * The constraint has no `attribute` prefix, because a searchable field is not necessarily an entity attribute.
 * The order of the matched entities is controlled by the {@link io.evitadb.api.query.order.Relevance} ordering
 * constraint, which reads the relevance this filter computes.
 *
 * @see InField
 * @see io.evitadb.api.query.order.Relevance
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@ConstraintDefinition(
	name = "textMatches",
	shortDescription = "The constraint matches entities whose searchable fields contain every word of the searched " +
		"text (fulltext search).",
	userDocsLink = "/documentation/query/filtering/fulltext#text-matches",
	supportedIn = ConstraintDomain.ENTITY
)
public class TextMatches extends AbstractFilterConstraintContainer implements GenericConstraint<FilterConstraint> {
	@Serial private static final long serialVersionUID = -6412785527930563170L;
	private static final String CONSTRAINT_NAME = "textMatches";
	private static final TextMatchesSpecificationFilterConstraint[] NO_SPECIFICATIONS =
		new TextMatchesSpecificationFilterConstraint[0];

	private TextMatches(@Nonnull Serializable[] arguments, @Nonnull FilterConstraint... children) {
		super(CONSTRAINT_NAME, arguments, children);
	}

	/**
	 * Creates the fulltext search constraint.
	 *
	 * @param text           the text to search for, must not be blank
	 * @param specifications optional refinements of the search, e.g. {@link InField} restrictions
	 */
	@Creator
	public TextMatches(
		@Nonnull String text,
		@Nullable @Child TextMatchesSpecificationFilterConstraint... specifications
	) {
		super(
			CONSTRAINT_NAME,
			new Serializable[] { text },
			specifications == null ? NO_SPECIFICATIONS : specifications
		);
		Assert.isTrue(
			text != null && !text.isBlank(),
			"Text searched by the `textMatches` constraint must not be blank!"
		);
	}

	/**
	 * Returns the text to search for.
	 */
	@Nonnull
	public String getText() {
		return (String) getArguments()[0];
	}

	/**
	 * Returns all refinements of the search in the order they were specified.
	 */
	@Nonnull
	public TextMatchesSpecificationFilterConstraint[] getSpecifications() {
		final FilterConstraint[] children = getChildren();
		final TextMatchesSpecificationFilterConstraint[] result =
			new TextMatchesSpecificationFilterConstraint[children.length];
		for (int i = 0; i < children.length; i++) {
			result[i] = (TextMatchesSpecificationFilterConstraint) children[i];
		}
		return result;
	}

	/**
	 * Returns the {@link InField} restrictions of the search. An empty array means that every searchable field of the
	 * queried collection is searched.
	 */
	@Nonnull
	public InField[] getInFields() {
		final FilterConstraint[] children = getChildren();
		int count = 0;
		for (final FilterConstraint child : children) {
			if (child instanceof InField) {
				count++;
			}
		}
		final InField[] result = new InField[count];
		int index = 0;
		for (final FilterConstraint child : children) {
			if (child instanceof InField inField) {
				result[index++] = inField;
			}
		}
		return result;
	}

	/**
	 * The container is necessary even with a single child — flattening it would leave a bare `inField` behind.
	 */
	@Override
	public boolean isNecessary() {
		return isApplicable();
	}

	/**
	 * The container is applicable without children — no child means "search all searchable fields".
	 */
	@Override
	public boolean isApplicable() {
		final Serializable[] arguments = getArguments();
		return arguments.length == 1 && arguments[0] instanceof String;
	}

	@Nonnull
	@Override
	public FilterConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		Assert.isTrue(
			newArguments.length == 1 && newArguments[0] instanceof String,
			"Constraint `textMatches` requires exactly one argument of type String!"
		);
		return new TextMatches(newArguments, getChildren());
	}

	@Nonnull
	@Override
	public FilterConstraint getCopyWithNewChildren(
		@Nonnull FilterConstraint[] children,
		@Nonnull Constraint<?>[] additionalChildren
	) {
		Assert.isTrue(
			ArrayUtils.isEmpty(additionalChildren),
			"Constraint `textMatches` doesn't accept other than filtering constraints!"
		);
		for (final FilterConstraint child : children) {
			Assert.isTrue(
				child instanceof TextMatchesSpecificationFilterConstraint,
				() -> "Constraint `textMatches` doesn't accept `" + child.getName() + "` as its child!"
			);
		}
		return new TextMatches(getArguments(), children);
	}
}
