package com.slopeconnector.hotfix.client;

import com.slopeconnector.hotfix.ArcRibbonBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * 0.9.24 surface-metric renderer.
 *
 * <p>Texture distance is measured on the actual offset surface, not on the centre line. Inner and
 * outer walls therefore receive their own physical arc lengths and no longer squeeze or stretch
 * checker cells. Every face is anchored to the UVs of the imaginary native block immediately on
 * the arc side of both endpoint faces. The end phase is corrected by at most half a tile, which
 * keeps the complete arc continuous while matching both native endpoint blocks.</p>
 */
public final class NativeSeamMetricArcRenderer {
    private static final float EPS = 1.0E-6f;
    private static final float JOIN_EPS = 0.22f;
    private static final int DISCOVERY_RADIUS = 3;
    private static final int MAX_COMPONENT_ENTITIES = 4096;
    private static final int MAX_COMPONENT_SEGMENTS = 131072;
    private static final int MAX_TILE_CELLS_PER_TRIANGLE = 16384;
    private static final long ATLAS_TTL_TICKS = 10L;

    private static final Map<ArcRibbonBlockEntity, CompiledMesh> MESH_CACHE = new WeakHashMap<>();
    private static final Map<ArcRibbonBlockEntity, AtlasHandle> ATLAS_CACHE = new WeakHashMap<>();

    private NativeSeamMetricArcRenderer() {}

    public static void renderReplacement(ArcRibbonBlockEntity entity, float tickDelta,
                                         MatrixStack matrices, VertexConsumerProvider consumers,
                                         int light, int overlay) {
        if (entity.getWorld() == null) return;
        ComponentAtlas atlas = atlasFor(entity);
        if (atlas.segments.isEmpty()) return;
        CompiledMesh mesh = MESH_CACHE.get(entity);
        if (mesh == null || mesh.entityRevision != entity.getRenderRevision()
                || mesh.atlasRevision != atlas.revision) {
            mesh = compile(entity, atlas);
            MESH_CACHE.put(entity, mesh);
        }
        if (mesh.triangles.isEmpty()) return;

        VertexConsumer consumer = consumers.getBuffer(RenderLayers.getBlockLayer(entity.getSourceState()));
        MatrixStack.Entry entry = matrices.peek();
        Matrix4f position = entry.getPositionMatrix();
        Matrix3f normal = entry.getNormalMatrix();
        int[] directionalLights = {-1, -1, -1, -1, -1, -1};
        BlockPos holder = entity.getPos();
        for (Triangle triangle : mesh.triangles) {
            int index = triangle.direction.ordinal();
            int packedLight = directionalLights[index];
            if (packedLight < 0) {
                int sampled = WorldRenderer.getLightmapCoordinates(entity.getWorld(),
                        entity.getSourceState(), holder.offset(triangle.direction));
                packedLight = maxPacked(light, sampled);
                directionalLights[index] = packedLight;
            }
            int color = triangle.material.color();
            int red = (color >> 16) & 255;
            int green = (color >> 8) & 255;
            int blue = color & 255;
            emit(consumer, position, normal, triangle.a, triangle, packedLight, overlay, red, green, blue);
            emit(consumer, position, normal, triangle.b, triangle, packedLight, overlay, red, green, blue);
            emit(consumer, position, normal, triangle.c, triangle, packedLight, overlay, red, green, blue);
            emit(consumer, position, normal, triangle.c, triangle, packedLight, overlay, red, green, blue);
        }
    }

    private static CompiledMesh compile(ArcRibbonBlockEntity entity, ComponentAtlas atlas) {
        List<Triangle> output = new ArrayList<>();
        for (ArcRibbonBlockEntity.Prism prism : entity.getPrisms()) {
            PrismAssignment assignment = atlas.assignment(entity, prism);
            if (assignment == null) continue;
            float[] xyz = prism.xyz();
            if (prism.draws(0)) addFace(entity, atlas, assignment, output, xyz,
                    new int[]{0,4,5,1}, SurfaceSide.BOTTOM, prism.materialHint());
            if (prism.draws(1)) addFace(entity, atlas, assignment, output, xyz,
                    new int[]{3,2,6,7}, SurfaceSide.TOP, prism.materialHint());
            if (prism.draws(2)) addFace(entity, atlas, assignment, output, xyz,
                    new int[]{0,3,7,4}, SurfaceSide.LEFT, prism.materialHint());
            if (prism.draws(3)) addFace(entity, atlas, assignment, output, xyz,
                    new int[]{1,5,6,2}, SurfaceSide.RIGHT, prism.materialHint());
            // Component endpoint blocks are rendered natively. End caps inside the arc are still
            // allowed when the generator explicitly exposes them.
            if (prism.draws(4)) addFace(entity, atlas, assignment, output, xyz,
                    new int[]{0,1,2,3}, SurfaceSide.START_CAP, prism.materialHint());
            if (prism.draws(5)) addFace(entity, atlas, assignment, output, xyz,
                    new int[]{4,7,6,5}, SurfaceSide.END_CAP, prism.materialHint());
        }
        return new CompiledMesh(entity.getRenderRevision(), atlas.revision, List.copyOf(output));
    }

