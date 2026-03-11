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

package io.evitadb.core.expression.query;

import io.evitadb.api.query.expression.ExpressionFactory;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.dataType.expression.Expression;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;

import static io.evitadb.api.query.QueryConstraints.and;
import static io.evitadb.api.query.QueryConstraints.attributeEquals;
import static io.evitadb.api.query.QueryConstraints.attributeGreaterThan;
import static io.evitadb.api.query.QueryConstraints.attributeGreaterThanEquals;
import static io.evitadb.api.query.QueryConstraints.attributeLessThan;
import static io.evitadb.api.query.QueryConstraints.attributeLessThanEquals;
import static io.evitadb.api.query.QueryConstraints.entityHaving;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.api.query.QueryConstraints.groupHaving;
import static io.evitadb.api.query.QueryConstraints.not;
import static io.evitadb.api.query.QueryConstraints.or;
import static io.evitadb.api.query.QueryConstraints.referenceHaving;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ExpressionToQueryTranslator}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("ExpressionToQueryTranslator")
class ExpressionToQueryTranslatorTest {

	private static final String REF_NAME = "refName";

	// --- Happy path: entity attribute comparisons ---

	@Test
	@DisplayName("$entity.attributes['status'] == 'ACTIVE' -> filterBy(attributeEquals(\"status\", \"ACTIVE\"))")
	void shouldTranslateEntityAttributeEqualsString() {
		final FilterBy result = translate("$entity.attributes['status'] == 'ACTIVE'");
		assertEquals(
			filterBy(attributeEquals("status", "ACTIVE")),
			result
		);
	}

	@Test
	@DisplayName("$entity.attributes['isActive'] == true -> filterBy(attributeEquals(\"isActive\", true))")
	void shouldTranslateEntityAttributeEqualsBooleanTrue() {
		final FilterBy result = translate("$entity.attributes['isActive'] == true");
		assertEquals(
			filterBy(attributeEquals("isActive", true)),
			result
		);
	}

	@Test
	@DisplayName("$entity.attributes['isActive'] == false -> filterBy(attributeEquals(\"isActive\", false))")
	void shouldTranslateEntityAttributeEqualsBooleanFalse() {
		final FilterBy result = translate("$entity.attributes['isActive'] == false");
		assertEquals(
			filterBy(attributeEquals("isActive", false)),
			result
		);
	}

	@Test
	@DisplayName("$entity.attributes['priority'] == 1 -> filterBy(attributeEquals(\"priority\", 1L))")
	void shouldTranslateEntityAttributeEqualsInteger() {
		final FilterBy result = translate("$entity.attributes['priority'] == 1");
		assertEquals(
			filterBy(attributeEquals("priority", 1L)),
			result
		);
	}

	@Test
	@DisplayName("$entity.attributes['price'] > 100 -> filterBy(attributeGreaterThan(\"price\", 100L))")
	void shouldTranslateEntityAttributeGreaterThan() {
		final FilterBy result = translate("$entity.attributes['price'] > 100");
		assertEquals(
			filterBy(attributeGreaterThan("price", 100L)),
			result
		);
	}

	@Test
	@DisplayName("$entity.attributes['price'] >= 100 -> filterBy(attributeGreaterThanEquals(\"price\", 100L))")
	void shouldTranslateEntityAttributeGreaterThanEquals() {
		final FilterBy result = translate("$entity.attributes['price'] >= 100");
		assertEquals(
			filterBy(attributeGreaterThanEquals("price", 100L)),
			result
		);
	}

	@Test
	@DisplayName("$entity.attributes['price'] < 100 -> filterBy(attributeLessThan(\"price\", 100L))")
	void shouldTranslateEntityAttributeLessThan() {
		final FilterBy result = translate("$entity.attributes['price'] < 100");
		assertEquals(
			filterBy(attributeLessThan("price", 100L)),
			result
		);
	}

	@Test
	@DisplayName("$entity.attributes['price'] <= 100 -> filterBy(attributeLessThanEquals(\"price\", 100L))")
	void shouldTranslateEntityAttributeLessThanEquals() {
		final FilterBy result = translate("$entity.attributes['price'] <= 100");
		assertEquals(
			filterBy(attributeLessThanEquals("price", 100L)),
			result
		);
	}

	// --- Happy path: Expression and NestedOperator unwrapping ---

	@Test
	@DisplayName("($entity.attributes['isActive'] == true) -> same as non-parenthesized")
	void shouldTranslateParenthesizedExpression() {
		final FilterBy result = translate("($entity.attributes['isActive'] == true)");
		assertEquals(
			filterBy(attributeEquals("isActive", true)),
			result
		);
	}

