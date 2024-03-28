EvitaResponse<ISealedEntity> entities = evita.QueryCatalog(
	"evita",
	session => session.QuerySealedEntity(
        Query(
        	Collection("Product"),
        	FilterBy(
        		EntityLocaleEquals(CultureInfo.GetCultureInfo("en")),
        		HierarchyWithin(
        			"categories",
        			AttributeEquals("url", "/en/smartwatches")
        		)
        	),
        	OrderBy(
        		AttributeNatural("orderedQuantity", Desc)
        	),
        	Require(
        		EntityFetch(
        			AttributeContent("name")
        		),
        		Page(1, 5)
        	)
        )
	)
);