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
}