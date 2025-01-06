```json
{
  "data" : {
    "queryProduct" : {
      "recordPage" : {
        "data" : [
          {
            "primaryKey" : 63049,
            "attributes" : {
              "code" : "macbook-pro-13-2022",
              "name" : "Macbook Pro 13 2022"
            },
            "categories" : [
              {
                "referencedPrimaryKey" : 66470,
                "referencedEntity" : {
                  "primaryKey" : 66470,
                  "parentPrimaryKey" : null,
                  "parents" : [ ],
                  "attributes" : {
                    "code" : "prepared-products",
                    "name" : "Products in preparation"
                  }
                }
              },
              {
                "referencedPrimaryKey" : 66479,
                "referencedEntity" : {
                  "primaryKey" : 66479,
                  "parentPrimaryKey" : 66467,
                  "parents" : [
                    {
                      "primaryKey" : 66467,
                      "attributes" : {
                        "code" : "laptops",
                        "name" : "Laptops",
                        "level" : 1
                      }
                    }
                  ],
                  "attributes" : {
                    "code" : "macbooks",
                    "name" : "Macbooks"
                  }
                }
              }
            ]
          }
        ]
      }
    }
  }
}
```