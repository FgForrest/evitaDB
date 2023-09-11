EvitaResponse<ISealedEntity> entities = evita.QueryCatalog(
	"evita",
	session => session.QuerySealedEntity(
        Query(
        	Collection("Product"),
        	FilterBy(
        		ReferenceHaving(
        			"brand",
        			EntityHaving(
        				AttributeEquals("code", "sony")
        			)
        		)
        	),
        	OrderBy(
        		ReferenceProperty(
        			"brand",
        			AttributeNatural("orderInBrand", Asc)
        		)
        	),
        	Require(
        		EntityFetch(
        			AttributeContent("code"),
        			ReferenceContentWithAttributes(
        				"brand",
        				AttributeContent("orderInBrand")
        			)
        		)
        	)
        )
	)
);