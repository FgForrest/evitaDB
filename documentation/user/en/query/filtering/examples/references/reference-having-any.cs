EvitaResponse<ISealedEntity> entities = evita.QueryCatalog(
	"evita",
	session => session.QuerySealedEntity(
        Query(
        	Collection("Product"),
        	FilterBy(
        		ReferenceHaving("relatedProducts")
        	),
        	Require(
        		EntityFetch(
        			ReferenceContentWithAttributes(
        				"relatedProducts",
        				AttributeContent("category")
        			)
        		)
        	)
        )
	)
);