-- @description query01 for PXF test on small data
SELECT * FROM pxf_hive_small_data_types ORDER BY name;

-- @description query02 for PXF test on small data
SELECT name, num FROM pxf_hive_small_data_types WHERE num > 50 ORDER BY name;
