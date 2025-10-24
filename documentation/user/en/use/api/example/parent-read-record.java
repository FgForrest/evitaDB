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
