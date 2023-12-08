@Data
public class MyEntity {
	
	// contains id of parent entity, or null if this entity is a root entity
	@ParentEntity
	@Nullable private final Integer parentId,

	// contains parent entity, or null if this entity is a root entity
	@ParentEntity
	@Nullable private final SealedEntity parentEntity,

	// contains reference to a parent entity, or null if this entity is a root entity
	@ParentEntity
	@Nullable private final EntityReference parentEntityReference,

	// contains reference to a parent wrapped in this interface, or null if this entity is a root entity
	@ParentEntity
	@Nullable private final MyEntity parentMyEntity

}