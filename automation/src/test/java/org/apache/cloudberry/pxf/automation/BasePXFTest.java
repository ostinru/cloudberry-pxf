package org.apache.cloudberry.pxf.automation;

import org.apache.commons.lang.StringUtils;

import java.util.ArrayList;
import java.util.List;
import org.apache.cloudberry.pxf.automation.structures.tables.basic.Table;

public abstract class BasePXFTest {

    /**
     * Create data table with small data (100 rows).
     * Fields: name (text), num (int), dub (double), longNum (long), bool (boolean).
     */
    protected Table getSmallData() {
        return getSmallData("");
    }

    protected Table getSmallData(String uniqueName) {
        return getSmallData(uniqueName, 100);
    }

    protected Table getSmallData(String uniqueName, int numRows) {
        List<List<String>> data = new ArrayList<>();

        for (int i = 1; i <= numRows; i++) {
            List<String> row = new ArrayList<>();
            row.add(String.format("%s%srow_%d", uniqueName, StringUtils.isBlank(uniqueName) ? "" : "_", i));
            row.add(String.valueOf(i));
            row.add(String.valueOf(Double.toString(i)));
            row.add(Long.toString(100000000000L * i));
            row.add(String.valueOf(i % 2 == 0));
            data.add(row);
        }

        Table dataTable = new Table("dataTable", null);
        dataTable.setData(data);

        return dataTable;
    }
}