    private static void addFace(ArcRibbonBlockEntity entity, ComponentAtlas atlas,
                                PrismAssignment assignment, List<Triangle> output,
                                float[] source, int[] ids, SurfaceSide side, byte materialHint) {
        GeometryVertex[] corners = new GeometryVertex[4];
        for (int i = 0; i < 4; i++) {
            int vi = ids[i];
            int p = vi * 3;
            float lx = source[p], ly = source[p + 1], lz = source[p + 2];
            Vec3 world = new Vec3(entity.getPos().getX() + lx,
                    entity.getPos().getY() + ly, entity.getPos().getZ() + lz);
            CurveCoordinate coordinate = atlas.coordinate(assignment, vi < 4, world);
            corners[i] = new GeometryVertex(lx, ly, lz, coordinate);
        }

        Vector3f faceNormal = normal(corners[0], corners[1], corners[2]);
        if (faceNormal == null) return;
        GeometryVertex faceCenter = average(corners);
        GeometryVertex prismCenter = prismCenter(entity, source, atlas, assignment);
        Vector3f outward = new Vector3f(faceCenter.x - prismCenter.x,
                faceCenter.y - prismCenter.y, faceCenter.z - prismCenter.z);
        if (faceNormal.dot(outward) < 0.0f) {
            GeometryVertex swap = corners[1]; corners[1] = corners[3]; corners[3] = swap;
            faceNormal.mul(-1.0f);
        }
        Direction lightingDirection = ArcMaterialHelper.dominant(faceNormal.x, faceNormal.y, faceNormal.z);

        float edgeA = Math.max(distance(corners[0], corners[1]), distance(corners[3], corners[2]));
        float edgeB = Math.max(distance(corners[0], corners[3]), distance(corners[1], corners[2]));
        float aspect = Math.max(edgeA, edgeB) / Math.max(1.0E-4f, Math.min(edgeA, edgeB));
        float area = Math.max(1.0E-4f, edgeA * edgeB);
        TextureMapper mapper = TextureMapper.create(entity, atlas, side, materialHint, aspect, area);
        if (mapper == null) return;

        ParameterVertex[] vertices = new ParameterVertex[4];
        for (int i = 0; i < 4; i++) {
            CurveCoordinate coordinate = corners[i].coordinate;
            float[] uv = mapper.uv(atlas, coordinate);
            vertices[i] = new ParameterVertex(corners[i].x, corners[i].y, corners[i].z,
                    atlas.visibleCoordinate(coordinate.centerS), uv[0], uv[1]);
        }

        List<ParameterVertex> visible = new ArrayList<>(List.of(vertices));
        visible = clip(visible, 0, 0.0f, true);
        visible = clip(visible, 0, atlas.visibleCenterLength, false);
        if (visible.size() < 3) return;
        ParameterVertex origin = visible.get(0);
        for (int i = 1; i < visible.size() - 1; i++) {
            splitTriangleByTiles(output, origin, visible.get(i), visible.get(i + 1),
                    lightingDirection, mapper.startMaterial);
        }
    }

    private static GeometryVertex prismCenter(ArcRibbonBlockEntity entity, float[] source,
                                              ComponentAtlas atlas, PrismAssignment assignment) {
        float x = 0, y = 0, z = 0;
        for (int i = 0; i < 8; i++) {
            x += source[i * 3]; y += source[i * 3 + 1]; z += source[i * 3 + 2];
        }
        x /= 8.0f; y /= 8.0f; z /= 8.0f;
        Vec3 world = new Vec3(entity.getPos().getX() + x,
                entity.getPos().getY() + y, entity.getPos().getZ() + z);
        return new GeometryVertex(x, y, z, atlas.coordinate(assignment, false, world));
    }

    /**
     * Phase-locked curvilinear texture mapper.
     *
     * <p>All vertices across the ribbon width use the same longitudinal progress. This keeps the
     * checker columns radial, like a single bent sheet, instead of letting inner and outer rows run
     * at different U speeds. Endpoint phase matching is solved once per face with one uniform scale;
     * no per-vertex or smooth-step correction is allowed.</p>
     */
    private static final class TextureMapper {
        final SurfaceSide side;
        final Direction startDirection;
        final Direction endDirection;
        final ArcMaterialHelper.FaceMaterial startMaterial;
        final ArcMaterialHelper.FaceMaterial endMaterial;
        final BlockPos startCell;
        final BlockPos endCell;
        final Vec3 longitudinalParameter;
        final float totalAdvance;

        private TextureMapper(SurfaceSide side, Direction startDirection, Direction endDirection,
                              ArcMaterialHelper.FaceMaterial startMaterial,
                              ArcMaterialHelper.FaceMaterial endMaterial,
                              BlockPos startCell, BlockPos endCell,
                              Vec3 longitudinalParameter, float totalAdvance) {
            this.side = side;
            this.startDirection = startDirection;
            this.endDirection = endDirection;
            this.startMaterial = startMaterial;
            this.endMaterial = endMaterial;
            this.startCell = startCell;
            this.endCell = endCell;
            this.longitudinalParameter = longitudinalParameter;
            this.totalAdvance = totalAdvance;
        }

