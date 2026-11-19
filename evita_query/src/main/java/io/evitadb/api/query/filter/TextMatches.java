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
 * The search behaves as if only selected places were searchable, so it can match entities absent from plain search.
 *
 * **Syntax**
 *
 * ```evitaql
 * textMatches(
 *     argument:string!,
 *     filterConstraint:placeSelector*
 * )
 * ```
 *
 * **Arguments**
 *
 * - `argument:string!`: the text to search for, mandatory and non-blank
 * - `filterConstraint:placeSelector*`: optional selectors restricting attributes, associated data or references;
 *   without any child, every searchable place of the queried collection is searched
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
 *         relevanceText()
 *     )
 * )
 * ```
 *
 * ```evitaql
 * textMatches(
 *     "black leather jacket",
 *     inAttribute("name"),
 *     inAttribute("description")
 * )
 * ```
 *
 * **Constraints on usage**
 *
 * - the fulltext index is maintained per locale, so the query must declare its locale by `entityLocaleEquals`
 * - the constraint is forbidden inside {@link UserFilter}: it has no relaxation semantics a facet or histogram
 *   computation could peel off, and facet counts are expected to describe the search result
 * - only entity-level place selectors are accepted
 *
 * The constraint has no `attribute` prefix, because a searchable field is not necessarily an entity attribute.
 * The order of the matched entities is controlled by the {@link io.evitadb.api.query.order.RelevanceText} ordering
 * constraint, which reads the relevance this filter computes.
 *
 * @see AttributeIn
 * @see io.evitadb.api.query.order.RelevanceText
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@ConstraintDefinition(
	name = "textMatches",
	shortDescription = "The constraint matches entities whose searchable fields contain every word of the searched " +
		"text (fulltext search).",
	userDocsLink = "/documentation/query/filtering/fulltext#text-matches",
	supportedIn = { ConstraintDomain.ENTITY, ConstraintDomain.REFERENCE }
)
public class TextMatches extends AbstractFilterConstraintContainer implements GenericConstraint<FilterConstraint> {
	@Serial private static final long serialVersionUID = -6412785527930563170L;
	private static final String CONSTRAINT_NAME = "textMatches";
	private static final TextMatchesEntitySpecificationFilterConstraint[] NO_SELECTORS =
		new TextMatchesEntitySpecificationFilterConstraint[0];

	/**
	 * Creates a copy with new arguments or children.
	 *
	 * @param arguments the searched text
	 * @param in place selectors
	 */
	private TextMatches(@Nonnull Serializable[] arguments, @Nonnull FilterConstraint... in) {
		super(CONSTRAINT_NAME, arguments, in);
	}

	/**
	 * Creates the fulltext search constraint.
	 *
	 * @param text           the text to search for, must not be blank
	 * @param in optional selectors of the search, e.g. {@link AttributeIn} restrictions
	 */
	@Creator
	public TextMatches(
		@Nonnull String text,
		@Nullable @Child(domain = ConstraintDomain.TEXT, uniqueChildren = true)
		TextMatchesEntitySpecificationFilterConstraint... in
	) {
		super(
			CONSTRAINT_NAME,
			new Serializable[] { text },
			in == null ? NO_SELECTORS : in
		);
		Assert.isTrue(
			text != null && !text.isBlank(),
			"Text searched by the `textMatches` constraint must not be blank!"
		);
	}

	/**
	 * Returns the text to search for.
	 *
	 * @return the searched text
	 */
	@Nonnull
	public String getText() {
		return (String) getArguments()[0];
	}

	/**
	 * Returns all selectors of the search in the order they were specified.
	 *
	 * @return the place selectors
	 */
	@Nonnull
	public TextMatchesEntitySpecificationFilterConstraint[] getIn() {
		final FilterConstraint[] children = getChildren();
		final TextMatchesEntitySpecificationFilterConstraint[] result =
			new TextMatchesEntitySpecificationFilterConstraint[children.length];
		for (int i = 0; i < children.length; i++) {
			result[i] = (TextMatchesEntitySpecificationFilterConstraint) children[i];
		}
		return result;
	}

	/**
	 * The container is necessary even with a single child — flattening it would leave a bare `inAttribute` behind.
	 *
	 * @return whether the searched text is present, regardless of the number of selectors
	 */
	@Override
	public boolean isNecessary() {
		return isApplicable();
	}

	/**
	 * The container is applicable without children — no child means "search all searchable fields".
	 *
	 * @return whether the searched text argument is present
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
				child instanceof TextMatchesEntitySpecificationFilterConstraint,
				() -> "Constraint `textMatches` doesn't accept `" + child.getName() + "` as its child!"
			);
		}
		return new TextMatches(getArguments(), children);
	}
}
