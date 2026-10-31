package com.jarvys.factory.runtime;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,manifest=Config.NONE)
public class PreferencesBackendBackupTest {
    @Test public void nativeUnsupportedTypesFailBackupWithoutChangingOrdinaryReadOrData() throws Exception {
        Context context=RuntimeEnvironment.getApplication();SharedPreferences raw=context.getSharedPreferences("factory.storage.v1."+context.getPackageName(),Context.MODE_PRIVATE);
        raw.edit().clear().putString("theme","light").putInt("unknown",7).commit();PreferencesBackend backend=new PreferencesBackend(context);BoundedStore store=new BoundedStore(backend);
        assertEquals("light",backend.read().get("theme"));assertFalse(backend.read().containsKey("unknown"));
        StorageBackupFormat.Archive archive=StorageBackupFormatTest.archive(new JSONObject().put("theme","dark"));
        assertEquals("STORAGE_ERROR",assertThrows(FactoryException.class,backend::readForBackup).code);
        assertEquals("STORAGE_ERROR",assertThrows(FactoryException.class,()->store.backupBaseline(archive,()->true)).code);
        assertEquals("STORAGE_ERROR",assertThrows(FactoryException.class,()->store.backupExport(archive.appId,archive.certificateSha256,1,archive.keys,()->true)).code);
        assertEquals(7,raw.getInt("unknown",-1));assertEquals("light",raw.getString("theme",null));raw.edit().remove("unknown").commit();
        BoundedStore.BackupBaseline baseline=store.backupBaseline(archive,()->true);raw.edit().putBoolean("laterUnsupported",true).commit();
        assertEquals("STORAGE_ERROR",assertThrows(FactoryException.class,()->store.backupImport(archive,"replaceSelectedKeys",baseline,()->true)).code);
        assertEquals("light",raw.getString("theme",null));assertTrue(raw.getBoolean("laterUnsupported",false));
    }
    @Test public void selectedCommitNeverCallsClearRemoveOrApplyEvenWhenCommitIsUncertain() throws Exception {
        for(int behavior=0;behavior<3;behavior++) {
            final int result=behavior;Map<String,Object> persisted=new HashMap<>();persisted.put("unselected","keep");Map<String,String> staged=new HashMap<>();AtomicInteger commits=new AtomicInteger(),puts=new AtomicInteger();
            SharedPreferences.Editor editor=(SharedPreferences.Editor)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{SharedPreferences.Editor.class},(proxy,method,args)->{
                if(method.getName().equals("putString")){puts.incrementAndGet();staged.put((String)args[0],(String)args[1]);return proxy;}
                if(method.getName().equals("commit")){commits.incrementAndGet();persisted.putAll(staged);if(result==2)throw new IllegalStateException("private error");return result==0;}
                throw new AssertionError("Unexpected preference edit: "+method.getName());
            });
            SharedPreferences preferences=(SharedPreferences)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{SharedPreferences.class},(proxy,method,args)->{
                if(method.getName().equals("getAll"))return new HashMap<>(persisted);if(method.getName().equals("edit"))return editor;
                throw new AssertionError("Unexpected preferences method: "+method.getName());
            });
            BoundedStore store=new BoundedStore(new PreferencesBackend(preferences));StorageBackupFormat.Archive archive=StorageBackupFormatTest.archive(new JSONObject().put("theme","dark"));
            BoundedStore.BackupBaseline baseline=store.backupBaseline(archive,()->true);
            if(result==0)assertTrue(store.backupImport(archive,"insertOnly",baseline,()->true).getBoolean("committed"));
            else assertEquals("OUTCOME_UNKNOWN",assertThrows(FactoryException.class,()->store.backupImport(archive,"insertOnly",baseline,()->true)).code);
            assertEquals(1,commits.get());assertEquals(1,puts.get());assertEquals("dark",persisted.get("theme"));assertEquals("keep",persisted.get("unselected"));
        }
    }
}
