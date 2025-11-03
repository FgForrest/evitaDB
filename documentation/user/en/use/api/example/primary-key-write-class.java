@EntityRef("Product")
@Data
public class MyEntityEditor {
	@PrimaryKey private int id;
	@PrimaryKey private Integer idAsIntegerObject;
	@PrimaryKey private long idAsLong;
	@PrimaryKey private Long idAsLongObject;

	@PrimaryKeyRef private int idRef;
	@PrimaryKeyRef private Integer idRefAsIntegerObject;
	@PrimaryKeyRef private long idRefAsLong;
	@PrimaryKeyRef private Long idRefAsLongObject;

}
