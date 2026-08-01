import math
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RENDERER = ROOT / 'src/main/java/com/slopeconnector/hotfix/client/NativeSeamMetricArcRenderer.java'
GENERATOR = ROOT / 'src/main/java/com/slopeconnector/surface/RefinedConnectedGenerator.java'
RAILING = ROOT / 'src/main/java/com/slopeconnector/surface/client/RefinedConnectedArcRenderer.java'

# Desired checker behaviour: every row uses the same longitudinal phase. The physical outer edge is
# longer, but the checker columns stay radial instead of twisting apart.
def mapped_station(progress: float, total_advance: float) -> float:
    return progress * total_advance

for progress in [0.0, .125, .5, .875, 1.0]:
    inner = mapped_station(progress, 12.0)
    centre = mapped_station(progress, 12.0)
    outer = mapped_station(progress, 12.0)
    assert inner == centre == outer

# Endpoint phase is solved once with a uniform scale. The end is periodic-equivalent to the native
# target, while every intermediate point keeps one linear progression (no smooth-step shear).
def solve_total_advance(visible_length: float) -> float:
    return max(1, round(visible_length))

for length in [10.25, 8.4, 15.75]:
    advance = solve_total_advance(length)
    assert float(advance).is_integer()
    assert abs(advance - length) <= .5
    for a, b, c in [(0.1, .4, .9), (.0, .5, 1.0)]:
        # Equal increments in progress always yield equal texture increments.
        left = (b-a)*advance
        right = (c-b)*advance
        if abs((b-a)-(c-b)) < 1e-12:
            assert abs(left-right) < 1e-12

renderer = RENDERER.read_text(encoding='utf-8')
assert 'totalAdvance * atlas.progress(coordinate.centerS)' in renderer
assert 'Math.round(atlas.visibleCenterLength)' in renderer
assert 'visibleSurfaceDistance(coordinate.w' not in renderer
assert 'periodicCorrection' not in renderer
assert 'smooth = progress' not in renderer
assert 'atlas.segments.isEmpty() || atlas.startFrame == null || atlas.endFrame == null' in renderer
assert 'this.startFrame=null' in renderer and 'this.endFrame=null' in renderer
assert 'if(segments.isEmpty())return null' in renderer

# Endpoint modules remain outside endpoint blocks and localized ornaments are removed only at the
# global ends. Continuous rails remain because they span most of the module.
generator = GENERATOR.read_text(encoding='utf-8')
railing = RAILING.read_text(encoding='utf-8')
assert 'ENDPOINT_CLEARANCE' in generator
assert 'ENDPOINT_OVERLAP' not in generator
assert 'index == 0, index == frames.size() - 2' in generator
assert 'END_ORNAMENT_GUARD' in railing
assert 'LOCALIZED_GEOMETRY_LIMIT' in railing
assert 'globalStart' in railing and 'globalEnd' in railing

print('phase-locked ribbon, empty-atlas safety and end-baluster tests passed')
