EvitaResponse<ISealedEntity> entities = evita.QueryCatalog(
	"evita",
	session => session.QuerySealedEntity(
        Query(
        	Collection("Product"),
        	FilterBy(
        		EntityLocaleEquals(CultureInfo.GetCultureInfo("cs"))
        	),
        	OrderBy(
        		EntityPrimaryKeyNatural(Desc)
        	),
        	Require(
        		EntityFetch(
        			AttributeContent("name")
        		)
        	)
        )
	)
);