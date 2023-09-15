EvitaResponse<ISealedEntity> entities = evita.QueryCatalog(
	"evita",
	session => session.QuerySealedEntity(
        Query(
        	Collection("Product"),
        	OrderBy(
        		AttributeNatural("orderedQuantity", Desc)
        	),
        	Require(
        		EntityFetch(
        			AttributeContent("code", "orderedQuantity")
        		)
        	)
        )
	)
);