	@Test
	@DisplayName("(($entity.attributes['isActive'] == true)) -> same as non-parenthesized")
	void shouldTranslateDoubleParenthesizedExpression() {
		final FilterBy result = translate("(($entity.attributes['isActive'] == true))");
		assertEquals(
			filterBy(attributeEquals("isActive", true)),
			result
		);
	}

	// --- Happy path: reversed operand order ---

	@Test
	@DisplayName("'ACTIVE' == $entity.attributes['status'] -> same as path-on-left")
	void shouldTranslateReversedOperandOrderConstantOnLeft() {
		final FilterBy result = translate("'ACTIVE' == $entity.attributes['status']");
		assertEquals(
			filterBy(attributeEquals("status", "ACTIVE")),
			result
		);
	}

	@Test
	@DisplayName("100 < $entity.attributes['price'] -> filterBy(attributeGreaterThan(\"price\", 100L))")
	void shouldTranslateReversedOperandOrderForComparison() {
		// 100 < price  ===  price > 100
		final FilterBy result = translate("100 < $entity.attributes['price']");
		assertEquals(
			filterBy(attributeGreaterThan("price", 100L)),
			result
		);
	}

	// --- Happy path: NotEquals ---

	@Test
	@DisplayName("$entity.attributes['status'] != 'DELETED' -> filterBy(not(attributeEquals(\"status\", \"DELETED\")))")
	void shouldTranslateEntityAttributeNotEqualsString() {
		final FilterBy result = translate("$entity.attributes['status'] != 'DELETED'");
		assertEquals(
			filterBy(not(attributeEquals("status", "DELETED"))),
			result
		);
	}

	@Test
	@DisplayName("$entity.attributes['priority'] != 1 -> filterBy(not(attributeEquals(\"priority\", 1L)))")
	void shouldTranslateEntityAttributeNotEqualsInteger() {
		final FilterBy result = translate("$entity.attributes['priority'] != 1");
		assertEquals(
			filterBy(not(attributeEquals("priority", 1L))),
			result
		);
	}

	@Test
	@DisplayName("'DELETED' != $entity.attributes['status'] -> same as path-on-left")
	void shouldTranslateReversedNotEquals() {
		final FilterBy result = translate("'DELETED' != $entity.attributes['status']");
		assertEquals(
			filterBy(not(attributeEquals("status", "DELETED"))),
			result
		);
	}

	// --- Happy path: Inverse (!) ---

	@Test
	@DisplayName("!($entity.attributes['isActive'] == true) -> filterBy(not(attributeEquals(\"isActive\", true)))")
	void shouldTranslateInverseOfEntityAttributeEquals() {
		final FilterBy result = translate("!($entity.attributes['isActive'] == true)");
		assertEquals(
			filterBy(not(attributeEquals("isActive", true))),
			result
		);
	}

	@Test
	@DisplayName("!!($entity.attributes['isActive'] == true) -> filterBy(not(not(attributeEquals(...))))")
	void shouldTranslateDoubleNegation() {
		final FilterBy result = translate("!!($entity.attributes['isActive'] == true)");
		assertEquals(
			filterBy(not(not(attributeEquals("isActive", true)))),
			result
		);
	}

	// --- Happy path: Boolean operators (&&, ||) with flattening ---

	@Test
	@DisplayName("a == 1 && b == 2 -> filterBy(and(eq(a,1L), eq(b,2L)))")
	void shouldTranslateConjunctionOfTwoAttributes() {
		final FilterBy result = translate(
			"$entity.attributes['a'] == 1 && $entity.attributes['b'] == 2"
		);
		assertEquals(
			filterBy(and(attributeEquals("a", 1L), attributeEquals("b", 2L))),
			result
		);
	}

	@Test
	@DisplayName("a == 1 || b == 2 -> filterBy(or(eq(a,1L), eq(b,2L)))")
	void shouldTranslateDisjunctionOfTwoAttributes() {
		final FilterBy result = translate(
			"$entity.attributes['a'] == 1 || $entity.attributes['b'] == 2"
		);
		assertEquals(
			filterBy(or(attributeEquals("a", 1L), attributeEquals("b", 2L))),
			result
		);
	}

	@Test
	@DisplayName("a == 1 && b == 2 && c == 3 -> flat and(eq(a), eq(b), eq(c))")
	void shouldTranslateConjunctionOfThreeAttributesFlattened() {
		final FilterBy result = translate(
			"$entity.attributes['a'] == 1 && $entity.attributes['b'] == 2 "
				+ "&& $entity.attributes['c'] == 3"
		);
		assertEquals(
			filterBy(and(
				attributeEquals("a", 1L),
				attributeEquals("b", 2L),
				attributeEquals("c", 3L)
			)),
			result
		);
	}

