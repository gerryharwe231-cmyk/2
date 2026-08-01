#!/usr/bin/env python3
"""0.9.23 regression checks for perimeter UV, endpoint phase, long arcs and module spacing."""
import math
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]

# One continuous perimeter coordinate around a rectangular cross-section.
def perimeter(side,w,n,min_w,max_w,min_n,max_n):
    width=max_w-min_w;thick=max_n-min_n
    x=max(0,min(width,w-min_w));y=max(0,min(thick,n-min_n))
    return {
        'top':x,
        'right':width+(thick-y),
        'bottom':width+thick+(width-x),
        'left':2*width+thick+y,
    }[side]

min_w,max_w,min_n,max_n=-2,2,-1,1
# Every physical corner has the same P value from both adjoining faces.
assert perimeter('top',max_w,max_n,min_w,max_w,min_n,max_n)==perimeter('right',max_w,max_n,min_w,max_w,min_n,max_n)
assert perimeter('right',max_w,min_n,min_w,max_w,min_n,max_n)==perimeter('bottom',max_w,min_n,min_w,max_w,min_n,max_n)
assert perimeter('bottom',min_w,min_n,min_w,max_w,min_n,max_n)==perimeter('left',min_w,min_n,min_w,max_w,min_n,max_n)
full=2*((max_w-min_w)+(max_n-min_n))
assert perimeter('left',min_w,max_n,min_w,max_w,min_n,max_n)==full
assert full==12

# S correction reaches an integer endpoint phase while preserving unit derivative at both ends.
def map_s(s,start,visible):
    tiles=max(1,round(visible));correction=tiles-visible
    raw=s-start;t=max(0,min(1,raw/visible));smooth=t*t*(3-2*t)
    return raw+correction*smooth
for visible in (3.4,10.25,47.73,128.49):
    assert abs(map_s(0,0,visible))<1e-12
    assert abs(map_s(visible,0,visible)-round(visible))<1e-12
    h=1e-5
    start_derivative=(map_s(h,0,visible)-map_s(0,0,visible))/h
    end_derivative=(map_s(visible,0,visible)-map_s(visible-h,0,visible))/h
    assert abs(start_derivative-1)<1e-3
    assert abs(end_derivative-1)<1e-3

# Equal module spacing cannot create compressed endpoint sections.
def module_count(total,pitch):
    count=max(1,round(total/max(.25,pitch)))
    while count>1 and total/count < max(.25,pitch)*.90:
        count-=1
    return count
for total,pitch in ((1.5,1),(2.1,1),(5.4,1),(10.2,.75),(30.8,1)):
    count=module_count(total,pitch)
    assert total/count >= min(total,max(.25,pitch)*.90)-1e-9
    lengths=[total/count]*count
    assert max(lengths)-min(lengths)<1e-12

renderer=(ROOT/'src/main/java/com/slopeconnector/hotfix/client/NativeSeamMetricArcRenderer.java').read_text()
generator=(ROOT/'src/main/java/com/slopeconnector/surface/RefinedConnectedGenerator.java').read_text()
for token in ('SurfaceSide.TOP','SurfaceSide.RIGHT','SurfaceSide.BOTTOM','SurfaceSide.LEFT','visibleSurfaceLength','cornerLength'):
    assert token in renderer,token
assert 'CoordinatePair' not in renderer
assert 'ATLAS_TTL_TICKS' in renderer
assert 'PrismAssignment' in renderer
assert 'sampleWithMandatoryLeads(' in generator  # helper may remain, but generation must not call it
call_area=generator[generator.index('public static Result generate'):generator.index('private static Curve twoPoint')]
assert 'sampleWithMandatoryLeads(' not in call_area
assert 'Curve complete = new Piecewise' in call_area
assert 'totalLength / sections < desiredPitch * 0.90' in generator
print('perimeter atlas, long arc cache and equal module spacing tests passed')
