public interface MyEntity {

	// return id of parent entity, or null if this entity is a root entity
	@ParentEntity
	@Nullable Integer getParentId();

	// return optional id of parent entity, or empty if this entity is a root entity
	@ParentEntity
	@Nonnull OptionalInt getParentIdIfNotRoot();

	// return parent entity, or null if this entity is a root entity
	@ParentEntity
	@Nullable SealedEntity getParentEntity();

	// return optional parent entity, or empty if this entity is a root entity
	@ParentEntity
	@Nonnull Optional<SealedEntity> getParentEntityIfNotRoot();

	// return reference to a parent entity, or null if this entity is a root entity
	@ParentEntity
	@Nullable EntityReference getParentEntityReference();

	// return optional reference to a parent entity, or empty if this entity is a root entity
	@ParentEntity
	@Nonnull Optional<EntityReference> getParentEntityReferenceIfPresent();

	// return reference to a parent wrapped in this interface, or null if this entity is a root entity
	@ParentEntity
	@Nullable MyEntity getParentMyEntity();

	// return optional reference to a parent wrapped in this interface, or empty if this entity is a root entity
	@ParentEntity
	@Nonnull Optional<MyEntity> getParentMyEntityIfPresent();

}