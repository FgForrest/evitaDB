/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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

@EntityRef("Category")
public record MyEntity(
	// contains id of parent entity, or null if this entity is a root entity
	@ParentEntity
	@Nullable Integer parentId,

	// contains parent entity, or null if this entity is a root entity
	@ParentEntity
	@Nullable SealedEntity parentEntity,

	// contains reference to a parent entity, or null if this entity is a root entity
	@ParentEntity
	@Nullable EntityReference parentEntityReference,

	// contains reference to a parent wrapped in this interface, or null if this entity is a root entity
	@ParentEntity
	@Nullable MyEntity parentMyEntity
) {
}
