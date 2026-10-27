package dev.discraft.forge.server;

import com.google.gson.*;
import dev.discraft.core.Attachment;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import java.util.List;
import javax.imageio.ImageIO;

/** Independent Java2D/CPU implementation of a bounded standard item-model profile; no client classes. */
final class HeadlessItemRenderer {
    record Result(Attachment attachment,String status,boolean fontFallback){}
    private record Model(JsonObject data,boolean generated){}
    private record Vec(double x,double y,double z){Vec add(Vec v){return new Vec(x+v.x,y+v.y,z+v.z);}Vec multiply(Vec v){return new Vec(x*v.x,y*v.y,z*v.z);}}
    private record Face(Vec[] vertices,double[] uv,BufferedImage texture,double light,int tint,double depth){}
    private final ItemTooltipRenderer tooltipRenderer;
    ItemTooltipRenderer.Result tooltip(ItemSnapshot item)throws Exception{return tooltipRenderer.render(item.tooltip());}
    private final ResourcePackIndex resources;private final ResourceImages images;private final PackBitmapFont font;
    private final Map<String,JsonObject> models=new LinkedHashMap<>(16,.75f,true);
    private final Map<ItemSnapshot,Result> cache=new LinkedHashMap<>(32,.75f,true);
    private long cachedBytes,deadline;private int work;
    private final IdentityHashMap<BufferedImage,Boolean> renderImages=new IdentityHashMap<>();private long renderPixels;
    HeadlessItemRenderer(ResourcePackIndex resources)throws Exception{this.resources=resources;images=new ResourceImages(resources);font=new PackBitmapFont(resources,images);tooltipRenderer=new ItemTooltipRenderer(font);}
    Result render(ItemSnapshot item)throws Exception{
        if(cache.containsKey(item))return cache.get(item);deadline=System.nanoTime()+2_000_000_000L;work=0;renderImages.clear();renderPixels=0;
        var icon=new BufferedImage(176,176,BufferedImage.TYPE_INT_ARGB);String status="Standard model";
        try{drawModel(icon,resolve(item.id().replace(":",":item/"),item,new HashSet<>(),0),item);}
        catch(Exception unsupported){status="Model unavailable / unsupported";icon=new BufferedImage(176,176,BufferedImage.TYPE_INT_ARGB);var g=icon.createGraphics();g.setColor(new Color(100,73,110));g.fillRoundRect(42,30,92,112,12,12);g.setColor(Color.WHITE);font.draw(g,"?","minecraft:default",64,65,112,80);g.dispose();}
        finally{renderImages.clear();}
        var card=new BufferedImage(288,256,BufferedImage.TYPE_INT_ARGB);var g=card.createGraphics();g.setColor(new Color(30,33,40));g.fillRect(0,0,288,256);g.drawImage(icon,56,4,null);g.setColor(Color.WHITE);
        boolean fontFallback=font.draw(g,item.name(),item.font(),18,12,201,264);g.setColor(new Color(180,190,205));font.draw(g,item.id()+" ×"+item.count(),"minecraft:default",12,12,222,264);
        g.setColor(new Color(220,184,110));font.draw(g,fontFallback?"Font fallback · "+status:status.equals("Standard model")&&item.enchanted()?"Enchanted · glint omitted":status,"minecraft:default",11,12,244,264);g.dispose();
        var bytes=new ByteArrayOutputStream();ImageIO.write(card,"png",bytes);if(bytes.size()>256*1024)throw new IOException("Rendered item exceeds media budget");
        var result=new Result(Attachment.of("item.png",bytes.toByteArray()),status,fontFallback);cache.put(item,result);cachedBytes+=bytes.size();
        while(cache.size()>32||cachedBytes>4L*1024*1024){var first=cache.keySet().iterator().next();cachedBytes-=cache.remove(first).attachment().bytes().length;}return result;
    }
    private void budget()throws IOException{if(++work>4096||System.nanoTime()>deadline||Thread.currentThread().isInterrupted())throw new IOException("Item render budget exceeded");}
    private BufferedImage image(String id)throws IOException{var image=images.image(id);if(renderImages.put(image,Boolean.TRUE)==null){renderPixels+=(long)image.getWidth()*image.getHeight();if(renderPixels>8L*1024*1024)throw new IOException("Render texture pixel budget exceeded");}return image;}
    private JsonObject raw(String id)throws Exception{
        budget();if(models.containsKey(id))return models.get(id);byte[] bytes=resources.asset(ResourcePackIndex.resourcePath(id,"models",".json"));JsonObject data=bytes==null?null:ResourcePackIndex.json(bytes);
        models.put(id,data);while(models.size()>16)models.remove(models.keySet().iterator().next());return data;
    }
    private Model resolve(String id,ItemSnapshot item,Set<String> path,int depth)throws Exception{
        if(depth>16||!path.add(id))throw new IOException("Model reference cycle or depth");JsonObject local=raw(id);Model parent=null;
        if(local==null){
            if(Set.of("minecraft:item/generated","minecraft:item/handheld","item/generated","item/handheld","minecraft:builtin/generated","builtin/generated").contains(id)){path.remove(id);return new Model(new JsonObject(),true);}
            if(Set.of("minecraft:block/cube_all","block/cube_all","minecraft:block/cube","block/cube").contains(id)){local=blockDefaults();var elements=new JsonArray();var cube=new JsonObject();cube.add("from",array(0,0,0));cube.add("to",array(16,16,16));var faces=new JsonObject();for(String side:List.of("north","south","west","east","up","down")){var face=new JsonObject();face.addProperty("texture",id.endsWith("cube_all")?"#all":"#"+side);faces.add(side,face);}cube.add("faces",faces);elements.add(cube);local.add("elements",elements);}
            else if(Set.of("minecraft:block/block","block/block").contains(id))local=blockDefaults();
            else throw new IOException("Missing model");
        }
        if(Set.of("loader","transform","visibility","render_type","forge_data").stream().anyMatch(local::has))throw new IOException("Custom model extension unsupported");
        if(local.has("parent"))parent=resolve(local.get("parent").getAsString(),item,path,depth+1);
        var merged=new JsonObject();if(parent!=null)for(var entry:parent.data().entrySet())merged.add(entry.getKey(),entry.getValue());
        for(var entry:local.entrySet()){
            if(Set.of("textures","display").contains(entry.getKey())&&merged.has(entry.getKey())){var combined=new JsonObject();for(var member:merged.getAsJsonObject(entry.getKey()).entrySet())combined.add(member.getKey(),member.getValue());for(var member:entry.getValue().getAsJsonObject().entrySet())combined.add(member.getKey(),member.getValue());merged.add(entry.getKey(),combined);}
            else merged.add(entry.getKey(),entry.getValue());
        }
        if(merged.has("overrides")){
            var overrides=merged.getAsJsonArray("overrides");if(overrides.size()>128)throw new IOException("Model override budget exceeded");
            for(int i=overrides.size()-1;i>=0;i--){var override=overrides.get(i).getAsJsonObject();boolean matches=true;var predicates=override.getAsJsonObject("predicate");if(predicates.size()>16)throw new IOException("Predicate budget exceeded");
                for(var predicate:predicates.entrySet()){
                    double value=switch(predicate.getKey()) {case "custom_model_data"->item.customModelData();case "damage"->item.damage();case "damaged"->item.damaged()?1:0;case "lefthanded"->item.leftHanded()?1:0;default->throw new IOException("Unsupported model predicate");};
                    double threshold=predicate.getValue().getAsDouble();if(!Double.isFinite(threshold))throw new IOException("Invalid predicate");if(value<threshold)matches=false;
                }
                if(matches){var selected=resolve(override.get("model").getAsString(),item,path,depth+1);path.remove(id);return selected;}
            }
        }
        path.remove(id);return new Model(merged,parent!=null&&parent.generated()&&!merged.has("elements"));
    }
    private static JsonArray array(double... values){var array=new JsonArray();for(double value:values)array.add(value);return array;}
    private static JsonObject blockDefaults(){var model=new JsonObject();var display=new JsonObject();var gui=new JsonObject();gui.add("rotation",array(30,225,0));gui.add("scale",array(.625,.625,.625));display.add("gui",gui);model.add("display",display);return model;}
    private String texture(String reference,JsonObject textures)throws Exception{
        var seen=new HashSet<String>();for(int depth=0;reference.startsWith("#");depth++){
            if(depth>=16||!seen.add(reference)||!textures.has(reference.substring(1)))throw new IOException("Texture reference cycle or missing key");reference=textures.get(reference.substring(1)).getAsString();
        }return reference;
    }
    private static double number(JsonElement element,double min,double max)throws IOException{double value=element.getAsDouble();if(!Double.isFinite(value)||value<min||value>max)throw new IOException("Model coordinate bounds");return value;}
    private static Vec vector(JsonElement element,Vec fallback,double min,double max)throws Exception{if(element==null)return fallback;var a=element.getAsJsonArray();if(a.size()!=3)throw new IOException("Invalid model vector");return new Vec(number(a.get(0),min,max),number(a.get(1),min,max),number(a.get(2),min,max));}
    private static Vec rotate(Vec p,Vec degrees){double x=Math.toRadians(degrees.x),y=Math.toRadians(degrees.y),z=Math.toRadians(degrees.z);p=new Vec(p.x*Math.cos(z)-p.y*Math.sin(z),p.x*Math.sin(z)+p.y*Math.cos(z),p.z);p=new Vec(p.x*Math.cos(y)+p.z*Math.sin(y),p.y,-p.x*Math.sin(y)+p.z*Math.cos(y));return new Vec(p.x,p.y*Math.cos(x)-p.z*Math.sin(x),p.y*Math.sin(x)+p.z*Math.cos(x));}
    private static Vec element(Vec p,JsonObject data)throws Exception{
        if(!data.has("rotation"))return p;var r=data.getAsJsonObject("rotation");var origin=vector(r.get("origin"),new Vec(8,8,8),-16,32);double angle=number(r.get("angle"),-45,45);if(angle%22.5!=0)throw new IOException("Unsupported element angle");String axis=r.get("axis").getAsString();
        Vec rotation=switch(axis){case "x"->new Vec(angle,0,0);case "y"->new Vec(0,angle,0);case "z"->new Vec(0,0,angle);default->throw new IOException("Invalid rotation axis");};p=p.add(new Vec(-origin.x,-origin.y,-origin.z));p=rotate(p,rotation);
        if(r.has("rescale")&&r.get("rescale").getAsBoolean()){double scale=1/Math.cos(Math.toRadians(angle));p=p.multiply(new Vec(axis.equals("x")?1:scale,axis.equals("y")?1:scale,axis.equals("z")?1:scale));}return p.add(origin);
    }
    private void drawModel(BufferedImage icon,Model model,ItemSnapshot item)throws Exception{
        JsonObject data=model.data(),textures=data.has("textures")?data.getAsJsonObject("textures"):new JsonObject();if(textures.size()>128)throw new IOException("Texture key budget exceeded");
        if(data.has("gui_light")&&!Set.of("front","side").contains(data.get("gui_light").getAsString()))throw new IOException("Unsupported GUI lighting");
        JsonObject gui=data.has("display")&&data.getAsJsonObject("display").has("gui")?data.getAsJsonObject("display").getAsJsonObject("gui"):new JsonObject();
        Vec rotation=vector(gui.get("rotation"),new Vec(0,0,0),-360,360),scale=vector(gui.get("scale"),new Vec(1,1,1),-4,4),translation=vector(gui.get("translation"),new Vec(0,0,0),-80,80);
        var faces=new ArrayList<Face>();
        if(model.generated()){
            if(rotation.x!=0||rotation.y!=0)throw new IOException("Generated extrusion unsupported");
            for(int layer=0;layer<5&&textures.has("layer"+layer);layer++){
                var image=image(texture("#layer"+layer,textures));Vec[] v={new Vec(0,0,8+layer*.001),new Vec(16,0,8+layer*.001),new Vec(16,16,8+layer*.001),new Vec(0,16,8+layer*.001)};
                faces.add(face(v,arrayUV(0,0,16,16,0),image,1,layer==0?item.tint():-1,rotation,scale,translation));
            }
        }else{
            var elements=data.getAsJsonArray("elements");if(elements==null||elements.size()>64)throw new IOException("Unsupported element count");
            for(var value:elements){budget();var e=value.getAsJsonObject();Vec from=vector(e.get("from"),new Vec(0,0,0),-16,32),to=vector(e.get("to"),new Vec(16,16,16),-16,32);if(from.x>to.x||from.y>to.y||from.z>to.z)throw new IOException("Invalid element bounds");
                var sides=e.getAsJsonObject("faces");if(sides==null||sides.size()>6)throw new IOException("Invalid element faces");
                for(var entry:sides.entrySet()){
                    var f=entry.getValue().getAsJsonObject();String side=entry.getKey();Vec[] vertices=switch(side){
                        case "south"->new Vec[]{new Vec(from.x,from.y,to.z),new Vec(to.x,from.y,to.z),new Vec(to.x,to.y,to.z),new Vec(from.x,to.y,to.z)};
                        case "north"->new Vec[]{new Vec(to.x,from.y,from.z),new Vec(from.x,from.y,from.z),new Vec(from.x,to.y,from.z),new Vec(to.x,to.y,from.z)};
                        case "east"->new Vec[]{new Vec(to.x,from.y,to.z),new Vec(to.x,from.y,from.z),new Vec(to.x,to.y,from.z),new Vec(to.x,to.y,to.z)};
                        case "west"->new Vec[]{new Vec(from.x,from.y,from.z),new Vec(from.x,from.y,to.z),new Vec(from.x,to.y,to.z),new Vec(from.x,to.y,from.z)};
                        case "up"->new Vec[]{new Vec(from.x,to.y,to.z),new Vec(to.x,to.y,to.z),new Vec(to.x,to.y,from.z),new Vec(from.x,to.y,from.z)};
                        case "down"->new Vec[]{new Vec(from.x,from.y,from.z),new Vec(to.x,from.y,from.z),new Vec(to.x,from.y,to.z),new Vec(from.x,from.y,to.z)};default->throw new IOException("Invalid face direction");};
                    double[] uv=switch(side){case "north"->new double[]{16-to.x,16-to.y,16-from.x,16-from.y};case "south"->new double[]{from.x,16-to.y,to.x,16-from.y};case "east"->new double[]{16-to.z,16-to.y,16-from.z,16-from.y};case "west"->new double[]{from.z,16-to.y,to.z,16-from.y};case "up"->new double[]{from.x,from.z,to.x,to.z};default->new double[]{from.x,16-to.z,to.x,16-from.z};};
                    if(f.has("uv")){var a=f.getAsJsonArray("uv");if(a.size()!=4)throw new IOException("Invalid UV");for(int i=0;i<4;i++)uv[i]=number(a.get(i),0,16);}for(double coordinate:uv)if(coordinate<0||coordinate>16)throw new IOException("Unsupported outside-atlas UV");
                    int turn=f.has("rotation")?f.get("rotation").getAsInt():0;if(turn<0||turn>270||turn%90!=0)throw new IOException("Invalid UV rotation");
                    for(int i=0;i<4;i++)vertices[i]=element(vertices[i],e);
                    int tint=f.has("tintindex")?item.tint():-1;if(f.has("tintindex")&&(f.get("tintindex").getAsInt()!=0||tint<0))throw new IOException("Unsupported tint callback");
                    faces.add(face(vertices,arrayUV(uv[0],uv[1],uv[2],uv[3],turn),image(texture(f.get("texture").getAsString(),textures)),e.has("shade")&&!e.get("shade").getAsBoolean()||data.has("gui_light")&&data.get("gui_light").getAsString().equals("front")?1:.8,tint,rotation,scale,translation));
                }
            }
        }
        if(faces.isEmpty())throw new IOException("No drawable model layers");
        faces.sort(Comparator.comparingDouble(Face::depth));double[] depth=new double[icon.getWidth()*icon.getHeight()];Arrays.fill(depth,Double.NEGATIVE_INFINITY);int drawn=0;
        for(Face face:faces){budget();Vec a=face.vertices[0],b=face.vertices[1],c=face.vertices[2];double normal=(b.x-a.x)*(c.y-a.y)-(b.y-a.y)*(c.x-a.x);if(normal<=0)continue;drawn++;triangle(icon,depth,face,0,1,2);triangle(icon,depth,face,0,2,3);}
        if(drawn==0)throw new IOException("No visible model faces");
    }
    private static double[] arrayUV(double u0,double v0,double u1,double v1,int rotation){double[] corners={u0,v1,u1,v1,u1,v0,u0,v0},out=new double[8];for(int i=0;i<4;i++){int p=(i+rotation/90)%4;out[2*i]=corners[2*p]/16;out[2*i+1]=corners[2*p+1]/16;}return out;}
    private static Face face(Vec[] points,double[] uv,BufferedImage texture,double light,int tint,Vec rotation,Vec scale,Vec translation){Vec[] transformed=new Vec[4];for(int i=0;i<4;i++)transformed[i]=rotate(points[i].add(new Vec(-8,-8,-8)).multiply(scale),rotation).add(translation);
        if(light!=1){Vec a=transformed[0],b=transformed[1],c=transformed[2];double nx=(b.y-a.y)*(c.z-a.z)-(b.z-a.z)*(c.y-a.y),ny=(b.z-a.z)*(c.x-a.x)-(b.x-a.x)*(c.z-a.z),nz=(b.x-a.x)*(c.y-a.y)-(b.y-a.y)*(c.x-a.x),length=Math.sqrt(nx*nx+ny*ny+nz*nz);light=.55+.45*Math.max(0,(-.4*nx+.8*ny+.6*nz)/Math.max(.00001,length*1.077));}
        return new Face(transformed,uv,texture,light,tint,Arrays.stream(transformed).mapToDouble(Vec::z).average().orElse(0));}
    private void triangle(BufferedImage image,double[] depths,Face face,int ia,int ib,int ic)throws Exception{
        int w=image.getWidth(),h=image.getHeight();Vec[] v={face.vertices[ia],face.vertices[ib],face.vertices[ic]};double[] x=new double[3],y=new double[3];for(int i=0;i<3;i++){x[i]=w/2.0+v[i].x*w/18;y[i]=h/2.0-v[i].y*h/18;}
        double area=(y[1]-y[2])*(x[0]-x[2])+(x[2]-x[1])*(y[0]-y[2]);if(Math.abs(area)<.0001)return;
        int minX=Math.max(0,(int)Math.floor(Math.min(x[0],Math.min(x[1],x[2])))),maxX=Math.min(w-1,(int)Math.ceil(Math.max(x[0],Math.max(x[1],x[2]))));int minY=Math.max(0,(int)Math.floor(Math.min(y[0],Math.min(y[1],y[2])))),maxY=Math.min(h-1,(int)Math.ceil(Math.max(y[0],Math.max(y[1],y[2]))));int[] indices={ia,ib,ic};
        for(int py=minY;py<=maxY;py++){budget();for(int px=minX;px<=maxX;px++){
            double a=((y[1]-y[2])*(px+.5-x[2])+(x[2]-x[1])*(py+.5-y[2]))/area,b=((y[2]-y[0])*(px+.5-x[2])+(x[0]-x[2])*(py+.5-y[2]))/area,c=1-a-b;if(a<0||b<0||c<0)continue;
            double z=a*v[0].z+b*v[1].z+c*v[2].z;int position=py*w+px;if(z<=depths[position]+.0000001)continue;double u=0,t=0;double[] weights={a,b,c};for(int i=0;i<3;i++){u+=weights[i]*face.uv[indices[i]*2];t+=weights[i]*face.uv[indices[i]*2+1];}
            int color=face.texture.getRGB(Math.floorMod((int)Math.floor(u*face.texture.getWidth()),face.texture.getWidth()),Math.floorMod((int)Math.floor(t*face.texture.getHeight()),face.texture.getHeight()));int alpha=color>>>24;if(alpha==0)continue;
            int red=(color>>16)&255,green=(color>>8)&255,blue=color&255;if(face.tint>=0){red=red*((face.tint>>16)&255)/255;green=green*((face.tint>>8)&255)/255;blue=blue*(face.tint&255)/255;}red=(int)(red*face.light);green=(int)(green*face.light);blue=(int)(blue*face.light);
            int old=image.getRGB(px,py),oldA=old>>>24;double opacity=alpha/255.0,combined=opacity+oldA/255.0*(1-opacity);int r=(int)((red*opacity+((old>>16)&255)*oldA/255.0*(1-opacity))/combined),g=(int)((green*opacity+((old>>8)&255)*oldA/255.0*(1-opacity))/combined),bl=(int)((blue*opacity+(old&255)*oldA/255.0*(1-opacity))/combined);image.setRGB(px,py,((int)Math.round(combined*255)<<24)|(r<<16)|(g<<8)|bl);depths[position]=z;
        }}
    }
    int cachedItems(){return cache.size();}
    long cachedBytes(){return cachedBytes;}
    long cachedTexturePixels(){return images.pixels();}
}