        static TextureMapper create(ArcRibbonBlockEntity entity, ComponentAtlas atlas,
                                    SurfaceSide side, byte materialHint, float aspect, float area) {
            if (atlas.segments.isEmpty() || atlas.startFrame == null || atlas.endFrame == null) return null;
            Frame startFrame = atlas.startFrame;
            Frame endFrame = atlas.endFrame;
            Direction startDirection = side.direction(startFrame);
            Direction endDirection = side.direction(endFrame);
            ArcMaterialHelper.FaceMaterial startMaterial = ArcMaterialHelper.material(
                    entity.getSourceState(), startDirection, entity.getWorld(), entity.getPos(),
                    materialHint, aspect, area);
            ArcMaterialHelper.FaceMaterial endMaterial = ArcMaterialHelper.material(
                    entity.getSourceState(), endDirection, entity.getWorld(), entity.getPos(),
                    materialHint, aspect, area);
            Vec3 startCenter = startFrame.center;
            Vec3 endCenter = endFrame.center;
            BlockPos startCell = BlockPos.ofFloored(startCenter.x + startFrame.tangent.x * 0.002,
                    startCenter.y + startFrame.tangent.y * 0.002,
                    startCenter.z + startFrame.tangent.z * 0.002);
            BlockPos endCell = BlockPos.ofFloored(endCenter.x - endFrame.tangent.x * 0.002,
                    endCenter.y - endFrame.tangent.y * 0.002,
                    endCenter.z - endFrame.tangent.z * 0.002);
            float[] p0 = localFace(startDirection, startCenter, startCell);
            float[] p1 = localFace(startDirection, startCenter.add(startFrame.tangent), startCell);
            Vec3 longParameter = new Vec3(p1[0] - p0[0], p1[1] - p0[1], 0);
            float length = longParameter.length();
            if (length < EPS) return null;
            longParameter = longParameter.multiply(1.0f / length);

            float referenceW = (atlas.minW + atlas.maxW) * 0.5f;
            float referenceN = (atlas.minN + atlas.maxN) * 0.5f;
            switch (side) {
                case LEFT -> referenceW = atlas.minW;
                case RIGHT -> referenceW = atlas.maxW;
                case BOTTOM -> referenceN = atlas.minN;
                case TOP -> referenceN = atlas.maxN;
                default -> { }
            }
            // One shared integer number of longitudinal texture cycles is used on TOP, BOTTOM,
            // LEFT and RIGHT. Choosing the count once from the complete visible centre length keeps
            // radial checker columns aligned across all four faces and limits uniform scale error to
            // at most half a tile over the entire arc.
            float totalAdvance = Math.max(1.0f, Math.round(atlas.visibleCenterLength));
            return new TextureMapper(side, startDirection, endDirection,
                    startMaterial, endMaterial, startCell, endCell, longParameter, totalAdvance);
        }

        float[] uv(ComponentAtlas atlas, CurveCoordinate coordinate) {
            if (side == SurfaceSide.START_CAP || side == SurfaceSide.END_CAP) {
                Frame frame = side == SurfaceSide.START_CAP ? atlas.startFrame : atlas.endFrame;
                BlockPos cell = side == SurfaceSide.START_CAP ? startCell : endCell;
                Direction direction = side == SurfaceSide.START_CAP ? startDirection : endDirection;
                Vec3 point = frame.center.add(frame.width.multiply(coordinate.w))
                        .add(frame.radial.multiply(coordinate.n));
                return localFace(direction, point, cell);
            }
            Vec3 startPoint = atlas.startPoint(coordinate.w, coordinate.n);
            float[] start = localFace(startDirection, startPoint, startCell);
            float advance = totalAdvance * atlas.progress(coordinate.centerS);
            return new float[]{
                    start[0] + longitudinalParameter.x * advance,
                    start[1] + longitudinalParameter.y * advance
            };
        }
    }

    private static float[] inverse(ArcMaterialHelper.FaceMaterial material,
                                   float targetU, float targetV, float[] fallback) {
        float det = material.uS() * material.vT() - material.uT() * material.vS();
        if (Math.abs(det) < 1.0E-8f) return fallback;
        float du = targetU - material.uC();
        float dv = targetV - material.vC();
        return new float[]{
                (du * material.vT() - material.uT() * dv) / det,
                (material.uS() * dv - du * material.vS()) / det
        };
    }

    private static float[] localFace(Direction direction, Vec3 point, BlockPos cell) {
        float x = point.x - cell.getX();
        float y = point.y - cell.getY();
        float z = point.z - cell.getZ();
        return switch (direction.getAxis()) {
            case Y -> new float[]{x, z};
            case X -> new float[]{z, y};
            case Z -> new float[]{x, y};
        };
    }

    private static void splitTriangleByTiles(List<Triangle> output,
                                             ParameterVertex a, ParameterVertex b, ParameterVertex c,
                                             Direction direction,
                                             ArcMaterialHelper.FaceMaterial material) {
        float minU = Math.min(a.u, Math.min(b.u, c.u));
        float maxU = Math.max(a.u, Math.max(b.u, c.u));
        float minV = Math.min(a.v, Math.min(b.v, c.v));
        float maxV = Math.max(a.v, Math.max(b.v, c.v));
        int firstU = floorTile(minU), lastU = maxU - minU < EPS ? firstU : ceilTile(maxU) - 1;
        int firstV = floorTile(minV), lastV = maxV - minV < EPS ? firstV : ceilTile(maxV) - 1;
        long cells = (long)(lastU - firstU + 1) * (long)(lastV - firstV + 1);
        if (cells > MAX_TILE_CELLS_PER_TRIANGLE) {
            subdivide(output, a, b, c, direction, material, 0);
            return;
        }
        List<ParameterVertex> original = List.of(a, b, c);
        for (int tileU = firstU; tileU <= lastU; tileU++) {
            for (int tileV = firstV; tileV <= lastV; tileV++) {
                List<ParameterVertex> polygon = new ArrayList<>(original);
                polygon = clip(polygon, 1, tileU, true);
                polygon = clip(polygon, 1, tileU + 1.0f, false);
                polygon = clip(polygon, 2, tileV, true);
                polygon = clip(polygon, 2, tileV + 1.0f, false);
                if (polygon.size() < 3) continue;
                ParameterVertex origin = polygon.get(0).localize(tileU, tileV);
                for (int i = 1; i < polygon.size() - 1; i++) {
                    addTriangle(output, origin, polygon.get(i).localize(tileU, tileV),
                            polygon.get(i + 1).localize(tileU, tileV), direction, material);
                }
            }
        }
    }