	@Test
	@DisplayName("a == 1 || b == 2 || c == 3 -> flat or(eq(a), eq(b), eq(c))")
	void shouldTranslateDisjunctionOfThreeAttributesFlattened() {
		final FilterBy result = translate(
			"$entity.attributes['a'] == 1 || $entity.attributes['b'] == 2 "
				+ "|| $entity.attributes['c'] == 3"
		);
		assertEquals(
			filterBy(or(
				attributeEquals("a", 1L),
				attributeEquals("b", 2L),
				attributeEquals("c", 3L)
			)),
			result
		);
	}

	@Test
	@DisplayName("a == 1 || (b == 2 && c == 3) -> or(eq(a), and(eq(b), eq(c))) — no cross-type flattening")
	void shouldTranslateMixedBooleanWithoutCrossFlattening() {
		final FilterBy result = translate(
			"$entity.attributes['a'] == 1 || "
				+ "($entity.attributes['b'] == 2 && $entity.attributes['c'] == 3)"
		);
		assertEquals(
			filterBy(or(
				attributeEquals("a", 1L),
				and(attributeEquals("b", 2L), attributeEquals("c", 3L))
			)),
			result
		);
	}

	@Test
	@DisplayName("(a == 1 && b == 2) && c == 3 -> flat and(eq(a), eq(b), eq(c)) — flattens through parens")
	void shouldTranslateParenthesizedConjunctionFlattened() {
		final FilterBy result = translate(
			"($entity.attributes['a'] == 1 && $entity.attributes['b'] == 2) "
				+ "&& $entity.attributes['c'] == 3"
		);
		assertEquals(
			filterBy(and(
				attributeEquals("a", 1L),
				attributeEquals("b", 2L),
				attributeEquals("c", 3L)
			)),
			result
		);
	}

	// --- Happy path: reference attribute comparisons ---

	@Test
	@DisplayName("$reference.attributes['priority'] == 1 -> referenceHaving(refName, attributeEquals(...))")
	void shouldTranslateReferenceAttributeEquals() {
		final FilterBy result = translate("$reference.attributes['priority'] == 1");
		assertEquals(
			filterBy(referenceHaving(REF_NAME, attributeEquals("priority", 1L))),
			result
		);
	}

	@Test
	@DisplayName("$reference.attributes['order'] > 5 -> referenceHaving(refName, attributeGreaterThan(...))")
	void shouldTranslateReferenceAttributeGreaterThan() {
		final FilterBy result = translate("$reference.attributes['order'] > 5");
		assertEquals(
			filterBy(referenceHaving(REF_NAME, attributeGreaterThan("order", 5L))),
			result
		);
	}

	@Test
	@DisplayName("$reference.attributes['priority'] != 0 -> referenceHaving(refName, not(attributeEquals(...)))")
	void shouldTranslateReferenceAttributeNotEquals() {
		final FilterBy result = translate("$reference.attributes['priority'] != 0");
		assertEquals(
			filterBy(referenceHaving(REF_NAME, not(attributeEquals("priority", 0L)))),
			result
		);
	}

	// --- Happy path: group entity attribute comparisons ---

	@Test
	@DisplayName("$ref.groupEntity?.attributes['status'] == 'ACTIVE' -> referenceHaving(groupHaving(eq(...)))")
	void shouldTranslateGroupEntityAttributeEquals() {
		final FilterBy result = translate("$reference.groupEntity?.attributes['status'] == 'ACTIVE'");
		assertEquals(
			filterBy(referenceHaving(REF_NAME, groupHaving(attributeEquals("status", "ACTIVE")))),
			result
		);
	}

	@Test
	@DisplayName("$reference.groupEntity?.attributes['priority'] > 0 -> referenceHaving(refName, groupHaving(gt(...)))")
	void shouldTranslateGroupEntityAttributeGreaterThan() {
		final FilterBy result = translate("$reference.groupEntity?.attributes['priority'] > 0");
		assertEquals(
			filterBy(referenceHaving(REF_NAME, groupHaving(attributeGreaterThan("priority", 0L)))),
			result
		);
	}

	// --- Happy path: referenced entity attribute comparisons ---

	@Test
	@DisplayName("$ref.referencedEntity.attributes['status'] == 'PREVIEW' -> referenceHaving(entityHaving(eq(...)))")
	void shouldTranslateReferencedEntityAttributeEquals() {
		final FilterBy result = translate("$reference.referencedEntity.attributes['status'] == 'PREVIEW'");
		assertEquals(
			filterBy(referenceHaving(REF_NAME, entityHaving(attributeEquals("status", "PREVIEW")))),
			result
		);
	}

