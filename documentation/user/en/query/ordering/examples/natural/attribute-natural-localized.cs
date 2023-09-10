EvitaResponse<SealedEntity> entities = evita.QueryCatalog(
	"evita",
	session => session.QuerySealedEntity(
        Query(
        	Collection("Product"),
        	FilterBy(
        		EntityLocaleEquals(CultureInfo.GetCultureInfo("cs"))
        	),
        	OrderBy(
        		AttributeNatural("name", Asc)
        	),
        	Require(
        		EntityFetch(
        			AttributeContent("name")
        		)
        	)
        )
	)
);