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

package io.evitadb.api.requestResponse.schema;

import io.evitadb.api.query.filter.AttributeContains;
import io.evitadb.api.query.filter.AttributeEndsWith;
import io.evitadb.api.query.filter.AttributeStartsWith;
import io.evitadb.dataType.Scope;

/**
 * Optional accelerations an attribute's filter index may be asked to maintain **on top of** the plain filter index that
 * {@link AttributeSchemaContract#isFilterableInScope(Scope) filterable()} or
 * {@link AttributeSchemaContract#isUniqueInScope(Scope) unique()} already provides. Each constant costs additional
 * memory and additional write-path work, which is why none of them is implied by those declarations alone - an
 * attribute declares the ones its workload actually queries and pays for nothing else.
 *
 * An accelerator is declared *per scope*, on its **own builder axis** - see
 * {@link AttributeSchemaEditor#acceleratedFor(AttributeFilterAccelerator...)} - and is therefore orthogonal to
 * filterability rather than folded into it. What it is not orthogonal to is the existence of a filter index: an
 * accelerator speeds up an index that must already be there, so a scope declaring one without either `filterable()` or
 * `unique()` is refused - see {@link AttributeSchemaContract#hasFilterIndexInScope(Scope)}.
 *
 * The enum names the *accelerator the index gains*, not the physical structure that provides it - the structure is an
 * implementation detail the engine is free to change, and the schema must not pin it down.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 * @see AttributeSchemaContract#getAcceleratorsInScope(Scope)
 */
public enum AttributeFilterAccelerator {

	/**
	 * Substring matching against the attribute's values is served from a dedicated index instead of scanning every
	 * distinct value of the attribute.
	 *
	 * **What it accelerates.** {@link AttributeContains} and {@link AttributeEndsWith}, and only those two.
	 * {@link AttributeStartsWith} is deliberately excluded: it already has an anchored range-scan fast path over the
	 * shared value tree with an early break, so routing it through this index would trade a cheap prefix walk for a
	 * more expensive one. Declaring this accelerator never changes what a query *matches* - the predicate semantics are
	 * identical with and without it, only the way the candidates are found differs.
	 *
	 * **When it does not help.** Patterns shorter than three code points cannot be decomposed into a trigram and fall
	 * back to the ordinary value scan, so an attribute queried only with one- or two-character patterns gains nothing
	 * from this accelerator while still paying its memory and its write-path cost.
	 *
	 * **Where it is allowed.** Only on attributes whose type is `String` or `String[]`; any other type is refused at
	 * schema-mutation time. Enabling it on an entity collection that already holds data is refused as well - the index
	 * is built as entities are indexed and no reindexing machinery exists, so the accelerator must be declared before
	 * the data is inserted. It is also **entity-level attributes only**, including catalog-shared global ones:
	 * declaring it on a *reference* attribute is refused, because the index serving it is maintained on the entity's
	 * global index and never sees reference attribute values. That last restriction is expected to be lifted once the
	 * index learns to host them, which is why it is a refusal rather than a silent no-op - lifting a refusal is
	 * backward compatible, withdrawing a permission is not.
	 *
	 * The measurements this design rests on - the false-positive bound that makes positions unnecessary, the posting
	 * representation threshold, and the memory-per-value figures - are recorded in
	 * `documentation/adr/2026-08-24-fulltext-search-lucene-vs-inhouse/prototypes/p8-trigram-substring-index.md` §35.
	 */
	SUBSTRING_SEARCH

}