	@Test
	@DisplayName("$reference.referencedEntity.attributes['rank'] < 50 -> referenceHaving(refName, entityHaving(lt(...)))")
	void shouldTranslateReferencedEntityAttributeLessThan() {
		final FilterBy result = translate("$reference.referencedEntity.attributes['rank'] < 50");
		assertEquals(
			filterBy(referenceHaving(REF_NAME, entityHaving(attributeLessThan("rank", 50L)))),
			result
		);
	}

	// --- Happy path: cross-entity boolean expressions ---

	@Test
	@DisplayName("WBS example: group OR referenced entity -> each wrapped in referenceHaving independently")
	void shouldTranslateCrossEntityOr() {
		final FilterBy result = translate(
			"$reference.groupEntity?.attributes['status'] == 'ACTIVE' "
				+ "|| $reference.referencedEntity.attributes['status'] == 'PREVIEW'"
		);
		assertEquals(
			filterBy(or(
				referenceHaving(REF_NAME, groupHaving(attributeEquals("status", "ACTIVE"))),
				referenceHaving(REF_NAME, entityHaving(attributeEquals("status", "PREVIEW")))
			)),
			result
		);
	}

	@Test
	@DisplayName("WBS example: reference AND entity attribute -> and(referenceHaving(...), eq(...))")
	void shouldTranslateMixedReferenceAndEntityAttribute() {
		final FilterBy result = translate(
			"$reference.groupEntity?.attributes['inputWidgetType'] == 'CHECKBOX' "
				+ "&& $entity.attributes['isActive'] == true"
		);
		assertEquals(
			filterBy(and(
				referenceHaving(REF_NAME, groupHaving(attributeEquals("inputWidgetType", "CHECKBOX"))),
				attributeEquals("isActive", true)
			)),
			result
		);
	}

	@Test
	@DisplayName("reference attributes in AND -> merged into single referenceHaving(refName, and(...))")
	void shouldTranslateReferenceAttributesInAndMerged() {
		final FilterBy result = translate(
			"$reference.attributes['a'] == 1 && $reference.attributes['b'] == 2"
		);
		assertEquals(
			filterBy(referenceHaving(REF_NAME, and(attributeEquals("a", 1L), attributeEquals("b", 2L)))),
			result
		);
	}

	// --- Happy path: referenceHaving merging in AND context ---

	@Test
	@DisplayName("three reference attributes in AND -> single referenceHaving(refName, and(a, b, c))")
	void shouldTranslateThreeReferenceAttributesInAndMerged() {
		final FilterBy result = translate(
			"$reference.attributes['a'] == 1 && $reference.attributes['b'] == 2 "
				+ "&& $reference.attributes['c'] == 3"
		);
		assertEquals(
			filterBy(referenceHaving(REF_NAME, and(
				attributeEquals("a", 1L), attributeEquals("b", 2L), attributeEquals("c", 3L)
			))),
			result
		);
	}

	@Test
	@DisplayName("mixed reference + entity in AND -> merge reference, leave entity separate")
	void shouldTranslateMixedReferenceAndEntityInAndMerged() {
		final FilterBy result = translate(
			"$reference.attributes['a'] == 1 && $entity.attributes['b'] == 2 "
				+ "&& $reference.attributes['c'] == 3"
		);
		assertEquals(
			filterBy(and(
				attributeEquals("b", 2L),
				referenceHaving(REF_NAME, and(attributeEquals("a", 1L), attributeEquals("c", 3L)))
			)),
			result
		);
	}

	@Test
	@DisplayName("groupHaving + reference attribute in AND -> merge under single referenceHaving")
	void shouldTranslateGroupAndReferenceAttributeInAndMerged() {
		final FilterBy result = translate(
			"$reference.groupEntity?.attributes['status'] == 'ACTIVE' "
				+ "&& $reference.attributes['priority'] == 1"
		);
		assertEquals(
			filterBy(referenceHaving(REF_NAME, and(
				groupHaving(attributeEquals("status", "ACTIVE")),
				attributeEquals("priority", 1L)
			))),
			result
		);
	}

	@Test
	@DisplayName("reference attributes in OR -> NO merging, separate referenceHaving nodes")
	void shouldNotMergeReferenceHavingInOr() {
		final FilterBy result = translate(
			"$reference.attributes['a'] == 1 || $reference.attributes['b'] == 2"
		);
		assertEquals(
			filterBy(or(
				referenceHaving(REF_NAME, attributeEquals("a", 1L)),
				referenceHaving(REF_NAME, attributeEquals("b", 2L))
			)),
			result
		);
	}

