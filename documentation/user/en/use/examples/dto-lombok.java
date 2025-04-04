@Data
public class ProductStockAvailability implements Serializable {
    private int id;
    private String stockName;
    @NonSerializedData private URL stockUrl;
    @NonSerializedData private URL stockMotive;

    public boolean isActive() { return false; }
}
