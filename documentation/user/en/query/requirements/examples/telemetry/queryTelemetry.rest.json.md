```json
{
  "operation": "OVERALL",
  "start": 182792818627703,
  "steps": [
    {
      "operation": "PLANNING",
      "start": 182792818636219,
      "steps": [
        {
          "operation": "PLANNING_INDEX_USAGE",
          "start": 182792818637391,
          "steps": [ ],
          "arguments": [ ],
          "spentTime": "0.000010700s"
        },
        {
          "operation": "PLANNING_FILTER",
          "start": 182792818660134,
          "steps": [
            {
              "operation": "PLANNING_FILTER_ALTERNATIVE",
              "start": 182792818663169,
              "steps": [ ],
              "arguments": [
                "Index type: GLOBAL, estimated costs 585"
              ],
              "spentTime": "0.000884458s"
            }
          ],
          "arguments": [
            "Selected index: Index type: GLOBAL, estimated costs 585"
          ],
          "spentTime": "0.000895418s"
        },
        {
          "operation": "PLANNING_SORT",
          "start": 182792819556774,
          "steps": [ ],
          "arguments": [ ],
          "spentTime": "0.000109916s"
        },
        {
          "operation": "PLANNING_EXTRA_RESULT_FABRICATION",
          "start": 182792819667421,
          "steps": [ ],
          "arguments": [ ],
          "spentTime": "0.000008095s"
        }
      ],
      "arguments": [ ],
      "spentTime": "0.001042102s"
    },
    {
      "operation": "EXECUTION",
      "start": 182792819679143,
      "steps": [
        {
          "operation": "EXECUTION_FILTER",
          "start": 182792819686767,
          "steps": [ ],
          "arguments": [ ],
          "spentTime": "0.000039393s"
        },
        {
          "operation": "EXECUTION_SORT_AND_SLICE",
          "start": 182792819726230,
          "steps": [ ],
          "arguments": [ ],
          "spentTime": "0.000127649s"
        }
      ],
      "arguments": [ ],
      "spentTime": "0.000273029s"
    }
  ],
  "arguments": [ ],
  "spentTime": "0.001324970s"
}
```