    private static void subdivide(List<Triangle> output, ParameterVertex a, ParameterVertex b,
                                  ParameterVertex c, Direction direction,
                                  ArcMaterialHelper.FaceMaterial material, int depth) {
        float rangeU = max(a.u,b.u,c.u)-min(a.u,b.u,c.u);
        float rangeV = max(a.v,b.v,c.v)-min(a.v,b.v,c.v);
        if (Math.max(rangeU, rangeV) <= 6.0f || depth >= 16) {
            if (depth >= 16) {
                // Never collapse a long face to one tile. Emit local geometric pieces only.
                ParameterVertex ab=a.lerp(b,.5f), bc=b.lerp(c,.5f), ca=c.lerp(a,.5f);
                splitTriangleByTiles(output,a,ab,ca,direction,material);
                splitTriangleByTiles(output,ab,b,bc,direction,material);
                splitTriangleByTiles(output,ca,bc,c,direction,material);
                splitTriangleByTiles(output,ab,bc,ca,direction,material);
            } else splitTriangleByTiles(output,a,b,c,direction,material);
            return;
        }
        ParameterVertex ab=a.lerp(b,.5f), bc=b.lerp(c,.5f), ca=c.lerp(a,.5f);
        subdivide(output,a,ab,ca,direction,material,depth+1);
        subdivide(output,ab,b,bc,direction,material,depth+1);
        subdivide(output,ca,bc,c,direction,material,depth+1);
        subdivide(output,ab,bc,ca,direction,material,depth+1);
    }

    private static List<ParameterVertex> clip(List<ParameterVertex> input, int axis,
                                              float boundary, boolean keepGreater) {
        if (input.isEmpty()) return input;
        List<ParameterVertex> output = new ArrayList<>();
        ParameterVertex previous = input.get(input.size() - 1);
        boolean previousInside = inside(previous, axis, boundary, keepGreater);
        for (ParameterVertex current : input) {
            boolean currentInside = inside(current, axis, boundary, keepGreater);
            if (currentInside != previousInside) {
                float pv = previous.axis(axis), cv = current.axis(axis);
                float amount = Math.abs(cv-pv)<EPS ? 0.0f : (boundary-pv)/(cv-pv);
                output.add(previous.lerp(current, clamp01(amount)));
            }
            if (currentInside) output.add(current);
            previous=current; previousInside=currentInside;
        }
        return output;
    }

    private static boolean inside(ParameterVertex vertex, int axis,
                                  float boundary, boolean keepGreater) {
        float value = vertex.axis(axis);
        return keepGreater ? value >= boundary - EPS : value <= boundary + EPS;
    }

    private static void addTriangle(List<Triangle> output, ParameterVertex a,
                                    ParameterVertex b, ParameterVertex c,
                                    Direction expectedDirection,
                                    ArcMaterialHelper.FaceMaterial material) {
        Vector3f n = normal(a,b,c);
        if (n == null) return;
        Vector3f expected = new Vector3f(expectedDirection.getOffsetX(), expectedDirection.getOffsetY(), expectedDirection.getOffsetZ());
        if (n.dot(expected) < 0.0f) { ParameterVertex swap=b; b=c; c=swap; n.mul(-1.0f); }
        output.add(new Triangle(a,b,c,n.x,n.y,n.z,
                ArcMaterialHelper.dominant(n.x,n.y,n.z),material));
    }

    private static ComponentAtlas atlasFor(ArcRibbonBlockEntity entity) {
        long tick = entity.getWorld() == null ? 0L : entity.getWorld().getTime();
        AtlasHandle cached = ATLAS_CACHE.get(entity);
        if (cached != null && cached.entityRevision == entity.getRenderRevision()
                && tick - cached.builtTick >= 0 && tick - cached.builtTick < ATLAS_TTL_TICKS) return cached.atlas;
        ComponentAtlas atlas = ComponentAtlas.build(entity);
        for (ArcRibbonBlockEntity member : atlas.members) {
            ATLAS_CACHE.put(member,new AtlasHandle(member.getRenderRevision(),tick,atlas));
        }
        return atlas;
    }

    private static final class ComponentAtlas {
        final List<ArcRibbonBlockEntity> members;
        final List<MetricSegment> segments;
        final int revision;
        final float minW,maxW,minN,maxN,widthSpan,thicknessSpan;
        final float startInset,endInset,visibleCenterLength;
        final Frame startFrame,endFrame;

        private ComponentAtlas(List<ArcRibbonBlockEntity> members,List<MetricSegment> segments,int revision,
                               float minW,float maxW,float minN,float maxN,
                               float startInset,float endInset) {
            this.members=members;this.segments=segments;this.revision=revision;
            this.minW=minW;this.maxW=maxW;this.minN=minN;this.maxN=maxN;
            this.widthSpan=Math.max(EPS,maxW-minW);this.thicknessSpan=Math.max(EPS,maxN-minN);
            this.startInset=startInset;this.endInset=endInset;
            float total=segments.isEmpty()?0:segments.get(segments.size()-1).centerS0+segments.get(segments.size()-1).centerLength;
            this.visibleCenterLength=Math.max(EPS,total-startInset-endInset);
            if (segments.isEmpty()) {
                this.startFrame=null;
                this.endFrame=null;
            } else {
                this.startFrame=frameAtStart();
                this.endFrame=frameAtEnd();
            }
        }

