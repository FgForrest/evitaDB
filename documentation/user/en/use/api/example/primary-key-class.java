@EntityRef("Product")
@Data
public class MyEntity {
	@PrimaryKey private final int id;
	@PrimaryKey private final Integer idAsIntegerObject;
	@PrimaryKey private final long idAsLong;
	@PrimaryKey private final Long idAsLongObject;

	@PrimaryKeyRef private final int idRef;
	@PrimaryKeyRef private final Integer idRefAsIntegerObject;
	@PrimaryKeyRef private final long idRefAsLong;
	@PrimaryKeyRef private final Long idRefAsLongObject;

	// constructor is usually generated by Lombok
	public MyEntity(
		int id, Integer idAsIntegerObject, long idAsLong, Long idAsLongObject,
		int idRef, Integer idRefAsIntegerObject, long idRefAsLong, Long idRefAsLongObject
	) {
		this.id = id;
		this.idAsIntegerObject = idAsIntegerObject;
		this.idAsLong = idAsLong;
		this.idAsLongObject = idAsLongObject;
		this.idRef = idRef;
		this.idRefAsIntegerObject = idRefAsIntegerObject;
		this.idRefAsLong = idRefAsLong;
		this.idRefAsLongObject = idRefAsLongObject;
	}

}