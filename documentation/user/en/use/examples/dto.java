public class ProductStockAvailability implements Serializable {
    private int id;
    private String stockName;
    @NonSerializedData
    private URL stockUrl;
    private URL stockMotive;

    // id gets serialized - both methods are present and
    // are valid JavaBean property methods
    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    // stockName gets serialized - both methods are present
    // and are valid JavaBean property methods
    public String getStockName() { return stockName; }
    public void setStockName(String stockName) { this.stockName = stockName; }

    // stockUrl will not be serialized
    // corresponding field is annotated with @NonSerializedData
    public URL getStockUrl() { return stockUrl; }
    public void setStockUrl(URL stockUrl) { this.stockUrl = stockUrl; }

    // active will not be serialized - it has no corresponding mutator method
    public boolean isActive() { return false; }

    // stock motive will not be serialized
    // because getter method is marked with @NonSerializedData
    @NonSerializedData
    public URL getStockMotive() { return stockMotive; }
    public void setStockMotive(URL stockMotive) { this.stockMotive = stockMotive; }
}
