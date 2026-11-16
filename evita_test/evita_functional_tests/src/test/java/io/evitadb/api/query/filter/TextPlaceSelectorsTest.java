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
import io.evitadb.api.query.Query;
import io.evitadb.api.query.parser.DefaultQueryParser;
import io.evitadb.api.query.parser.exception.EvitaSyntaxException;
import io.evitadb.api.query.visitor.ConstraintCloneVisitor;
import io.evitadb.api.query.visitor.QueryPurifierVisitor;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.utils.CollectionUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.FILTER;
import static io.evitadb.test.TestTags.QUERY;
import static org.junit.jupiter.api.Assertions.*;

/** Model, parser and normalization contracts of level-specific fulltext place selectors. */
@Tag(CONTRACT)
@Tag(FILTER)
@Tag(QUERY)
@DisplayName("Fulltext place selectors")
class TextPlaceSelectorsTest {
	/** All named, wildcard and container forms, including the semantic empty target boundary. */
	@Nonnull
	static Stream<FilterConstraint> forms() {
		return Stream.of(
			inAttribute("a", "b"), inAttributeAll(), inAssociatedData("a"), inAssociatedData("a", "b"),
			inAssociatedDataAll(), inReference("links"), inEntity(), inEntity(inAttributeAll()),
			inEntity(inAssociatedDataAll()), inEntity(inAttribute("a", "b"), inAssociatedData("a", "b")),
			inReference("links", inAttribute("a", "b")), inReference("links", inAttributeAll()),
			inReference("links", inEntity()),
			inReference("links", inAttributeAll(), inEntity(inAttributeAll(), inAssociatedDataAll())),
			textMatches("term", inAttributeAll(), inAssociatedDataAll(), inReference("links", inEntity()))
		);
	}

	/** Each legal representation survives parsing, purification, argument cloning and child copying. */
	@ParameterizedTest
	@MethodSource("forms")
	@DisplayName("Preserves every selector form through parsing and cloning")
	void shouldRoundTripEveryForm(@Nonnull FilterConstraint constraint) {
		final DefaultQueryParser parser = DefaultQueryParser.getInstance();
		final FilterConstraint parsed = parser.parseFilterConstraintUnsafe(constraint.toString()).getChildren()[0];
		assertEquals(constraint, parsed);
		assertEquals(parsed, parser.parseFilterConstraintUnsafe(parsed.toString()).getChildren()[0]);
		assertTrue(constraint.isApplicable());
		assertEquals(constraint, QueryPurifierVisitor.purify(constraint));
		assertEquals(constraint, ConstraintCloneVisitor.clone(constraint, (visitor, child) -> child));
		assertEquals(constraint, constraint.cloneWithArguments(constraint.getArguments()));
		if (constraint instanceof AbstractFilterConstraintContainer container) {
			assertTrue(container.isNecessary());
			assertEquals(constraint, container.getCopyWithNewChildren(container.getChildren(), new Constraint<?>[0]));
		}
	}

	/** Empty containers and wildcards cannot be dropped or flattened by full query normalization. */
	@Test
	@DisplayName("Preserves meaningful empty selectors through query normalization")
	void shouldNormalizeMeaningfulEmptySelectors() {
		final Query original = query(collection("Item"), filterBy(textMatches("term", inAttributeAll(),
			inAssociatedDataAll(), inReference("links"), inReference("blocks", inEntity()))));
		assertEquals(original, original.normalizeQuery());
		assertEquals(original, DefaultQueryParser.getInstance().parseQueryUnsafe(original.toString()));
		assertEquals("inAttributeAll()", inAttributeAll().toString());
		assertEquals("inAssociatedDataAll()", inAssociatedDataAll().toString());
		assertEquals("inReference('links')", inReference("links").toString());
		assertEquals("inEntity()", inEntity().toString());
	}

	/** Names retain all supplied values and validation refuses invalid later items instead of widening the search. */
	@Test
	@DisplayName("Validates every associated data and reference name")
	void shouldValidateNamesAndArguments() {
		assertArrayEquals(new String[]{"a", "b"}, inAssociatedData("a", "b").getAssociatedDataNames());
		assertEquals(inAssociatedData("b"), inAssociatedData("a").cloneWithArguments(new Serializable[]{"b"}));
		assertEquals(inAssociatedData("a"), new AssociatedDataIn("a"));
		assertThrows(EvitaInvalidUsageException.class, () -> new AssociatedDataIn());
		assertThrows(EvitaInvalidUsageException.class, () -> inAssociatedData());
		assertThrows(EvitaInvalidUsageException.class, () -> inAssociatedData((String[]) null));
		assertThrows(EvitaInvalidUsageException.class, () -> new AssociatedDataIn((String[]) null));
		for (final String invalid : new String[]{null, "", "  "}) {
			assertThrows(EvitaInvalidUsageException.class, () -> inAssociatedData("a", invalid));
			assertThrows(EvitaInvalidUsageException.class, () -> new AssociatedDataIn(invalid));
			assertThrows(EvitaInvalidUsageException.class, () -> inReference(invalid));
			assertThrows(EvitaInvalidUsageException.class, () -> new ReferenceIn(invalid));
			assertThrows(EvitaInvalidUsageException.class,
				() -> inReference("links").cloneWithArguments(new Serializable[]{invalid}));
			assertThrows(EvitaInvalidUsageException.class,
				() -> inAssociatedData("a").cloneWithArguments(new Serializable[]{"a", invalid}));
		}
		assertThrows(EvitaInvalidUsageException.class,
			() -> inAssociatedData("a").cloneWithArguments(new Serializable[0]));
		assertThrows(EvitaInvalidUsageException.class,
			() -> inAssociatedData("a").cloneWithArguments(new Serializable[]{1}));
		assertThrows(EvitaInvalidUsageException.class, () -> inEntity().cloneWithArguments(new Serializable[]{"a"}));
		assertThrows(EvitaInvalidUsageException.class,
			() -> inReference("links").cloneWithArguments(new Serializable[0]));
		assertEquals(inEntity(), new EntityIn((TextMatchesTargetSpecificationFilterConstraint[]) null));
		assertEquals(inReference("links"), new ReferenceIn("links",
			(TextMatchesReferenceSpecificationFilterConstraint[]) null));
	}

