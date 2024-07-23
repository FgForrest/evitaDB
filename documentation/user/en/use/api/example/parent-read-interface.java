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
public interface MyEntity {

	// return id of parent entity, or null if this entity is a root entity
	// method throws ContextMissingException if the information about parent was not fetched from the server
	@ParentEntity
	@Nullable Integer getParentId() throws ContextMissingException;

	// return optional id of parent entity, or empty if this entity is a root entity
	@ParentEntity
	@Nonnull OptionalInt getParentIdIfNotRoot();

	// return parent entity, or null if this entity is a root entity
	// method throws ContextMissingException if the information about parent was not fetched from the server
	@ParentEntity
	@Nullable SealedEntity getParentEntity() throws ContextMissingException;

	// return optional parent entity, or empty if this entity is a root entity
	@ParentEntity
	@Nonnull Optional<SealedEntity> getParentEntityIfNotRoot();

	// return reference to a parent entity, or null if this entity is a root entity
	// method throws ContextMissingException if the information about parent was not fetched from the server
	@ParentEntity
	@Nullable EntityReference getParentEntityReference() throws ContextMissingException;

	// return optional reference to a parent entity, or empty if this entity is a root entity
	@ParentEntity
	@Nonnull Optional<EntityReferenceContract> getParentEntityReferenceIfPresent();

	// return reference to a parent wrapped in this interface, or null if this entity is a root entity
	// method throws ContextMissingException if the information about parent was not fetched from the server
	@ParentEntity
	@Nullable MyEntity getParentMyEntity() throws ContextMissingException;

	// return optional reference to a parent wrapped in this interface, or empty if this entity is a root entity
	@ParentEntity
	@Nonnull Optional<MyEntity> getParentMyEntityIfPresent();

}
