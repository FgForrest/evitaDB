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
 * Reusable operation request that avoid memory allocation.
 * DTO might return following operation plan:
 * <p>
 * - no item: there are no more modification operations requested
 * - single item: either insertion or removal is requested for the same position
 * - two items: both insertion and removal is requested for the same position, INSERTION is always first, REMOVAL second
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2019
 */
class ChangePlan {
	@Getter private int position;
	@Getter private boolean insertion;
	@Getter private boolean both;
	@Getter private boolean none;

	void planInsertOperation(int position) {
		this.position = position;
		this.insertion = true;
		this.both = false;
		this.none = false;
	}

	void planRemovalOperation(int position) {
		this.position = position;
		this.insertion = false;
		this.both = false;
		this.none = false;
	}

	void planBothOperations(int position) {
		this.position = position;
		this.insertion = true;
		this.both = true;
		this.none = false;
	}

	public void noOperations() {
		this.position = 0;
		this.insertion = false;
		this.both = false;
		this.none = true;
	}

	boolean hasAnythingToDo() {
		return !this.none;
	}

	boolean bothOperationsRequested() {
		return this.both;
	}
}
