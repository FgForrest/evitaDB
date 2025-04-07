@Data
public class SomeDataWithCollections implements Serializable {
	private List<String> names;
	private Map<String, Integer> index;
	private Set<BigDecimal> amounts;
	private SomeDataWithCollections[] innerContainers;
}
