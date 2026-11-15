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


package io.evitadb.core.expression.trigger;

import io.evitadb.core.expression.trigger.IndexingExpressionInputValidator.IndexingExpressionKind;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The table of indexing expressions `IndexingExpressionInputValidator` admits or refuses in each expression slot of
 * a reference schema, shared by the validator's own test, which checks every cell directly, and the functional tests,
 * which define the cells through the engine: histogram conditions admit only reads evitaDB keeps the histogram current
 * with, histogram values only the four attribute sources, facet conditions everything except the reject list. A
 * refused cell names the read the message must name and a fragment of the reason it must give.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class IndexingExpressionInputCells {

	/** Reason: data no hook tracks for a histogram. */
	public static final String UNTRACKED = "does not track changes of this data";
	/** Reason: the read depends on the entity's locale set. */
	public static final String LOCALE_SET = "depends on which locales the entity has";
	/** Reason: a hop read without an attribute read through the same hop. */
	public static final String HOP = "only through an attribute the same expression reads";
	/** Reason: an `$entity` or `$reference` root nested in a key, a predicate or a spread mapping. */
	public static final String NESTED_ROOT = "read inside a key, a predicate or a spread mapping";
	/** Reason: a value evitaDB maintains itself. */
	public static final String ENGINE_MAINTAINED = "maintains this value itself";
	/** Reason: `random()`. */
	public static final String RANDOM = "returns a different value on every evaluation";
	/** Reason: prices. */
	public static final String PRICES = "prices are not available";
	/** Reason: a property that does not exist. */
	public static final String MISSING = "there is no such property";
	/** Reason: a name that is not a string constant. */
	public static final String DYNAMIC = "must name the attribute, associated data or reference";
	/** Reason: an order-dependent read of a locale-derived list. */
	public static final String POSITIONAL = "the order of locales is not stable";
	/** Reason: a read that is always `null` at indexing time. */
	public static final String NULL_HOP = "always `null`";
	/** Reason: a variable that is not available. */
	public static final String VARIABLE = "only `$entity`, `$reference`";
	/** Reason: a histogram value expression of an unsupported form. */
	public static final String VALUE_FORM = "a histogram value must be a single attribute read";
	/** Reason: a step on the result of `??` or `*?`, which the engine cannot map to the data it reads. */
	public static final String COMPUTED_RECEIVER = "cannot map a step applied to the result of `??` or `*?`";

	/**
	 * The histogram condition cells, in the `bucketedPartially` slot; each also applies to `assignedWhen`.
	 */
	public static final List<Cell> HISTOGRAM_CONDITION_CELLS = histogramConditionCells();
	/**
	 * The histogram value cells: one of four attribute sources, optionally with a numeric `??` default.
	 */
	public static final List<Cell> HISTOGRAM_VALUE_CELLS = histogramValueCells();
	/**
	 * The facet condition cells: everything is admitted except the reject list - data evitaDB maintains itself,
	 * order-dependent locale reads, `random()`, prices, properties that do not exist, dynamic names, reads that are
	 * always `null` and unknown variables, at every position.
	 */
	public static final List<Cell> FACET_CONDITION_CELLS = facetConditionCells();

	/**
	 * Prevents instantiation of the table.
	 */
	private IndexingExpressionInputCells() {
		// no instances
	}

	/**
	 * Creates a cell whose expression the validator must accept, in the `bucketedPartially` slot.
	 *
	 * @param id         the cell identifier
	 * @param expression the expression text
	 * @return the cell
	 */
	@Nonnull
	public static Cell accepted(@Nonnull String id, @Nonnull String expression) {
		return new Cell(id, IndexingExpressionKind.BUCKETED_PARTIALLY, expression, null, null);
	}

	/**
	 * Creates a cell whose expression the validator must refuse, in the `bucketedPartially` slot.
	 *
	 * @param id         the cell identifier
	 * @param expression the expression text
	 * @param read       the refused read the message must name, `null` when the message says the expression has an
	 *                   unsupported form instead
	 * @param reason     a fragment of the reason the message must give
	 * @return the cell
	 */
	@Nonnull
	public static Cell refused(
		@Nonnull String id, @Nonnull String expression, @Nullable String read, @Nonnull String reason
	) {
		return new Cell(id, IndexingExpressionKind.BUCKETED_PARTIALLY, expression, read, reason);
	}

	/**
	 * Returns `true` when the stack of the throwable holds a frame of the method of `Catalog`.
	 *
	 * @param throwable  the throwable
	 * @param methodName the method name
	 * @return `true` when the frame is present
	 */
	public static boolean hasCatalogFrame(@Nonnull Throwable throwable, @Nonnull String methodName) {
		return Arrays.stream(throwable.getStackTrace()).anyMatch(
			frame -> frame.getMethodName().equals(methodName) &&
				frame.getClassName().equals("io.evitadb.core.catalog.Catalog")
		);
	}

	/**
	 * Builds the histogram condition cells.
	 *
	 * @return the cells
	 */
	@Nonnull
	private static List<Cell> histogramConditionCells() {
		final List<Cell> cells = new ArrayList<>(112);
		// accepted: constants, own attributes and associated data, the parent link, attributes through one hop
		cells.add(accepted("A-E1", "$entity.primaryKey > 0"));
		cells.add(accepted("A-R1", "$reference.referencedPrimaryKey > 0"));
		cells.add(accepted("A-E2", "$entity.attributes['status'] == 'A'"));
		cells.add(accepted("A-E2a", "$entity.attributes['tags'].any($ == 'x')"));
		cells.add(accepted("A-E2b", "$entity.attributes['tags'][0] == 'x'"));
		cells.add(accepted("A-E2c", "$entity.attributes['tags'].size() > 1"));
		cells.add(accepted("A-E2d", "($entity.attributes['tags'] *? 'none').any($ == 'x')"));
		cells.add(accepted("A-E2e", "$entity.attributes['tags'].*[$ + '!'].any($ == 'x!')"));
		cells.add(accepted("A-E3", "$entity.localizedAttributes['name']['en'] != null"));
		cells.add(accepted("A-E3n", "$entity?.localizedAttributes?['name']?['en'] != null"));
		cells.add(accepted("A-C4b", "$entity.localizedAttributes['name']['e' + 'n'] != null"));
		cells.add(accepted("A-C4c", "$entity.attributes['tags'][1 - 1] == 'x'"));
		cells.add(accepted("A-E5", "$entity.associatedData['flag'] != null"));
		cells.add(accepted("A-E5l", "$entity.localizedAssociatedData['label']['en'] != null"));
		cells.add(accepted("A-E13", "$entity.parentEntity != null"));
		cells.add(accepted("A-E13b", "null == $entity.parentEntity"));
		cells.add(accepted("A-E15", "($entity.parentEntity?.attributes['status'] ?? '') == 'ACTIVE'"));
		cells.add(accepted("A-E15l", "$entity.parentEntity?.localizedAttributes['name']['en'] != null"));
		cells.add(accepted(
			"A-E15h", "$entity.parentEntity?.primaryKey > 0 && $entity.parentEntity?.attributes['status'] == 'A'"
		));
		cells.add(accepted(
			"A-E15b", "$entity.parentEntity != null && $entity.parentEntity.attributes['status'] == 'A'"
		));
		cells.add(accepted("A-R2", "$reference.attributes['share'] > 1"));
		// the presence of the reference itself, tracked by its own insert and removal
		cells.add(accepted("A-R5", "$reference != null"));
		cells.add(accepted("A-N2", "$reference.referencedEntity.attributes['status'] == 'A'"));
		cells.add(accepted("A-N2g", "$reference.groupEntity?.attributes['type'] == 'X'"));
		cells.add(accepted("A-N2l", "$reference.referencedEntity.localizedAttributes['lweight']['en'] > 1"));
		cells.add(accepted(
			"A-N9",
			"$reference.referencedEntity.scope == 'LIVE' && $reference.referencedEntity.attributes['status'] == 'A'"
		));
		cells.add(accepted(
			"A-N9k",
			"$reference.referencedEntity != null && $reference.referencedEntity.primaryKey > 0 && " +
				"$reference.referencedEntity.attributes['status'] == 'A'"
		));
		cells.add(accepted(
			"A-N9c",
			"($reference.referencedEntity.attributes['status'] ?? $reference.referencedEntity.scope) != 'X'"
		));
		cells.add(accepted(
			"A-E15c", "($entity.parentEntity?.attributes['delta'] ?? $entity.parentEntity?.primaryKey) > 0"
		));
		cells.add(accepted("A-C7", "abs($entity.attributes['delta']) > 5 && !($reference.attributes['share'] == 1)"));
		cells.add(accepted("A-C6", "($entity.attributes['delta'] ?? 0) + ($reference.attributes['share'] ?? 0) > 3"));
		cells.add(accepted("A-C9", "($entity.attributes['tags'] ?*? 'none').size() > 0"));
		cells.add(accepted("A-K", "true"));
		cells.add(accepted("A-M", "max($entity.attributes['delta'], 3) ^ ($entity.attributes['delta'] % 2 == 0)"));
		// a parenthesized prefix of a read is walked as the read itself
		cells.add(accepted("A-par-root", "($entity).attributes['status'] == 'A'"));
		cells.add(accepted("A-par-container", "($entity.attributes)['status'] == 'A'"));
		cells.add(accepted("A-par-map", "($entity.localizedAttributes['name'])['en'] != null"));
		cells.add(refused("R-par-version", "($entity).version > 1", "$entity.version", ENGINE_MAINTAINED));
		cells.add(accepted("A-par-parent-null", "($entity).parentEntity != null"));
		// a condition reading an attribute through a hop is translated into a query, which accepts no parenthesized
		// prefix of a read, so such a condition is walked as written and refused as it reads there
		cells.add(refused("R-par-translated",
			"($entity).attributes['status'] == 'A' && $reference.referencedEntity.attributes['status'] == 'A'",
			"$entity", UNTRACKED));
		cells.add(refused("R-par-hop", "($reference.referencedEntity.attributes)['status'] == 'A'",
			"$reference.referencedEntity.attributes", DYNAMIC));
		// walked as written, the attribute is still read through the hop: the parenthesized chain starts at
		// `$reference`
		cells.add(accepted("A-par-hop-prefix", "($reference.referencedEntity).attributes['status'] == 'A'"));
		// a step on the result of `??` or `*?` reads data the engine cannot map to the operands
		cells.add(refused("R-coalesce-index",
			"($entity.attributes['tags'] ?? $entity.attributes['fallbackTags'])[0] == 'A'",
			"($entity.attributes['tags'] ?? $entity.attributes['fallbackTags'])[0]", COMPUTED_RECEIVER));
		cells.add(refused("R-coalesce-property",
			"($entity.attributes['validity'] ?? $entity.attributes['fallbackValidity']).from != null",
			"($entity.attributes['validity'] ?? $entity.attributes['fallbackValidity']).from", COMPUTED_RECEIVER));
		cells.add(refused("R-spread-coalesce-index", "($entity.attributes['tags'] *? 'none')[0] == 'A'",
			"($entity.attributes['tags'] *? 'none')[0]", COMPUTED_RECEIVER));
		// inside a predicate the step continues the path of the value the predicate is applied to
		cells.add(accepted("A-coalesce-nested", "$entity.attributes['tags'].any(($ ?? 'x')[0] == 'a')"));
		// a method step on an entity fails at evaluation, also when the entity is an operand of `??`
		cells.add(refused("R-coalesce-hop-method",
			"($reference.referencedEntity ?? $reference.referencedEntity).size() > 0 && " +
				"$reference.referencedEntity.attributes['status'] == 'A'",
			"($reference.referencedEntity ?? $reference.referencedEntity).size()", MISSING));
		// `null` in parentheses is `null`: the comparison depends only on whether the other operand is present
		cells.add(accepted("A-par-null-parent", "$entity.parentEntity != (null)"));
		cells.add(accepted("A-par-null-parent-left", "(null) == $entity.parentEntity"));
		cells.add(accepted("A-par-null-parent-double", "$entity.parentEntity != ((null))"));
		cells.add(accepted("A-par-null-reference", "$reference != (null)"));
		cells.add(accepted("A-par-null-reference-wrapped", "($reference) != (null)"));
		cells.add(accepted("A-par-null-translated",
			"$entity.parentEntity != (null) && $entity.parentEntity.attributes['status'] == 'A'"));
		cells.add(accepted("A-par-null-predicate", "$entity.attributes['tags'].any($ != (null))"));
		// a key computed from the element `$` inside a predicate or a spread mapping reads no `$entity` or
		// `$reference` data
		cells.add(accepted("A-key-this-predicate", "$entity.associatedData['rows'].any($[$.size() - 1] > 0)"));
		cells.add(accepted("A-key-this-mapping", "$entity.associatedData['rows'].*[$[$.size() - 1]].size() > 0"));
		cells.add(refused("R-par-nonnull-reference", "$reference != ('x')", "$reference", UNTRACKED));
		// the engine takes a name only from a string constant written directly in the brackets, so a name in
		// parentheses names no data evitaDB knows at definition
		cells.add(refused("R-par-name", "$entity.attributes[('status')] == 'A'", "$entity.attributes[('status')]",
			DYNAMIC));
		// a key that is not written as a constant adds nothing to the data the engine maps, so it reads only the
		// operands of `??`
		cells.add(accepted("A-coalesce-paren-index",
			"($entity.attributes['tags'] ?? $entity.attributes['fallbackTags'])[(0)] == 'A'"));
		cells.add(refused("R-coalesce-paren-receiver",
			"((($entity.attributes['tags'] ?? $entity.attributes['fallbackTags'])))[0] == 'A'",
			"((($entity.attributes['tags'] ?? $entity.attributes['fallbackTags'])))[0]", COMPUTED_RECEIVER));
		// a step on the result of `??` reads data the engine cannot map, whichever entity the operands are
		cells.add(refused("R-coalesce-hop-attribute",
			"($reference.referencedEntity ?? $reference.groupEntity).attributes['status'] == 'A'",
			"($reference.referencedEntity ?? $reference.groupEntity).attributes", COMPUTED_RECEIVER));
		cells.add(refused("R-coalesce-hop-pk",
			"($reference.referencedEntity ?? $reference.referencedEntity).primaryKey > 0 && " +
				"$reference.referencedEntity.attributes['status'] == 'A'",
			"($reference.referencedEntity ?? $reference.referencedEntity).primaryKey", COMPUTED_RECEIVER));
		// an attribute read through the fallback of `*?` is not an attribute read through the hop: the engine maps
		// no path from the reference to the hop's attribute
		cells.add(refused("R-spread-coalesce-hop-credit",
			"($entity.attributes['tags'] *? $reference.referencedEntity).any($.attributes['status'] == 'A')",
			"$reference.referencedEntity", HOP));
		// the fallback of `*?` is used as a value, as the default of `??` is
		cells.add(refused("R-spread-coalesce-reference", "($entity.attributes['tags'] *? $reference).size() > 0",
			"$reference", UNTRACKED));
		// a coalesced localized map follows the locale set of the entity, a selector after `??` notwithstanding
		cells.add(refused("R-coalesce-map-selector",
			"($entity.localizedAttributes['name'] ?? $entity.localizedAttributes['alt'])['en'] != null",
			"$entity.localizedAttributes['name']", LOCALE_SET));
		// the read named by a refusal before a null-safe step ends at the read, not at the marker
		cells.add(refused("R-nullsafe-attributes", "$entity.attributes?.size() > 0", "$entity.attributes", DYNAMIC));
		// refused: the locale set, whose changes no hook withdraws on
		cells.add(refused("R-E4", "$entity.localizedAttributes['name'].size() > 0",
			"$entity.localizedAttributes['name']", LOCALE_SET));
		cells.add(refused("R-E4n", "$entity.localizedAttributes['name'] != null",
			"$entity.localizedAttributes['name']", LOCALE_SET));
		cells.add(refused("R-E4c", "($entity.localizedAttributes['name'] *? '').entries.any($.value == 'x')",
			"$entity.localizedAttributes['name']", LOCALE_SET));
		cells.add(refused("R-E4p", "$entity.localizedAttributes['name'].entries[0].value == 'x'",
			"$entity.localizedAttributes['name']", LOCALE_SET));
		cells.add(refused("R-E6", "$entity.localizedAssociatedData['label'].size() > 0",
			"$entity.localizedAssociatedData['label']", LOCALE_SET));
		cells.add(refused("R-E16", "$entity.locales.size() > 1", "$entity.locales", LOCALE_SET));
		cells.add(refused("R-E16a", "$entity.allLocales.any($ == 'en')", "$entity.allLocales", LOCALE_SET));
		cells.add(refused("R-E16p", "$entity.locales[0] == 'en'", "$entity.locales", LOCALE_SET));
		cells.add(refused("R-N8", "$reference.referencedEntity.localizedAttributes['lweight'].size() > 0",
			"$reference.referencedEntity.localizedAttributes['lweight']", LOCALE_SET));
		cells.add(refused("R-N3",
			"$reference.referencedEntity.locales.size() > 0 && $reference.referencedEntity.attributes['status'] == 'A'",
			"$reference.referencedEntity.locales", LOCALE_SET));
		// refused: the owner's other references, the references of an entity one hop away, untracked hop data
		cells.add(refused("R-E7", "$entity.references['tag'] != null", "$entity.references", UNTRACKED));
		cells.add(refused("R-E8", "$entity.references['tag'].attributes['w'] > 0", "$entity.references", UNTRACKED));
		cells.add(refused("R-N5", "$reference.referencedEntity.references['tag'].attributes['w'] > 0",
			"$reference.referencedEntity.references", UNTRACKED));
		cells.add(refused("R-N6",
			"$reference.referencedEntity.references['pvTag'].any(($.attributes['tagWeight'] ?? 0) > 5)",
			"$reference.referencedEntity.references", UNTRACKED));
		cells.add(refused("R-N6g", "$reference.groupEntity?.references['tag']?.any(($.attributes['w'] ?? 0) > 5)",
			"$reference.groupEntity?.references", UNTRACKED));
		cells.add(refused("R-N4",
			"$reference.referencedEntity.associatedData['x'] != null && " +
				"$reference.referencedEntity.attributes['status'] == 'A'",
			"$reference.referencedEntity.associatedData", UNTRACKED));
		cells.add(refused("R-N7",
			"$reference.referencedEntity.parentEntity != null && " +
				"$reference.referencedEntity.attributes['status'] == 'A'",
			"$reference.referencedEntity.parentEntity", UNTRACKED));
		cells.add(refused("R-N14", "$entity.parentEntity.parentEntity != null",
			"$entity.parentEntity.parentEntity", UNTRACKED));
		cells.add(refused("R-E17", "$entity.scope == 'LIVE'", "$entity.scope", UNTRACKED));
		cells.add(refused("R-R3", "$reference.localizedAttributes['lshare']['en'] > 1",
			"$reference.localizedAttributes", UNTRACKED));
		cells.add(refused("R-bare", "$entity != null", "$entity", UNTRACKED));
		cells.add(refused("R-bareR", "$reference == 'x'", "$reference", UNTRACKED));
		// refused: a hop read without an attribute read through the same hop
		cells.add(refused("R-E13s", "$entity.parentEntity?.primaryKey > 0", "$entity.parentEntity?.primaryKey", HOP));
		cells.add(refused("R-E13c", "$entity.parentEntity?.scope == 'LIVE'", "$entity.parentEntity?.scope", HOP));
		cells.add(refused("R-E13t", "$entity.parentEntity == $entity.parentEntity", "$entity.parentEntity", HOP));
		cells.add(refused("R-R6", "$reference.referencedEntity != null", "$reference.referencedEntity", HOP));
		cells.add(refused("R-R6k", "$reference.referencedEntity.primaryKey > 0", "$reference.referencedEntity", HOP));
		cells.add(refused("R-R6s", "$reference.referencedEntity?.scope == 'LIVE'", "$reference.referencedEntity", HOP));
		cells.add(refused("R-R7", "$reference.groupEntity == null", "$reference.groupEntity", HOP));
		cells.add(refused("R-R7m",
			"$reference.groupEntity == null && $reference.referencedEntity.attributes['status'] == 'A'",
			"$reference.groupEntity", HOP));
		// refused: data evitaDB maintains itself, prices, properties that do not exist
		cells.add(refused("R-E19", "$entity.version >= 3", "$entity.version", ENGINE_MAINTAINED));
		cells.add(refused("R-E20", "$entity.dropped == false", "$entity.dropped", ENGINE_MAINTAINED));
		cells.add(refused("R-N10", "$reference.referencedEntity.version > 1",
			"$reference.referencedEntity.version", ENGINE_MAINTAINED));
		cells.add(refused("R-E18", "$entity.priceForSale != null", "$entity.priceForSale", PRICES));
		cells.add(refused("R-E18p", "$entity.prices.size() > 0", "$entity.prices", PRICES));
		cells.add(refused("R-E18a", "$entity.allPricesForSale != null", "$entity.allPricesForSale", PRICES));
		cells.add(refused("R-E18c", "$entity.accompanyingPrice != null", "$entity.accompanyingPrice", PRICES));
		cells.add(refused("R-E18h", "$entity.priceInnerRecordHandling == 'NONE'",
			"$entity.priceInnerRecordHandling", PRICES));
		cells.add(refused("R-E21", "$entity.parent > 0", "$entity.parent", MISSING));
		cells.add(refused("R-E21t", "$entity.type == 'product'", "$entity.type", MISSING));
		cells.add(refused("R-E21u", "$entity.unknown == 1", "$entity.unknown", MISSING));
		cells.add(refused("R-E22", "$entity['status'] == 'A'", "$entity['status']", MISSING));
		cells.add(refused("R-R8", "$reference.group == 1", "$reference.group", MISSING));
		cells.add(refused("R-R8b", "$reference.referencedEntityType == 'brand'",
			"$reference.referencedEntityType", MISSING));
		// refused: dynamic names
		cells.add(refused("R-E10", "$entity.attributes['st' + 'atus'] == 'A'",
			"$entity.attributes['st' + 'atus']", DYNAMIC));
		cells.add(refused("R-C4", "$entity.associatedData[$reference.attributes['k']] != null",
			"$entity.associatedData[$reference.attributes['k']]", DYNAMIC));
		cells.add(refused("R-E10c", "$entity.attributes.size() > 0", "$entity.attributes", DYNAMIC));
		// refused: roots nested in a predicate, a spread mapping or a key
		cells.add(refused("R-C1", "$entity.attributes['tags'].any($ == $entity.attributes['code'])",
			"$entity.attributes['code']", NESTED_ROOT));
		cells.add(refused("R-C2", "$entity.attributes['tags'].*[$entity.primaryKey].size() > 0",
			"$entity.primaryKey", NESTED_ROOT));
		cells.add(refused("R-C3", "$entity.localizedAttributes['name'][$reference.attributes['k']] != null",
			"$reference.attributes['k']", NESTED_ROOT));
		// refused: random() and unknown variables
		cells.add(refused("R-X1", "random() > 5", "random()", RANDOM));
		cells.add(refused("R-X1n", "$entity.attributes['tags'].any($ == random(3))", "random(3)", RANDOM));
		cells.add(refused("R-X2", "$unknown == 1", "$unknown", VARIABLE));
		cells.add(refused("R-X2p", "$other.attributes['x'] == 1", "$other", VARIABLE));
		cells.add(refused("R-X2t", "$ == 1", "$", VARIABLE));
		return List.copyOf(cells);
	}

	/**
	 * Builds the histogram value cells.
	 *
	 * @return the cells
	 */
	@Nonnull
	private static List<Cell> histogramValueCells() {
		final List<Cell> cells = new ArrayList<>(24);
		cells.add(accepted("V-A1", "$reference.attributes['share']"));
		cells.add(accepted("V-A2", "$reference.attributes['share'] ?? 5"));
		cells.add(accepted("V-A3", "$reference.localizedAttributes['lshare'] ?? 5"));
		cells.add(accepted("V-A4", "$reference.referencedEntity?.attributes['weight'] ?? 0"));
		cells.add(accepted("V-A5", "$reference.referencedEntity.localizedAttributes['lweight']"));
		cells.add(accepted("V-A6", "$reference.attributes['share'] ?? -1"));
		cells.add(accepted("V-A7", "$reference.attributes['share'] ?? 0.5"));
		cells.add(accepted("V-A8", "$reference?.attributes?['share']"));
		cells.add(refused("V-R1", "$reference.attributes['share'] + 1", null, VALUE_FORM));
		cells.add(refused("V-R2", "$reference.attributes['share'] ?? $reference.attributes['other']", null,
			VALUE_FORM));
		cells.add(refused("V-R3", "($reference.attributes['share'] ?? 5)", null, VALUE_FORM));
		cells.add(refused("V-R4", "abs($reference.attributes['share'])", null, VALUE_FORM));
		cells.add(refused("V-R5", "$reference.attributes['share'][0]", null, VALUE_FORM));
		cells.add(refused("V-R6", "$reference.attributes['share'] ?? null", null, VALUE_FORM));
		cells.add(refused("V-R7", "$reference.attributes['share'] ?? 'x'", null, VALUE_FORM));
		cells.add(refused("V-R8", "$reference.groupEntity.attributes['weight']", null, VALUE_FORM));
		cells.add(refused("V-R9", "$entity.attributes['delta']", null, VALUE_FORM));
		cells.add(refused("V-R10", "$entity.parentEntity.attributes['status']", null, VALUE_FORM));
		cells.add(refused("V-R11", "$reference.referencedEntity.references['t'].attributes['w']", null, VALUE_FORM));
		cells.add(refused("V-R12", "$reference.attributes['sh' + 'are']", null, VALUE_FORM));
		cells.add(refused("V-R13", "$reference.attributes['share'] ?? 5 ?? 6", null, VALUE_FORM));
		return cells.stream().map(cell -> cell.withKind(IndexingExpressionKind.HISTOGRAM_VALUE)).toList();
	}

	/**
	 * Builds the facet condition cells.
	 *
	 * @return the cells
	 */
	@Nonnull
	private static List<Cell> facetConditionCells() {
		final List<Cell> cells = new ArrayList<>(112);
		cells.add(accepted("FA-1",
			"$reference.referencedEntity.references['tag'].any(($.attributes['weight'] ?? 0) > 5)"));
		cells.add(accepted("FA-1g",
			"$reference.groupEntity?.references['tag']?.any(($.attributes['weight'] ?? 0) > 5)"));
		cells.add(accepted("FA-1s",
			"($reference.referencedEntity.references['singleTag']?.attributes['weight'] ?? 0) > 5"));
		cells.add(accepted("FA-2", "$entity.references['tag'] != null"));
		cells.add(accepted("FA-2a",
			"$entity.references['tag'].any($.attributes['w'] > 1 && $.referencedPrimaryKey > 0)"));
		cells.add(accepted("FA-3", "$entity.scope == 'LIVE'"));
		cells.add(accepted("FA-4", "$entity.locales.size() > 1"));
		cells.add(accepted("FA-4a", "$entity.locales.any($ == 'en')"));
		cells.add(accepted("FA-4n", "$entity.locales != null"));
		cells.add(accepted("FA-4s", "$entity.allLocales.*[$ + '!'].any($ == 'en!')"));
		cells.add(accepted("FA-4c", "($entity.locales *? 'x').size() > 0"));
		cells.add(accepted("FA-5", "$entity.localizedAttributes['name'].entries.any($.value == 'x')"));
		cells.add(accepted("FA-5m", "$entity.localizedAttributes['name'] != null"));
		cells.add(accepted("FA-5s", "$entity.localizedAttributes['name'].*[$ + '!'].size() > 0"));
		cells.add(refused("FR-3s", "$entity.localizedAttributes['name'].*[$ + '!'].entries[0].value == 'x'",
			"$entity.localizedAttributes['name'].*[$ + '!'].entries", POSITIONAL));
		cells.add(accepted("FA-6", "$reference.groupEntity == null"));
		cells.add(accepted("FA-6p", "$reference.referencedEntity.parentEntity != null"));
		cells.add(accepted("FA-7", "$entity.attributes['tags'].any($ == $entity.attributes['code'])"));
		cells.add(accepted("FA-8", "($entity.parentEntity?.references['singleTag']?.attributes['w'] ?? 0) > 5"));
		cells.add(accepted("FA-9", "$reference.localizedAttributes['note']['en'] != null"));
		cells.add(accepted("FA-10", "$entity.associatedData['metadata'] != null"));
		cells.add(accepted("FA-11", "$reference.referencedEntity.attributes['price'] + 5 > 10"));
		// a data container ending the chain evaluates to the container, which is never `null`
		cells.add(accepted("FA-12", "$entity.attributes != null"));
		cells.add(accepted("FA-12l", "$entity.localizedAttributes != null"));
		cells.add(accepted("FA-12a", "$entity.associatedData != null"));
		cells.add(accepted("FA-12al", "$entity.localizedAssociatedData != null"));
		cells.add(accepted("FA-12e", "$entity.references != null"));
		cells.add(accepted("FA-12r", "$reference.attributes != null"));
		cells.add(accepted("FA-12rl", "$reference.localizedAttributes != null"));
		cells.add(accepted("FA-12s", "$entity.references['singleTag']?.attributes != null"));
		cells.add(accepted("FA-12h", "$reference.referencedEntity.attributes != null"));
		cells.add(accepted("FA-12hr", "$reference.referencedEntity.references != null"));
		cells.add(accepted("FA-12p", "$entity.parentEntity?.attributes != null"));
		cells.add(accepted("FA-12m", "$entity.attributes != null && $entity.parentEntity != null"));
		// a condition reading an attribute through a hop is translated into a query, which cannot express a container
		// without a name
		cells.add(refused(
			"FR-28", "$entity.attributes != null && $reference.referencedEntity.attributes['status'] == 'A'",
			"$entity.attributes", DYNAMIC
		));
		cells.add(refused(
			"FR-28g", "$entity.references != null && $reference.groupEntity?.attributes['type'] == 'X'",
			"$entity.references", DYNAMIC
		));
		cells.add(refused(
			"FR-28h",
			"$reference.referencedEntity.attributes != null && " +
				"$reference.referencedEntity.attributes['status'] == 'A'",
			"$reference.referencedEntity.attributes", DYNAMIC
		));
		cells.add(refused(
			"FR-28p", "($entity.parentEntity?.attributes['status'] ?? '') == 'A' && $entity.associatedData != null",
			"$entity.associatedData", DYNAMIC
		));
		cells.add(refused(
			"FR-28n",
			"$entity.attributes != null && " +
				"$reference.referencedEntity.references['tag'].any(($.attributes['weight'] ?? 0) > 5)",
			"$entity.attributes", DYNAMIC
		));
		cells.add(refused(
			"FR-28r",
			"$entity.references != null && ($reference.groupEntity?.references['singleTag']?.attributes['w'] ?? 0) > 1",
			"$entity.references", DYNAMIC
		));
		// a hop's references read without an attribute of theirs keep the condition local
		cells.add(accepted(
			"FA-12n", "$entity.attributes != null && $reference.referencedEntity.references['tag'] != null"
		));
		// the shape of a typical production condition: a boolean reference attribute combined with attributes of the
		// referenced and the group entity, every read defaulted
		cells.add(accepted(
			"FA-P",
			"($reference.attributes['visible'] ?? false) == true && " +
				"(($reference.referencedEntity.attributes['weight'] ?? 0) > 0 || " +
				"($reference.groupEntity?.attributes['type'] ?? '') == 'X')"
		));
		// reads that fail at evaluation on every non-null receiver
		cells.add(refused("FR-14", "$entity.size() > 0", "$entity.size()", MISSING));
		cells.add(refused("FR-15", "$entity.*[$ != null] != null", "$entity.*[$ != null]", MISSING));
		cells.add(refused("FR-16", "$reference['share'] > 1", "$reference['share']", MISSING));
		cells.add(refused("FR-17", "$reference.size() > 0", "$reference.size()", MISSING));
		cells.add(refused("FR-18", "$entity.references['tag'][0]['w'] > 1", "$entity.references['tag'][0]['w']",
			MISSING));
		cells.add(refused("FR-19", "$entity['brand'] != null", "$entity['brand']", MISSING));
		cells.add(refused("FR-20", "$reference.referencedEntity.size() > 0", "$reference.referencedEntity.size()",
			MISSING));
		cells.add(refused("FR-21", "$entity.type == 'product'", "$entity.type", MISSING));
		cells.add(refused("FR-22", "$reference.referencedEntityType == 'brand'", "$reference.referencedEntityType",
			MISSING));
		cells.add(refused("FR-23", "$entity.attributes.size() > 0", "$entity.attributes", DYNAMIC));
		cells.add(refused("FR-24", "$entity.localizedAttributes['name'].keys != null",
			"$entity.localizedAttributes['name'].keys", MISSING));
		cells.add(refused("FR-25", "$entity.locales.contains('en')", "$entity.locales.contains('en')", MISSING));
		cells.add(refused("FR-26", "$reference.group.primaryKey > 0", "$reference.group", MISSING));
		cells.add(refused("FR-27", "$reference.referencedEntity['status'] == 'A'",
			"$reference.referencedEntity['status']", MISSING));
		cells.add(refused("FR-1", "$entity.version > 1", "$entity.version", ENGINE_MAINTAINED));
		cells.add(refused("FR-1n", "$entity.attributes['tags'].any($ == $entity.version)", "$entity.version",
			ENGINE_MAINTAINED));
		cells.add(refused("FR-2", "$entity.locales[0] == 'en'", "$entity.locales", POSITIONAL));
		cells.add(refused("FR-3", "$entity.localizedAttributes['name'].entries[0].value == 'x'",
			"$entity.localizedAttributes['name'].entries", POSITIONAL));
		cells.add(refused("FR-4", "$entity.locales.*[$ + '!'][0] == 'en!'", "$entity.locales.*[$ + '!']", POSITIONAL));
		cells.add(refused("FR-5", "$entity.locales == 'en'", "$entity.locales", POSITIONAL));
		cells.add(refused("FR-5f", "max($entity.locales) > 0", "$entity.locales", POSITIONAL));
		cells.add(refused("FR-5e", "$entity.locales", "$entity.locales", POSITIONAL));
		cells.add(refused("FR-5d", "('x' ?? $entity.locales) != null", "$entity.locales", POSITIONAL));
		cells.add(refused("FR-5h", "$reference.referencedEntity.locales[0] == 'en'",
			"$reference.referencedEntity.locales", POSITIONAL));
		cells.add(refused("FR-6", "random() > 1", "random()", RANDOM));
		cells.add(refused("FR-7", "$entity.priceForSale != null", "$entity.priceForSale", PRICES));
		cells.add(refused("FR-8", "$entity.parent > 0", "$entity.parent", MISSING));
		cells.add(refused("FR-8r", "$reference.group == 1", "$reference.group", MISSING));
		cells.add(refused("FR-9", "$entity.attributes['st' + 'atus'] == 'A'", "$entity.attributes['st' + 'atus']",
			DYNAMIC));
		cells.add(refused("FR-10", "$entity.references['brand'].referencedEntity.attributes['status'] == 'A'",
			"$entity.references['brand'].referencedEntity", NULL_HOP));
		cells.add(refused("FR-11", "$reference.referencedEntity.references['tag'].any($.groupEntity != null)",
			"$.groupEntity", NULL_HOP));
		cells.add(refused("FR-12", "$foo == 1", "$foo", VARIABLE));
		cells.add(refused("FR-13", "$entity.dropped == false", "$entity.dropped", ENGINE_MAINTAINED));
		// the reject list holds for the parent of an entity one hop away, whose attribute reads stay local
		cells.add(refused("FR-hop-parent-version", "$entity.parentEntity?.parentEntity?.version > 0",
			"$entity.parentEntity?.parentEntity?.version", ENGINE_MAINTAINED));
		cells.add(refused("FR-hop-parent-price", "$reference.referencedEntity.parentEntity?.priceForSale != null",
			"$reference.referencedEntity.parentEntity?.priceForSale", PRICES));
		cells.add(refused("FR-hop-parent-type", "$entity.parentEntity?.parentEntity?.type == 'x'",
			"$entity.parentEntity?.parentEntity?.type", MISSING));
		cells.add(accepted("FA-hop-parent-local",
			"$entity.attributes != null && " +
				"($reference.referencedEntity.parentEntity?.attributes['status'] ?? '') == 'A'"));
		// the reject list holds for the elements of an identity spread over references, which are references
		cells.add(refused("FR-spread-identity-any", "$entity.references['tag'].*[$].any($.referencedEntity != null)",
			"$.referencedEntity", NULL_HOP));
		cells.add(refused("FR-spread-identity-index", "$entity.references['tag'].*[$][0].group != null",
			"$entity.references['tag'].*[$][0].group", MISSING));
		cells.add(accepted("FA-spread-value", "$entity.references['tag'].*[$.attributes['w']].any($ > 1)"));
		// a condition reading an attribute through a hop is translated into a query, which accepts no parenthesized
		// prefix of a read, so such a condition is walked as written: the container ends its chain there
		cells.add(refused("FR-par-translated",
			"($entity.attributes)['status'] == 'A' && $reference.referencedEntity.attributes['status'] == 'A'",
			"$entity.attributes", DYNAMIC));
		// the read named by a refusal before a null-safe step ends at the read, not at the marker
		cells.add(refused("FR-nullsafe-locales", "$entity.locales?[0] == 'en'", "$entity.locales", POSITIONAL));
		cells.add(refused("FR-nullsafe-references", "$entity.references?.size() > 0", "$entity.references", DYNAMIC));
		// an element, method or spread step on an entity or a reference fails at evaluation, also when the entity or
		// the reference is an operand of `??`
		cells.add(refused("FR-coalesce-entity-method", "($entity ?? $entity).size() > 0",
			"($entity ?? $entity).size()", MISSING));
		cells.add(refused("FR-coalesce-reference-element", "($reference ?? $reference)['share'] > 1",
			"($reference ?? $reference)['share']", MISSING));
		cells.add(refused("FR-coalesce-hop-spread",
			"($reference.referencedEntity ?? $reference.groupEntity).*[$ != null] != null",
			"($reference.referencedEntity ?? $reference.groupEntity).*[$ != null]", MISSING));
		cells.add(refused("FR-coalesce-listed-method",
			"($entity.references['tag'][0] ?? $entity.references['tag'][1]).size() > 0",
			"($entity.references['tag'][0] ?? $entity.references['tag'][1]).size()", MISSING));
		cells.add(refused("FR-coalesce-mixed", "($entity.attributes['status'] ?? $entity).size() > 0",
			"($entity.attributes['status'] ?? $entity).size()", MISSING));
		// `null` in parentheses is `null`: a list ordered by locale may be compared with it
		cells.add(accepted("FA-par-null-locales", "$entity.locales != (null)"));
		cells.add(accepted("FA-par-null-locales-left", "(null) == $entity.locales"));
		cells.add(refused("FR-par-name", "$entity.attributes[('status')] == 'A'", "$entity.attributes[('status')]",
			DYNAMIC));
		cells.add(refused("FR-par-this", "$entity.references['tag'].any(($).referencedEntity != null)",
			"$.referencedEntity", NULL_HOP));
		// the result of `*?` is the collection, whose `null` elements the fallback replaces: the fallback is an
		// element, never the receiver of a step
		cells.add(accepted("FA-spread-coalesce-reference", "($entity.references['tag'] *? $reference).size() > 0"));
		cells.add(accepted("FA-spread-coalesce-reference-ns",
			"($entity.references['tag'] ?*? $reference).size() > 0"));
		cells.add(accepted("FA-spread-coalesce-own", "($entity.references['brand'] *? $reference).size() > 0"));
		cells.add(accepted("FA-coalesce-in-spread",
			"(($entity.references['tag'] ?? null) *? $reference).size() > 0"));
		// an element of the result of `*?` is an element of the collection or the fallback, and the reject list
		// holds for either
		cells.add(refused("FR-spread-coalesce-element",
			"($entity.references['tag'] *? $reference)[0].referencedEntity != null",
			"($entity.references['tag'] *? $reference)[0].referencedEntity", NULL_HOP));
		cells.add(refused("FR-spread-coalesce-fallback", "($entity.attributes['tags'] *? $entity).any($.version > 0)",
			"$.version", ENGINE_MAINTAINED));
		cells.add(refused("FR-spread-coalesce-fallback-missing",
			"($entity.attributes['tags'] *? $reference).any($.referencedEntityType == 'x')",
			"$.referencedEntityType", MISSING));
		cells.add(refused("FR-spread-coalesce-null-fallback",
			"($entity.references['tag'] *? null).any($.referencedEntity != null)", "$.referencedEntity", NULL_HOP));
		cells.add(refused("FR-coalesce-in-spread",
			"(($entity.references['tag'] ?? null) *? $reference).any($.referencedEntity != null)",
			"$.referencedEntity", NULL_HOP));
		// the result of `??` is either operand, and the reject list holds for a step on it whichever it is
		cells.add(refused("FR-coalesce-entity-version", "($entity ?? $entity).version >= 3",
			"($entity ?? $entity).version", ENGINE_MAINTAINED));
		cells.add(refused("FR-coalesce-mixed-version", "($entity.attributes['status'] ?? $entity).version > 0",
			"($entity.attributes['status'] ?? $entity).version", ENGINE_MAINTAINED));
		cells.add(refused("FR-par-entity-operand", "(($entity) ?? $entity).version > 0",
			"(($entity) ?? $entity).version", ENGINE_MAINTAINED));
		cells.add(refused("FR-coalesce-listed-hop",
			"($entity.references['tag'][0] ?? $reference).referencedEntity != null",
			"($entity.references['tag'][0] ?? $reference).referencedEntity", NULL_HOP));
		cells.add(refused("FR-coalesce-this-hop", "$entity.references['tag'].any(($ ?? $).referencedEntity != null)",
			"($ ?? $).referencedEntity", NULL_HOP));
		cells.add(refused("FR-coalesce-set-predicate", "($entity.references['tag'] ?? null).any($.groupEntity != null)",
			"$.groupEntity", NULL_HOP));
		cells.add(refused("FR-par-null-operand",
			"(($entity.references['tag'] ?? (null))).any($.referencedEntity != null)", "$.referencedEntity",
			NULL_HOP));
		cells.add(refused("FR-coalesce-in-predicate",
			"($entity.references['tag'] ?? null).any(($ ?? $).groupEntity != null)", "($ ?? $).groupEntity",
			NULL_HOP));
		cells.add(refused("FR-spread-in-coalesce",
			"(($entity.attributes['tags'] *? $entity) ?? null).any($.dropped == false)", "$.dropped",
			ENGINE_MAINTAINED));
		cells.add(refused("FR-coalesce-map-entries",
			"(null ?? $entity.localizedAttributes['name']).entries[0].value == 'x'",
			"(null ?? $entity.localizedAttributes['name']).entries", POSITIONAL));
		cells.add(refused("FR-coalesce-map-spread",
			"(null ?? $entity.localizedAttributes['name']).*[$ + '!'].entries[0].value == 'x'",
			"(null ?? $entity.localizedAttributes['name']).*[$ + '!'].entries", POSITIONAL));
		cells.add(refused("FR-coalesce-map-unknown", "(null ?? $entity.localizedAttributes['name']).keys != null",
			"(null ?? $entity.localizedAttributes['name']).keys", MISSING));
		// the reads the reject list admits stay admitted through `??`
		cells.add(accepted("FA-coalesce-set-predicate",
			"($entity.references['tag'] ?? null).any($.attributes['x'] > 0)"));
		cells.add(accepted("FA-coalesce-this-attribute",
			"$entity.references['tag'].any(($ ?? null).attributes['w'] > 1)"));
		cells.add(accepted("FA-coalesce-this-pk",
			"$entity.references['tag'].any(($ ?? null).referencedPrimaryKey > 0)"));
		return cells.stream().map(cell -> cell.withKind(IndexingExpressionKind.FACETED_PARTIALLY)).toList();
	}

	/**
	 * One expression in one slot, and the verdict the validator must reach.
	 *
	 * @param id         the cell identifier, unique within its table
	 * @param kind       the expression slot
	 * @param expression the expression text
	 * @param read       the refused read the message must name; `null` for an accepted cell, and for a refused one
	 *                   when the message says the expression has an unsupported form instead
	 * @param reason     a fragment of the reason the message must give; `null` for an accepted cell
	 */
	public record Cell(
		@Nonnull String id,
		@Nonnull IndexingExpressionKind kind,
		@Nonnull String expression,
		@Nullable String read,
		@Nullable String reason
	) {

		/**
		 * Returns the same cell in another slot.
		 *
		 * @param newKind the slot
		 * @return the cell
		 */
		@Nonnull
		public Cell withKind(@Nonnull IndexingExpressionKind newKind) {
			return new Cell(this.id, newKind, this.expression, this.read, this.reason);
		}

		/**
		 * Returns `true` when the validator must refuse the cell.
		 *
		 * @return `true` for a refused cell
		 */
		public boolean refused() {
			return this.reason != null;
		}

		/**
		 * Returns the identifier, the slot and the expression, which name the cell in a test report.
		 *
		 * @return the text naming the cell
		 */
		@Nonnull
		@Override
		public String toString() {
			return this.id + " [" + this.kind + "] " + this.expression;
		}
	}

}
