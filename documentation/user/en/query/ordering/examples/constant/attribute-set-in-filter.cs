EvitaResponse<SealedEntity> entities = evita.QueryCatalog(
	"evita",
	session => session.QuerySealedEntity(
        Query(
        	Collection("Product"),
        	FilterBy(
        		AttributeInSet(
        			"code", 
        			"msi-gs66-10sf-stealth-1", 
        			"apple-iphone-14-plus", 
        			"lenovo-thinkpad-p14s-5"
        		)
        	),
        	OrderBy(
        		AttributeSetInFilter("code")
        	),
        	Require(
        		EntityFetch(
        			AttributeContent("code")
        		)
        	)
        )
	)
);