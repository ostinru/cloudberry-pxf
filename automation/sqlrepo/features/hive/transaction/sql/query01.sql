-- @description query01 for PXF Hive in transaction
BEGIN;
SELECT t1 AS s1, t2 AS s2, num1 AS n1, dub1 AS d1 FROM pxf_hive_small_data ORDER BY s1;
SELECT t1 AS t0, t2 AS t1, num1, dub1 AS d1, fmt FROM pxf_hive_partitioned_table ORDER BY fmt, t0;
SELECT t1 AS s1, t2 AS s2, num1 AS n1, dub1 AS d1, dec1::NUMERIC(38,18) AS dc1, tm, r AS f, bg, b, tn, sml, dt, vc1, c1, bin FROM gpdb_hive_types ORDER BY s1;
END;

SELECT t1 AS s1, t2 AS s2, num1 AS n1, dub1 AS d1, dec1::NUMERIC(38,18) AS dc1, tm, r AS f, bg, b, tn, sml, dt, vc1, c1, bin FROM gpdb_hive_types ORDER BY s1;
SELECT t1 AS s1, t2 AS s2, num1 AS n1, dub1 AS d1 FROM pxf_hive_small_data ORDER BY s1;
SELECT t1 AS t0, t2 AS t1, num1, dub1 AS d1, fmt FROM pxf_hive_partitioned_table ORDER BY fmt, t0;