        static ComponentAtlas build(ArcRibbonBlockEntity target) {
            List<ArcRibbonBlockEntity> members=discoverComponent(target);
            List<RawSegment> raw=new ArrayList<>();int revision=1;
            for (ArcRibbonBlockEntity member:members) {
                revision=31*revision+member.getRenderRevision();revision=31*revision+member.getPos().hashCode();
                extractSegments(member,raw);if(raw.size()>=MAX_COMPONENT_SEGMENTS)break;
            }
            List<OrderedSegment> ordered=orderSegments(raw);
            if(ordered.isEmpty())return new ComponentAtlas(members,List.of(),revision,-.5f,.5f,-.5f,.5f,0,0);
            float minW=Float.POSITIVE_INFINITY,maxW=Float.NEGATIVE_INFINITY,minN=Float.POSITIVE_INFINITY,maxN=Float.NEGATIVE_INFINITY;
            for(ArcRibbonBlockEntity member:members)for(ArcRibbonBlockEntity.Prism prism:member.getPrisms()) {
                OrderedAssignment a=assignmentOf(ordered,member,prism);if(a==null)continue;float[] xyz=prism.xyz();
                for(int v=0;v<8;v++) {
                    int p=v*3;Vec3 point=new Vec3(member.getPos().getX()+xyz[p],member.getPos().getY()+xyz[p+1],member.getPos().getZ()+xyz[p+2]);
                    Frame f=a.frame(v<4);Vec3 off=point.subtract(f.center);
                    float w=off.dot(f.width),n=off.dot(f.radial);
                    minW=Math.min(minW,w);maxW=Math.max(maxW,w);minN=Math.min(minN,n);maxN=Math.max(maxN,n);
                }
            }
            if(!Float.isFinite(minW)){minW=-.5f;maxW=.5f;}if(!Float.isFinite(minN)){minN=-.5f;maxN=.5f;}
            float[] cumulative=new float[4];List<MetricSegment> metric=new ArrayList<>();
            float centerS=0;
            for(int i=0;i<ordered.size();i++) {
                OrderedSegment o=ordered.get(i);float[] starts=cumulative.clone(),lengths=new float[4];
                for(int k=0;k<4;k++) {
                    float w=(k&1)==0?minW:maxW;float n=(k&2)==0?minN:maxN;
                    Vec3 p0=o.c0.add(o.width0.multiply(w)).add(o.radial0.multiply(n));
                    Vec3 p1=o.c1.add(o.width1.multiply(w)).add(o.radial1.multiply(n));
                    lengths[k]=p1.subtract(p0).length();cumulative[k]+=lengths[k];
                }
                metric.add(new MetricSegment(i,o.c0,o.c1,o.width0,o.width1,o.radial0,o.radial1,o.centerLength,centerS,starts,lengths));
                centerS+=o.centerLength;
            }
            MetricSegment first=metric.get(0),last=metric.get(metric.size()-1);
            float startInset=gridInset(first.c0,first.tangent(0));
            float endInset=gridInset(last.c1,last.tangent(1).multiply(-1));
            revision=31*revision+metric.size();revision=31*revision+Float.floatToIntBits(centerS);
            revision=31*revision+Float.floatToIntBits(minW);revision=31*revision+Float.floatToIntBits(maxW);
            return new ComponentAtlas(members,List.copyOf(metric),revision,minW,maxW,minN,maxN,startInset,endInset);
        }

        CurveCoordinate coordinate(PrismAssignment assignment,boolean atFirst,Vec3 point) {
            boolean start=atFirst^assignment.reversed;MetricSegment segment=assignment.segment;
            Frame frame=segment.frame(start?0:1);Vec3 off=point.subtract(frame.center);
            float w=off.dot(frame.width),n=off.dot(frame.radial);
            float centerS=segment.centerS0+(start?0:segment.centerLength);
            float surfaceS=segment.surfaceStation(start,w,n,minW,maxW,minN,maxN);
            return new CurveCoordinate(centerS,surfaceS,w,n);
        }

        PrismAssignment assignment(ArcRibbonBlockEntity entity,ArcRibbonBlockEntity.Prism prism) {
            float[] xyz=prism.xyz();Vec3 offset=new Vec3(entity.getPos().getX(),entity.getPos().getY(),entity.getPos().getZ());
            Vec3 c0=average(xyz,0,4).add(offset),c1=average(xyz,4,8).add(offset);
            MetricSegment best=null;boolean reversed=false;float distance=Float.POSITIVE_INFINITY;
            for(MetricSegment s:segments) {
                float direct=c0.distanceSquared(s.c0)+c1.distanceSquared(s.c1);if(direct<distance){distance=direct;best=s;reversed=false;}
                float reverse=c0.distanceSquared(s.c1)+c1.distanceSquared(s.c0);if(reverse<distance){distance=reverse;best=s;reversed=true;}
            }
            return best==null?null:new PrismAssignment(best,reversed);
        }

        float visibleCoordinate(float centerS){return centerS-startInset;}
        float progress(float centerS){return clamp01((centerS-startInset)/visibleCenterLength);}

        float visibleSurfaceDistance(float w,float n,float surfaceS) {
            return surfaceS-visibleSurfaceStart(w,n);
        }
        float visibleSurfaceLength(float w,float n) {
            return Math.max(EPS,visibleSurfaceEnd(w,n)-visibleSurfaceStart(w,n));
        }
        private float visibleSurfaceStart(float w,float n) {
            if (segments.isEmpty()) return 0.0f;
            MetricSegment first=segments.get(0);float f=first.centerLength<EPS?0:startInset/first.centerLength;
            return first.surfaceStationFraction(f,w,n,minW,maxW,minN,maxN);
        }
        private float visibleSurfaceEnd(float w,float n) {
            if (segments.isEmpty()) return 0.0f;
            MetricSegment last=segments.get(segments.size()-1);float f=last.centerLength<EPS?1:1-endInset/last.centerLength;
            return last.surfaceStationFraction(f,w,n,minW,maxW,minN,maxN);
        }
        Frame frameAtStart(){if(segments.isEmpty())return null;MetricSegment s=segments.get(0);return s.frame(s.centerLength<EPS?0:startInset/s.centerLength);}
        Frame frameAtEnd(){if(segments.isEmpty())return null;MetricSegment s=segments.get(segments.size()-1);return s.frame(s.centerLength<EPS?1:1-endInset/s.centerLength);}
        Vec3 startPoint(float w,float n){if(startFrame==null)return new Vec3(0,0,0);return startFrame.center.add(startFrame.width.multiply(w)).add(startFrame.radial.multiply(n));}
        Vec3 endPoint(float w,float n){if(endFrame==null)return new Vec3(0,0,0);return endFrame.center.add(endFrame.width.multiply(w)).add(endFrame.radial.multiply(n));}
    }

