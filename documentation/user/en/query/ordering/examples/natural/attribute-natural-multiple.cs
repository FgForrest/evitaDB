EvitaResponse<SealedEntity> entities = evita.QueryCatalog(
	"evita",
	session => session.QuerySealedEntity(
        Query(
        	Collection("Product"),
        	OrderBy(
        		AttributeNatural("ean", Asc),
        		AttributeNatural("catalogNumber", Desc)
        	),
        	Require(
        		EntityFetch(
        			AttributeContent("code", "ean", "catalogNumber")
        		)
        	)
        )
	)
);