	@Test
	@DisplayName("negated reference in AND -> NO merging for negated operand")
	void shouldNotMergeNegatedReferenceHaving() {
		final FilterBy result = translate(
			"!($reference.attributes['a'] == 1) && $reference.attributes['b'] == 2"
		);
		assertEquals(
			filterBy(and(
				not(referenceHaving(REF_NAME, attributeEquals("a", 1L))),
				referenceHaving(REF_NAME, attributeEquals("b", 2L))
			)),
			result
		);
	}

	// --- Happy path: edge cases ---

	@Test
	@DisplayName("attribute name with underscores and digits passes through as-is")
	void shouldTranslateAttributeNameWithSpecialCharacters() {
		final FilterBy result = translate("$entity.attributes['myAttr_v2'] == 1");
		assertEquals(
			filterBy(attributeEquals("myAttr_v2", 1L)),
			result
		);
	}

	@Test
	@DisplayName("string value with underscores passes through unchanged")
	void shouldTranslateStringValueWithSpecialCharacters() {
		final FilterBy result = translate("$entity.attributes['status'] == 'ACTIVE_v2'");
		assertEquals(
			filterBy(attributeEquals("status", "ACTIVE_v2")),
			result
		);
	}

	@Test
	@DisplayName("numeric constant 42 is preserved as Long in the constraint")
	void shouldTranslateNumericValueAsLong() {
		final FilterBy result = translate("$entity.attributes['count'] == 42");
		assertEquals(
			filterBy(attributeEquals("count", 42L)),
			result
		);
	}

	// --- Missing happy-path tests from WBS spec ---

	@Test
	@DisplayName("$reference.attributes['weight'] <= 10 -> referenceHaving(refName, attributeLessThanEquals(...))")
	void shouldTranslateReferenceAttributeLessThanEquals() {
		final FilterBy result = translate("$reference.attributes['weight'] <= 10");
		assertEquals(
			filterBy(referenceHaving(REF_NAME, attributeLessThanEquals("weight", 10L))),
			result
		);
	}

	@Test
	@DisplayName("!($entity.attributes['a'] == 1 || $entity.attributes['b'] == 2) -> not(or(eq(a), eq(b)))")
	void shouldTranslateNegationOfDisjunction() {
		final FilterBy result = translate(
			"!($entity.attributes['a'] == 1 || $entity.attributes['b'] == 2)"
		);
		assertEquals(
			filterBy(not(or(attributeEquals("a", 1L), attributeEquals("b", 2L)))),
			result
		);
	}

	@Test
	@DisplayName("(a == 1 || b == 2) && c == 3 -> and(or(eq(a), eq(b)), eq(c)) — no cross-flattening")
	void shouldTranslateOrInsideAndWithoutCrossFlattening() {
		final FilterBy result = translate(
			"($entity.attributes['a'] == 1 || $entity.attributes['b'] == 2) "
				+ "&& $entity.attributes['c'] == 3"
		);
		assertEquals(
			filterBy(and(
				or(attributeEquals("a", 1L), attributeEquals("b", 2L)),
				attributeEquals("c", 3L)
			)),
			result
		);
	}

	@Test
	@DisplayName("!(($entity.attributes['a'] == 1 && $entity.attributes['b'] == 2)) -> not(and(eq(a), eq(b)))")
	void shouldTranslateNestedNotWithAnd() {
		final FilterBy result = translate(
			"!(($entity.attributes['a'] == 1 && $entity.attributes['b'] == 2))"
		);
		assertEquals(
			filterBy(not(and(attributeEquals("a", 1L), attributeEquals("b", 2L)))),
			result
		);
	}

	@Test
	@DisplayName("three path types in AND -> all combined under single and()")
	void shouldTranslateComplexThreePathConjunction() {
		final FilterBy result = translate(
			"$entity.attributes['isActive'] == true "
				+ "&& $reference.groupEntity?.attributes['status'] == 'ACTIVE' "
				+ "&& $reference.referencedEntity.attributes['visible'] == true"
		);
		assertEquals(
			filterBy(and(
				attributeEquals("isActive", true),
				referenceHaving(REF_NAME, and(
					groupHaving(attributeEquals("status", "ACTIVE")),
					entityHaving(attributeEquals("visible", true))
				))
			)),
			result
		);
	}

	@Test
	@DisplayName("entity attribute + negated cross-entity attribute -> and(eq(...), not(referenceHaving(...)))")
	void shouldTranslateEntityAttributeWithNegatedCrossEntityAttribute() {
		final FilterBy result = translate(
			"$entity.attributes['isActive'] == true "
				+ "&& !($reference.groupEntity?.attributes['status'] == 'INACTIVE')"
		);
		assertEquals(
			filterBy(and(
				attributeEquals("isActive", true),
				not(referenceHaving(REF_NAME, groupHaving(attributeEquals("status", "INACTIVE"))))
			)),
			result
		);
	}