    private static OrderedAssignment assignmentOf(List<OrderedSegment> segments,ArcRibbonBlockEntity entity,ArcRibbonBlockEntity.Prism prism) {
        float[] xyz=prism.xyz();Vec3 offset=new Vec3(entity.getPos().getX(),entity.getPos().getY(),entity.getPos().getZ());
        Vec3 c0=average(xyz,0,4).add(offset),c1=average(xyz,4,8).add(offset);OrderedSegment best=null;boolean reversed=false;float d=Float.POSITIVE_INFINITY;
        for(OrderedSegment s:segments){float a=c0.distanceSquared(s.c0)+c1.distanceSquared(s.c1);if(a<d){d=a;best=s;reversed=false;}float b=c0.distanceSquared(s.c1)+c1.distanceSquared(s.c0);if(b<d){d=b;best=s;reversed=true;}}
        return best==null?null:new OrderedAssignment(best,reversed);
    }

    private static List<ArcRibbonBlockEntity> discoverComponent(ArcRibbonBlockEntity target) {
        if(target.getWorld()==null)return List.of(target);Set<ArcRibbonBlockEntity> accepted=Collections.newSetFromMap(new IdentityHashMap<>());Deque<ArcRibbonBlockEntity> queue=new ArrayDeque<>();Map<ArcRibbonBlockEntity,List<RawSegment>> cache=new IdentityHashMap<>();accepted.add(target);queue.add(target);
        while(!queue.isEmpty()&&accepted.size()<MAX_COMPONENT_ENTITIES){ArcRibbonBlockEntity current=queue.removeFirst();List<RawSegment> currentSegments=cache.computeIfAbsent(current,NativeSeamMetricArcRenderer::segmentsOf);BlockPos origin=current.getPos();
            for(int dx=-DISCOVERY_RADIUS;dx<=DISCOVERY_RADIUS;dx++)for(int dy=-DISCOVERY_RADIUS;dy<=DISCOVERY_RADIUS;dy++)for(int dz=-DISCOVERY_RADIUS;dz<=DISCOVERY_RADIUS;dz++){BlockEntity be=target.getWorld().getBlockEntity(origin.add(dx,dy,dz));if(!(be instanceof ArcRibbonBlockEntity candidate)||accepted.contains(candidate)||!candidate.getSourceState().equals(target.getSourceState()))continue;List<RawSegment> candidateSegments=cache.computeIfAbsent(candidate,NativeSeamMetricArcRenderer::segmentsOf);if(connected(currentSegments,candidateSegments)){accepted.add(candidate);queue.add(candidate);}}}
        List<ArcRibbonBlockEntity> result=new ArrayList<>(accepted);result.sort(Comparator.comparing(ArcRibbonBlockEntity::getPos,NativeSeamMetricArcRenderer::comparePos));return List.copyOf(result);
    }
    private static boolean connected(List<RawSegment>a,List<RawSegment>b){float limit=JOIN_EPS*JOIN_EPS;for(RawSegment x:a)for(RawSegment y:b)if(x.c0.distanceSquared(y.c0)<=limit||x.c0.distanceSquared(y.c1)<=limit||x.c1.distanceSquared(y.c0)<=limit||x.c1.distanceSquared(y.c1)<=limit)return true;return false;}
    private static List<RawSegment> segmentsOf(ArcRibbonBlockEntity entity){List<RawSegment> out=new ArrayList<>();extractSegments(entity,out);return out;}
    private static void extractSegments(ArcRibbonBlockEntity entity,List<RawSegment> out){Vec3 origin=new Vec3(entity.getPos().getX(),entity.getPos().getY(),entity.getPos().getZ());for(ArcRibbonBlockEntity.Prism prism:entity.getPrisms()){float[]v=prism.xyz();Vec3 c0=average(v,0,4).add(origin),c1=average(v,4,8).add(origin);if(c0.distanceSquared(c1)<EPS*EPS)continue;Vec3 w0=point(v,1).subtract(point(v,0)).add(point(v,2).subtract(point(v,3))).normalizeOr(new Vec3(0,0,1));Vec3 w1=point(v,5).subtract(point(v,4)).add(point(v,6).subtract(point(v,7))).normalizeOr(w0);Vec3 n0=point(v,3).subtract(point(v,0)).add(point(v,2).subtract(point(v,1))).normalizeOr(new Vec3(0,1,0));Vec3 n1=point(v,7).subtract(point(v,4)).add(point(v,6).subtract(point(v,5))).normalizeOr(n0);out.add(new RawSegment(c0,c1,w0,w1,n0,n1));if(out.size()>=MAX_COMPONENT_SEGMENTS)return;}}

