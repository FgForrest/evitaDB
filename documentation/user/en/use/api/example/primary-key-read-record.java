@EntityRef("Product")
public record MyEntity(
	@PrimaryKey int id,
	@PrimaryKey Integer idAsIntegerObject,
	@PrimaryKey long idAsLong,
	@PrimaryKey Long idAsLongObject,

	@PrimaryKeyRef int idRef,
	@PrimaryKeyRef Integer idRefAsIntegerObject,
	@PrimaryKeyRef long idRefAsLong,
	@PrimaryKeyRef Long idRefAsLongObject
) {

}
