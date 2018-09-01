import pytest
import os
from osgeo import gdal
import affine
import skope.analysis
import numpy as np

################################################################################
# Module-scoped constants defining properties of the test dataset.
################################################################################

DATASET_ROW_COUNT            = 4
DATASET_COLUMN_COUNT         = 5
DATASET_BAND_COUNT           = 6
DATASET_ORIGIN_LONGITUDE     = -123
DATASET_ORIGIN_LATITUDE      = 45
DATASET_PIXEL_WIDTH          = 1.0
DATASET_PIXEL_HEIGHT         = 2.0

################################################################################
# Test fixtures run once for this module.
################################################################################

@pytest.fixture(scope='module')
def raster_dataset(test_dataset_filename):
    '''Return a new RasterDataset.'''
    return skope.analysis.RasterDataset.new(
        filename     = test_dataset_filename(__file__),
        format       = 'GTiff',
        pixel_type   = gdal.GDT_Float32, 
        rows         = DATASET_ROW_COUNT, 
        cols         = DATASET_COLUMN_COUNT, 
        bands        = DATASET_BAND_COUNT,
        origin_long  = DATASET_ORIGIN_LONGITUDE,
        origin_lat   = DATASET_ORIGIN_LATITUDE,
        pixel_width  = DATASET_PIXEL_WIDTH,
        pixel_height = DATASET_PIXEL_HEIGHT,
        coordinate_system='WGS84'
    )


################################################################################
# Tests of the RasterDataset coordinate transformation methods.
################################################################################

def test_pixel_size(raster_dataset):
    assert raster_dataset.pixel_size == (1,2)

def test_origin(raster_dataset):
    assert raster_dataset.origin == (-123, 45)

def test_northwest_corner(raster_dataset):
    assert raster_dataset.northwest_corner == (-123, 45)

def test_northeast_corner(raster_dataset):
    assert raster_dataset.northeast_corner == (-118, 45)

def test_southeast_corner(raster_dataset):
    assert raster_dataset.southeast_corner == (-118, 37)

def test_southwest_corner(raster_dataset):
    assert raster_dataset.southwest_corner == (-123, 37)

def test_center(raster_dataset):
    assert raster_dataset.center == (-120.5, 41)

def test_pixel_for_origin_is_0_0(raster_dataset):
    assert raster_dataset.pixel_for(-123,45) == (0,0)

def test_pixel_for_point_at_center_of_northwest_pixel_is_0_0(raster_dataset):
    assert raster_dataset.pixel_for(-122.5,44) == (0,0)

def test_pixel_for_point_just_northwest_of_southeast_corner_of_northwest_pixel_is_0_0(raster_dataset):
    assert raster_dataset.pixel_for(-122.001,43.001) == (0,0)

def test_pixel_for_point_just_southeast_of_southwest_corner_of_northwest_pixel_is_0_0(raster_dataset):
    assert raster_dataset.pixel_for(-121.999,42.999) == (1,1)

def test_pixel_for_point_just_northwest_of_northwest_corner_of_northwest_pixel_is_outside_coverage(raster_dataset):
    assert raster_dataset.pixel_for(-123.001,45.001) == None

def test_pixel_for_point_just_northwest_of_southeast_corner_of_southeast_pixel_is_boottom_right_pixel(raster_dataset):
    assert raster_dataset.pixel_for(-118.001, 37.001) == (4,3)

def test_pixel_for_point_just_southeast_of_southeast_corner_of_southeast_pixel_is_outside_coverage(raster_dataset):
    assert raster_dataset.pixel_for(-117.999, 36.999) == None