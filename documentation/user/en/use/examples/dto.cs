public class ProductStockAvailability {
    public int Id { get; set; }
    public string StockName { get; set; }

    // this field will not be serialized, because it is a private field and not a public property
    // with public getter and public setter
    private Uri stockUrl;

    // this property will not be serialized, because does not have a public getter
    public Uri StockMotive { private get; set;}

    // this property will not be serialized, it is decorated with `NonSerializableData` attribute
    [NonSerializableData]
    public Uri StockBaseUrl { get; set; }
}