	@Test
	@DisplayName("$entity.attributes['refName'] == 'x' -> attributeEquals, NOT referenceHaving")
	void shouldCorrectlyIdentifyEntityAttributePathNotConfusedWithReference() {
		final FilterBy result = translate("$entity.attributes['refName'] == 'x'");
		assertEquals(
			filterBy(attributeEquals("refName", "x")),
			result
		);
	}

	@Test
	@DisplayName("reference name is taken from parameter, not from expression")
	void shouldUseProvidedReferenceNameInReferenceHaving() {
		final Expression expression = ExpressionFactory.parse("$reference.attributes['a'] == 1");
		final FilterBy resultParameter = ExpressionToQueryTranslator.translate(expression, "parameter");
		final FilterBy resultBrand = ExpressionToQueryTranslator.translate(expression, "brand");
		assertEquals(
			filterBy(referenceHaving("parameter", attributeEquals("a", 1L))),
			resultParameter
		);
		assertEquals(
			filterBy(referenceHaving("brand", attributeEquals("a", 1L))),
			resultBrand
		);
	}

	@Test
	@DisplayName("two groupEntity attributes in AND -> single referenceHaving(and(groupHaving(...), groupHaving(...)))")
	void shouldMergeGroupEntityAttributesUnderSingleReferenceHaving() {
		final FilterBy result = translate(
			"$reference.groupEntity?.attributes['a'] == 1 "
				+ "&& $reference.groupEntity?.attributes['b'] == 2"
		);
		assertEquals(
			filterBy(referenceHaving(REF_NAME, and(
				groupHaving(attributeEquals("a", 1L)),
				groupHaving(attributeEquals("b", 2L))
			))),
			result
		);
	}

	@Test
	@DisplayName("two referencedEntity attrs in AND -> single referenceHaving(and(entityHaving, entityHaving))")
	void shouldMergeReferencedEntityAttributesUnderSingleReferenceHaving() {
		final FilterBy result = translate(
			"$reference.referencedEntity.attributes['a'] == 1 "
				+ "&& $reference.referencedEntity.attributes['b'] == 2"
		);
		assertEquals(
			filterBy(referenceHaving(REF_NAME, and(
				entityHaving(attributeEquals("a", 1L)),
				entityHaving(attributeEquals("b", 2L))
			))),
			result
		);
	}

	// --- Rejection cases ---

	@Test
	@DisplayName("rejects XOR operator with suggestion to rewrite")
	void shouldRejectXorOperator() {
		final NonTranslatableExpressionException ex = assertThrows(
			NonTranslatableExpressionException.class,
			() -> translate("$entity.attributes['a'] == 1 ^ $entity.attributes['b'] == 2")
		);
		assertTrue(ex.getMessage().contains("XOR"), "message should mention XOR");
		assertTrue(
			ex.getMessage().contains("(a || b) && !(a && b)"),
			"message should suggest rewrite pattern"
		);
	}

	@Test
	@DisplayName("rejects addition operator")
	void shouldRejectAdditionOperator() {
		final NonTranslatableExpressionException ex = assertThrows(
			NonTranslatableExpressionException.class,
			() -> translate("$entity.attributes['price'] + 10 > 100")
		);
		assertTrue(ex.getMessage().contains("Addition"), "message should identify addition");
	}

	@Test
	@DisplayName("rejects subtraction operator")
	void shouldRejectSubtractionOperator() {
		final NonTranslatableExpressionException ex = assertThrows(
			NonTranslatableExpressionException.class,
			() -> translate("$entity.attributes['price'] - 10 > 100")
		);
		assertTrue(ex.getMessage().contains("Subtraction"), "message should identify subtraction");
	}

	@Test
	@DisplayName("rejects multiplication operator")
	void shouldRejectMultiplicationOperator() {
		final NonTranslatableExpressionException ex = assertThrows(
			NonTranslatableExpressionException.class,
			() -> translate("$entity.attributes['price'] * 2 > 100")
		);
		assertTrue(ex.getMessage().contains("Multiplication"), "message should identify multiplication");
	}

	@Test
	@DisplayName("rejects division operator")
	void shouldRejectDivisionOperator() {
		final NonTranslatableExpressionException ex = assertThrows(
			NonTranslatableExpressionException.class,
			() -> translate("$entity.attributes['price'] / 2 > 50")
		);
		assertTrue(ex.getMessage().contains("Division"), "message should identify division");
	}

