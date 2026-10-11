package com.jarvys.factory.runtime;

import android.app.PendingIntent;
import android.appwidget.*;
import android.content.*;
import android.content.pm.ActivityInfo;
import android.graphics.*;
import android.net.Uri;
import android.os.*;
import android.widget.RemoteViews;
import java.io.*;
import java.util.*;
import org.json.*;

/** Builds bounded trees from compiled XML resources, resolving IDs in the generated APK package. */
public final class NativeWidgetPlatform implements WidgetEngine.Platform {
    public static final String PROVIDER="com.jarvys.factory.runtime.FactoryWidgetProvider";
    public static final String ACTION_RECEIVER="com.jarvys.factory.runtime.FactoryWidgetActionReceiver";
    private final Context context;private final WidgetEngine.Source source;private final ComponentName provider;
    public NativeWidgetPlatform(Context context,WidgetEngine.Source source){if(context==null||source==null||!context.getPackageName().equals(source.appId))throw new IllegalArgumentException();Context app=context.getApplicationContext();this.context=app==null?context:app;this.source=source;provider=new ComponentName(source.appId,PROVIDER);}
    private AppWidgetManager manager()throws FactoryException{AppWidgetManager m=AppWidgetManager.getInstance(context);if(m==null)throw unavailable();return m;}
    public boolean registered(){try{ActivityInfo info=context.getPackageManager().getReceiverInfo(provider,android.content.pm.PackageManager.GET_META_DATA);if(!info.enabled||info.exported||info.applicationInfo==null||!info.applicationInfo.enabled||!source.appId.equals(info.packageName)||info.metaData==null||info.metaData.getInt("android.appwidget.provider",0)!=resource("xml","factory_widget_info"))return false;ActivityInfo actions=context.getPackageManager().getReceiverInfo(new ComponentName(source.appId,ACTION_RECEIVER),0);return actions.enabled&&!actions.exported&&source.appId.equals(actions.packageName);}catch(Exception e){return false;}}
    @Override public Set<Integer> ownInstances()throws FactoryException{if(!registered())throw unavailable();TreeSet<Integer> result=new TreeSet<>();int[] ids=manager().getAppWidgetIds(provider);if(ids==null)throw unavailable();if(ids.length>4096)throw new FactoryException("RESOURCE_LIMIT","Too many launcher widget instances to enumerate safely.");for(int id:ids){AppWidgetProviderInfo info=manager().getAppWidgetInfo(id);if(id>0&&info!=null&&provider.equals(info.provider))result.add(id);}return result;}
    @Override public boolean pinSupported(){try{return registered()&&Build.VERSION.SDK_INT>=26&&manager().isRequestPinAppWidgetSupported();}catch(Exception e){return false;}}
    public JSONObject availability()throws FactoryException{try{return new JSONObject().put("available",registered()).put("requestPinSupported",pinSupported()).put("minimumPinApi",26).put("placementControlledByLauncher",true).put("htmlSupported",false).put("eventDelivery","at_least_once_delivery");}catch(JSONException e){throw new AssertionError(e);}}
    @Override public void validate(WidgetModel model)throws FactoryException{render(model,Collections.emptyMap());}
    @Override public boolean requestPin(WidgetModel model,NativeOperationAuthorization.Access live)throws FactoryException{
        if(Build.VERSION.SDK_INT<26||!pinSupported())return false;RemoteViews preview=render(model,Collections.emptyMap());Bundle extras=new Bundle();extras.putParcelable(AppWidgetManager.EXTRA_APPWIDGET_PREVIEW,preview);live.check();return manager().requestPinAppWidget(provider,extras,null);
    }
    @Override public void update(WidgetLedger.Record row,NativeOperationAuthorization.Access live)throws FactoryException{
        if(!ownInstances().contains(row.widgetId))throw new FactoryException("NOT_OWN_WIDGET","Widget is no longer owned by this provider.");RemoteViews views=render(row.model,row.tokens);live.check();manager().updateAppWidget(row.widgetId,views);
    }
    void restoreCompleted(int id,NativeOperationAuthorization.Access live)throws FactoryException{if(!ownInstances().contains(id))throw unavailable();Bundle options=manager().getAppWidgetOptions(id);if(!options.getBoolean(AppWidgetManager.OPTION_APPWIDGET_RESTORE_COMPLETED,false)){options.putBoolean(AppWidgetManager.OPTION_APPWIDGET_RESTORE_COMPLETED,true);live.check();manager().updateAppWidgetOptions(id,options);}}
    private RemoteViews render(WidgetModel model,Map<String,String> tokens)throws FactoryException{
        try{
            RemoteViews root=new RemoteViews(source.appId,resource("layout","factory_widget_root"));root.removeAllViews(resource("id","factory_widget_children"));
            if(model==null)root.addView(resource("id","factory_widget_children"),new RemoteViews(source.appId,resource("layout","factory_widget_empty")));
            else root.addView(resource("id","factory_widget_children"),node(model.root,tokens,new long[]{0,0}));
            Parcel parcel=Parcel.obtain();try{root.writeToParcel(parcel,0);if(parcel.dataSize()>524288)throw new FactoryException("TOO_LARGE","Widget RemoteViews transport exceeds the runtime's bounded parcel envelope.");}finally{parcel.recycle();}return root;
        }catch(FactoryException e){throw e;}catch(RuntimeException|IOException e){throw new FactoryException("NOT_AVAILABLE","Widget resources or packaged image cannot be rendered.");}
    }
    private RemoteViews node(WidgetModel.Node node,Map<String,String> tokens,long[] imageBudget)throws FactoryException,IOException{
        RemoteViews views=new RemoteViews(source.appId,resource("layout","factory_widget_"+node.type));int content=resource("id","factory_widget_content");
        switch(node.type){
            case "row":case "column":int children=resource("id","factory_widget_children");views.removeAllViews(children);for(WidgetModel.Node child:node.children)views.addView(children,node(child,tokens,imageBudget));break;
            case "text":views.setTextViewText(content,node.text);break;
            case "image":views.setImageViewBitmap(content,image(node.asset,imageBudget));views.setContentDescription(content,node.description);break;
            case "progress":views.setProgressBar(content,node.max,node.value,node.indeterminate);break;
            case "button":views.setTextViewText(content,node.label);String token=tokens.get(node.id);if(token!=null){Intent intent=actionIntent(context,token);PendingIntent action=PendingIntent.getBroadcast(context,0,intent,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);views.setOnClickPendingIntent(content,action);}else views.setOnClickPendingIntent(content,null);break;
            default:throw WidgetModel.invalid();
        }return views;
    }
    private Bitmap image(String asset,long[] budget)throws IOException,FactoryException{
        ByteArrayOutputStream out=new ByteArrayOutputStream();try(InputStream in=context.getAssets().open(asset)){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){budget[0]+=n;if(budget[0]>source.limits.imageBytes)throw new FactoryException("TOO_LARGE","Combined widget image bytes exceed the signed budget.");out.write(b,0,n);}}
        byte[] encoded=out.toByteArray();BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(encoded,0,encoded.length,bounds);
        long pixels=(long)bounds.outWidth*bounds.outHeight;if(bounds.outWidth<1||bounds.outHeight<1||pixels<1||pixels>source.limits.imagePixels-budget[1])throw new FactoryException("TOO_LARGE","Combined decoded widget pixels exceed the signed budget.");budget[1]+=pixels;
        BitmapFactory.Options options=new BitmapFactory.Options();options.inScaled=false;options.inPreferredConfig=Bitmap.Config.ARGB_8888;Bitmap bitmap=BitmapFactory.decodeByteArray(encoded,0,encoded.length,options);if(bitmap==null||bitmap.getWidth()!=bounds.outWidth||bitmap.getHeight()!=bounds.outHeight||bitmap.getAllocationByteCount()>pixels*4L)throw unavailable();return bitmap;
    }
    private int resource(String type,String name)throws FactoryException{int id=context.getResources().getIdentifier(name,type,source.appId);if(id==0||!source.appId.equals(context.getResources().getResourcePackageName(id))||!type.equals(context.getResources().getResourceTypeName(id))||!name.equals(context.getResources().getResourceEntryName(id)))throw unavailable();return id;}
    static Intent actionIntent(Context context,String token){if(!WidgetLedger.token(token))throw new IllegalArgumentException();String own=context.getPackageName();return new Intent(own+".factory.WIDGET_ACTION").setPackage(own).setComponent(new ComponentName(own,ACTION_RECEIVER)).setData(new Uri.Builder().scheme("factory-widget").authority(own).appendPath("action").appendPath(token).build());}
    static String actionToken(Context context,Intent intent){if(intent==null)return null;String own=context.getPackageName();if(!(own+".factory.WIDGET_ACTION").equals(intent.getAction())||!own.equals(intent.getPackage())||!new ComponentName(own,ACTION_RECEIVER).equals(intent.getComponent()))return null;Uri uri=intent.getData();if(uri==null||!"factory-widget".equals(uri.getScheme())||!own.equals(uri.getAuthority())||uri.getQuery()!=null||uri.getFragment()!=null)return null;List<String> parts=uri.getPathSegments();if(parts.size()!=2||!parts.get(0).equals("action")||!WidgetLedger.token(parts.get(1)))return null;String token=parts.get(1);return actionIntent(context,token).getData().equals(uri)?token:null;}
    private static FactoryException unavailable(){return new FactoryException("NOT_AVAILABLE","Own widget provider/resources are unavailable.");}
}