    private static List<OrderedSegment> orderSegments(List<RawSegment> raw){if(raw.isEmpty())return List.of();boolean[]used=new boolean[raw.size()];Vec3 current=chooseStart(raw),previousW=null,previousN=null;List<OrderedSegment>out=new ArrayList<>();int remaining=raw.size();
        while(remaining>0){int selected=-1;boolean reverse=false;float best=Float.POSITIVE_INFINITY;for(int i=0;i<raw.size();i++){if(used[i])continue;RawSegment r=raw.get(i);float d0=current.distanceSquared(r.c0),d1=current.distanceSquared(r.c1);if(d0<best){best=d0;selected=i;reverse=false;}if(d1<best){best=d1;selected=i;reverse=true;}}if(selected<0||(!out.isEmpty()&&best>JOIN_EPS*JOIN_EPS))break;used[selected]=true;remaining--;RawSegment r=reverse?raw.get(selected).reversed():raw.get(selected);Vec3 w0=r.width0,w1=r.width1,n0=r.radial0,n1=r.radial1;if(previousW!=null&&previousW.dot(w0)<0){w0=w0.multiply(-1);w1=w1.multiply(-1);}if(previousN!=null&&previousN.dot(n0)<0){n0=n0.multiply(-1);n1=n1.multiply(-1);}float len=r.c1.subtract(r.c0).length();if(len<EPS)continue;out.add(new OrderedSegment(out.size(),r.c0,r.c1,w0,w1,n0,n1,len));current=r.c1;previousW=w1;previousN=n1;}return List.copyOf(out);}
    private static Vec3 chooseStart(List<RawSegment> raw){List<Vec3>points=new ArrayList<>();for(RawSegment r:raw){points.add(r.c0);points.add(r.c1);}Vec3 open=null,any=null;for(int i=0;i<points.size();i++){Vec3 p=points.get(i);if(any==null||compare(p,any)<0)any=p;int neighbors=0;for(int j=0;j<points.size();j++)if(i!=j&&p.distanceSquared(points.get(j))<=JOIN_EPS*JOIN_EPS)neighbors++;if(neighbors==0&&(open==null||compare(p,open)<0))open=p;}return open==null?any:open;}

    private static float gridInset(Vec3 point,Vec3 direction){float ax=Math.abs(direction.x),ay=Math.abs(direction.y),az=Math.abs(direction.z),coordinate,component;if(ax>=ay&&ax>=az){coordinate=point.x;component=direction.x;}else if(ay>=az){coordinate=point.y;component=direction.y;}else{coordinate=point.z;component=direction.z;}if(Math.abs(component)<EPS)return 0;float nearest=Math.round(coordinate);if(Math.abs(coordinate-nearest)<1.0E-4)return 0;double boundary=component>0?Math.ceil(coordinate-1.0E-7):Math.floor(coordinate+1.0E-7);float distance=(float)((boundary-coordinate)/component);return distance>=-EPS&&distance<=.35f?Math.max(0,distance):0;}

    private enum SurfaceSide {BOTTOM,TOP,LEFT,RIGHT,START_CAP,END_CAP;
        Direction direction(Frame frame){return switch(this){case TOP->dominant(frame.radial);case BOTTOM->dominant(frame.radial.multiply(-1));case LEFT->dominant(frame.width.multiply(-1));case RIGHT->dominant(frame.width);case START_CAP->dominant(frame.tangent.multiply(-1));case END_CAP->dominant(frame.tangent);};}}
    private static Direction dominant(Vec3 v){return ArcMaterialHelper.dominant(v.x,v.y,v.z);}

    private record Frame(Vec3 center,Vec3 tangent,Vec3 width,Vec3 radial){}
    private record RawSegment(Vec3 c0,Vec3 c1,Vec3 width0,Vec3 width1,Vec3 radial0,Vec3 radial1){RawSegment reversed(){return new RawSegment(c1,c0,width1,width0,radial1,radial0);}}
    private record OrderedSegment(int index,Vec3 c0,Vec3 c1,Vec3 width0,Vec3 width1,Vec3 radial0,Vec3 radial1,float centerLength){Frame frame(float f){Vec3 center=c0.lerp(c1,f),width=width0.lerp(width1,f).normalizeOr(width0),radial=radial0.lerp(radial1,f).normalizeOr(radial0),tangent=c1.subtract(c0).normalizeOr(new Vec3(1,0,0));return new Frame(center,tangent,width,radial);}}
    private record OrderedAssignment(OrderedSegment segment,boolean reversed){Frame frame(boolean first){return segment.frame((first^reversed)?0:1);}}
    private record MetricSegment(int index,Vec3 c0,Vec3 c1,Vec3 width0,Vec3 width1,Vec3 radial0,Vec3 radial1,float centerLength,float centerS0,float[]cornerStart,float[]cornerLength){
        Frame frame(float f){Vec3 center=c0.lerp(c1,f),width=width0.lerp(width1,f).normalizeOr(width0),radial=radial0.lerp(radial1,f).normalizeOr(radial0),tangent=c1.subtract(c0).normalizeOr(new Vec3(1,0,0));return new Frame(center,tangent,width,radial);}Vec3 tangent(float f){return frame(f).tangent;}
        float surfaceStation(boolean atStart,float w,float n,float minW,float maxW,float minN,float maxN){return surfaceStationFraction(atStart?0:1,w,n,minW,maxW,minN,maxN);}float surfaceStationFraction(float f,float w,float n,float minW,float maxW,float minN,float maxN){float tw=ratio(w,minW,maxW),tn=ratio(n,minN,maxN);return bilerp(cornerStart,tw,tn)+f*bilerp(cornerLength,tw,tn);}}
    private record PrismAssignment(MetricSegment segment,boolean reversed){}
    private record CurveCoordinate(float centerS,float surfaceS,float w,float n){}
    private record GeometryVertex(float x,float y,float z,CurveCoordinate coordinate){}
    private record ParameterVertex(float x,float y,float z,float clip,float u,float v){float axis(int axis){return axis==0?clip:(axis==1?u:v);}ParameterVertex lerp(ParameterVertex o,float t){return new ParameterVertex(x+(o.x-x)*t,y+(o.y-y)*t,z+(o.z-z)*t,clip+(o.clip-clip)*t,u+(o.u-u)*t,v+(o.v-v)*t);}ParameterVertex localize(int tu,int tv){return new ParameterVertex(x,y,z,clip,clamp01(u-tu),clamp01(v-tv));}}
    private record Triangle(ParameterVertex a,ParameterVertex b,ParameterVertex c,float nx,float ny,float nz,Direction direction,ArcMaterialHelper.FaceMaterial material){}
    private record CompiledMesh(int entityRevision,int atlasRevision,List<Triangle>triangles){}
    private record AtlasHandle(int entityRevision,long builtTick,ComponentAtlas atlas){}
    private record Vec3(float x,float y,float z){Vec3 add(Vec3 o){return new Vec3(x+o.x,y+o.y,z+o.z);}Vec3 subtract(Vec3 o){return new Vec3(x-o.x,y-o.y,z-o.z);}Vec3 multiply(float a){return new Vec3(x*a,y*a,z*a);}Vec3 lerp(Vec3 o,float t){return new Vec3(x+(o.x-x)*t,y+(o.y-y)*t,z+(o.z-z)*t);}float dot(Vec3 o){return x*o.x+y*o.y+z*o.z;}float length(){return(float)Math.sqrt(x*x+y*y+z*z);}float distanceSquared(Vec3 o){float dx=x-o.x,dy=y-o.y,dz=z-o.z;return dx*dx+dy*dy+dz*dz;}Vec3 normalizeOr(Vec3 fallback){float l=length();return l<EPS?fallback:multiply(1/l);}}