	@Test
	@DisplayName("rejects modulo operator")
	void shouldRejectModuloOperator() {
		final NonTranslatableExpressionException ex = assertThrows(
			NonTranslatableExpressionException.class,
			() -> translate("$entity.attributes['count'] % 2 == 0")
		);
		assertTrue(ex.getMessage().contains("Modulo"), "message should identify modulo");
	}

	@Test
	@DisplayName("rejects numeric negation operator (not boolean NOT)")
	void shouldRejectNegationArithmeticOperator() {
		// -$entity.attributes['price'] parses as NegativeOperator wrapping the path,
		// so the comparison sees NegativeOperator (not ObjectAccessOperator) as an operand
		final NonTranslatableExpressionException ex = assertThrows(
			NonTranslatableExpressionException.class,
			() -> translate("-$entity.attributes['price'] < 0")
		);
		assertTrue(
			ex.getMessage().contains("Unsupported comparison operand")
				|| ex.getMessage().contains("NegativeOperator"),
			"message should identify unsupported operand type"
		);
	}

	@Test
	@DisplayName("rejects function operator")
	void shouldRejectFunctionOperator() {
		final NonTranslatableExpressionException ex = assertThrows(
			NonTranslatableExpressionException.class,
			() -> translate("random() > 0")
		);
		assertTrue(ex.getMessage().contains("Function"), "message should identify function");
	}

	@Test
	@DisplayName("rejects null coalesce operator")
	void shouldRejectNullCoalesceOperator() {
		// ($entity.attributes['name'] ?? 'default') parses as NestedOperator wrapping
		// NullCoalesceOperator, so the comparison sees NestedOperator (not ObjectAccessOperator)
		final NonTranslatableExpressionException ex = assertThrows(
			NonTranslatableExpressionException.class,
			() -> translate("($entity.attributes['name'] ?? 'default') == 'test'")
		);
		assertTrue(
			ex.getMessage().contains("Unsupported comparison operand")
				|| ex.getMessage().contains("NullCoalesceOperator")
				|| ex.getMessage().contains("NestedOperator"),
			"message should identify unsupported operand type"
		);
	}

	@Test
	@DisplayName("rejects cross-to-local comparison (both operands are data paths)")
	void shouldRejectCrossToLocalComparison() {
		final NonTranslatableExpressionException ex = assertThrows(
			NonTranslatableExpressionException.class,
			() -> translate(
				"$reference.groupEntity?.attributes['type'] == $entity.attributes['category']"
			)
		);
		assertTrue(
			ex.getMessage().contains("Cross-constraint") || ex.getMessage().contains("cross"),
			"message should explain cross-constraint limitation"
		);
	}

	@Test
	@DisplayName("rejects associated data access")
	void shouldRejectAssociatedDataAccess() {
		final NonTranslatableExpressionException ex = assertThrows(
			NonTranslatableExpressionException.class,
			() -> translate("$entity.associatedData['desc'] == 'test'")
		);
		assertTrue(
			ex.getMessage().contains("associated data") || ex.getMessage().contains("Associated data"),
			"message should explain no FilterBy equivalent for associated data"
		);
	}

	@Test
	@DisplayName("rejects reference primary key comparison")
	void shouldRejectReferencePrimaryKeyComparison() {
		final NonTranslatableExpressionException ex = assertThrows(
			NonTranslatableExpressionException.class,
			() -> translate("$reference.referencedPrimaryKey == 42")
		);
		assertTrue(
			ex.getMessage().contains("primary key") || ex.getMessage().contains("PK"),
			"message should explain PK scoping is handled by executor"
		);
	}

	@Test
	@DisplayName("rejects non-boolean expression (pure arithmetic)")
	void shouldRejectNonBooleanExpression() {
		final NonTranslatableExpressionException ex = assertThrows(
			NonTranslatableExpressionException.class,
			() -> translate("$entity.attributes['price'] + 10")
		);
		assertTrue(
			ex.getMessage().contains("boolean") || ex.getMessage().contains("Boolean"),
			"message should explain expression must be boolean"
		);
	}

	@Test
	@DisplayName("rejects dynamic attribute path with variable")
	void shouldRejectDynamicAttributePathWithVariable() {
		final NonTranslatableExpressionException ex = assertThrows(
			NonTranslatableExpressionException.class,
			() -> translate("$entity.attributes[$someVar] == 1")
		);
		assertTrue(
			ex.getMessage().contains("Dynamic") || ex.getMessage().contains("compile-time constant"),
			"message should identify dynamic path"
		);
	}