	/** The associated data wildcard has no names and is told apart from named selectors by its flag and suffix. */
	@Test
	@DisplayName("Exposes the wildcard flag and creator suffix of the associated data selector")
	void shouldExposeWildcardFlagAndSuffixOfAssociatedDataSelector() {
		final AssociatedDataIn wildcard = inAssociatedDataAll();
		assertTrue(wildcard.isAllRequested());
		assertArrayEquals(new String[0], wildcard.getAssociatedDataNames());
		assertEquals(Optional.of("all"), wildcard.getSuffixIfApplied());
		assertFalse(inAssociatedData("a").isAllRequested());
		assertEquals(Optional.empty(), inAssociatedData("a").getSuffixIfApplied());
		assertEquals(inAssociatedData("a"), wildcard.cloneWithArguments(new Serializable[]{"a"}));
		assertNotEquals(inAssociatedDataAll(), inAttributeAll());
	}

	/** Equality, which keeps memoized searches of different selections apart, covers the name and every child. */
	@Test
	@DisplayName("Distinguishes reference selectors by name and children")
	void shouldDistinguishReferenceSelectorsByNameAndChildren() {
		assertEquals("links", inReference("links").getReferenceName());
		assertNotEquals(inReference("links"), inReference("blocks"));
		assertNotEquals(inReference("links"), inReference("links", inEntity()));
		assertNotEquals(inReference("links", inAttribute("a")), inReference("links", inAttribute("b")));
		assertNotEquals(inReference("links", inEntity(inAttributeAll())),
			inReference("links", inEntity(inAssociatedDataAll())));
		assertEquals(inReference("links", inEntity(inAttributeAll())).hashCode(),
			inReference("links", inEntity(inAttributeAll())).hashCode());
	}

	/** Copy visitors must enforce the same level markers as public constructors. */
	@Test
	@DisplayName("Rejects children of the wrong selector level during copying")
	void shouldRejectWrongChildLevels() {
		assertThrows(EvitaInvalidUsageException.class, () -> inEntity().getCopyWithNewChildren(
			new FilterConstraint[]{inReference("links")}, new Constraint<?>[0]));
		assertThrows(EvitaInvalidUsageException.class, () -> inReference("links").getCopyWithNewChildren(
			new FilterConstraint[]{inAssociatedDataAll()}, new Constraint<?>[0]));
		assertThrows(EvitaInvalidUsageException.class, () -> textMatches("term").getCopyWithNewChildren(
			new FilterConstraint[]{inEntity()}, new Constraint<?>[0]));
		assertThrows(EvitaInvalidUsageException.class, () -> inEntity().getCopyWithNewChildren(
			new FilterConstraint[0], new Constraint<?>[]{page(1, 10)}));
		assertThrows(EvitaInvalidUsageException.class, () -> inReference("links").getCopyWithNewChildren(
			new FilterConstraint[0], new Constraint<?>[]{page(1, 10)}));
	}

	/** The EvitaQL visitor enforces name validation and the same level markers as Java. */
	@ParameterizedTest
	@ValueSource(strings = {
		"inAttribute()", "inAssociatedData()", "inAssociatedData('')", "inAssociatedData('a',' ')",
		"inReference()", "inReference('')", "inReference(' ')", "inReference(1)", "inEntity('a')",
		"inAttributeAll('a')", "inAssociatedDataAll('a')", "textMatches('term',inEntity())",
		"inReference('links',inAssociatedDataAll())", "inEntity(inReference('links'))",
		"inEntity(inEntity())", "inReference('links',attributeEquals('a','b'))"
	})
	@DisplayName("Refuses invalid selector syntax and nesting")
	void shouldRejectInvalidSyntax(@Nonnull String input) {
		assertThrows(EvitaSyntaxException.class,
			() -> DefaultQueryParser.getInstance().parseFilterConstraintUnsafe(input));
	}

	/** Bound arrays must retain invalid nulls so they cannot silently become wildcard selectors. */
	@Test
	@DisplayName("Refuses null, blank and empty bound names")
	void shouldRejectInvalidBoundNames() {
		final DefaultQueryParser parser = DefaultQueryParser.getInstance();
		for (final Object invalid :
			new Object[]{null, new String[0], new String[]{"a", null}, new String[]{"a", " "}}) {
			final Map<String, Object> bindings = CollectionUtils.createHashMap(1);
			bindings.put("names", invalid);
			assertThrows(EvitaSyntaxException.class,
				() -> parser.parseFilterConstraint("inAssociatedData(@names)", bindings));
			assertThrows(EvitaSyntaxException.class,
				() -> parser.parseFilterConstraint("inAssociatedData(?)", new Object[]{invalid}));
		}
		assertThrows(EvitaSyntaxException.class,
			() -> parser.parseFilterConstraint("inReference(?)", new Object[]{null}));
		assertEquals(inReference("links", inEntity(inAssociatedData("a", "b"))),
			parser.parseFilterConstraint("inReference(?,inEntity(inAssociatedData(?)))", "links",
				new String[]{"a", "b"}).getChildren()[0]);
	}
}
