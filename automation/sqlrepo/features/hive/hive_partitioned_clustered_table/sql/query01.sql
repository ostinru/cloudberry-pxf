-- @description query01 for PXF Hive partitioned clustered table cases
SELECT t1 AS t0, t2 AS t1, num1, dub1 AS d1, fmt FROM pxf_hive_partitioned_clustered_table ORDER BY fmt, t0;
