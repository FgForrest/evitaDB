@EntityRef("Category")
@Data
public class MyEntityEditor {

	// contains id of parent entity; or null if this entity is a root entity
	@ParentEntity
	@Nullable private Integer parentId;

	// contains parent entity; or null if this entity is a root entity
	@ParentEntity
	@Nullable private SealedEntity parentEntity;

	// contains reference to a parent entity; or null if this entity is a root entity
	@ParentEntity
	@Nullable private EntityReferenceContract parentEntityReference;

	// contains reference to a parent wrapped in this interface; or null if this entity is a root entity
	@ParentEntity
	@Nullable private MyEntityEditor parentMyEntity;

}
