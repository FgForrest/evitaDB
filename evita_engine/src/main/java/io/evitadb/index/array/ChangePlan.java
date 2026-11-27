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

package io.evitadb.index.array;

import lombok.Getter;

/**
 * Reusable operation request for merging changes into an integer array.
 * Each request represents one of three states.
 *
 * - no item: there are no more modification operations requested
 * - single item: either insertion or removal is requested at the earlier pending cursor position
 * - two items: both insertion and removal is requested for the same position, INSERTION is always first, REMOVAL second
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2019
 */
class ChangePlan {
	/**
	 * Position in the original array the planned operation applies to; meaningless once the plan is exhausted.
	 */
	@Getter private int position;
	/**
	 * True when the planned operation starts with an insertion (alone or followed by a removal), false for a lone
	 * removal or an exhausted plan.
	 */
	@Getter private boolean insertion;
	/**
	 * True when an insertion and a removal are planned at the same position.
	 */
	private boolean both;
	/**
	 * True when both cursors are exhausted and nothing is left to merge.
	 */
	private boolean none;

	/**
	 * Plans the operation at the earlier non-negative cursor position, or the sole available operation.
	 * Equal non-negative positions plan both operations, with insertion before removal.
	 *
	 * Callers pass each cursor as either `-1` or a non-negative position in the original array; two negative cursors
	 * exhaust the plan and end the merge loop in {@link IntArrayChanges}.
	 *
	 * @param nextInsertionPosition position in the original array of the next non-processed insertion, or `-1` when
	 *                              no insertion is left
	 * @param nextRemovalPosition   position in the original array of the next non-processed removal, or `-1` when no
	 *                              removal is left
	 */
	void planNextOperation(int nextInsertionPosition, int nextRemovalPosition) {
		if (nextInsertionPosition >= 0) {
			if (nextRemovalPosition == -1 || nextRemovalPosition > nextInsertionPosition) {
				planInsertOperation(nextInsertionPosition);
			} else if (nextInsertionPosition == nextRemovalPosition) {
				planBothOperations(nextInsertionPosition);
			} else {
				planRemovalOperation(nextRemovalPosition);
			}
		} else if (nextRemovalPosition >= 0) {
			planRemovalOperation(nextRemovalPosition);
		} else {
			noOperations();
		}
	}

	/**
	 * Records an insertion: the merge copies the inserted values in front of the original record at `position`.
	 *
	 * @param position index into the original array of the record the values precede; equals the length of that
	 *                 array when the values follow its last record
	 */
	private void planInsertOperation(int position) {
		this.position = position;
		this.insertion = true;
		this.both = false;
		this.none = false;
	}

	/**
	 * Records a removal: the merge skips the original record at `position`.
	 *
	 * @param position index into the original array of the record to leave out
	 */
	private void planRemovalOperation(int position) {
		this.position = position;
		this.insertion = false;
		this.both = false;
		this.none = false;
	}

	/**
	 * Records an insertion followed by a removal at the same position: the merge copies the inserted values in place
	 * of the original record there, which it skips.
	 *
	 * @param position index into the original array of the record the inserted values replace
	 */
	private void planBothOperations(int position) {
		this.position = position;
		this.insertion = true;
		this.both = true;
		this.none = false;
	}

	/**
	 * Marks the plan exhausted, so {@link #hasAnythingToDo()} returns `false`; the position is reset to `0` and
	 * carries no meaning.
	 */
	private void noOperations() {
		this.position = 0;
		this.insertion = false;
		this.both = false;
		this.none = true;
	}

	/**
	 * Tells whether the plan still holds an operation for the merge loop to apply.
	 *
	 * @return `false` once both cursors are exhausted, `true` while an insertion, a removal or both are planned
	 */
	boolean hasAnythingToDo() {
		return !this.none;
	}

	/**
	 * Tells whether the planned operation is an insertion followed by a removal at the same position.
	 *
	 * @return `true` when the inserted values replace the original record at the planned `position`
	 */
	boolean bothOperationsRequested() {
		return this.both;
	}
}