    private static float bilerp(float[]v,float x,float y){float a=v[0]*(1-x)+v[1]*x,b=v[2]*(1-x)+v[3]*x;return a*(1-y)+b*y;}
    private static float ratio(float v,float a,float b){return b-a<EPS?0:clamp01((v-a)/(b-a));}
    private static Vector3f normal(GeometryVertex a,GeometryVertex b,GeometryVertex c){Vector3f x=new Vector3f(b.x-a.x,b.y-a.y,b.z-a.z),y=new Vector3f(c.x-a.x,c.y-a.y,c.z-a.z),n=x.cross(y);return n.lengthSquared()<1.0E-10?null:n.normalize();}
    private static Vector3f normal(ParameterVertex a,ParameterVertex b,ParameterVertex c){Vector3f x=new Vector3f(b.x-a.x,b.y-a.y,b.z-a.z),y=new Vector3f(c.x-a.x,c.y-a.y,c.z-a.z),n=x.cross(y);return n.lengthSquared()<1.0E-10?null:n.normalize();}
    private static GeometryVertex average(GeometryVertex[]v){float x=0,y=0,z=0,cs=0,ss=0,w=0,n=0;for(GeometryVertex p:v){x+=p.x;y+=p.y;z+=p.z;cs+=p.coordinate.centerS;ss+=p.coordinate.surfaceS;w+=p.coordinate.w;n+=p.coordinate.n;}float q=1f/v.length;return new GeometryVertex(x*q,y*q,z*q,new CurveCoordinate(cs*q,ss*q,w*q,n*q));}
    private static float distance(GeometryVertex a,GeometryVertex b){float x=b.x-a.x,y=b.y-a.y,z=b.z-a.z;return(float)Math.sqrt(x*x+y*y+z*z);}
    private static Vec3 point(float[]d,int i){return new Vec3(d[i*3],d[i*3+1],d[i*3+2]);}
    private static Vec3 average(float[]d,int from,int to){Vec3 r=new Vec3(0,0,0);for(int i=from;i<to;i++)r=r.add(point(d,i));return r.multiply(1f/(to-from));}
    private static float min(float a,float b,float c){return Math.min(a,Math.min(b,c));}private static float max(float a,float b,float c){return Math.max(a,Math.max(b,c));}
    private static int floorTile(float v){return(int)Math.floor(v+EPS);}private static int ceilTile(float v){return(int)Math.ceil(v-EPS);}private static float clamp01(float v){return clamp(v,0,1);}private static float clamp(float v,float a,float b){return Math.max(a,Math.min(b,v));}
    private static int comparePos(BlockPos a,BlockPos b){int y=Integer.compare(a.getY(),b.getY());if(y!=0)return y;int x=Integer.compare(a.getX(),b.getX());return x!=0?x:Integer.compare(a.getZ(),b.getZ());}private static int compare(Vec3 a,Vec3 b){int y=Float.compare(a.y,b.y);if(y!=0)return y;int x=Float.compare(a.x,b.x);return x!=0?x:Float.compare(a.z,b.z);}
    private static int maxPacked(int a,int b){int block=Math.max(a&0xFFFF,b&0xFFFF),sky=Math.max((a>>>16)&0xFFFF,(b>>>16)&0xFFFF);return block|(sky<<16);}
    private static void emit(VertexConsumer c,Matrix4f p,Matrix3f n,ParameterVertex v,Triangle t,int light,int overlay,int r,int g,int b){c.vertex(p,v.x,v.y,v.z).color(r,g,b,255).texture(t.material.u(clamp01(v.u),clamp01(v.v)),t.material.v(clamp01(v.u),clamp01(v.v))).overlay(overlay).light(light).normal(n,t.nx,t.ny,t.nz).next();}
}
