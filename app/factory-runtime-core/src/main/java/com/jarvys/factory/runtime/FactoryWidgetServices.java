package com.jarvys.factory.runtime;

import android.content.Context;
import android.os.SystemClock;
import java.util.function.BooleanSupplier;

/** Cold-process resolution. No Activity registration, cached grant, static app identity or WebView. */
public final class FactoryWidgetServices {
    public static final class Environment {
        public final FactoryConfig config;public final WidgetEngine.Source source;public final WidgetLedger ledger;
        public final NativeWidgetPlatform platform;public final WidgetEngine engine;
        Environment(Context context,FactoryConfig config,WidgetEngine.Source source){this.config=config;this.source=source;ledger=new WidgetLedger(new WidgetFilePersistence(context),source);platform=new NativeWidgetPlatform(context,source);engine=new WidgetEngine(source,ledger,platform);}
    }
    public static Environment open(Context context,long deadline,BooleanSupplier active)throws FactoryException {
        if(context==null||active==null||!active.getAsBoolean()||SystemClock.elapsedRealtime()>=deadline)throw new FactoryException("NOT_AVAILABLE","Widget session ended.");
        FactoryConfig config;try{config=FactoryConfig.parse(FactoryRuntime.readConfiguration(context.getAssets().open("factory-app.json")),context.getPackageName());}catch(Exception e){throw new FactoryException("SOURCE_UNAVAILABLE","Installed widget configuration is unavailable.");}
        NativeInstalledSource proof=NativeInstalledSource.describeLocal(context,config,"widgets");
        WidgetModel.Limits limits;try{limits=WidgetModel.Limits.fromJson(ResourceBudgetsJson.toJson(proof.resources).getJSONObject("widgets"));}catch(Exception e){throw new FactoryException("SOURCE_UNAVAILABLE","This app's packaged widget budget is unavailable.");}
        if(!active.getAsBoolean()||SystemClock.elapsedRealtime()>=deadline)throw new FactoryException("NOT_AVAILABLE","Widget session ended.");
        return new Environment(context,config,new WidgetEngine.Source(proof.appId,proof.certificateSha256,proof.apkSha256,proof.recordSha256,proof.versionCode,limits));
    }
    private FactoryWidgetServices(){}
}