	@Test
	@DisplayName("rejects spread access operator in comparison")
	void shouldRejectSpreadAccessOperator() {
		final NonTranslatableExpressionException ex = assertThrows(
			NonTranslatableExpressionException.class,
			() -> translate("$entity.references['categories'].*[$.referencedPrimaryKey] == 1")
		);
		assertTrue(
			ex.getMessage().contains("Spread access") || ex.getMessage().contains(".*["),
			"message should identify spread access operator: " + ex.getMessage()
		);
	}

	// --- Bug: localizedAttributes support ---

	@Test
	@DisplayName("$entity.localizedAttributes['name'] == 'test' -> filterBy(attributeEquals(\"name\", \"test\"))")
	void shouldTranslateEntityLocalizedAttributeEquals() {
		final FilterBy result = translate("$entity.localizedAttributes['name'] == 'test'");
		assertEquals(
			filterBy(attributeEquals("name", "test")),
			result
		);
	}

	@Test
	@DisplayName("$reference.localizedAttributes['name'] == 'test' -> referenceHaving(refName, attributeEquals(...))")
	void shouldTranslateReferenceLocalizedAttributeEquals() {
		final FilterBy result = translate("$reference.localizedAttributes['name'] == 'test'");
		assertEquals(
			filterBy(referenceHaving(REF_NAME, attributeEquals("name", "test"))),
			result
		);
	}

	@Test
	@DisplayName("$ref.groupEntity?.localizedAttributes['name'] == 'x' -> referenceHaving(groupHaving(eq(...)))")
	void shouldTranslateGroupEntityLocalizedAttributeEquals() {
		final FilterBy result = translate(
			"$reference.groupEntity?.localizedAttributes['name'] == 'x'"
		);
		assertEquals(
			filterBy(referenceHaving(REF_NAME, groupHaving(attributeEquals("name", "x")))),
			result
		);
	}

	@Test
	@DisplayName("$ref.referencedEntity.localizedAttributes['name'] == 'x' -> referenceHaving(entityHaving(eq(...)))")
	void shouldTranslateReferencedEntityLocalizedAttributeEquals() {
		final FilterBy result = translate(
			"$reference.referencedEntity.localizedAttributes['name'] == 'x'"
		);
		assertEquals(
			filterBy(referenceHaving(REF_NAME, entityHaving(attributeEquals("name", "x")))),
			result
		);
	}

	// --- Bug: parenthesized comparison operand unwrapping ---

	@Test
	@DisplayName("($entity.attributes['a']) == 1 -> filterBy(attributeEquals(\"a\", 1L))")
	void shouldTranslateParenthesizedLeftOperandInComparison() {
		final FilterBy result = translate("($entity.attributes['a']) == 1");
		assertEquals(
			filterBy(attributeEquals("a", 1L)),
			result
		);
	}

	@Test
	@DisplayName("1 == ($entity.attributes['a']) -> filterBy(attributeEquals(\"a\", 1L))")
	void shouldTranslateParenthesizedRightOperandReversedInComparison() {
		final FilterBy result = translate("1 == ($entity.attributes['a'])");
		assertEquals(
			filterBy(attributeEquals("a", 1L)),
			result
		);
	}

	// --- Bug: localized associated data rejection ---

	@Test
	@DisplayName("rejects localized associated data access")
	void shouldRejectLocalizedAssociatedDataAccess() {
		final NonTranslatableExpressionException ex = assertThrows(
			NonTranslatableExpressionException.class,
			() -> translate("$entity.localizedAssociatedData['desc'] == 'test'")
		);
		assertTrue(
			ex.getMessage().contains("associated data") || ex.getMessage().contains("Associated data"),
			"message should explain no FilterBy equivalent for associated data"
		);
	}

	// --- Bug: localized attributes dynamic path pre-validation ---

	@Test
	@DisplayName("rejects dynamic localized attribute path with variable")
	void shouldRejectDynamicLocalizedAttributePathWithVariable() {
		final NonTranslatableExpressionException ex = assertThrows(
			NonTranslatableExpressionException.class,
			() -> translate("$entity.localizedAttributes[$someVar] == 1")
		);
		assertTrue(
			ex.getMessage().contains("Dynamic") || ex.getMessage().contains("compile-time constant"),
			"message should identify dynamic path"
		);
	}

	// --- Helper ---

	/**
	 * Parses the given expression string and translates it into a {@link FilterBy} constraint tree
	 * using the default reference name {@link #REF_NAME}.
	 */
	@Nonnull
	private static FilterBy translate(@Nonnull String expressionString) {
		final Expression expression = ExpressionFactory.parse(expressionString);
		return ExpressionToQueryTranslator.translate(expression, REF_NAME);
	}
}
